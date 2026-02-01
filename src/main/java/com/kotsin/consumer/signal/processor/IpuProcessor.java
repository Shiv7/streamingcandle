package com.kotsin.consumer.signal.processor;

import com.kotsin.consumer.config.IPUConfig;
import com.kotsin.consumer.logging.TraceContext;
import com.kotsin.consumer.model.OIMetrics;
import com.kotsin.consumer.model.StrategyState.IpuSnapshot;
import com.kotsin.consumer.model.StrategyState.IpuState;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * IpuProcessor - Institutional Participation & Urgency calculation.
 *
 * IPU measures the intensity and direction of institutional activity:
 * - Institutional Proxy: Large trades, volume spikes, price impact
 * - Urgency: Speed of price movement, momentum acceleration
 * - Exhaustion: Signs of trend reversal or continuation
 *
 * Key Outputs:
 * - IPU Score (0-1): Overall institutional activity level
 * - Direction: BULLISH, BEARISH, NEUTRAL
 * - Momentum State: ACCELERATING, DECELERATING, STEADY, EXHAUSTED
 * - Exhaustion Score: Likelihood of reversal
 */
@Component
@Slf4j
public class IpuProcessor {

    private static final String LOG_PREFIX = "[IPU]";

    @Autowired(required = false)
    private IPUConfig ipuConfig;

    // Default thresholds
    private static final double LARGE_TRADE_THRESHOLD = 10000;
    private static final double VOLUME_SPIKE_THRESHOLD = 2.0;  // 2x average
    private static final int LOOKBACK_PERIOD = 20;

    /**
     * Calculate IPU score for a single candle.
     *
     * @param candle Current candle
     * @param history Previous candles for context
     * @return IpuSnapshot for this period
     */
    public IpuSnapshot calculate(UnifiedCandle candle, List<UnifiedCandle> history) {
        TraceContext.addStage("IPU");

        if (candle == null) {
            log.debug("{} {} No candle provided, returning empty snapshot",
                LOG_PREFIX, TraceContext.getShortPrefix());
            return createEmptySnapshot();
        }

        log.debug("{} {} Calculating IPU with {} history candles",
            LOG_PREFIX, TraceContext.getShortPrefix(),
            history != null ? history.size() : 0);

        // Calculate components
        double instProxy = calculateInstitutionalProxy(candle, history);
        double momentum = calculateMomentumContext(candle, history);
        double exhaustion = calculateExhaustion(candle, history);
        double urgency = calculateUrgency(candle, history);
        String direction = determineDirection(candle, history);

        // Composite IPU score
        double ipuScore = calculateCompositeScore(instProxy, momentum, exhaustion, urgency);

        log.debug("{} {} IPU complete: score={}, direction={}, exhaustion={}, instProxy={}, momentum={}, urgency={}",
            LOG_PREFIX, TraceContext.getShortPrefix(),
            String.format("%.3f", ipuScore),
            direction,
            String.format("%.3f", exhaustion),
            String.format("%.3f", instProxy),
            String.format("%.3f", momentum),
            String.format("%.3f", urgency));

        return IpuSnapshot.builder()
            .timestamp(candle.getTimestamp())
            .ipuScore(ipuScore)
            .exhaustionScore(exhaustion)
            .instProxy(instProxy)
            .momentumContext(momentum)
            .direction(direction)
            .build();
    }

    /**
     * Update IpuState with new snapshot.
     */
    public IpuState updateState(IpuState currentState, IpuSnapshot snapshot) {
        if (currentState == null) {
            currentState = IpuState.builder()
                .history(new ArrayList<>())
                .build();
        }

        List<IpuSnapshot> history = currentState.getHistory();
        if (history == null) {
            history = new ArrayList<>();
        }

        // Add new snapshot to front
        history.add(0, snapshot);
        if (history.size() > 100) {
            history = new ArrayList<>(history.subList(0, 100));
        }

        // Update current values
        currentState.setHistory(history);
        currentState.setCurrentIpuScore(snapshot.getIpuScore());
        currentState.setCurrentExhaustion(snapshot.getExhaustionScore());
        currentState.setCurrentMomentumState(determineMomentumState(history));
        currentState.setCurrentDirection(snapshot.getDirection());

        // Calculate rolling averages
        if (history.size() >= 10) {
            double avg10 = history.subList(0, 10).stream()
                .mapToDouble(IpuSnapshot::getIpuScore)
                .average().orElse(0);
            currentState.setAvgIpuScore10(avg10);
        }

        if (history.size() >= 20) {
            double avg20 = history.subList(0, 20).stream()
                .mapToDouble(IpuSnapshot::getIpuScore)
                .average().orElse(0);
            currentState.setAvgIpuScore20(avg20);
        }

        // Trend detection
        currentState.setIpuRising(isIpuRising(history));
        currentState.setExhaustionBuilding(isExhaustionBuilding(history));
        currentState.setCalculatedAt(Instant.now());

        return currentState;
    }

    /**
     * Calculate Institutional Proxy score.
     * Measures presence of large institutional trades.
     */
    private double calculateInstitutionalProxy(UnifiedCandle candle, List<UnifiedCandle> history) {
        double score = 0;

        // 1. Large trade count
        int largeTradeCount = candle.getLargeTradeCount();
        double avgLargeTrades = getAverageLargeTrades(history);
        if (avgLargeTrades > 0) {
            score += Math.min(1, largeTradeCount / (avgLargeTrades * 2)) * 0.25;
        }

        // 2. Volume spike
        long volume = candle.getVolume();
        double avgVolume = getAverageVolume(history);
        if (avgVolume > 0) {
            double volumeRatio = volume / avgVolume;
            score += Math.min(1, volumeRatio / VOLUME_SPIKE_THRESHOLD) * 0.25;
        }

        // 3. Price impact (Kyle's Lambda if available)
        if (candle.isHasOrderbook() && candle.getKyleLambda() != null) {
            double kyleLambda = candle.getKyleLambda();
            // Higher lambda = more price impact = institutional activity
            score += Math.min(1, kyleLambda * 100) * 0.25;
        }

        // 4. OFI (Order Flow Imbalance)
        if (candle.isHasOrderbook() && candle.getOfi() != null) {
            double ofi = Math.abs(candle.getOfi());
            double avgOfi = getAverageOfi(history);
            if (avgOfi > 0) {
                score += Math.min(1, ofi / (avgOfi * 2)) * 0.25;
            }
        }

        return Math.min(1, score);
    }

    /**
     * Calculate Momentum Context score.
     * Measures the speed and direction of price movement.
     */
    private double calculateMomentumContext(UnifiedCandle candle, List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) return 0;

        // 1. Price momentum (rate of change)
        double priceChange = candle.getClose() - candle.getOpen();
        double avgPriceChange = getAveragePriceChange(history);
        double priceRatio = avgPriceChange != 0 ? Math.abs(priceChange / avgPriceChange) : 0;

        // 2. Volume confirmation
        double volumeConfirmation = 0;
        if (priceChange > 0 && candle.getBuyPressure() > 0.6) {
            volumeConfirmation = 1;
        } else if (priceChange < 0 && candle.getSellPressure() > 0.6) {
            volumeConfirmation = 1;
        }

        // 3. Consecutive direction
        int consecutiveDir = getConsecutiveDirection(history, priceChange > 0);
        double consecutiveScore = Math.min(1, consecutiveDir / 5.0);

        // 4. ATR-normalized move
        double atr = calculateATR(history, 14);
        double atrNormalized = atr > 0 ? Math.abs(priceChange) / atr : 0;

        return (priceRatio * 0.3 + volumeConfirmation * 0.3 +
                consecutiveScore * 0.2 + atrNormalized * 0.2);
    }

    /**
     * Calculate Exhaustion score.
     * Measures signs of trend reversal.
     */
    private double calculateExhaustion(UnifiedCandle candle, List<UnifiedCandle> history) {
        double exhaustion = 0;

        // 1. Volume divergence (price up, volume down or vice versa)
        double priceChange = candle.getClose() - candle.getOpen();
        double volumeChange = getVolumeChange(candle, history);

        if ((priceChange > 0 && volumeChange < 0) || (priceChange < 0 && volumeChange > 0)) {
            exhaustion += 0.3;
        }

        // 2. Wicks (rejection)
        double bodySize = Math.abs(candle.getClose() - candle.getOpen());
        double totalRange = candle.getHigh() - candle.getLow();
        if (totalRange > 0) {
            double wickRatio = 1 - (bodySize / totalRange);
            exhaustion += wickRatio * 0.2;
        }

        // 3. Buy/Sell pressure divergence
        if (priceChange > 0 && candle.getSellPressure() > candle.getBuyPressure()) {
            exhaustion += 0.2;
        } else if (priceChange < 0 && candle.getBuyPressure() > candle.getSellPressure()) {
            exhaustion += 0.2;
        }

        // 4. OI interpretation suggests reversal
        if (candle.isHasOI() && candle.getOiSuggestsReversal() != null && candle.getOiSuggestsReversal()) {
            exhaustion += 0.3;
        }

        return Math.min(1, exhaustion);
    }

    /**
     * Calculate Urgency score.
     * Measures speed/intensity of current move.
     */
    private double calculateUrgency(UnifiedCandle candle, List<UnifiedCandle> history) {
        double urgency = 0;

        // 1. Tick count (activity level)
        int tickCount = candle.getTickCount();
        double avgTicks = getAverageTickCount(history);
        if (avgTicks > 0) {
            urgency += Math.min(1, tickCount / (avgTicks * 2)) * 0.3;
        }

        // 2. VIB/DIB triggers
        if (candle.isVibTriggered()) urgency += 0.3;
        if (candle.isDibTriggered()) urgency += 0.2;

        // 3. Spread tightness (tight spread = urgent)
        if (candle.isHasOrderbook() && candle.getSpreadPercent() != null) {
            // Tighter spread (lower percentage) = more urgent
            double spreadScore = Math.max(0, 1 - candle.getSpreadPercent() * 10);
            urgency += spreadScore * 0.2;
        }

        return Math.min(1, urgency);
    }

    /**
     * Determine direction from price action and order flow.
     */
    private String determineDirection(UnifiedCandle candle, List<UnifiedCandle> history) {
        int bullishPoints = 0;
        int bearishPoints = 0;

        // Price direction
        if (candle.getClose() > candle.getOpen()) bullishPoints += 2;
        else if (candle.getClose() < candle.getOpen()) bearishPoints += 2;

        // Buy/Sell pressure
        if (candle.getBuyPressure() > 0.6) bullishPoints++;
        if (candle.getSellPressure() > 0.6) bearishPoints++;

        // OFI direction
        if (candle.isHasOrderbook() && candle.getOfi() != null) {
            if (candle.getOfi() > 0) bullishPoints++;
            else if (candle.getOfi() < 0) bearishPoints++;
        }

        // OI interpretation
        if (candle.isHasOI() && candle.getOiInterpretation() != null) {
            OIMetrics.OIInterpretation interp = candle.getOiInterpretation();
            if (interp == OIMetrics.OIInterpretation.LONG_BUILDUP ||
                interp == OIMetrics.OIInterpretation.SHORT_COVERING) {
                bullishPoints += 2;
            } else if (interp == OIMetrics.OIInterpretation.SHORT_BUILDUP ||
                       interp == OIMetrics.OIInterpretation.LONG_UNWINDING) {
                bearishPoints += 2;
            }
        }

        if (bullishPoints > bearishPoints + 1) return "BULLISH";
        if (bearishPoints > bullishPoints + 1) return "BEARISH";
        return "NEUTRAL";
    }

    /**
     * Calculate composite IPU score.
     */
    private double calculateCompositeScore(double instProxy, double momentum,
                                           double exhaustion, double urgency) {
        // Higher exhaustion reduces score
        double exhaustionPenalty = 1 - (exhaustion * 0.3);

        double raw = (instProxy * 0.30 +
                     momentum * 0.30 +
                     urgency * 0.40) * exhaustionPenalty;

        return Math.min(1, Math.max(0, raw));
    }

    /**
     * Determine momentum state from history.
     */
    private String determineMomentumState(List<IpuSnapshot> history) {
        if (history.size() < 5) return "STEADY";

        double current = history.get(0).getIpuScore();
        double recent = history.subList(0, Math.min(5, history.size())).stream()
            .mapToDouble(IpuSnapshot::getIpuScore)
            .average().orElse(0);
        double older = history.subList(0, Math.min(10, history.size())).stream()
            .mapToDouble(IpuSnapshot::getIpuScore)
            .average().orElse(0);

        double currentExhaustion = history.get(0).getExhaustionScore();

        if (currentExhaustion > 0.7) {
            return "EXHAUSTED";
        }

        if (current > recent * 1.1 && recent > older * 1.1) {
            return "ACCELERATING";
        }

        if (current < recent * 0.9 && recent < older * 0.9) {
            return "DECELERATING";
        }

        // Check for reversal
        if (history.size() >= 3) {
            String prevDir = history.get(2).getDirection();
            String currDir = history.get(0).getDirection();
            if (!prevDir.equals(currDir) && !"NEUTRAL".equals(currDir)) {
                return "REVERSING";
            }
        }

        return "STEADY";
    }

    /**
     * Check if IPU is rising.
     */
    private boolean isIpuRising(List<IpuSnapshot> history) {
        if (history.size() < 3) return false;

        double recent = history.subList(0, 3).stream()
            .mapToDouble(IpuSnapshot::getIpuScore).average().orElse(0);
        double older = history.size() >= 6 ?
            history.subList(3, 6).stream()
                .mapToDouble(IpuSnapshot::getIpuScore).average().orElse(0) :
            0;

        return recent > older * 1.1;
    }

    /**
     * Check if exhaustion is building.
     */
    private boolean isExhaustionBuilding(List<IpuSnapshot> history) {
        if (history.size() < 3) return false;

        double recentExhaustion = history.subList(0, 3).stream()
            .mapToDouble(IpuSnapshot::getExhaustionScore).average().orElse(0);
        double olderExhaustion = history.size() >= 6 ?
            history.subList(3, 6).stream()
                .mapToDouble(IpuSnapshot::getExhaustionScore).average().orElse(0) :
            0;

        return recentExhaustion > olderExhaustion * 1.2;
    }

    // ==================== HELPER METHODS ====================

    private IpuSnapshot createEmptySnapshot() {
        return IpuSnapshot.builder()
            .timestamp(Instant.now())
            .ipuScore(0)
            .exhaustionScore(0)
            .instProxy(0)
            .momentumContext(0)
            .direction("NEUTRAL")
            .build();
    }

    private double getAverageLargeTrades(List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) return 0;
        return history.stream()
            .filter(c -> c != null)
            .mapToInt(UnifiedCandle::getLargeTradeCount)
            .average().orElse(0);
    }

    private double getAverageVolume(List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) return 0;
        return history.stream()
            .filter(c -> c != null)
            .mapToLong(UnifiedCandle::getVolume)
            .average().orElse(0);
    }

    private double getAverageOfi(List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) return 0;
        return history.stream()
            .filter(c -> c != null && c.isHasOrderbook() && c.getOfi() != null)
            .mapToDouble(c -> Math.abs(c.getOfi()))
            .average().orElse(0);
    }

    private double getAveragePriceChange(List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) return 0;
        return history.stream()
            .filter(c -> c != null)
            .mapToDouble(c -> Math.abs(c.getClose() - c.getOpen()))
            .average().orElse(0);
    }

    private double getAverageTickCount(List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) return 0;
        return history.stream()
            .filter(c -> c != null)
            .mapToInt(UnifiedCandle::getTickCount)
            .average().orElse(0);
    }

    private int getConsecutiveDirection(List<UnifiedCandle> history, boolean bullish) {
        if (history == null || history.isEmpty()) return 0;
        int count = 0;
        for (UnifiedCandle candle : history) {
            if (candle == null) continue;
            boolean up = candle.getClose() > candle.getOpen();
            if (up == bullish) count++;
            else break;
        }
        return count;
    }

    private double getVolumeChange(UnifiedCandle candle, List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) return 0;
        double avgVolume = getAverageVolume(history);
        return avgVolume > 0 ? (candle.getVolume() - avgVolume) / avgVolume : 0;
    }

    private double calculateATR(List<UnifiedCandle> history, int period) {
        if (history == null || history.size() < period) return 0;

        double atr = 0;
        for (int i = 0; i < Math.min(period, history.size()); i++) {
            UnifiedCandle c = history.get(i);
            if (c == null) continue;
            double tr = c.getHigh() - c.getLow();
            if (i > 0 && history.get(i - 1) != null) {
                double prevClose = history.get(i - 1).getClose();
                tr = Math.max(tr, Math.max(
                    Math.abs(c.getHigh() - prevClose),
                    Math.abs(c.getLow() - prevClose)));
            }
            atr += tr;
        }
        return atr / period;
    }
}
