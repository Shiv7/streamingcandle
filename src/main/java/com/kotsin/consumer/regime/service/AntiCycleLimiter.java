package com.kotsin.consumer.regime.service;

import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.regime.model.ACLOutput;
import com.kotsin.consumer.regime.model.ACLOutput.ACLState;
import com.kotsin.consumer.regime.model.IndexRegime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AntiCycleLimiter - Module 3: ACL
 * 
 * Prevents late-stage entries into exhausted trends.
 * Tracks trend age across timeframes and applies multipliers based on:
 * - Cross-TF agreement score
 * - Ordered vs chaotic transitions
 * - Flow disagreement acceleration
 * 
 * Rules:
 * - Ordered transition (higher TF leads) = 10% bonus
 * - Chaotic disagreement = 30% penalty
 * - Flow disagreement accelerates aging by 1.5x
 */
@Slf4j
@Service
public class AntiCycleLimiter {

    // Trend age thresholds (in candles)
    @Value("${acl.early.threshold:5}")
    private int earlyThreshold;

    @Value("${acl.mid.threshold:15}")
    private int midThreshold;

    @Value("${acl.late.threshold:30}")
    private int lateThreshold;

    // State stores for trend tracking per scripCode
    private final ConcurrentHashMap<String, TrendState> trendStates = new ConcurrentHashMap<>();

    /**
     * Internal trend state tracking
     */
    private static class TrendState {
        int direction;           // +1/-1/0
        int age30m;
        int age2H;
        int age4H;
        int age1D;
        long lastUpdate;
        
        void reset(int newDirection) {
            this.direction = newDirection;
            this.age30m = 0;
            this.age2H = 0;
            this.age4H = 0;
            this.age1D = 0;
            this.lastUpdate = System.currentTimeMillis();
        }
        
        void incrementAge(String timeframe) {
            switch (timeframe) {
                case "30m": age30m++; break;
                case "2h": age2H++; break;
                case "4h": age4H++; break;
                case "1d": age1D++; break;
            }
            this.lastUpdate = System.currentTimeMillis();
        }
    }

    /**
     * Calculate ACL output for a security
     * 
     * @param scripCode Security's scrip code
     * @param companyName Security's company name
     * @param candles30m 30m candle history
     * @param indexRegime Parent index regime
     * @return ACLOutput with multiplier and state
     */
    public ACLOutput calculate(
            String scripCode,
            String companyName,
            List<UnifiedCandle> candles30m,
            IndexRegime indexRegime) {

        if (candles30m == null || candles30m.size() < 3) {
            return emptyResult(scripCode, companyName);
        }

        // Get or create trend state
        TrendState state = trendStates.computeIfAbsent(scripCode, k -> new TrendState());

        // Calculate current flow direction
        int currentFlow = calculateFlowDirection(candles30m, 3);

        // Check if trend has changed
        // FIX: Handle neutral flow (0) - don't increment age if flow becomes neutral
        if (state.direction != currentFlow && currentFlow != 0) {
            state.reset(currentFlow);
        } else if (currentFlow == state.direction && currentFlow != 0) {
            state.incrementAge("30m");
        }
        // If currentFlow == 0 (neutral), don't increment age - trend is unclear

        // Get flow agreements from index
        int[] tfFlowAgreements = new int[4];
        if (indexRegime != null) {
            tfFlowAgreements[0] = indexRegime.getTf30m() != null ? indexRegime.getTf30m().getFlowAgreement() : 0;
            tfFlowAgreements[1] = indexRegime.getTf2H() != null ? indexRegime.getTf2H().getFlowAgreement() : 0;
            tfFlowAgreements[2] = 0;  // No 4H data from index
            tfFlowAgreements[3] = indexRegime.getTf1D() != null ? indexRegime.getTf1D().getFlowAgreement() : 0;
        }

        // Calculate agreement score (how many TFs agree)
        int agreementScore = calculateAgreementScore(tfFlowAgreements);

        // Check for ordered transition (higher TF leads lower)
        boolean isOrderedTransition = checkOrderedTransition(tfFlowAgreements, currentFlow);

        // Check for chaotic disagreement
        boolean isChaoticDisagreement = checkChaoticDisagreement(tfFlowAgreements);

        // Calculate effective trend age (with flow disagreement acceleration)
        int effectiveAge = state.age30m;
        if (indexRegime != null && indexRegime.getFlowAgreement() != currentFlow && indexRegime.getFlowAgreement() != 0) {
            effectiveAge = (int) (effectiveAge * 1.5);  // Accelerate aging
        }

        // Determine ACL state based on effective age
        ACLState aclState;
        if (effectiveAge <= earlyThreshold) {
            aclState = ACLState.EARLY_TREND;
        } else if (effectiveAge <= midThreshold) {
            aclState = ACLState.MID_TREND;
        } else if (effectiveAge <= lateThreshold) {
            aclState = ACLState.LATE_TREND;
        } else {
            aclState = ACLState.EXHAUSTION;
        }

        // Apply ordered/chaotic modifiers
        double baseMultiplier = aclState.getMultiplier();
        double finalMultiplier;
        
        if (isOrderedTransition) {
            finalMultiplier = Math.min(baseMultiplier * 1.1, 1.1);  // 10% bonus, cap at 1.1
            if (aclState == ACLState.MID_TREND) {
                aclState = ACLState.EARLY_TREND;  // Upgrade state
            }
        } else if (isChaoticDisagreement) {
            finalMultiplier = baseMultiplier * 0.7;  // 30% penalty
            aclState = ACLState.TRANSITION;
        } else {
            finalMultiplier = baseMultiplier;
        }

        // Check for exhaustion warning
        boolean exhaustionNear = effectiveAge > lateThreshold * 0.8 || aclState == ACLState.EXHAUSTION;

        return ACLOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .trendAge30m(state.age30m)
                .trendAge2H(state.age2H)
                .trendAge4H(state.age4H)
                .trendAge1D(state.age1D)
                .agreementScore(agreementScore)
                .isOrderedTransition(isOrderedTransition)
                .isChaoticDisagreement(isChaoticDisagreement)
                .aclMultiplier(finalMultiplier)
                .aclState(aclState)
                .trendDirection(state.direction)
                .exhaustionNear(exhaustionNear)
                .tfFlowAgreements(tfFlowAgreements)
                .build();
    }

    /**
     * Calculate flow direction from candle history
     */
    private int calculateFlowDirection(List<UnifiedCandle> candles, int bars) {
        if (candles.size() < bars) return 0;
        
        double totalDelta = 0;
        for (int i = candles.size() - bars; i < candles.size(); i++) {
            UnifiedCandle c = candles.get(i);
            totalDelta += c.getBuyVolume() - c.getSellVolume();
        }
        
        if (totalDelta > 0) return 1;
        if (totalDelta < 0) return -1;
        return 0;
    }

    /**
     * Calculate how many TFs agree on direction
     */
    private int calculateAgreementScore(int[] tfFlows) {
        int bullish = 0, bearish = 0;
        for (int flow : tfFlows) {
            if (flow > 0) bullish++;
            else if (flow < 0) bearish++;
        }
        return Math.max(bullish, bearish);
    }

    /**
     * Check if higher TFs are leading lower TFs (ordered transition)
     * 1D -> 2H -> 30m in same direction
     */
    private boolean checkOrderedTransition(int[] tfFlows, int currentFlow) {
        if (currentFlow == 0) return false;
        
        // Check if daily leads the way
        int daily = tfFlows[3];
        int h2 = tfFlows[1];
        int m30 = tfFlows[0];
        
        // Ordered: daily matches current, propagating down
        return daily == currentFlow && (h2 == currentFlow || h2 == 0);
    }

    /**
     * Check for chaotic disagreement (TFs contradict each other)
     */
    private boolean checkChaoticDisagreement(int[] tfFlows) {
        int bullish = 0, bearish = 0;
        for (int flow : tfFlows) {
            if (flow > 0) bullish++;
            else if (flow < 0) bearish++;
        }
        
        // Chaotic if we have both bullish and bearish signals
        return bullish >= 2 && bearish >= 2;
    }

    /**
     * Empty result for insufficient data
     */
    private ACLOutput emptyResult(String scripCode, String companyName) {
        return ACLOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .aclMultiplier(1.0)
                .aclState(ACLState.MID_TREND)
                .agreementScore(0)
                .trendDirection(0)
                .exhaustionNear(false)
                .tfFlowAgreements(new int[4])
                .build();
    }

    /**
     * Clear trend state for a security (on regime change)
     */
    public void clearState(String scripCode) {
        trendStates.remove(scripCode);
    }

    /**
     * Clear all trend states
     */
    public void clearAllStates() {
        trendStates.clear();
    }
}
