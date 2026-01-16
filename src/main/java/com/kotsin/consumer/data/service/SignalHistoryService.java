package com.kotsin.consumer.data.service;

import com.kotsin.consumer.capital.model.FinalMagnitude;
import com.kotsin.consumer.curated.model.*;
import com.kotsin.consumer.data.model.SignalHistory;
import com.kotsin.consumer.data.repository.SignalHistoryRepository;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.gate.model.GateResult;
import com.kotsin.consumer.model.IPUOutput;
import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.regime.model.ACLOutput;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.SecurityRegime;
import com.kotsin.consumer.signal.model.CSSOutput;
import com.kotsin.consumer.stats.model.TradeOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * SignalHistoryService - Manages signal history records
 * 
 * Creates comprehensive signal records with all features,
 * gate decisions, and eventual outcomes for future ML.
 */
@Service
public class SignalHistoryService {

    private static final Logger log = LoggerFactory.getLogger(SignalHistoryService.class);

    @Autowired
    private SignalHistoryRepository historyRepo;

    /**
     * Create a new signal history record with all features
     */
    public SignalHistory createHistory(
            String scripCode,
            String signalType,
            String direction,
            MultiTFBreakout breakout,
            FamilyCandle family,
            IndexRegime indexRegime,
            SecurityRegime securityRegime,
            MTVCPOutput vcp,
            IPUOutput ipu,
            CSSOutput css,
            ACLOutput acl,
            FinalMagnitude fma,
            FuturesOptionsAlignment foAlignment,
            MultiTimeframeLevels levels,
            RetestEntry entry,
            int mtfConfirmations
    ) {
        SignalHistory history = SignalHistory.create(scripCode, signalType, direction);
        
        // Company name
        if (vcp != null) {
            history.setCompanyName(vcp.getCompanyName());
        }
        
        // Populate VCP features
        if (vcp != null) {
            history.setVcpCombined(vcp.getVcpCombinedScore());
            history.setVcpRunway(vcp.getRunwayScore());
            history.setVcpStructuralBias(vcp.getStructuralBias());
            history.setVcpSupportScore(vcp.getSupportScore());
            history.setVcpResistanceScore(vcp.getResistanceScore());
        }
        
        // Populate IPU features
        if (ipu != null) {
            history.setIpuFinalScore(ipu.getFinalIpuScore());
            history.setIpuInstProxy(ipu.getInstProxy());
            history.setIpuMomentum(ipu.getMomentumContext());
            history.setIpuExhaustion(ipu.getExhaustionScore());
            history.setIpuUrgency(ipu.getUrgencyScore());
            history.setIpuDirectionalConviction(ipu.getDirectionalConviction());
            history.setIpuXfactor(ipu.isXfactorFlag());
            history.setIpuMomentumState(ipu.getMomentumState() != null ? ipu.getMomentumState().name() : null);
            history.setIpuDirection(ipu.getDirection() != null ? ipu.getDirection().name() : null);
        }
        
        // Populate Index Regime features
        if (indexRegime != null) {
            history.setIndexRegimeLabel(indexRegime.getLabel() != null ? indexRegime.getLabel().name() : null);
            history.setIndexRegimeStrength(indexRegime.getRegimeStrength());
            history.setIndexRegimeCoherence(indexRegime.getRegimeCoherence());
            history.setIndexVolatilityState(indexRegime.getVolatilityState() != null ? indexRegime.getVolatilityState().name() : null);
            history.setSessionPhase(indexRegime.getSessionPhase() != null ? indexRegime.getSessionPhase().name() : null);
        }
        
        // Populate Security Regime features
        if (securityRegime != null) {
            history.setSecurityRegimeLabel(securityRegime.getLabel() != null ? securityRegime.getLabel().name() : null);
            history.setSecurityStrength(securityRegime.getSecurityContextScore());
            history.setSecurityAligned(securityRegime.isAlignedWithIndex());
            history.setSecurityEmaAlignment("EMA20/50"); // Simplified for new formula
            history.setSecurityAtrState(securityRegime.getAtrState() != null ? securityRegime.getAtrState().name() : null);
        }
        
        // Populate OI/F&O features from FamilyCandle
        if (family != null) {
            history.setOiSignal(family.getOiSignal());
            history.setPcr(family.getPcr());
            history.setPcrChange(family.getPcrChange());
            history.setSpotFuturePremium(family.getSpotFuturePremium());
            history.setFuturesBuildup(family.getFuturesBuildup());
            history.setTotalCallOI(family.getTotalCallOI());
            history.setTotalPutOI(family.getTotalPutOI());
        }
        
        // Populate F&O Alignment
        if (foAlignment != null) {
            history.setFoAlignmentScore(foAlignment.getAlignmentScore());
            history.setFoBias(foAlignment.getBias() != null ? foAlignment.getBias().name() : null);
            history.setFoAligned(foAlignment.isAligned());
        }
        
        // Populate MTF features
        history.setMtfConfluenceCount(mtfConfirmations);
        
        // Populate breakout features
        if (breakout != null) {
            history.setBreakoutConfirmations(breakout.getConfirmations());
            history.setBreakoutVolumeZScore(breakout.getAvgVolumeZScore());
            history.setBreakoutKyleLambda(breakout.getAvgKyleLambda());
            history.setBreakoutPattern(breakout.getPattern() != null 
                    ? (breakout.getPattern().isCoiling() ? "COILING" : "CONSOLIDATION")
                    : null);
            
            if (breakout.getPrimaryBreakout() != null) {
                history.setBreakoutPivotLevel(breakout.getPrimaryBreakout().getPivotLevel());
                history.setBreakoutHigh(breakout.getPrimaryBreakout().getBreakoutHigh());
                history.setBreakoutLow(breakout.getPrimaryBreakout().getBreakoutLow());
            }
        }
        
        // Populate entry features
        if (entry != null) {
            history.setEntryPrice(entry.getEntryPrice());
            history.setStopLoss(entry.getStopLoss());
            history.setTarget(entry.getTarget());
            history.setRiskRewardRatio(entry.getRiskReward());
            history.setPositionMultiplier(entry.getPositionSizeMultiplier());
        }
        
        return history;
    }

    /**
     * Record gate results
     */
    public void recordGateResults(
            SignalHistory history,
            GateResult hardGate,
            GateResult mtfGate,
            GateResult qualityGate,
            GateResult statsGate
    ) {
        if (hardGate != null) {
            history.setHardGatePassed(hardGate.isPassed());
            history.setHardGateReason(hardGate.getReason());
            history.setHardGateDetail(hardGate.getDetail());
        }
        
        if (mtfGate != null) {
            history.setMtfGatePassed(mtfGate.isPassed());
            history.setMtfGateReason(mtfGate.getReason());
            history.setMtfGateDetail(mtfGate.getDetail());
        }
        
        if (qualityGate != null) {
            history.setQualityGatePassed(qualityGate.isPassed());
            history.setQualityGateReason(qualityGate.getReason());
            history.setQualityGateDetail(qualityGate.getDetail());
        }
        
        if (statsGate != null) {
            history.setStatsGatePassed(statsGate.isPassed());
            history.setStatsGateReason(statsGate.getReason());
            history.setStatsGateDetail(statsGate.getDetail());
        }
    }

    /**
     * Mark signal as emitted
     */
    public void markEmitted(SignalHistory history, double curatedScore, double positionMultiplier) {
        history.setSignalEmitted(true);
        history.setFinalCuratedScore(curatedScore);
        history.setPositionMultiplier(positionMultiplier);
        history.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * Mark signal as rejected
     */
    public void markRejected(SignalHistory history, String rejectReason) {
        history.setSignalEmitted(false);
        history.setRejectReason(rejectReason);
        history.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * Save history to MongoDB
     */
    public SignalHistory save(SignalHistory history) {
        return historyRepo.save(history);
    }

    /**
     * Link trade outcome to signal history
     */
    public void linkOutcome(TradeOutcome outcome) {
        if (outcome == null || outcome.getSignalId() == null) {
            log.warn("Cannot link outcome - missing signalId");
            return;
        }
        
        Optional<SignalHistory> historyOpt = historyRepo.findBySignalId(outcome.getSignalId());
        if (historyOpt.isEmpty()) {
            log.warn("No signal history found for signalId: {}", outcome.getSignalId());
            return;
        }
        
        SignalHistory history = historyOpt.get();
        history.updateOutcome(
                outcome.isWin(),
                outcome.getRMultiple(),
                outcome.getPnl(),
                outcome.getExitReason()
        );
        historyRepo.save(history);
        
        log.debug("OUTCOME LINKED | signalId={} | win={} | R={}",
                outcome.getSignalId(), outcome.isWin(), String.format("%.2f", outcome.getRMultiple()));
    }

    /**
     * Find history by signalId
     */
    public Optional<SignalHistory> findBySignalId(String signalId) {
        return historyRepo.findBySignalId(signalId);
    }
}

