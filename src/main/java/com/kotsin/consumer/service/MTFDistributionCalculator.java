package com.kotsin.consumer.service;

import com.kotsin.consumer.model.EvolutionMetrics;
import com.kotsin.consumer.model.EvolutionMetrics.*;
import com.kotsin.consumer.model.MTFDistribution;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MTFDistributionCalculator - Multi-Timeframe Distribution Analysis
 *
 * PHASE 2 & 3 ENHANCEMENT: Analyzes intra-window characteristics
 *
 * Calculates distribution metrics from sub-candles to understand:
 * - Directional consistency (trend strength)
 * - Volume timing (early exhaustion vs late building)
 * - Momentum evolution (accelerating vs decelerating)
 * - EvolutionMetrics (CandleSequence, WyckoffPhase, etc.) - PHASE 3
 *
 * Example:
 * Input: 5x 1m candles aggregating into 1x 5m candle
 * Output: MTFDistribution showing pattern (5 bullish = strong, 3-2 = weak, etc.)
 */
@Slf4j
@Service
public class MTFDistributionCalculator {

    /**
     * Calculate MTF distribution from sub-candles
     *
     * @param subCandles List of sub-candles (e.g., 5x 1m for 5m aggregate)
     * @return MTFDistribution metrics
     */
    public MTFDistribution calculate(List<UnifiedCandle> subCandles) {
        if (subCandles == null || subCandles.isEmpty()) {
            return createEmpty();
        }

        int total = subCandles.size();

        // Calculate directional distribution
        int bullish = 0, bearish = 0;
        for (UnifiedCandle candle : subCandles) {
            if (candle.getClose() > candle.getOpen()) {
                bullish++;
            } else if (candle.getClose() < candle.getOpen()) {
                bearish++;
            }
        }

        double directionalConsistency = total > 0 ?
            Math.abs(bullish - bearish) / (double) total : 0.0;

        MTFDistribution.Direction dominantDirection =
            bullish > bearish ? MTFDistribution.Direction.BULLISH :
            bearish > bullish ? MTFDistribution.Direction.BEARISH :
            MTFDistribution.Direction.NEUTRAL;

        // Calculate volume distribution
        int volumeSpikeIndex = findVolumeSpikeIndex(subCandles);
        double earlyVolRatio = calculateEarlyVolumeRatio(subCandles);
        double lateVolRatio = calculateLateVolumeRatio(subCandles);
        boolean volumeDrying = isVolumeDrying(subCandles);

        // Calculate momentum evolution
        double earlyMom = calculateEarlyMomentum(subCandles);
        double lateMom = calculateLateMomentum(subCandles);
        double momShift = lateMom - earlyMom;

        // Confidence based on sample size
        double confidence = calculateConfidence(total);

        // PHASE 3: Calculate EvolutionMetrics
        EvolutionMetrics evolution = calculateEvolutionMetrics(subCandles, bullish, bearish, dominantDirection);

        return MTFDistribution.builder()
                .bullishSubCandles(bullish)
                .bearishSubCandles(bearish)
                .totalSubCandles(total)
                .directionalConsistency(directionalConsistency)
                .dominantDirection(dominantDirection)
                .volumeSpikeCandleIndex(volumeSpikeIndex)
                .earlyVolumeRatio(earlyVolRatio)
                .lateVolumeRatio(lateVolRatio)
                .volumeDrying(volumeDrying)
                .earlyMomentum(earlyMom)
                .lateMomentum(lateMom)
                .momentumShift(momShift)
                .momentumAccelerating(momShift > 0.1)
                .momentumDecelerating(momShift < -0.1)
                .confidence(confidence)
                .evolution(evolution)  // PHASE 3: Add evolution metrics
                .build();
    }

    // ========================================================================
    // PHASE 3: EvolutionMetrics Calculation
    // ========================================================================

    /**
     * Calculate comprehensive EvolutionMetrics from sub-candles.
     * This includes CandleSequence, WyckoffPhase, and basic OI/Volume evolution.
     */
    private EvolutionMetrics calculateEvolutionMetrics(List<UnifiedCandle> subCandles,
                                                        int bullish, int bearish,
                                                        MTFDistribution.Direction direction) {
        if (subCandles == null || subCandles.size() < 2) {
            return EvolutionMetrics.empty();
        }

        try {
            // Calculate CandleSequence
            CandleSequence candleSequence = calculateCandleSequence(subCandles);

            // Calculate WyckoffPhase
            WyckoffPhase wyckoffPhase = calculateWyckoffPhase(subCandles, direction);

            // Calculate OI Evolution (if OI data available)
            OIEvolution oiEvolution = calculateOIEvolution(subCandles);

            // Calculate Volume Profile Evolution
            VolumeProfileEvolution volumeProfileEvolution = calculateVolumeProfileEvolution(subCandles);

            return EvolutionMetrics.builder()
                    .candleSequence(candleSequence)
                    .wyckoffPhase(wyckoffPhase)
                    .oiEvolution(oiEvolution)
                    .volumeProfileEvolution(volumeProfileEvolution)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to calculate EvolutionMetrics: {}", e.getMessage());
            return EvolutionMetrics.empty();
        }
    }

    /**
     * Calculate CandleSequence - pattern, direction array, momentum metrics
     */
    private CandleSequence calculateCandleSequence(List<UnifiedCandle> subCandles) {
        int size = subCandles.size();
        int[] directionArray = new int[size];
        double[] momentumArray = new double[size];
        StringBuilder patternBuilder = new StringBuilder();

        int longestRun = 0;
        int currentRun = 1;
        int reversalCount = 0;
        int reversalIndex = -1;
        int prevDir = 0;

        for (int i = 0; i < size; i++) {
            UnifiedCandle candle = subCandles.get(i);
            double change = candle.getClose() - candle.getOpen();
            double range = candle.getHigh() - candle.getLow();

            // Direction: 1 = bullish, -1 = bearish, 0 = neutral
            int dir = change > 0 ? 1 : (change < 0 ? -1 : 0);
            directionArray[i] = dir;

            // Pattern string
            patternBuilder.append(dir > 0 ? "↑" : (dir < 0 ? "↓" : "→"));

            // Momentum: (close-open)/(high-low)
            momentumArray[i] = range > 0 ? change / range : 0;

            // Track runs and reversals
            if (i > 0) {
                if (dir == prevDir && dir != 0) {
                    currentRun++;
                } else {
                    longestRun = Math.max(longestRun, currentRun);
                    currentRun = 1;
                    if (dir != 0 && prevDir != 0 && dir != prevDir) {
                        reversalCount++;
                        if (reversalIndex < 0) {
                            reversalIndex = i;
                        }
                    }
                }
            }
            prevDir = dir;
        }
        longestRun = Math.max(longestRun, currentRun);

        // Calculate momentum slope using linear regression
        double[] slopeAndR2 = calculateLinearRegression(momentumArray);
        double momentumSlope = slopeAndR2[0];
        double momentumR2 = slopeAndR2[1];

        // Determine sequence type
        CandleSequence.SequenceType sequenceType = determineSequenceType(
            directionArray, reversalCount, longestRun, size
        );

        return CandleSequence.builder()
                .pattern(patternBuilder.toString())
                .directionArray(directionArray)
                .longestRun(longestRun)
                .reversalCount(reversalCount)
                .reversalIndex(reversalIndex >= 0 ? reversalIndex : 0)
                .sequenceType(sequenceType)
                .momentumArray(momentumArray)
                .momentumSlope(momentumSlope)
                .momentumR2(momentumR2)
                .build();
    }

    /**
     * Determine sequence type from direction pattern
     */
    private CandleSequence.SequenceType determineSequenceType(int[] directions,
                                                               int reversalCount,
                                                               int longestRun,
                                                               int total) {
        if (total < 2) return CandleSequence.SequenceType.CONSOLIDATION;

        // Count net direction
        int sum = 0;
        for (int d : directions) sum += d;

        // Check for V-pattern or Inverted-V
        if (reversalCount == 1 && total >= 3) {
            int firstHalfSum = 0, secondHalfSum = 0;
            int mid = total / 2;
            for (int i = 0; i < mid; i++) firstHalfSum += directions[i];
            for (int i = mid; i < total; i++) secondHalfSum += directions[i];

            if (firstHalfSum < 0 && secondHalfSum > 0) {
                return CandleSequence.SequenceType.V_PATTERN;
            }
            if (firstHalfSum > 0 && secondHalfSum < 0) {
                return CandleSequence.SequenceType.INVERTED_V;
            }
        }

        // Choppy if many reversals
        if (reversalCount > 2) {
            return CandleSequence.SequenceType.CHOP;
        }

        // Strong trend if high consistency
        double consistency = (double) longestRun / total;
        if (consistency >= 0.6 && Math.abs(sum) > total * 0.5) {
            return CandleSequence.SequenceType.TREND;
        }

        // Single reversal with clear direction change
        if (reversalCount == 1) {
            return CandleSequence.SequenceType.REVERSAL;
        }

        // Default to consolidation
        return CandleSequence.SequenceType.CONSOLIDATION;
    }

    /**
     * Calculate WyckoffPhase based on price action and volume patterns
     */
    private WyckoffPhase calculateWyckoffPhase(List<UnifiedCandle> subCandles,
                                                MTFDistribution.Direction direction) {
        int size = subCandles.size();
        if (size < 3) {
            return WyckoffPhase.builder()
                    .phase(WyckoffPhase.Phase.UNKNOWN)
                    .phaseStrength(0)
                    .build();
        }

        // Calculate price range and volume characteristics
        double firstPrice = subCandles.get(0).getClose();
        double lastPrice = subCandles.get(size - 1).getClose();
        double priceChange = (lastPrice - firstPrice) / firstPrice;

        // Volume analysis
        long earlyVol = 0, lateVol = 0;
        int mid = size / 2;
        for (int i = 0; i < mid; i++) earlyVol += subCandles.get(i).getVolume();
        for (int i = mid; i < size; i++) lateVol += subCandles.get(i).getVolume();

        double volumeRatio = earlyVol > 0 ? (double) lateVol / earlyVol : 1.0;

        // Determine phase
        WyckoffPhase.Phase phase;
        double strength;
        double volumeFit, priceFit, oiFit = 0.5;

        if (priceChange > 0.005 && volumeRatio > 1.1) {
            // Price up with increasing volume = Markup
            phase = WyckoffPhase.Phase.MARKUP;
            strength = Math.min(1.0, Math.abs(priceChange) * 20);
            volumeFit = Math.min(1.0, volumeRatio - 1.0);
            priceFit = Math.min(1.0, priceChange * 50);
        } else if (priceChange < -0.005 && volumeRatio > 1.1) {
            // Price down with increasing volume = Markdown
            phase = WyckoffPhase.Phase.MARKDOWN;
            strength = Math.min(1.0, Math.abs(priceChange) * 20);
            volumeFit = Math.min(1.0, volumeRatio - 1.0);
            priceFit = Math.min(1.0, Math.abs(priceChange) * 50);
        } else if (Math.abs(priceChange) < 0.003 && volumeRatio > 0.8) {
            // Sideways with decent volume = Accumulation or Distribution
            if (direction == MTFDistribution.Direction.BULLISH) {
                phase = WyckoffPhase.Phase.ACCUMULATION;
            } else if (direction == MTFDistribution.Direction.BEARISH) {
                phase = WyckoffPhase.Phase.DISTRIBUTION;
            } else {
                phase = WyckoffPhase.Phase.ACCUMULATION; // Default to accumulation in sideways
            }
            strength = 0.6;
            volumeFit = 0.6;
            priceFit = 0.5;
        } else {
            phase = WyckoffPhase.Phase.UNKNOWN;
            strength = 0.3;
            volumeFit = 0.3;
            priceFit = 0.3;
        }

        return WyckoffPhase.builder()
                .phase(phase)
                .phaseStartIndex(0)
                .phaseStrength(strength)
                .phaseTransition(false)
                .volumePatternFit(volumeFit)
                .priceActionFit(priceFit)
                .oiPatternFit(oiFit)
                .build();
    }

    /**
     * Calculate OI Evolution from sub-candles (if OI data available)
     */
    private OIEvolution calculateOIEvolution(List<UnifiedCandle> subCandles) {
        int size = subCandles.size();

        // Check if OI data is available (UnifiedCandle uses oiClose for OI)
        boolean hasOI = subCandles.stream()
                .anyMatch(c -> c.getOiClose() != null && c.getOiClose() > 0);

        if (!hasOI) {
            return OIEvolution.builder()
                    .buildupType(OIEvolution.BuildupType.NEUTRAL)
                    .buildupConfidence(0.3)
                    .build();
        }

        // Calculate OI deltas
        long[] oiDeltas = new long[size];
        long totalOIChange = 0;
        long earlyOIChange = 0;
        long lateOIChange = 0;
        int mid = size / 2;

        for (int i = 1; i < size; i++) {
            Long prevOI = subCandles.get(i - 1).getOiClose();
            Long currOI = subCandles.get(i).getOiClose();
            if (prevOI != null && currOI != null) {
                long delta = currOI - prevOI;
                oiDeltas[i] = delta;
                totalOIChange += delta;
                if (i <= mid) earlyOIChange += delta;
                else lateOIChange += delta;
            }
        }

        // Determine accumulation phase
        OIEvolution.AccumulationPhase accumPhase;
        if (totalOIChange == 0) {
            accumPhase = OIEvolution.AccumulationPhase.NONE;
        } else {
            double earlyRatio = Math.abs(earlyOIChange) / (double) Math.abs(totalOIChange);
            double lateRatio = Math.abs(lateOIChange) / (double) Math.abs(totalOIChange);

            if (earlyRatio > 0.6) accumPhase = OIEvolution.AccumulationPhase.EARLY;
            else if (lateRatio > 0.6) accumPhase = OIEvolution.AccumulationPhase.LATE;
            else accumPhase = OIEvolution.AccumulationPhase.THROUGHOUT;
        }

        // Determine buildup type based on price + OI
        double firstPrice = subCandles.get(0).getClose();
        double lastPrice = subCandles.get(size - 1).getClose();
        double priceChange = lastPrice - firstPrice;

        OIEvolution.BuildupType buildupType;
        if (priceChange > 0 && totalOIChange > 0) {
            buildupType = OIEvolution.BuildupType.LONG_BUILDUP;
        } else if (priceChange < 0 && totalOIChange > 0) {
            buildupType = OIEvolution.BuildupType.SHORT_BUILDUP;
        } else if (priceChange < 0 && totalOIChange < 0) {
            buildupType = OIEvolution.BuildupType.LONG_UNWINDING;
        } else if (priceChange > 0 && totalOIChange < 0) {
            buildupType = OIEvolution.BuildupType.SHORT_COVERING;
        } else {
            buildupType = OIEvolution.BuildupType.NEUTRAL;
        }

        return OIEvolution.builder()
                .futureOIDeltas(oiDeltas)
                .futAccumPhase(accumPhase)
                .earlyOIRatio(totalOIChange != 0 ? Math.abs(earlyOIChange) / (double) Math.abs(totalOIChange) : 0)
                .lateOIRatio(totalOIChange != 0 ? Math.abs(lateOIChange) / (double) Math.abs(totalOIChange) : 0)
                .oiDivergence(false)
                .oiMomentum(totalOIChange / (double) size)
                .buildupType(buildupType)
                .buildupConfidence(0.7)
                .build();
    }

    /**
     * Calculate Volume Profile Evolution - POC migration and Value Area dynamics
     */
    private VolumeProfileEvolution calculateVolumeProfileEvolution(List<UnifiedCandle> subCandles) {
        int size = subCandles.size();

        // Track POC (Point of Control) as VWAP-like center of volume
        double[] pocHistory = new double[size];
        double[] vahHistory = new double[size];
        double[] valHistory = new double[size];

        for (int i = 0; i < size; i++) {
            UnifiedCandle c = subCandles.get(i);
            // POC approximation: typical price weighted by volume
            double typicalPrice = (c.getHigh() + c.getLow() + c.getClose()) / 3;
            pocHistory[i] = typicalPrice;

            // VAH/VAL approximation: high/low of the candle
            vahHistory[i] = c.getHigh();
            valHistory[i] = c.getLow();
        }

        double pocStart = pocHistory[0];
        double pocEnd = pocHistory[size - 1];
        double pocMigration = pocEnd - pocStart;

        // Determine POC trend
        VolumeProfileEvolution.POCTrend pocTrend;
        if (pocMigration > pocStart * 0.001) {
            pocTrend = VolumeProfileEvolution.POCTrend.RISING;
        } else if (pocMigration < -pocStart * 0.001) {
            pocTrend = VolumeProfileEvolution.POCTrend.FALLING;
        } else {
            pocTrend = VolumeProfileEvolution.POCTrend.STABLE;
        }

        // Check if Value Area is expanding or contracting
        double earlyRange = vahHistory[0] - valHistory[0];
        double lateRange = vahHistory[size - 1] - valHistory[size - 1];
        boolean expanding = lateRange > earlyRange * 1.1;
        boolean contracting = lateRange < earlyRange * 0.9;

        VolumeProfileEvolution.ValueAreaShift shift;
        if (expanding) shift = VolumeProfileEvolution.ValueAreaShift.EXPANDING;
        else if (contracting) shift = VolumeProfileEvolution.ValueAreaShift.CONTRACTING;
        else if (pocMigration > 0) shift = VolumeProfileEvolution.ValueAreaShift.UPWARD;
        else if (pocMigration < 0) shift = VolumeProfileEvolution.ValueAreaShift.DOWNWARD;
        else shift = VolumeProfileEvolution.ValueAreaShift.STABLE;

        return VolumeProfileEvolution.builder()
                .pocHistory(pocHistory)
                .pocStart(pocStart)
                .pocEnd(pocEnd)
                .pocMigration(pocMigration)
                .pocTrend(pocTrend)
                .vahHistory(vahHistory)
                .valHistory(valHistory)
                .valueAreaExpanding(expanding)
                .valueAreaContracting(contracting)
                .valueAreaShift(shift)
                .finalPOC(pocEnd)
                .finalVAH(vahHistory[size - 1])
                .finalVAL(valHistory[size - 1])
                .build();
    }

    /**
     * Simple linear regression to calculate slope and R-squared
     */
    private double[] calculateLinearRegression(double[] values) {
        int n = values.length;
        if (n < 2) return new double[]{0, 0};

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += values[i];
            sumXY += i * values[i];
            sumX2 += i * i;
        }

        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX + 0.0001);
        double intercept = (sumY - slope * sumX) / n;

        // Calculate R-squared
        double meanY = sumY / n;
        double ssTotal = 0, ssRes = 0;
        for (int i = 0; i < n; i++) {
            double predicted = slope * i + intercept;
            ssTotal += (values[i] - meanY) * (values[i] - meanY);
            ssRes += (values[i] - predicted) * (values[i] - predicted);
        }

        double r2 = ssTotal > 0 ? 1 - (ssRes / ssTotal) : 0;
        r2 = Math.max(0, Math.min(1, r2));

        return new double[]{slope, r2};
    }
    
    /**
     * Find index of candle with highest volume
     */
    private int findVolumeSpikeIndex(List<UnifiedCandle> candles) {
        if (candles.isEmpty()) return -1;
        
        long maxVolume = 0;
        int maxIndex = 0;
        
        for (int i = 0; i < candles.size(); i++) {
            long vol = candles.get(i).getVolume();
            if (vol > maxVolume) {
                maxVolume = vol;
                maxIndex = i;
            }
        }
        
        return maxIndex;
    }
    
    /**
     * Calculate ratio of volume in first 40% of candles
     */
    private double calculateEarlyVolumeRatio(List<UnifiedCandle> candles) {
        if (candles.isEmpty()) return 0.0;
        
        int earlyCount = Math.max(1, (int) (candles.size() * 0.4));
        long earlyVolume = 0;
        long totalVolume = 0;
        
        for (int i = 0; i < candles.size(); i++) {
            long vol = candles.get(i).getVolume();
            totalVolume += vol;
            if (i < earlyCount) {
                earlyVolume += vol;
            }
        }
        
        return totalVolume > 0 ? earlyVolume / (double) totalVolume : 0.0;
    }
    
    /**
     * Calculate ratio of volume in last 40% of candles
     */
    private double calculateLateVolumeRatio(List<UnifiedCandle> candles) {
        if (candles.isEmpty()) return 0.0;
        
        int lateStart = (int) (candles.size() * 0.6);
        long lateVolume = 0;
        long totalVolume = 0;
        
        for (int i = 0; i < candles.size(); i++) {
            long vol = candles.get(i).getVolume();
            totalVolume += vol;
            if (i >= lateStart) {
                lateVolume += vol;
            }
        }
        
        return totalVolume > 0 ? lateVolume / (double) totalVolume : 0.0;
    }
    
    /**
     * Check if volume is declining through window
     */
    private boolean isVolumeDrying(List<UnifiedCandle> candles) {
        if (candles.size() < 3) return false;
        
        // Compare first half avg vs second half avg
        int midPoint = candles.size() / 2;
        double firstHalfAvg = 0;
        double secondHalfAvg = 0;
        
        for (int i = 0; i < midPoint; i++) {
            firstHalfAvg += candles.get(i).getVolume();
        }
        firstHalfAvg /= midPoint;
        
        for (int i = midPoint; i < candles.size(); i++) {
            secondHalfAvg += candles.get(i).getVolume();
        }
        secondHalfAvg /= (candles.size() - midPoint);
        
        // Drying if second half is < 70% of first half
        return secondHalfAvg < 0.7 * firstHalfAvg;
    }
    
    /**
     * Calculate average momentum of early candles
     * Momentum = (close - open) / (high - low)
     */
    private double calculateEarlyMomentum(List<UnifiedCandle> candles) {
        if (candles.isEmpty()) return 0.0;
        
        int earlyCount = Math.max(1, (int) (candles.size() * 0.4));
        double totalMomentum = 0;
        int validCount = 0;
        
        for (int i = 0; i < Math.min(earlyCount, candles.size()); i++) {
            UnifiedCandle candle = candles.get(i);
            double range = candle.getHigh() - candle.getLow();
            if (range > 0) {
                double momentum = (candle.getClose() - candle.getOpen()) / range;
                totalMomentum += momentum;
                validCount++;
            }
        }
        
        return validCount > 0 ? totalMomentum / validCount : 0.0;
    }
    
    /**
     * Calculate average momentum of late candles
     */
    private double calculateLateMomentum(List<UnifiedCandle> candles) {
        if (candles.isEmpty()) return 0.0;
        
        int lateStart = (int) (candles.size() * 0.6);
        double totalMomentum = 0;
        int validCount = 0;
        
        for (int i = lateStart; i < candles.size(); i++) {
            UnifiedCandle candle = candles.get(i);
            double range = candle.getHigh() - candle.getLow();
            if (range > 0) {
                double momentum = (candle.getClose() - candle.getOpen()) / range;
                totalMomentum += momentum;
                validCount++;
            }
        }
        
        return validCount > 0 ? totalMomentum / validCount : 0.0;
    }
    
    /**
     * Calculate confidence based on sample size
     */
    private double calculateConfidence(int sampleSize) {
        if (sampleSize < 3) return 0.3;  // Low confidence
        if (sampleSize < 5) return 0.6;  // Medium confidence
        return 1.0;  // High confidence
    }
    
    /**
     * Create empty distribution (no data)
     */
    private MTFDistribution createEmpty() {
        return MTFDistribution.builder()
                .bullishSubCandles(0)
                .bearishSubCandles(0)
                .totalSubCandles(0)
                .directionalConsistency(0.0)
                .dominantDirection(MTFDistribution.Direction.NEUTRAL)
                .volumeSpikeCandleIndex(-1)
                .earlyVolumeRatio(0.0)
                .lateVolumeRatio(0.0)
                .volumeDrying(false)
                .earlyMomentum(0.0)
                .lateMomentum(0.0)
                .momentumShift(0.0)
                .momentumAccelerating(false)
                .momentumDecelerating(false)
                .confidence(0.0)
                .evolution(EvolutionMetrics.empty())  // PHASE 3: Include empty evolution
                .build();
    }
}
