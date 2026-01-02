package com.kotsin.consumer.logging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * PipelineTraceLogger - Unified logging for data flow tracing
 *
 * Shows the complete flow:
 * INPUT → CANDLE → FAMILY → REGIME → SIGNAL → OUTPUT
 *
 * Format: [STAGE] scripCode | timestamp | key metrics | status
 */
@Slf4j
@Component
public class PipelineTraceLogger {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final boolean TRACE_ENABLED = true; // Enable via config later

    /**
     * Stage 1: Input data received
     */
    public void logInputReceived(String type, String scripCode, String companyName,
                                 long timestamp, String keyMetrics) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        log.info("┌─[INPUT-{}] {} | {} | {} | {}",
            type, time, scripCode, companyName, keyMetrics);
    }

    /**
     * Stage 2: Candle aggregated
     */
    public void logCandleAggregated(String scripCode, String companyName,
                                   long windowStart, long windowEnd,
                                   double open, double high, double low, double close,
                                   long volume, boolean hasOB, boolean hasOI) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(windowStart);
        log.info("├─[CANDLE] {} | {} | {} | OHLC={}/{}/{}/{} vol={} OB={} OI={}",
            time, scripCode, companyName,
            String.format("%.2f", open), String.format("%.2f", high),
            String.format("%.2f", low), String.format("%.2f", close),
            volume, hasOB ? "✓" : "✗", hasOI ? "✓" : "✗");
    }

    /**
     * Stage 3: Family candle created
     */
    public void logFamilyCandleCreated(String familyId, String symbol,
                                      long windowStart,
                                      boolean hasEquity, boolean hasFuture, int optionCount,
                                      double close, long volume,
                                      String oiSignal, String directionalBias) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(windowStart);
        log.info("├─[FAMILY] {} | {} | {} | EQ={} FUT={} OPT={} | close={} vol={} | OI={} bias={}",
            time, familyId, symbol,
            hasEquity ? "✓" : "✗", hasFuture ? "✓" : "✗", optionCount,
            String.format("%.2f", close), volume, oiSignal, directionalBias);
    }

    /**
     * Stage 4: Regime calculated
     */
    public void logRegimeCalculated(String scripCode, String regimeType,
                                   long timestamp,
                                   String label, double score,
                                   String emaAlignment, String atrState,
                                   boolean alignedWithIndex, double indexMultiplier) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        log.info("├─[REGIME-{}] {} | {} | label={} score={} | EMA={} ATR={} | IndexAlign={} mult={}",
            regimeType, time, scripCode,
            label, String.format("%.2f", score), emaAlignment, atrState,
            alignedWithIndex ? "✓" : "✗", String.format("%.2f", indexMultiplier));
    }

    /**
     * Stage 5: Signal generated
     */
    public void logSignalGenerated(String signalType, String scripCode,
                                  long timestamp,
                                  double score, String state, String details) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        log.info("├─[SIGNAL-{}] {} | {} | score={} state={} | {}",
            signalType, time, scripCode, String.format("%.2f", score), state, details);
    }

    /**
     * Stage 6: Output emitted
     */
    public void logOutputEmitted(String outputType, String scripCode,
                                long timestamp,
                                String destination, String summary) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        log.info("└─[OUTPUT-{}] {} | {} | → {} | {}",
            outputType, time, scripCode, destination, summary);
    }

    /**
     * Signal ACCEPTED with indicator values (NOT dumb logs!)
     */
    public void logSignalAccepted(String signalType, String scripCode,
                                  long timestamp,
                                  String reason,
                                  Map<String, Object> indicators) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        String indicatorStr = formatIndicators(indicators);
        log.info("✅ [ACCEPT-{}] {} | {} | {} | {}",
            signalType, time, scripCode, reason, indicatorStr);
    }

    /**
     * Signal REJECTED with indicator values (shows WHY rejected!)
     */
    public void logSignalRejected(String signalType, String scripCode,
                                  long timestamp,
                                  String reason,
                                  Map<String, Object> indicators) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        String indicatorStr = formatIndicators(indicators);
        log.warn("❌ [REJECT-{}] {} | {} | {} | {}",
            signalType, time, scripCode, reason, indicatorStr);
    }

    /**
     * Format indicators for logging (key=value, key=value)
     */
    private String formatIndicators(Map<String, Object> indicators) {
        if (indicators == null || indicators.isEmpty()) {
            return "no_indicators";
        }

        StringBuilder sb = new StringBuilder();
        indicators.forEach((key, value) -> {
            if (sb.length() > 0) sb.append(" | ");

            if (value instanceof Double || value instanceof Float) {
                sb.append(String.format("%s=%.2f", key, ((Number) value).doubleValue()));
            } else if (value instanceof Integer || value instanceof Long) {
                sb.append(String.format("%s=%d", key, ((Number) value).longValue()));
            } else {
                sb.append(String.format("%s=%s", key, value));
            }
        });
        return sb.toString();
    }

    /**
     * Error/Warning in pipeline
     */
    public void logPipelineWarning(String stage, String scripCode, String warning) {
        if (!TRACE_ENABLED) return;
        log.warn("⚠️ [{}] {} | {}", stage, scripCode, warning);
    }

    /**
     * Complete flow summary (every N minutes)
     */
    public void logFlowSummary(String scripCode,
                              long totalInputs, long totalCandles,
                              long totalSignals, long totalOutputs) {
        log.info("📊 [FLOW-SUMMARY] {} | Inputs={} Candles={} Signals={} Outputs={}",
            scripCode, totalInputs, totalCandles, totalSignals, totalOutputs);
    }

    /**
     * Format timestamp to HH:mm:ss
     */
    private String formatTime(long epochMillis) {
        try {
            ZonedDateTime zdt = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis),
                ZoneId.of("Asia/Kolkata")
            );
            return zdt.format(TIME_FMT);
        } catch (Exception e) {
            return String.valueOf(epochMillis);
        }
    }

    /**
     * Log index regime calculation
     */
    public void logIndexRegimeCalculated(String indexCode, String indexName,
                                        long timestamp,
                                        String label, double strength,
                                        int flowAgreement, String flowState) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        log.info("├─[REGIME-IDX] {} | {} ({}) | label={} strength={} | flow={} state={}",
            time, indexCode, indexName, label, String.format("%.2f", strength), flowAgreement, flowState);
    }

    /**
     * Log ACL output
     */
    public void logACLCalculated(String scripCode, long timestamp,
                                String action, String reason, double multiplier) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        log.info("├─[REGIME-ACL] {} | {} | action={} mult={} | {}",
            time, scripCode, action, String.format("%.2f", multiplier), reason);
    }

    /**
     * Log trade classification
     */
    public void logTradeClassified(String scripCode, long timestamp,
                                   String tradeType, String direction,
                                   double confidence, String details) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        log.info("├─[TRADE-CLASS] {} | {} | type={} dir={} conf={} | {}",
            time, scripCode, tradeType, direction, String.format("%.2f", confidence), details);
    }

    /**
     * Log MTIS calculation
     */
    public void logMTISCalculated(String scripCode, long timestamp,
                                 double mtisScore, double priceScore,
                                 double foScore, double ipuScore,
                                 double regimeScore, String timeframe) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        log.info("├─[MTIS] {} | {} | score={} TF={} | price={} fo={} ipu={} regime={}",
            time, scripCode, String.format("%.2f", mtisScore), timeframe,
            String.format("%.1f", priceScore), String.format("%.1f", foScore),
            String.format("%.1f", ipuScore), String.format("%.1f", regimeScore));
    }

    /**
     * Log final magnitude calculation (THE MOST IMPORTANT!)
     */
    public void logFinalMagnitude(String scripCode, long timestamp,
                                 double finalMagnitude, String direction,
                                 double baseSignal, double aclMult, double cssScore,
                                 double somPenalty, double vtdPenalty,
                                 int rank) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        log.info("├─[FINAL-MAG] {} | {} | MAGNITUDE={} rank={} dir={} | base={} acl={} css={} som={} vtd={}",
            time, scripCode, String.format("%.3f", finalMagnitude), rank, direction,
            String.format("%.2f", baseSignal), String.format("%.2f", aclMult),
            String.format("%.2f", cssScore), String.format("%.2f", somPenalty),
            String.format("%.2f", vtdPenalty));
    }

    /**
     * Log final output emission (watchlist, kafka)
     */
    public void logFinalOutput(String scripCode, long timestamp,
                              String outputType, String destination,
                              String summary) {
        if (!TRACE_ENABLED) return;
        String time = formatTime(timestamp);
        log.info("└─[FINAL-OUT-{}] {} | {} | → {} | {}",
            outputType, time, scripCode, destination, summary);
    }

    /**
     * Enable/disable tracing at runtime
     */
    public static void setTraceEnabled(boolean enabled) {
        // TODO: Implement via config service
    }
}
