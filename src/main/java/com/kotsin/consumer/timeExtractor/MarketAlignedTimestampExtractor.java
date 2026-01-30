package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MarketAlignedTimestampExtractor - Exchange-aware timestamp alignment for multi-timeframe aggregation.
 *
 * PROBLEM:
 * Kafka Streams TimeWindows aligns to epoch (00:00 UTC), creating windows like:
 * - 30m: 9:00-9:30, 9:30-10:00 (in IST)
 *
 * NSE opens at 9:15 AM IST, MCX opens at 9:00 AM IST.
 * Without alignment, the first 30m NSE candle only contains 15 minutes of data.
 *
 * SOLUTION:
 * Apply exchange-specific offsets to align windows with market open:
 * - NSE/BSE: -15 minutes offset (market opens at 9:15)
 * - MCX: 0 offset (market opens at 9:00)
 *
 * IMPORTANT:
 * - Only 30m, 1h, 2h, 4h need offset (smaller timeframes naturally align)
 * - The TimeframeAggregator MUST add the offset back to output window boundaries
 * - The output Kafka record timestamp MUST also be corrected
 *
 * USAGE:
 * This extractor works with FamilyCandle which contains exchange info.
 * For FamilyCandle input, it extracts exchange from the primary instrument.
 */
public class MarketAlignedTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketAlignedTimestampExtractor.class);
    private static final long MIN_VALID_TIMESTAMP = 1577836800000L; // Jan 1, 2020

    // Market open offsets in milliseconds
    public static final long NSE_OFFSET_MS = 15 * 60 * 1000L;  // 15 minutes for NSE/BSE
    public static final long MCX_OFFSET_MS = 0L;                // 0 for MCX (opens at 9:00)

    // Timeframe in minutes - determines if offset is needed
    private final int timeframeMinutes;

    /**
     * Create extractor for a specific timeframe.
     *
     * @param timeframeMinutes The target aggregation timeframe (5, 15, 30, 60, etc.)
     */
    public MarketAlignedTimestampExtractor(int timeframeMinutes) {
        this.timeframeMinutes = timeframeMinutes;
    }

    /**
     * Default constructor for 30m alignment (backward compatible)
     */
    public MarketAlignedTimestampExtractor() {
        this(30);
    }

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();

        if (value instanceof FamilyCandle) {
            FamilyCandle candle = (FamilyCandle) value;
            long eventTime = candle.getWindowStartMillis();

            // Validate event time
            if (eventTime < MIN_VALID_TIMESTAMP) {
                LOGGER.warn("Invalid windowStartMillis {} for familyId {}, using record timestamp",
                        eventTime, candle.getFamilyId());
                return getSafeTimestamp(record, partitionTime);
            }

            // Determine exchange from candle
            String exchange = getExchange(candle);

            // Get offset for this exchange and timeframe
            long offsetMs = getOffsetMs(exchange, timeframeMinutes);

            // Apply offset (subtract to align windows)
            return eventTime - offsetMs;
        }

        // Fallback for non-FamilyCandle records
        return getSafeTimestamp(record, partitionTime);
    }

    /**
     * Extract exchange from FamilyCandle.
     * Checks equity first, then future (for commodities).
     */
    private String getExchange(FamilyCandle candle) {
        // Try equity first
        if (candle.getEquity() != null && candle.getEquity().getExchange() != null) {
            return candle.getEquity().getExchange();
        }
        // Fallback to future (for MCX commodities)
        if (candle.getFuture() != null && candle.getFuture().getExchange() != null) {
            return candle.getFuture().getExchange();
        }
        // Fallback to primaryInstrument
        InstrumentCandle primary = candle.getPrimaryInstrumentOrFallback();
        if (primary != null && primary.getExchange() != null) {
            return primary.getExchange();
        }
        // Default to NSE if unknown
        LOGGER.debug("Could not determine exchange for familyId {}, defaulting to NSE", candle.getFamilyId());
        return "N";
    }

    /**
     * Get the offset in milliseconds for a given exchange and timeframe.
     *
     * NSE MARKET ALIGNMENT TABLE (market opens at 9:15 AM IST):
     * ┌──────────┬───────────────────┬────────────┬─────────────────────────────────┐
     * │ Timeframe│ 555 % TF == 0?    │ Offset     │ First Window                    │
     * ├──────────┼───────────────────┼────────────┼─────────────────────────────────┤
     * │ 1m       │ 555 % 1 = 0  ✓    │ 0          │ 9:15-9:16 (natural)             │
     * │ 2m       │ 555 % 2 = 1  ✗    │ 15 min     │ 9:15-9:17 (offset corrects)     │
     * │ 3m       │ 555 % 3 = 0  ✓    │ 0          │ 9:15-9:18 (natural)             │
     * │ 5m       │ 555 % 5 = 0  ✓    │ 0          │ 9:15-9:20 (natural)             │
     * │ 15m      │ 555 % 15 = 0 ✓    │ 0          │ 9:15-9:30 (natural)             │
     * │ 30m      │ 555 % 30 = 15 ✗   │ 15 min     │ 9:15-9:45 (offset corrects)     │
     * │ 1h       │ 555 % 60 = 15 ✗   │ 15 min     │ 9:15-10:15 (offset corrects)    │
     * │ 2h       │ 555 % 120 = 75 ✗  │ 15 min     │ 9:15-11:15 (offset corrects)    │
     * │ 4h       │ 555 % 240 = 75 ✗  │ 15 min     │ 9:15-13:15 (offset corrects)    │
     * └──────────┴───────────────────┴────────────┴─────────────────────────────────┘
     *
     * HOW THE 15-MIN OFFSET WORKS:
     * 1. Input: Subtract 15 min from event time (e.g., 9:15 → 9:00)
     * 2. Kafka windowing: Uses epoch-aligned windows (e.g., 9:00-9:30 for 30m)
     * 3. Output: Add 15 min back (e.g., 9:00-9:30 → 9:15-9:45)
     *
     * This trick PRESERVES the window duration (30m stays 30m) while SHIFTING
     * the alignment to match NSE market open at 9:15.
     *
     * MCX opens at 9:00 AM IST which naturally aligns with epoch windows.
     *
     * @param exchange The exchange code (N=NSE, B=BSE, M=MCX)
     * @param tfMinutes The timeframe in minutes
     * @return Offset in milliseconds
     */
    public static long getOffsetMs(String exchange, int tfMinutes) {
        // MCX opens at 9:00 AM IST - naturally aligns with epoch windows
        if ("M".equalsIgnoreCase(exchange)) {
            return MCX_OFFSET_MS;  // 0
        }

        // NSE/BSE open at 9:15 AM IST
        // 9:15 = 555 minutes from midnight IST
        // If 555 % tfMinutes == 0, market open aligns naturally to epoch windows
        final int NSE_OPEN_MINUTES_FROM_MIDNIGHT = 9 * 60 + 15;  // 555

        if (NSE_OPEN_MINUTES_FROM_MIDNIGHT % tfMinutes == 0) {
            // Natural alignment - no offset needed
            // True for: 1m (555%1=0), 3m (555%3=0), 5m (555%5=0), 15m (555%15=0)
            return 0L;
        }

        // Offset needed for non-aligning timeframes
        // True for: 2m (555%2=1), 30m (555%30=15), 1h (555%60=15), 2h, 4h
        return NSE_OFFSET_MS;  // 15 minutes
    }

    /**
     * Get safe fallback timestamp
     */
    private long getSafeTimestamp(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (record.timestamp() > MIN_VALID_TIMESTAMP) {
            return record.timestamp();
        }
        if (partitionTime > MIN_VALID_TIMESTAMP) {
            return partitionTime;
        }
        return System.currentTimeMillis();
    }
}
