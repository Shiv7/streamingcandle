package com.kotsin.consumer.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Debug transformer that logs potential lateness by comparing the current record's event-time
 * with the max event-time observed on this task (approximate stream-time).
 */
@Slf4j
public class DebugLateRecordTransformer implements Transformer<String, TopologyConfiguration.MergedEvent, KeyValue<String, TopologyConfiguration.MergedEvent>> {

    private final String timeframeLabel;
    private final long graceMs;
    private final String storeName;

    private ProcessorContext context;
    private KeyValueStore<String, Long> store;

    public DebugLateRecordTransformer(String timeframeLabel, long graceMs, String storeName) {
        this.timeframeLabel = timeframeLabel;
        this.graceMs = graceMs;
        this.storeName = storeName;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void init(ProcessorContext context) {
        this.context = context;
        this.store = (KeyValueStore<String, Long>) context.getStateStore(storeName);
    }

    @Override
    public KeyValue<String, TopologyConfiguration.MergedEvent> transform(String key, TopologyConfiguration.MergedEvent value) {
        long ts = context.timestamp(); // extracted event-time
        Long maxTs = store.get("__max_ts__");
        if (maxTs == null || ts > maxTs) {
            maxTs = ts;
            store.put("__max_ts__", maxTs);
        }

        long lateness = maxTs - ts; // if positive, this record is behind the max seen

        if (lateness > graceMs) {
            long windowStart = (ts / 60_000L) * 60_000L;
            long windowEnd = windowStart + 60_000L;
            log.warn("[late-debug {}] key={} ts={} maxTs={} latenessMs={} > graceMs={} window=[{}, {})",
                timeframeLabel, key, ts, maxTs, lateness, graceMs, windowStart, windowEnd);
        }

        return KeyValue.pair(key, value);
    }

    @Override
    public void close() { }
}

