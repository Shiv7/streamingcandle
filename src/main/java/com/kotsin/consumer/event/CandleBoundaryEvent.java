package com.kotsin.consumer.event;

import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.TickCandle;
import lombok.Builder;
import lombok.Data;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.List;

/**
 * CandleBoundaryEvent - Published when a timeframe boundary is crossed.
 *
 * This enables EVENT-DRIVEN analysis:
 * - SignalEngine subscribes to these events
 * - Routes to appropriate trigger based on timeframe
 * - Only runs analysis when timeframe actually closes
 *
 * Example:
 * At 10:15 IST, a 1m candle closes. This crosses 15m, 30m, and 1h boundaries.
 * Three CandleBoundaryEvents are published (one per timeframe).
 * PivotConfluenceTrigger listens for 15m events to update LTF confirmation.
 * FudkiiSignalTrigger listens for 30m events.
 */
public class CandleBoundaryEvent extends ApplicationEvent {

    private final CandleBoundaryData data;

    public CandleBoundaryEvent(Object source, CandleBoundaryData data) {
        super(source);
        this.data = data;
    }

    public CandleBoundaryData getData() {
        return data;
    }

    public String getScripCode() {
        return data.getScripCode();
    }

    public String getSymbol() {
        return data.getSymbol();
    }

    public Timeframe getTimeframe() {
        return data.getTimeframe();
    }

    public Instant getWindowStart() {
        return data.getWindowStart();
    }

    public Instant getWindowEnd() {
        return data.getWindowEnd();
    }

    public boolean isTimeframe(Timeframe tf) {
        return data.getTimeframe() == tf;
    }

    /**
     * Data payload for CandleBoundaryEvent.
     */
    @Data
    @Builder
    public static class CandleBoundaryData {
        // Symbol identification
        private String scripCode;
        private String symbol;
        private String exchange;
        private String exchangeType;

        // Timeframe that just closed
        private Timeframe timeframe;

        // Window boundaries
        private Instant windowStart;
        private Instant windowEnd;

        // Aggregated candle data (OHLCV)
        private double open;
        private double high;
        private double low;
        private double close;
        private long volume;

        // The 1m candles that make up this candle (for detailed analysis)
        private List<TickCandle> constituent1mCandles;

        // Data type: "TICK", "OI", "ORDERBOOK" (Bug #8)
        @Builder.Default
        private String dataType = "TICK";

        // Timestamp when event was created
        private Instant eventTime;

        /**
         * Check if this candle is complete (has all expected constituent candles).
         */
        public boolean isComplete() {
            if (constituent1mCandles == null) {
                return false;
            }
            int expected = timeframe.getMinutes();
            return constituent1mCandles.size() >= (expected * 0.8); // Allow 80% threshold
        }

        /**
         * Get completeness ratio.
         */
        public double getCompletenessRatio() {
            if (constituent1mCandles == null || constituent1mCandles.isEmpty()) {
                return 0.0;
            }
            int expected = timeframe.getMinutes();
            return (double) constituent1mCandles.size() / expected;
        }
    }
}
