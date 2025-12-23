package com.kotsin.consumer.service;

import com.kotsin.consumer.config.VCPConfig;
import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.model.VCPCluster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * VCPCalculator - Core Volume Cluster Pivot Algorithm
 * 
 * This service implements the full MT-VCP algorithm:
 * 1. Build volume profile from candle history (using actual volumeAtPrice)
 * 2. Identify clusters (peak detection with sigma threshold)
 * 3. Enrich with OFI bias (directional context)
 * 4. Validate with order book (current depth confirmation)
 * 5. Adjust with OI dynamics (position building/unwinding)
 * 6. Calculate liquidity-adjusted proximity
 * 7. Estimate penetration difficulty (Kyle's Lambda)
 * 8. Fuse multi-timeframe scores
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VCPCalculator {

    private final VCPConfig config;

    /**
     * Calculate VCP result for a single timeframe from candle history.
     * 
     * @param history List of UnifiedCandles (most recent last)
     * @param timeframe "5m", "15m", or "30m"
     * @return VCPResult containing score and clusters
     */
    public VCPResult calculateForTimeframe(List<UnifiedCandle> history, String timeframe) {
        if (history == null || history.isEmpty()) {
            return VCPResult.empty();
        }

        // Get current candle (most recent)
        UnifiedCandle current = history.get(history.size() - 1);
        double currentPrice = current.getClose();
        
        if (currentPrice <= 0) {
            return VCPResult.empty();
        }

        // Step 1: Build volume profile
        Map<Double, Long> volumeProfile = buildVolumeProfile(history);
        if (volumeProfile.isEmpty()) {
            return VCPResult.empty();
        }

        // Step 2: Identify clusters
        List<VCPCluster> clusters = identifyClusters(volumeProfile, currentPrice);
        if (clusters.isEmpty()) {
            return VCPResult.empty();
        }

        // Step 3: Enrich clusters with OFI bias
        enrichWithOFI(clusters, history);

        // Step 4: Validate with order book
        validateWithOrderbook(clusters, current);

        // Step 5: Adjust with OI dynamics
        adjustWithOI(clusters, history);

        // Step 6: Calculate proximity (using current market data)
        double atr = calculateATR(history);
        calculateProximity(clusters, currentPrice, current.getBidAskSpread(), atr);

        // Step 7: Calculate penetration difficulty
        calculatePenetrationDifficulty(clusters, current.getKyleLambda(), atr);

        // Step 8: Calculate composite scores
        clusters.forEach(VCPCluster::calculateCompositeScore);

        // Sort by composite score and limit
        clusters.sort(Comparator.comparingDouble(VCPCluster::getCompositeScore).reversed());
        if (clusters.size() > config.getMaxClusters()) {
            clusters = new ArrayList<>(clusters.subList(0, config.getMaxClusters()));
        }

        // Calculate aggregate scores
        double supportScore = calculateSupportScore(clusters);
        double resistanceScore = calculateResistanceScore(clusters);
        double vcpScore = calculateVCPScore(clusters);
        double runwayScore = calculateRunwayScore(clusters);

        return VCPResult.builder()
                .score(vcpScore)
                .supportScore(supportScore)
                .resistanceScore(resistanceScore)
                .runwayScore(runwayScore)
                .clusters(clusters)
                .atr(atr)
                .build();
    }

    /**
     * Build combined multi-timeframe VCP output.
     * 
     * @param result5m VCP result for 5m
     * @param result15m VCP result for 15m
     * @param result30m VCP result for 30m
     * @param current Current UnifiedCandle for metadata
     * @return MTVCPOutput with fused scores
     */
    public MTVCPOutput buildCombinedOutput(VCPResult result5m, VCPResult result15m, VCPResult result30m,
                                           UnifiedCandle current) {
        // Weighted score fusion
        double vcpCombined = config.getWeight5m() * result5m.getScore()
                           + config.getWeight15m() * result15m.getScore()
                           + config.getWeight30m() * result30m.getScore();

        // Directional score fusion
        double supportScore = config.getWeight5m() * result5m.getSupportScore()
                            + config.getWeight15m() * result15m.getSupportScore()
                            + config.getWeight30m() * result30m.getSupportScore();

        double resistanceScore = config.getWeight5m() * result5m.getResistanceScore()
                               + config.getWeight15m() * result15m.getResistanceScore()
                               + config.getWeight30m() * result30m.getResistanceScore();

        // Structural bias
        double structuralBias = (supportScore - resistanceScore) 
                              / (supportScore + resistanceScore + config.getBiasEpsilon());

        // Runway score fusion
        double runwayScore = config.getWeight5m() * result5m.getRunwayScore()
                           + config.getWeight15m() * result15m.getRunwayScore()
                           + config.getWeight30m() * result30m.getRunwayScore();

        // Merge top clusters from all timeframes
        List<VCPCluster> allClusters = new ArrayList<>();
        allClusters.addAll(result5m.getClusters());
        allClusters.addAll(result15m.getClusters());
        allClusters.addAll(result30m.getClusters());

        // Sort and deduplicate by price (keep strongest)
        Map<Double, VCPCluster> uniqueClusters = new LinkedHashMap<>();
        allClusters.stream()
                .sorted(Comparator.comparingDouble(VCPCluster::getCompositeScore).reversed())
                .forEach(c -> {
                    double key = Math.round(c.getPrice() * 100) / 100.0;  // Round to 2 decimals
                    if (!uniqueClusters.containsKey(key)) {
                        uniqueClusters.put(key, c);
                    }
                });

        List<VCPCluster> topClusters = uniqueClusters.values().stream()
                .limit(config.getMaxClusters())
                .collect(Collectors.toList());

        return MTVCPOutput.builder()
                .scripCode(current != null ? current.getScripCode() : null)
                .companyName(current != null ? current.getCompanyName() : null)
                .timestamp(System.currentTimeMillis())
                .vcpCombinedScore(Math.min(vcpCombined, 1.0))
                .supportScore(Math.min(supportScore, 1.0))
                .resistanceScore(Math.min(resistanceScore, 1.0))
                .structuralBias(Math.max(-1.0, Math.min(1.0, structuralBias)))
                .runwayScore(Math.min(runwayScore, 1.0))
                .vcp5m(result5m.getScore())
                .vcp15m(result15m.getScore())
                .vcp30m(result30m.getScore())
                .currentPrice(current != null ? current.getClose() : 0)
                .microprice(current != null ? current.getMicroprice() : 0)
                .atr(result5m.getAtr())
                .clusters(topClusters)
                .build();
    }

    // ========== Step 1: Build Volume Profile ==========

    private Map<Double, Long> buildVolumeProfile(List<UnifiedCandle> history) {
        Map<Double, Long> profile = new HashMap<>();

        for (UnifiedCandle candle : history) {
            if (candle.getVolumeAtPrice() != null) {
                // Use actual volumeAtPrice (Option A in spec)
                candle.getVolumeAtPrice().forEach((price, vol) -> 
                    profile.merge(price, vol, Long::sum));
            } else if (candle.getVolume() > 0) {
                // Fallback: VWAP-weighted distribution (Option B in spec)
                distributeVolumeVWAPWeighted(candle, profile);
            }
        }

        return profile;
    }

    private void distributeVolumeVWAPWeighted(UnifiedCandle candle, Map<Double, Long> profile) {
        double high = candle.getHigh();
        double low = candle.getLow();
        double vwap = candle.getVwap() > 0 ? candle.getVwap() : (high + low) / 2;
        long volume = candle.getVolume();
        
        if (high <= low || volume <= 0) return;

        double range = high - low;
        double buyRatio = candle.getVolume() > 0 ? 
                         (double) candle.getBuyVolume() / candle.getVolume() : 0.5;
        double sellRatio = 1.0 - buyRatio;

        double tickSize = Math.max(config.getPriceBinSize() * vwap, 0.01);
        
        for (double price = low; price <= high; price += tickSize) {
            double weight;
            if (price >= vwap) {
                // Buyers were aggressive here
                weight = buyRatio * gaussianWeight(price, vwap, 0.3 * range);
            } else {
                // Sellers were aggressive here
                weight = sellRatio * gaussianWeight(price, vwap, 0.3 * range);
            }
            
            long volumeAtLevel = (long) (volume * weight);
            if (volumeAtLevel > 0) {
                double roundedPrice = Math.round(price / tickSize) * tickSize;
                profile.merge(roundedPrice, volumeAtLevel, Long::sum);
            }
        }
    }

    private double gaussianWeight(double x, double center, double width) {
        if (width <= 0) return 0;
        double diff = x - center;
        return Math.exp(-0.5 * (diff * diff) / (width * width));
    }

    // ========== Step 2: Identify Clusters ==========

    private List<VCPCluster> identifyClusters(Map<Double, Long> volumeProfile, double currentPrice) {
        List<VCPCluster> clusters = new ArrayList<>();

        if (volumeProfile.isEmpty()) return clusters;

        // Calculate statistics
        List<Long> volumes = new ArrayList<>(volumeProfile.values());
        double mean = volumes.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = volumes.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0);
        double std = Math.sqrt(variance);

        // Threshold for peak detection
        double threshold = mean + config.getPeakThresholdSigma() * std;

        // Sort prices for peak detection
        List<Double> sortedPrices = new ArrayList<>(volumeProfile.keySet());
        Collections.sort(sortedPrices);

        // Find peaks
        long maxVolume = volumes.stream().mapToLong(Long::longValue).max().orElse(1);

        for (int i = 1; i < sortedPrices.size() - 1; i++) {
            double price = sortedPrices.get(i);
            long vol = volumeProfile.get(price);

            if (vol < threshold) continue;

            // Check if local peak
            long prevVol = volumeProfile.getOrDefault(sortedPrices.get(i - 1), 0L);
            long nextVol = volumeProfile.getOrDefault(sortedPrices.get(i + 1), 0L);

            if (vol >= prevVol && vol >= nextVol) {
                // Determine type based on position relative to current price
                VCPCluster.ClusterType type = price < currentPrice ? 
                        VCPCluster.ClusterType.SUPPORT : VCPCluster.ClusterType.RESISTANCE;

                double strength = (double) vol / maxVolume;
                double distance = Math.abs(price - currentPrice) / currentPrice * 100;

                VCPCluster cluster = VCPCluster.builder()
                        .price(price)
                        .strength(strength)
                        .totalVolume(vol)
                        .type(type)
                        .distancePercent(distance)
                        .obValidation(1.0)  // Default, will be updated
                        .oiAdjustment(1.0)  // Default, will be updated
                        .ofiBias(0.0)       // Default, will be updated
                        .build();

                clusters.add(cluster);
            }
        }

        return clusters;
    }

    // ========== Step 3: Enrich with OFI ==========

    private void enrichWithOFI(List<VCPCluster> clusters, List<UnifiedCandle> history) {
        for (VCPCluster cluster : clusters) {
            double cumulativeOfi = 0;
            long cumulativeVolume = 0;

            for (UnifiedCandle candle : history) {
                // Check if this candle contributed to this cluster
                if (candle.getVolumeAtPrice() != null) {
                    Long volAtCluster = candle.getVolumeAtPrice().get(cluster.getPrice());
                    if (volAtCluster != null && volAtCluster > 0) {
                        // Weight OFI by contribution
                        double buyRatio = candle.getVolume() > 0 ? 
                                         (double) candle.getBuyVolume() / candle.getVolume() : 0.5;
                        double signedOfi = (buyRatio - 0.5) * 2;  // -1 to +1
                        
                        cumulativeOfi += signedOfi * volAtCluster;
                        cumulativeVolume += volAtCluster;
                    }
                }
            }

            // Calculate OFI bias
            if (cumulativeVolume > 0) {
                cluster.setOfiBias(cumulativeOfi / cumulativeVolume);
            }
        }
    }

    // ========== Step 4: Validate with Order Book ==========

    private void validateWithOrderbook(List<VCPCluster> clusters, UnifiedCandle current) {
        if (current == null) return;

        double avgBidDepth = current.getTotalBidDepth();
        double avgAskDepth = current.getTotalAskDepth();
        Map<Double, Integer> bidDepth = current.getBidDepthSnapshot();
        Map<Double, Integer> askDepth = current.getAskDepthSnapshot();

        for (VCPCluster cluster : clusters) {
            double radius = config.getObValidationRadius() * cluster.getPrice();
            double lowerBound = cluster.getPrice() - radius;
            double upperBound = cluster.getPrice() + radius;

            double depthAtCluster = 0;
            double avgDepth;

            if (cluster.getType() == VCPCluster.ClusterType.SUPPORT) {
                // Check bid depth near cluster
                if (bidDepth != null) {
                    depthAtCluster = bidDepth.entrySet().stream()
                            .filter(e -> e.getKey() >= lowerBound && e.getKey() <= upperBound)
                            .mapToInt(Map.Entry::getValue)
                            .sum();
                }
                avgDepth = avgBidDepth > 0 ? avgBidDepth : 1;
            } else {
                // Check ask depth near cluster
                if (askDepth != null) {
                    depthAtCluster = askDepth.entrySet().stream()
                            .filter(e -> e.getKey() >= lowerBound && e.getKey() <= upperBound)
                            .mapToInt(Map.Entry::getValue)
                            .sum();
                }
                avgDepth = avgAskDepth > 0 ? avgAskDepth : 1;
            }

            double validation = depthAtCluster / avgDepth;
            cluster.setObValidation(validation);

            // Apply depth imbalance adjustment
            double depthImbalance = current.getDepthImbalance();
            if (cluster.getType() == VCPCluster.ClusterType.SUPPORT && depthImbalance > 0) {
                cluster.setStrength(cluster.getStrength() * (1 + 0.2 * depthImbalance));
            } else if (cluster.getType() == VCPCluster.ClusterType.RESISTANCE && depthImbalance < 0) {
                cluster.setStrength(cluster.getStrength() * (1 - 0.2 * depthImbalance));
            }
        }
    }

    // ========== Step 5: Adjust with OI ==========

    private void adjustWithOI(List<VCPCluster> clusters, List<UnifiedCandle> history) {
        if (history.size() < 2) return;

        // Calculate OI change ratio over lookback
        int lookback = Math.min(config.getOiLookback(), history.size());
        UnifiedCandle oldest = history.get(history.size() - lookback);
        UnifiedCandle newest = history.get(history.size() - 1);

        if (oldest.getOiOpen() == null || newest.getOiClose() == null || oldest.getOiOpen() == 0) {
            return;  // No OI data available
        }

        double oiChangeRatio = (newest.getOiClose() - oldest.getOiOpen()) / (double) oldest.getOiOpen();
        oiChangeRatio = Math.max(-config.getOiAdjustmentCap(), 
                                  Math.min(config.getOiAdjustmentCap(), oiChangeRatio));

        double adjustment = 1.0 + oiChangeRatio;

        for (VCPCluster cluster : clusters) {
            cluster.setOiAdjustment(adjustment);
        }
    }

    // ========== Step 6: Calculate Proximity ==========

    private void calculateProximity(List<VCPCluster> clusters, double currentPrice, 
                                   double spread, double atr) {
        for (VCPCluster cluster : clusters) {
            double rawDistance = Math.abs(currentPrice - cluster.getPrice()) / currentPrice;

            // Spread factor (wider spread = feels further)
            double spreadFactor = 1.0;
            if (spread > 0 && currentPrice > 0) {
                spreadFactor = 1.0 + (spread / currentPrice) * config.getSpreadFactorMultiplier();
            }

            // ATR factor (higher volatility = feels closer)
            double atrFactor = 1.0;
            if (atr > 0 && currentPrice > 0) {
                atrFactor = rawDistance / (atr / currentPrice);
                atrFactor = Math.max(0.1, Math.min(10.0, atrFactor));  // Bound
            }

            // Effective distance
            double effectiveDistance = rawDistance * spreadFactor / atrFactor;

            // Exponential decay proximity
            double proximity = Math.exp(-effectiveDistance / config.getProximityDecayConstant());

            // Zero out if too far
            if (rawDistance > config.getMaxRelevantDistance()) {
                proximity = 0;
            }

            cluster.setProximity(proximity);
        }
    }

    // ========== Step 7: Calculate Penetration Difficulty ==========

    private void calculatePenetrationDifficulty(List<VCPCluster> clusters, double kyleLambda, double atr) {
        for (VCPCluster cluster : clusters) {
            double volumeToBreak = cluster.getTotalVolume() * config.getBreakoutVolumeFraction();
            
            // Estimate price impact
            double penetrationImpact = Math.abs(kyleLambda) * volumeToBreak;
            
            // Relative difficulty (compared to ATR)
            double difficulty = 0;
            if (atr > 0) {
                difficulty = penetrationImpact / atr;
            }

            cluster.setBreakoutDifficulty(difficulty);
        }
    }

    // ========== Score Calculations ==========

    private double calculateVCPScore(List<VCPCluster> clusters) {
        if (clusters.isEmpty()) return 0;
        
        double sum = clusters.stream()
                .mapToDouble(VCPCluster::getCompositeScore)
                .sum();
        
        return Math.min(sum, 1.0);
    }

    private double calculateSupportScore(List<VCPCluster> clusters) {
        return clusters.stream()
                .filter(c -> c.getType() == VCPCluster.ClusterType.SUPPORT)
                .mapToDouble(c -> {
                    double base = c.getCompositeScore();
                    return c.isAligned() ? base * (1 + c.getOfiBias()) : base * 0.5;
                })
                .sum();
    }

    private double calculateResistanceScore(List<VCPCluster> clusters) {
        return clusters.stream()
                .filter(c -> c.getType() == VCPCluster.ClusterType.RESISTANCE)
                .mapToDouble(c -> {
                    double base = c.getCompositeScore();
                    return c.isAligned() ? base * (1 - c.getOfiBias()) : base * 0.5;
                })
                .sum();
    }

    private double calculateRunwayScore(List<VCPCluster> clusters) {
        double totalDifficulty = clusters.stream()
                .mapToDouble(VCPCluster::getBreakoutDifficulty)
                .sum();
        
        return 1.0 / (1.0 + totalDifficulty);
    }

    private double calculateATR(List<UnifiedCandle> history) {
        if (history.isEmpty()) return 0;
        
        int period = Math.min(config.getAtrPeriod(), history.size());
        double atrSum = 0;

        for (int i = history.size() - period; i < history.size(); i++) {
            UnifiedCandle c = history.get(i);
            double tr = c.getHigh() - c.getLow();
            
            // Include gap from previous close
            if (i > 0) {
                double prevClose = history.get(i - 1).getClose();
                tr = Math.max(tr, Math.abs(c.getHigh() - prevClose));
                tr = Math.max(tr, Math.abs(c.getLow() - prevClose));
            }
            
            atrSum += tr;
        }

        double atr = atrSum / period;
        
        // Fallback if ATR is zero
        if (atr <= 0 && !history.isEmpty()) {
            double price = history.get(history.size() - 1).getClose();
            atr = price * config.getDefaultAtrFraction();
        }

        return atr;
    }

    // ========== Result Class ==========

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class VCPResult {
        private double score;
        private double supportScore;
        private double resistanceScore;
        private double runwayScore;
        private double atr;
        private List<VCPCluster> clusters;

        public static VCPResult empty() {
            return VCPResult.builder()
                    .score(0)
                    .supportScore(0)
                    .resistanceScore(0)
                    .runwayScore(1.0)  // Empty = clean runway
                    .atr(0)
                    .clusters(new ArrayList<>())
                    .build();
        }
    }
}
