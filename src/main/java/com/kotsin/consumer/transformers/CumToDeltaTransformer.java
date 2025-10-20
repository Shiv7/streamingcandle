package com.kotsin.consumer.transformers;

import com.kotsin.consumer.model.TickData;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;

public class CumToDeltaTransformer implements Transformer<String, TickData, KeyValue<String, TickData>> {
    private final String storeName;
    private KeyValueStore<String, Integer> store;

    public CumToDeltaTransformer(String storeName) { this.storeName = storeName; }

    @Override @SuppressWarnings("unchecked")
    public void init(ProcessorContext context) {
        this.store = (KeyValueStore<String, Integer>) context.getStateStore(storeName);
    }

    @Override
    public KeyValue<String, TickData> transform(String key, TickData tick) {
        if (tick == null) return null;

        // Ensure a stable per-instrument key for the delta store
        String stateKey = (key != null && !key.isEmpty()) ? key : tick.getScripCode();
        if (stateKey == null || stateKey.isEmpty()) {
            // Cannot compute without a stable key; pass through with zero delta
            tick.setDeltaVolume(0);
            return KeyValue.pair(key, tick);
        }

        int curr = Math.max(0, tick.getTotalQuantity());   // cumulative day volume
        Integer prevMax = store.get(stateKey);

        int add;
        if (prevMax == null) {
            // First tick after startup: include observed cumulative to seed state
            add = curr;
        } else if (curr < prevMax) {
            // Day rollover or producer reset: treat as reset and start fresh
            add = curr;
        } else {
            add = Math.max(0, curr - prevMax);
        }

        // Fallback: if cumulative delta is zero but we have a last trade size, use it
        if (add == 0 && tick.getLastQuantity() > 0) {
            add = tick.getLastQuantity();
        }

        tick.setDeltaVolume(add);
        // Track the highest observed cumulative to remain monotonic across glitches
        int newMax = (prevMax == null) ? curr : Math.max(prevMax, curr);
        store.put(stateKey, newMax);

        return KeyValue.pair(key, tick);
    }

    @Override public void close() {}
}
