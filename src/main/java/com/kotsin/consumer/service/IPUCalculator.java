package com.kotsin.consumer.service;

import com.kotsin.consumer.config.IPUConfig;
import com.kotsin.consumer.model.IPUOutput;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * IPUCalculator - Institutional Participation & Urgency with Integrated Momentum Context
 * 
 * Implements the 16-step IPU algorithm:
 * 1. Volume Expansion Score
 * 2. Price Efficiency Score
 * 3. Order Flow Quality Score
 * 4. Institutional Proxy Score
 * 5. Momentum Context (Slope, Acceleration, State)
 * 6. Flow-Momentum Validation
 * 7. Urgency Classification
 * 8. Directional Conviction
 * 9. Momentum Exhaustion Detection
 * 10. X-Factor Scoring
 * 11. Final IPU Score
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IPUCalculator {

    private final IPUConfig cfg;

    /**
     * Calculate IPU for a single timeframe from candle history.
     * 
     * @param history List of UnifiedCandles (most recent last)
     * @param timeframe "5m", "15m", or "30m"
     * @return IPUOutput with all scores
     */
    public IPUOutput calculate(List<UnifiedCandle> history, String timeframe) {
        if (history == null || history.size() < 5) {
            return emptyOutput(timeframe);
        }

        UnifiedCandle current = history.get(history.size() - 1);
        UnifiedCandle prev = history.get(history.size() - 2);

        if (current.getClose() <= 0) {
            return emptyOutput(timeframe);
        }

        // Compute needed stats from history
        double smaVolume = computeSMA(history, cfg.getVolumeSmaPeriod(), UnifiedCandle::getVolume);
        double smaTickCount = computeSMA(history, cfg.getVolumeSmaPeriod(), c -> (double) c.getTickCount());
        double atr = computeATR(history, 14);
        double prevAtr = computeATRAtIndex(history, 14, history.size() - 2);

        // ========== STEP 1: Volume Expansion Score ==========
        double volRatio = smaVolume > 0 ? current.getVolume() / smaVolume : 1.0;
        double volExpansionScore = 1 - Math.exp(-volRatio / cfg.getVolumeScaleFactor());

        // ========== STEP 2: Price Efficiency Score ==========
        double directionalMove = Math.abs(current.getClose() - current.getOpen());
        double totalRange = current.getHigh() - current.getLow() + cfg.getEpsilon();
        double rawEfficiency = directionalMove / totalRange;

        double upperWick = Math.max(0, current.getHigh() - Math.max(current.getOpen(), current.getClose()));
        double lowerWick = Math.max(0, Math.min(current.getOpen(), current.getClose()) - current.getLow());
        double wickRatio = (upperWick + lowerWick) / totalRange;
        double wickPenalty = 1 - (cfg.getEfficiencyWickPenaltyMax() * wickRatio);
        double priceEfficiency = rawEfficiency * wickPenalty;

        // ========== STEP 3: Order Flow Quality Score ==========
        // FIX Bug #11: Return empty output if zero volume (no data, not "neutral")
        if (current.getBuyVolume() == 0 && current.getSellVolume() == 0 && current.getVolume() == 0) {
            log.debug("Zero volume for {} - returning empty IPU (no data)", current.getScripCode());
            return emptyOutput(timeframe);
        }
        
        double totalVolume = current.getBuyVolume() + current.getSellVolume() + cfg.getEpsilon();
        double volumeDeltaPct = (current.getBuyVolume() - current.getSellVolume()) / totalVolume;
        double volumeDeltaAbs = Math.abs(volumeDeltaPct);

        double totalDepth = current.getTotalBidDepth() + current.getTotalAskDepth() + cfg.getEpsilon();
        double ofiNormalized = current.getOfi() / totalDepth;
        double ofiAbs = Math.abs(ofiNormalized);
        double ofiPressure = Math.min(ofiAbs * cfg.getOfiScaleFactor(), 1.0);

        double depthImbalanceAbs = Math.abs(current.getTotalBidDepth() - current.getTotalAskDepth()) / totalDepth;

        // Agreement check
        double agreementFactor = Math.signum(volumeDeltaPct) * Math.signum(ofiNormalized) 
                               * Math.signum(current.getDepthImbalance());

        double ofQuality;
        if (agreementFactor > 0) {
            // All signals agree - geometric mean with bonus
            ofQuality = Math.pow(volumeDeltaAbs * ofiPressure * depthImbalanceAbs, 1.0/3.0);
            ofQuality = ofQuality * cfg.getFlowAgreementBonus();
        } else {
            // Signals disagree - arithmetic mean
            ofQuality = (volumeDeltaAbs + ofiPressure + depthImbalanceAbs) / 3.0;
        }
        ofQuality = Math.min(ofQuality, 1.0);

        // ========== STEP 4: Institutional Proxy Score ==========
        double instCore = priceEfficiency * ofQuality;
        double volumeAmplifier = 0.5 + (0.5 * volExpansionScore);
        double lambdaBoost = 1 + Math.min(current.getKyleLambda() * cfg.getLambdaScale(), cfg.getLambdaBoostMax());
        double instProxy = Math.min(instCore * volumeAmplifier * lambdaBoost, 1.0);

        // ========== STEP 5: Momentum Context ==========
        // 5A: Momentum Slope
        double priceChange3bar = 0;
        double mmsSlope = 0;
        if (history.size() >= 4) {
            UnifiedCandle t3 = history.get(history.size() - 4);
            priceChange3bar = current.getClose() - t3.getClose();
            mmsSlope = atr > 0 ? priceChange3bar / atr : 0;
            mmsSlope = Math.max(Math.min(mmsSlope, cfg.getSlopeClamp()), -cfg.getSlopeClamp());
        }
        double slopeMagnitude = Math.abs(mmsSlope);

        // 5B: Momentum Acceleration
        double mmsSlopePrev = 0;
        double mmsAcceleration = 0;
        if (history.size() >= 5 && prevAtr > 0) {
            UnifiedCandle t4 = history.get(history.size() - 5);
            double priceChange3barPrev = prev.getClose() - t4.getClose();
            mmsSlopePrev = priceChange3barPrev / prevAtr;
            mmsSlopePrev = Math.max(Math.min(mmsSlopePrev, cfg.getSlopeClamp()), -cfg.getSlopeClamp());
            mmsAcceleration = mmsSlope - mmsSlopePrev;
        }
        double accelMagnitude = Math.min(Math.abs(mmsAcceleration) / cfg.getAccelerationThreshold(), 1.0);

        // 5C: Momentum Alignment
        double momentumDirection = Math.signum(mmsSlope);
        double candleDirection = Math.signum(current.getClose() - current.getOpen());
        double momentumAlignment;
        if (momentumDirection == candleDirection) {
            momentumAlignment = 1.0;
        } else if (momentumDirection == 0) {
            momentumAlignment = 0.7;
        } else {
            momentumAlignment = 0.4;
        }

        // 5D: Combined Momentum Context
        double momentumContext = (slopeMagnitude * 0.6) + (accelMagnitude * 0.4);
        double momentumContextAligned = momentumContext * momentumAlignment;

        // 5E: Momentum State Classification
        IPUOutput.MomentumState momentumState;
        if (mmsAcceleration > 0.1 && slopeMagnitude > 0.4) {
            momentumState = IPUOutput.MomentumState.ACCELERATING;
        } else if (mmsAcceleration < -0.1 && slopeMagnitude > 0.4) {
            momentumState = IPUOutput.MomentumState.DECELERATING;
        } else if (slopeMagnitude > 0.5) {
            momentumState = IPUOutput.MomentumState.TRENDING;
        } else if (slopeMagnitude > 0.25) {
            momentumState = IPUOutput.MomentumState.DRIFTING;
        } else {
            momentumState = IPUOutput.MomentumState.FLAT;
        }

        // ========== STEP 6: Flow-Momentum Validation ==========
        double flowDirection = Math.signum(volumeDeltaPct);
        double flowMomentumAgreement;
        if (flowDirection == momentumDirection && momentumDirection != 0) {
            flowMomentumAgreement = 1.0;
        } else if (flowDirection == 0 || momentumDirection == 0) {
            flowMomentumAgreement = 0.7;
        } else {
            flowMomentumAgreement = 0.3;
        }
        double validatedMomentum = momentumContextAligned * (0.6 + 0.4 * flowMomentumAgreement);

        // ========== STEP 7: Urgency Classification ==========
        double prevVol = prev.getVolume() + cfg.getEpsilon();
        double volRoc = (current.getVolume() - prev.getVolume()) / prevVol;
        boolean volAccelerating = volRoc > cfg.getVolAccelerationThreshold();

        double priceMoveAtr = atr > 0 ? Math.abs(current.getClose() - current.getOpen()) / atr : 0;
        double momentumStrength = Math.min(priceMoveAtr / cfg.getMomentumAtrScale(), 1.0);

        double tickDensity = smaTickCount > 0 ? current.getTickCount() / smaTickCount : 1.0;
        double tickIntensity = Math.min(tickDensity / cfg.getTickDensityScale(), 1.0);

        double baseUrgency = volExpansionScore * 0.25
                           + momentumStrength * 0.25
                           + tickIntensity * 0.20
                           + ofQuality * 0.15
                           + validatedMomentum * 0.15;

        double urgencyBoost;
        if (momentumState == IPUOutput.MomentumState.ACCELERATING && flowMomentumAgreement > 0.7) {
            urgencyBoost = cfg.getAcceleratingUrgencyBoost();
        } else if (momentumState == IPUOutput.MomentumState.DECELERATING) {
            urgencyBoost = cfg.getDeceleratingUrgencyPenalty();
        } else {
            urgencyBoost = 1.0;
        }
        double urgencyScore = Math.min(baseUrgency * urgencyBoost, 1.0);

        IPUOutput.UrgencyLevel urgencyLevel;
        if (urgencyScore >= 0.75) {
            urgencyLevel = IPUOutput.UrgencyLevel.AGGRESSIVE;
        } else if (urgencyScore >= 0.50) {
            urgencyLevel = IPUOutput.UrgencyLevel.ELEVATED;
        } else if (urgencyScore >= 0.30) {
            urgencyLevel = IPUOutput.UrgencyLevel.PATIENT;
        } else {
            urgencyLevel = IPUOutput.UrgencyLevel.PASSIVE;
        }

        // ========== STEP 8: Directional Conviction ==========
        // FIX: Use count-based agreement instead of weighted sum that cancels out
        double priceDir = Math.signum(current.getClose() - current.getOpen());
        double flowDir = Math.signum(current.getBuyVolume() - current.getSellVolume());
        double ofiDir = Double.isNaN(current.getOfi()) ? 0 : Math.signum(current.getOfi());
        double depthDir = Math.signum(current.getTotalBidDepth() - current.getTotalAskDepth());

        // FIX: Count bullish/bearish factors separately instead of using weighted sum
        int bullishCount = 0;
        int bearishCount = 0;
        int totalFactors = 0;
        
        if (priceDir > 0) { bullishCount++; totalFactors++; }
        else if (priceDir < 0) { bearishCount++; totalFactors++; }
        
        if (flowDir > 0) { bullishCount++; totalFactors++; }
        else if (flowDir < 0) { bearishCount++; totalFactors++; }
        
        if (ofiDir > 0) { bullishCount++; totalFactors++; }
        else if (ofiDir < 0) { bearishCount++; totalFactors++; }
        
        if (depthDir > 0) { bullishCount++; totalFactors++; }
        else if (depthDir < 0) { bearishCount++; totalFactors++; }
        
        if (momentumDirection > 0) { bullishCount++; totalFactors++; }
        else if (momentumDirection < 0) { bearishCount++; totalFactors++; }

        // FIX: Calculate agreement as percentage of factors agreeing with dominant direction
        int dominantCount = Math.max(bullishCount, bearishCount);
        double directionAgreement = totalFactors > 0 ? (double) dominantCount / totalFactors : 0;

        // Also keep weighted vote for direction determination
        double directionVotes = priceDir * cfg.getPriceDirectionWeight()
                              + flowDir * cfg.getFlowDirectionWeight()
                              + ofiDir * cfg.getOfiDirectionWeight()
                              + depthDir * cfg.getDepthDirectionWeight()
                              + momentumDirection * cfg.getMomentumDirectionWeight();

        IPUOutput.Direction direction;
        double directionalConviction;
        
        // FIX: Use count-based majority for direction, agreement for conviction
        if (bullishCount > bearishCount && bullishCount >= 3) {
            direction = IPUOutput.Direction.BULLISH;
            directionalConviction = instProxy * directionAgreement;
        } else if (bearishCount > bullishCount && bearishCount >= 3) {
            direction = IPUOutput.Direction.BEARISH;
            directionalConviction = instProxy * directionAgreement;
        } else if (Math.abs(directionVotes) > 0.3) {
            // Fallback to weighted vote if count is ambiguous
            direction = directionVotes > 0 ? IPUOutput.Direction.BULLISH : IPUOutput.Direction.BEARISH;
            directionalConviction = instProxy * Math.abs(directionVotes) / cfg.getMaxDirectionVotes();
        } else {
            direction = IPUOutput.Direction.NEUTRAL;
            directionalConviction = 0;
        }

        // ========== STEP 9: Momentum Exhaustion Detection ==========
        double prevMomentumContext = computePrevMomentumContext(history, cfg, prevAtr);
        double prevVolRatio = smaVolume > 0 ? prev.getVolume() / smaVolume : 1.0;

        boolean decliningMomentum = momentumContext < prevMomentumContext * cfg.getExhaustionDeclineThreshold();
        boolean highAbsoluteMomentum = slopeMagnitude > 0.5;
        boolean decliningVolume = volRatio < prevVolRatio * cfg.getExhaustionVolumeDecline();
        boolean wickRejection = wickRatio > cfg.getExhaustionWickThreshold();

        int exhaustionSignals = 0;
        if (decliningMomentum) exhaustionSignals++;
        if (decliningVolume) exhaustionSignals++;
        if (wickRejection) exhaustionSignals++;
        if (momentumState == IPUOutput.MomentumState.DECELERATING) exhaustionSignals++;

        double exhaustionScore;
        if (exhaustionSignals >= 3 && highAbsoluteMomentum) {
            exhaustionScore = 0.9;
        } else if (exhaustionSignals >= 2 && highAbsoluteMomentum) {
            exhaustionScore = 0.6;
        } else if (exhaustionSignals >= 2) {
            exhaustionScore = 0.4;
        } else {
            exhaustionScore = 0.1;
        }
        boolean exhaustionWarning = exhaustionScore >= 0.6;

        // ========== STEP 10: X-Factor Scoring ==========
        boolean volCondition = volExpansionScore >= cfg.getXfactorVolThreshold();
        boolean efficiencyCondition = priceEfficiency >= cfg.getXfactorEfficiencyThreshold();
        boolean flowCondition = ofiPressure >= cfg.getXfactorFlowThreshold() 
                             || depthImbalanceAbs >= cfg.getDepthImbalanceThreshold();
        boolean directionCondition = directionAgreement >= cfg.getXfactorDirectionThreshold();
        boolean momentumCondition = momentumState == IPUOutput.MomentumState.ACCELERATING 
                                 && slopeMagnitude > cfg.getXfactorMomentumThreshold();

        int conditionsMet = 0;
        if (volCondition) conditionsMet++;
        if (efficiencyCondition) conditionsMet++;
        if (flowCondition) conditionsMet++;
        if (directionCondition) conditionsMet++;
        if (momentumCondition) conditionsMet++;

        double xfactorScore;
        if (conditionsMet >= 5) {
            xfactorScore = 1.0;
        } else if (conditionsMet == 4) {
            xfactorScore = 0.85;
        } else if (conditionsMet == 3) {
            xfactorScore = 0.65;
        } else if (conditionsMet == 2) {
            xfactorScore = 0.35;
        } else {
            xfactorScore = 0;
        }

        // OI confirmation boost
        Double oiChangePct = current.getOiChangePercent();
        if (oiChangePct != null && oiChangePct > cfg.getXfactorOiThreshold()) {
            xfactorScore = Math.min(xfactorScore * cfg.getXfactorOiBoost(), 1.0);
        }
        boolean xfactorFlag = xfactorScore >= 0.65;

        // ========== STEP 11: Final IPU Score ==========
        double baseScore = instProxy;
        double momentumModifier = 1 + (cfg.getMomentumModifierStrength() * validatedMomentum);
        double urgencyModifier = 1 + (cfg.getUrgencyModifierStrength() * urgencyScore);
        double xfactorModifier = 1 + (cfg.getXfactorModifierStrength() * xfactorScore);
        double exhaustionPenalty = 1 - (cfg.getExhaustionPenaltyStrength() * exhaustionScore);

        double finalIpuScore = baseScore * momentumModifier * urgencyModifier * xfactorModifier * exhaustionPenalty;
        finalIpuScore = Math.min(finalIpuScore, 1.0);

        // Certainty
        double certainty = directionAgreement * Math.min(volExpansionScore + 0.3, 1.0) * flowMomentumAgreement;

        // Build output
        return IPUOutput.builder()
                .scripCode(current.getScripCode())
                .companyName(current.getCompanyName())
                .timeframe(timeframe)
                .timestamp(System.currentTimeMillis())
                .finalIpuScore(finalIpuScore)
                .volExpansionScore(volExpansionScore)
                .priceEfficiency(priceEfficiency)
                .ofQuality(ofQuality)
                .instProxy(instProxy)
                .momentumContext(momentumContext)
                .slopeMagnitude(slopeMagnitude)
                .accelMagnitude(accelMagnitude)
                .mmsSlope(mmsSlope)
                .mmsAcceleration(mmsAcceleration)
                .validatedMomentum(validatedMomentum)
                .momentumAlignment(momentumAlignment)
                .flowMomentumAgreement(flowMomentumAgreement)
                .momentumState(momentumState)
                .exhaustionScore(exhaustionScore)
                .exhaustionWarning(exhaustionWarning)
                .urgencyScore(urgencyScore)
                .urgencyLevel(urgencyLevel)
                .direction(direction)
                .directionalConviction(directionalConviction)
                .xfactorScore(xfactorScore)
                .xfactorFlag(xfactorFlag)
                .certainty(certainty)
                .raw(new IPUOutput.RawInputs(
                        volRatio,
                        volumeDeltaPct,
                        ofiPressure,
                        current.getDepthImbalance(),
                        tickDensity,
                        priceChange3bar,
                        wickRatio
                ))
                .build();
    }

    /**
     * Build combined multi-timeframe IPU output
     */
    public IPUOutput buildCombinedOutput(IPUOutput ipu5m, IPUOutput ipu15m, IPUOutput ipu30m) {
        // Weighted score fusion
        double combinedScore = cfg.getMtfWeight5m() * ipu5m.getFinalIpuScore()
                             + cfg.getMtfWeight15m() * ipu15m.getFinalIpuScore()
                             + cfg.getMtfWeight30m() * ipu30m.getFinalIpuScore();

        // Dominant momentum state (higher TF dominates)
        IPUOutput.MomentumState dominantMomentum;
        if (ipu30m.getMomentumState() == IPUOutput.MomentumState.ACCELERATING 
                || ipu30m.getMomentumState() == IPUOutput.MomentumState.TRENDING) {
            dominantMomentum = ipu30m.getMomentumState();
        } else if (ipu15m.getMomentumState() == IPUOutput.MomentumState.ACCELERATING 
                || ipu15m.getMomentumState() == IPUOutput.MomentumState.TRENDING) {
            dominantMomentum = ipu15m.getMomentumState();
        } else {
            dominantMomentum = ipu5m.getMomentumState();
        }

        // Direction alignment
        int bullishCount = 0, bearishCount = 0;
        if (ipu5m.getDirection() == IPUOutput.Direction.BULLISH) bullishCount++;
        if (ipu15m.getDirection() == IPUOutput.Direction.BULLISH) bullishCount++;
        if (ipu30m.getDirection() == IPUOutput.Direction.BULLISH) bullishCount++;
        if (ipu5m.getDirection() == IPUOutput.Direction.BEARISH) bearishCount++;
        if (ipu15m.getDirection() == IPUOutput.Direction.BEARISH) bearishCount++;
        if (ipu30m.getDirection() == IPUOutput.Direction.BEARISH) bearishCount++;

        IPUOutput.Direction direction;
        if (bullishCount == 3) {
            direction = IPUOutput.Direction.BULLISH;
        } else if (bearishCount == 3) {
            direction = IPUOutput.Direction.BEARISH;
        } else if (bullishCount >= 2) {
            direction = IPUOutput.Direction.BULLISH;
        } else if (bearishCount >= 2) {
            direction = IPUOutput.Direction.BEARISH;
        } else {
            direction = IPUOutput.Direction.NEUTRAL;
        }

        // Exhaustion consensus
        boolean mtfExhaustion = ipu30m.isExhaustionWarning() && ipu15m.isExhaustionWarning();

        // Return combined using 5m as base, override key fields
        return IPUOutput.builder()
                .scripCode(ipu5m.getScripCode())
                .companyName(ipu5m.getCompanyName())
                .timeframe("combined")
                .timestamp(System.currentTimeMillis())
                .finalIpuScore(combinedScore)
                .volExpansionScore(ipu5m.getVolExpansionScore())
                .priceEfficiency(ipu5m.getPriceEfficiency())
                .ofQuality(ipu5m.getOfQuality())
                .instProxy(ipu5m.getInstProxy())
                .momentumContext(ipu5m.getMomentumContext())
                .slopeMagnitude(ipu5m.getSlopeMagnitude())
                .accelMagnitude(ipu5m.getAccelMagnitude())
                .mmsSlope(ipu5m.getMmsSlope())
                .mmsAcceleration(ipu5m.getMmsAcceleration())
                .validatedMomentum(ipu5m.getValidatedMomentum())
                .momentumAlignment(ipu5m.getMomentumAlignment())
                .flowMomentumAgreement(ipu5m.getFlowMomentumAgreement())
                .momentumState(dominantMomentum)
                .exhaustionScore(Math.max(ipu15m.getExhaustionScore(), ipu30m.getExhaustionScore()))
                .exhaustionWarning(mtfExhaustion)
                .urgencyScore(ipu5m.getUrgencyScore())
                .urgencyLevel(ipu5m.getUrgencyLevel())
                .direction(direction)
                .directionalConviction(ipu5m.getDirectionalConviction())
                .xfactorScore(ipu5m.getXfactorScore())
                .xfactorFlag(ipu5m.isXfactorFlag())
                .certainty(ipu5m.getCertainty())
                .raw(ipu5m.getRaw())
                .build();
    }

    // ========== Helper Methods ==========

    private IPUOutput emptyOutput(String timeframe) {
        return IPUOutput.builder()
                .timeframe(timeframe)
                .timestamp(System.currentTimeMillis())
                .finalIpuScore(0)
                .momentumState(IPUOutput.MomentumState.FLAT)
                .urgencyLevel(IPUOutput.UrgencyLevel.PASSIVE)
                .direction(IPUOutput.Direction.NEUTRAL)
                .build();
    }

    private double computeSMA(List<UnifiedCandle> history, int period, java.util.function.ToDoubleFunction<UnifiedCandle> extractor) {
        int actualPeriod = Math.min(period, history.size());
        if (actualPeriod == 0) return 0;

        double sum = 0;
        for (int i = history.size() - actualPeriod; i < history.size(); i++) {
            sum += extractor.applyAsDouble(history.get(i));
        }
        return sum / actualPeriod;
    }

    private double computeATR(List<UnifiedCandle> history, int period) {
        return computeATRAtIndex(history, period, history.size() - 1);
    }

    private double computeATRAtIndex(List<UnifiedCandle> history, int period, int endIndex) {
        int actualPeriod = Math.min(period, endIndex + 1);
        if (actualPeriod <= 1) return 0;

        double sum = 0;
        int count = 0;
        for (int i = endIndex - actualPeriod + 1; i <= endIndex; i++) {
            if (i < 0) continue;
            UnifiedCandle c = history.get(i);
            double tr = c.getHigh() - c.getLow();

            if (i > 0) {
                double prevClose = history.get(i - 1).getClose();
                tr = Math.max(tr, Math.abs(c.getHigh() - prevClose));
                tr = Math.max(tr, Math.abs(c.getLow() - prevClose));
            }
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private double computePrevMomentumContext(List<UnifiedCandle> history, IPUConfig cfg, double prevAtr) {
        if (history.size() < 5 || prevAtr <= 0) return 0;

        UnifiedCandle prev = history.get(history.size() - 2);
        UnifiedCandle t4 = history.get(history.size() - 5);
        double priceChange = prev.getClose() - t4.getClose();
        double slope = priceChange / prevAtr;
        slope = Math.max(Math.min(slope, cfg.getSlopeClamp()), -cfg.getSlopeClamp());
        return Math.abs(slope) * 0.6;  // Simplified - just slope component
    }
}
