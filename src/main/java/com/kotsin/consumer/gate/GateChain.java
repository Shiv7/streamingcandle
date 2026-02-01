package com.kotsin.consumer.gate;

import com.kotsin.consumer.gate.model.GateResult;
import com.kotsin.consumer.gate.model.GateResult.ChainResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GateChain - Orchestrates quality gates for signal filtering.
 *
 * Features:
 * - Runs signals through multiple quality gates
 * - Configurable gate order and requirements
 * - Aggregate scoring
 * - Gate statistics tracking
 */
@Component
@Slf4j
public class GateChain {

    private final List<SignalGate> gates;
    private final Map<String, GateStats> gateStats = new ConcurrentHashMap<>();

    @Autowired
    public GateChain(List<SignalGate> gates) {
        this.gates = new ArrayList<>(gates);
        // Sort by weight (higher weight first)
        this.gates.sort((a, b) -> Double.compare(b.getWeight(), a.getWeight()));

        log.info("GateChain initialized with {} gates: {}",
            gates.size(),
            gates.stream().map(SignalGate::getName).toList());
    }

    /**
     * Evaluate a signal through all gates.
     *
     * @param symbol     Symbol being evaluated
     * @param signalType Signal type (VCP, IPU, etc.)
     * @param context    Context data for evaluation
     * @return ChainResult with aggregate pass/fail and scores
     */
    public ChainResult evaluate(String symbol, String signalType, Map<String, Object> context) {
        ChainResult result = ChainResult.builder()
            .symbol(symbol)
            .signalType(signalType)
            .passed(true)  // Assume pass until failure
            .gateResults(new ArrayList<>())
            .timestamp(Instant.now())
            .build();

        for (SignalGate gate : gates) {
            GateResult gateResult;
            try {
                gateResult = gate.evaluate(context);
            } catch (Exception e) {
                log.error("Gate {} threw exception: {}", gate.getName(), e.getMessage());
                gateResult = GateResult.fail(gate.getName(), gate.getWeight(),
                    "Gate error: " + e.getMessage());
            }

            result.addResult(gateResult);
            updateGateStats(gate.getName(), gateResult.isPassed());

            // Check if required gate failed
            if (gate.isRequired() && !gateResult.isPassed()) {
                result.setPassed(false);
                // Continue evaluating other gates for complete picture
            }
        }

        // Normalize score to 0-100
        double maxPossibleScore = gates.stream().mapToDouble(g -> g.getWeight() * 100).sum();
        if (maxPossibleScore > 0) {
            result.setTotalScore(result.getTotalScore() / maxPossibleScore * 100);
        }

        log.debug("GateChain result for {} {}: passed={} score={:.1f} ({}/{} gates)",
            symbol, signalType, result.isPassed(), result.getTotalScore(),
            result.getGatesPassed(), result.getTotalGates());

        return result;
    }

    /**
     * Quick check if signal passes all required gates.
     */
    public boolean quickCheck(Map<String, Object> context) {
        for (SignalGate gate : gates) {
            if (gate.isRequired()) {
                GateResult result = gate.evaluate(context);
                if (!result.isPassed()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Get minimum score required for a signal to be actionable.
     */
    public double getMinimumScore() {
        return 50.0;  // 50% minimum
    }

    /**
     * Check if a score indicates high quality.
     */
    public boolean isHighQuality(double score) {
        return score >= 70.0;
    }

    /**
     * Check if a score indicates excellent quality.
     */
    public boolean isExcellentQuality(double score) {
        return score >= 85.0;
    }

    /**
     * Get gate by name.
     */
    public SignalGate getGate(String name) {
        return gates.stream()
            .filter(g -> g.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * Get all registered gates.
     */
    public List<SignalGate> getGates() {
        return Collections.unmodifiableList(gates);
    }

    /**
     * Get statistics for a specific gate.
     */
    public GateStats getGateStats(String gateName) {
        return gateStats.get(gateName);
    }

    /**
     * Get statistics for all gates.
     */
    public Map<String, GateStats> getAllGateStats() {
        return new HashMap<>(gateStats);
    }

    /**
     * Get gates sorted by pass rate (lowest first - most restrictive).
     */
    public List<Map.Entry<String, GateStats>> getMostRestrictiveGates() {
        return gateStats.entrySet().stream()
            .sorted(Comparator.comparingDouble(e -> e.getValue().getPassRate()))
            .toList();
    }

    private void updateGateStats(String gateName, boolean passed) {
        gateStats.computeIfAbsent(gateName, k -> new GateStats())
            .record(passed);
    }

    // ==================== HELPER METHODS FOR CONTEXT BUILDING ====================

    /**
     * Create context builder for easier context construction.
     */
    public static ContextBuilder contextBuilder() {
        return new ContextBuilder();
    }

    public static class ContextBuilder {
        private final Map<String, Object> context = new HashMap<>();

        public ContextBuilder signalDirection(String direction) {
            context.put("signalDirection", direction);
            return this;
        }

        public ContextBuilder prices(double entry, double target, double stopLoss) {
            context.put("entryPrice", entry);
            context.put("targetPrice", target);
            context.put("stopLoss", stopLoss);
            return this;
        }

        public ContextBuilder volume(double volume, double avgVolume) {
            context.put("volume", volume);
            context.put("avgVolume", avgVolume);
            return this;
        }

        public ContextBuilder emas(boolean aboveEma20, boolean aboveEma50) {
            context.put("aboveEma20", aboveEma20);
            context.put("aboveEma50", aboveEma50);
            return this;
        }

        public ContextBuilder superTrend(boolean bullish) {
            context.put("superTrendBullish", bullish);
            return this;
        }

        public ContextBuilder htfTrend(String trend) {
            context.put("htfTrend", trend);
            return this;
        }

        public ContextBuilder rsi(double rsi) {
            context.put("rsi", rsi);
            return this;
        }

        public ContextBuilder vwap(double vwap, boolean above) {
            context.put("vwap", vwap);
            context.put("aboveVwap", above);
            return this;
        }

        public ContextBuilder sessionSegment(String segment) {
            context.put("sessionSegment", segment);
            return this;
        }

        public ContextBuilder fudkii(double score) {
            context.put("fudkiiScore", score);
            return this;
        }

        public ContextBuilder custom(String key, Object value) {
            context.put(key, value);
            return this;
        }

        public Map<String, Object> build() {
            return new HashMap<>(context);
        }
    }

    // ==================== STATS CLASS ====================

    public static class GateStats {
        private int total;
        private int passed;
        private int failed;

        public void record(boolean didPass) {
            total++;
            if (didPass) {
                passed++;
            } else {
                failed++;
            }
        }

        public int getTotal() {
            return total;
        }

        public int getPassed() {
            return passed;
        }

        public int getFailed() {
            return failed;
        }

        public double getPassRate() {
            return total > 0 ? (double) passed / total * 100 : 0;
        }
    }
}
