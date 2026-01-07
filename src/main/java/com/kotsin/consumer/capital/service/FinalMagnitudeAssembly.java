package com.kotsin.consumer.capital.service;

import com.kotsin.consumer.capital.model.FinalMagnitude;
import com.kotsin.consumer.capital.model.FinalMagnitude.*;
import com.kotsin.consumer.model.IPUOutput;
import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.model.TradingSignal;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.regime.model.ACLOutput;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.SecurityRegime;
import com.kotsin.consumer.signal.model.FUDKIIOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * FinalMagnitudeAssembly - Module 14: FMA (Critical)
 * 
 * This is the RANKING BACKBONE of the entire system.
 * Combines all module outputs into a single ranked magnitude score.
 * 
 * Formula:
 * FinalMagnitude = BaseSignal × ACL × Volume × CSS × Regime × Ignition × (1 - SOM) × (1 - VTD)
 * 
 * Where:
 * - BaseSignal = (IPU + VCP) / 2
 * - ACL = Anti-Cycle multiplier [0.7, 1.1]
 * - Volume = Institutional X-factor [0.5, 1.5]
 * - CSS = Composite Structure Score [0.5, 1.2]
 * - Regime = Index alignment multiplier [0.75, 1.1]
 * - Ignition = FUDKII multiplier [0.8, 1.2]
 * - SOM = Choppiness penalty [0, 0.5]
 * - VTD = Volatility trap penalty [0, 0.5]
 */
@Slf4j
@Service
public class FinalMagnitudeAssembly {

    // Weight configuration
    @Value("${fma.weight.ipu:0.40}")
    private double weightIPU;

    @Value("${fma.weight.vcp:0.30}")
    private double weightVCP;

    @Value("${fma.weight.regime:0.20}")
    private double weightRegime;

    @Value("${fma.weight.fudkii:0.10}")
    private double weightFUDKII;

    // Multiplier caps
    @Value("${fma.acl.max:1.1}")
    private double aclMax;

    @Value("${fma.volume.max:1.5}")
    private double volumeMax;

    @Value("${fma.regime.aligned.boost:1.1}")
    private double regimeAlignedBoost;

    @Value("${fma.regime.diverged.penalty:0.75}")
    private double regimeDivergedPenalty;

    // Penalty caps
    @Value("${fma.som.penalty.max:0.5}")
    private double somPenaltyMax;

    @Value("${fma.vtd.penalty.max:0.5}")
    private double vtdPenaltyMax;

    /**
     * Calculate Final Magnitude from all module outputs
     */
    public FinalMagnitude calculate(
            String scripCode,
            String companyName,
            UnifiedCandle currentCandle,
            TradingSignal tradingSignal,
            IPUOutput ipuOutput,
            MTVCPOutput vcpOutput,
            IndexRegime indexRegime,
            SecurityRegime securityRegime,
            ACLOutput aclOutput,
            FUDKIIOutput fudkiiOutput) {

        // Extract base scores
        double ipuScore = ipuOutput != null ? ipuOutput.getFinalIpuScore() : 0;
        double vcpScore = vcpOutput != null ? vcpOutput.getVcpCombinedScore() : 0;
        double regimeStrength = indexRegime != null ? indexRegime.getRegimeStrength() : 0.5;
        double securityStrength = securityRegime != null ? securityRegime.getFinalRegimeScore() : 0.5;
        double fudkiiStrength = fudkiiOutput != null ? fudkiiOutput.getFudkiiStrength() : 0;
        double momentumScore = ipuOutput != null ? ipuOutput.getMomentumContext() : 0;

        // Calculate weighted base signal
        double baseSignal = weightIPU * ipuScore 
                          + weightVCP * vcpScore 
                          + weightRegime * regimeStrength 
                          + weightFUDKII * fudkiiStrength;

        // Get multipliers
        double aclMultiplier = aclOutput != null ? 
                Math.min(aclOutput.getAclMultiplier(), aclMax) : 1.0;

        double volumeMultiplier = calculateVolumeMultiplier(currentCandle, ipuOutput);

        double cssMultiplier = calculateCSSMultiplier(vcpOutput, securityRegime);

        double regimeMultiplier = calculateRegimeMultiplier(securityRegime, indexRegime);

        double ignitionMultiplier = fudkiiOutput != null && fudkiiOutput.isIgnitionFlag() ?
                fudkiiOutput.getPositionMultiplier() : 1.0;

        // Get penalties
        double somPenalty = calculateSOMPenalty(currentCandle, ipuOutput);
        double vtdPenalty = 0;  // Placeholder - needs IV data
        double exhaustionPenalty = ipuOutput != null && ipuOutput.isExhaustionWarning() ? 0.2 : 0;
        double divergencePenalty = securityRegime != null && !securityRegime.isAlignedWithIndex() ? 0.15 : 0;

        // Calculate final magnitude
        double finalMagnitude = baseSignal
                * aclMultiplier
                * volumeMultiplier
                * cssMultiplier
                * regimeMultiplier
                * ignitionMultiplier
                * (1 - Math.min(somPenalty, somPenaltyMax))
                * (1 - Math.min(vtdPenalty, vtdPenaltyMax))
                * (1 - exhaustionPenalty)
                * (1 - divergencePenalty);

        finalMagnitude = Math.min(Math.max(finalMagnitude, 0), 1);

        // Determine direction
        Direction direction = determineDirection(ipuOutput, vcpOutput, securityRegime);
        double directionConfidence = calculateDirectionConfidence(ipuOutput, vcpOutput, securityRegime, indexRegime);

        // Get signal type
        String signalType = tradingSignal != null ? tradingSignal.getSignal().name() : "NO_SIGNAL";

        // Build component scores
        ComponentScores components = ComponentScores.builder()
                .ipuScore(ipuScore)
                .vcpScore(vcpScore)
                .regimeStrength(regimeStrength)
                .securityStrength(securityStrength)
                .fudkiiStrength(fudkiiStrength)
                .momentumScore(momentumScore)
                .build();

        // Build multiplier breakdown
        MultiplierBreakdown multipliers = MultiplierBreakdown.builder()
                .aclMultiplier(aclMultiplier)
                .volumeMultiplier(volumeMultiplier)
                .cssMultiplier(cssMultiplier)
                .regimeMultiplier(regimeMultiplier)
                .ignitionMultiplier(ignitionMultiplier)
                .build();

        // Build penalty breakdown
        PenaltyBreakdown penalties = PenaltyBreakdown.builder()
                .somPenalty(somPenalty)
                .vtdPenalty(vtdPenalty)
                .exhaustionPenalty(exhaustionPenalty)
                .divergencePenalty(divergencePenalty)
                .build();

        // Build trade params
        TradeParams tradeParams = calculateTradeParams(currentCandle, direction, finalMagnitude);

        // Build quality flags
        QualityFlags flags = QualityFlags.builder()
                .indexAligned(securityRegime != null && securityRegime.isAlignedWithIndex())
                .ignitionDetected(fudkiiOutput != null && fudkiiOutput.isIgnitionFlag())
                .highConfidence(finalMagnitude >= 0.7 && directionConfidence >= 0.6)
                .exhaustionWarning(ipuOutput != null && ipuOutput.isExhaustionWarning())
                .volumeConfirmed(volumeMultiplier >= 1.0)
                .multiTfAligned(indexRegime != null && indexRegime.getMultiTfAgreementScore() >= 0.75)
                .build();

        return FinalMagnitude.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .finalMagnitude(finalMagnitude)
                .direction(direction)
                .directionConfidence(directionConfidence)
                .signalType(signalType)
                .components(components)
                .multipliers(multipliers)
                .penalties(penalties)
                .tradeParams(tradeParams)
                .flags(flags)
                .build();
    }

    /**
     * Calculate volume multiplier from candle and IPU
     */
    private double calculateVolumeMultiplier(UnifiedCandle candle, IPUOutput ipu) {
        if (candle == null) return 1.0;
        
        double volExpansion = ipu != null ? ipu.getVolExpansionScore() : 0.5;
        
        // Scale from 0.5 to 1.5 based on volume expansion
        return 0.5 + volExpansion;
    }

    /**
     * Calculate CSS multiplier from VCP and security regime
     */
    private double calculateCSSMultiplier(MTVCPOutput vcp, SecurityRegime security) {
        double vcpStructure = vcp != null ? vcp.getStructuralBias() : 0;
        double securityStrength = security != null ? security.getSecurityContextScore() : 0.5;
        
        // Combine fast (VCP) and slow (regime) structure
        double css = 0.5 * Math.abs(vcpStructure) + 0.5 * securityStrength;
        
        // Scale to multiplier range [0.8, 1.2]
        return 0.8 + 0.4 * css;
    }

    /**
     * Calculate regime alignment multiplier
     */
    private double calculateRegimeMultiplier(SecurityRegime security, IndexRegime index) {
        if (security == null || index == null) return 1.0;
        
        if (security.isAlignedWithIndex()) {
            return regimeAlignedBoost;
        } else {
            return regimeDivergedPenalty;
        }
    }

    /**
     * Calculate SOM (choppiness) penalty
     */
    private double calculateSOMPenalty(UnifiedCandle candle, IPUOutput ipu) {
        if (candle == null) return 0;
        
        // Wick ratio as choppiness proxy
        double totalRange = candle.getHigh() - candle.getLow();
        if (totalRange <= 0) return 0;
        
        double body = Math.abs(candle.getClose() - candle.getOpen());
        double wickRatio = 1 - (body / totalRange);
        
        // High wicks = high penalty
        if (wickRatio > 0.7) return 0.3;
        if (wickRatio > 0.5) return 0.15;
        return 0;
    }

    /**
     * Determine overall direction
     */
    private Direction determineDirection(IPUOutput ipu, MTVCPOutput vcp, SecurityRegime security) {
        int bullishVotes = 0, bearishVotes = 0;
        
        if (ipu != null) {
            if (ipu.getDirection() == IPUOutput.Direction.BULLISH) bullishVotes++;
            else if (ipu.getDirection() == IPUOutput.Direction.BEARISH) bearishVotes++;
        }
        
        if (vcp != null) {
            if (vcp.getStructuralBias() > 0.2) bullishVotes++;
            else if (vcp.getStructuralBias() < -0.2) bearishVotes++;
        }
        
        if (security != null && security.getLabel() != null) {
            if (security.getLabel().isBullish()) bullishVotes++;
            else if (security.getLabel().isBearish()) bearishVotes++;
        }
        
        if (bullishVotes > bearishVotes) return Direction.BULLISH;
        if (bearishVotes > bullishVotes) return Direction.BEARISH;
        return Direction.NEUTRAL;
    }

    /**
     * Calculate direction confidence
     */
    private double calculateDirectionConfidence(IPUOutput ipu, MTVCPOutput vcp, 
                                                 SecurityRegime security, IndexRegime index) {
        int totalVotes = 0, alignedVotes = 0;
        Direction mainDirection = determineDirection(ipu, vcp, security);
        
        // IPU vote
        if (ipu != null && ipu.getDirection() != IPUOutput.Direction.NEUTRAL) {
            totalVotes++;
            if ((mainDirection == Direction.BULLISH && ipu.getDirection() == IPUOutput.Direction.BULLISH) ||
                (mainDirection == Direction.BEARISH && ipu.getDirection() == IPUOutput.Direction.BEARISH)) {
                alignedVotes++;
            }
        }
        
        // VCP vote
        if (vcp != null && Math.abs(vcp.getStructuralBias()) > 0.2) {
            totalVotes++;
            if ((mainDirection == Direction.BULLISH && vcp.getStructuralBias() > 0) ||
                (mainDirection == Direction.BEARISH && vcp.getStructuralBias() < 0)) {
                alignedVotes++;
            }
        }
        
        // Security regime vote
        if (security != null && security.getLabel() != null && !security.getLabel().isNeutral()) {
            totalVotes++;
            if ((mainDirection == Direction.BULLISH && security.getLabel().isBullish()) ||
                (mainDirection == Direction.BEARISH && security.getLabel().isBearish())) {
                alignedVotes++;
            }
        }
        
        // Index regime vote
        if (index != null && index.getLabel() != null && !index.getLabel().isNeutral()) {
            totalVotes++;
            if ((mainDirection == Direction.BULLISH && index.getLabel().isBullish()) ||
                (mainDirection == Direction.BEARISH && index.getLabel().isBearish())) {
                alignedVotes++;
            }
        }
        
        return totalVotes > 0 ? (double) alignedVotes / totalVotes : 0;
    }

    /**
     * Calculate trade parameters
     */
    private TradeParams calculateTradeParams(UnifiedCandle candle, Direction direction, double magnitude) {
        if (candle == null) {
            return TradeParams.builder().build();
        }
        
        double entry = candle.getClose();
        double atr = candle.getHigh() - candle.getLow();  // Simplified ATR proxy
        
        double stopLoss, target1, target2;
        if (direction == Direction.BULLISH) {
            stopLoss = entry - 1.5 * atr;
            target1 = entry + 2.0 * atr;
            target2 = entry + 3.5 * atr;
        } else if (direction == Direction.BEARISH) {
            stopLoss = entry + 1.5 * atr;
            target1 = entry - 2.0 * atr;
            target2 = entry - 3.5 * atr;
        } else {
            stopLoss = entry;
            target1 = entry;
            target2 = entry;
        }
        
        double rr = atr > 0 ? (target1 - entry) / (entry - stopLoss) : 0;
        
        // Position size based on magnitude
        double positionPct = magnitude * 5;  // Up to 5% of capital
        positionPct = Math.min(positionPct, 5);
        
        return TradeParams.builder()
                .entryPrice(entry)
                .stopLoss(stopLoss)
                .target1(target1)
                .target2(target2)
                .riskRewardRatio(Math.abs(rr))
                .suggestedPositionPct(positionPct)
                .build();
    }
}
