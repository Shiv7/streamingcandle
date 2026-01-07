package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.OrderbookAggregate;
import com.kotsin.consumer.util.MarketTimeAligner;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Multi-minute timestamp extractor for orderbook rollups.
 * Prefer START time of 1m aggregate, then END, then record/partition time,
 * and shift by per-exchange offset so windows align with market rules.
 */
public class MultiMinuteOffsetTimestampExtractorForOrderbook implements TimestampExtractor {

    private final int windowSizeMinutes;

    public MultiMinuteOffsetTimestampExtractorForOrderbook(int windowSizeMinutes) {
        this.windowSizeMinutes = windowSizeMinutes;
    }

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object v = record.value();
        long baseTs = 0L;
        if (v instanceof OrderbookAggregate agg) {
            if (agg.getWindowStartMillis() > 0L) {
                baseTs = agg.getWindowStartMillis();
            } else if (agg.getWindowEndMillis() > 0L) {
                baseTs = agg.getWindowEndMillis();
            } else if (record.timestamp() > 0L) {
                baseTs = record.timestamp();
            } else {
                baseTs = partitionTime;
            }

            String exch = agg.getExchange();
            int offMin = MarketTimeAligner.getWindowOffsetMinutes(exch, windowSizeMinutes);
            return baseTs + offMin * 60_000L;
        }
        return record.timestamp() > 0 ? record.timestamp() : Math.max(partitionTime, 0L);
    }
}

