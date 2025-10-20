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
            tick.setDeltaVolume(0);
            return KeyValue.pair(key, tick);
        }

        int curr = Math.max(0, tick.getTotalQuantity());   // cumulative day volume
        Integer prevMax = store.get(stateKey);

        int add;
        if (prevMax == null) {
            // First observation in our store
            add = curr;
            store.put(stateKey, curr);
        } else if (curr < prevMax) {
            // Day rollover or producer reset: reset baseline to curr immediately
            add = curr;
            store.put(stateKey, curr);
        } else {
            add = Math.max(0, curr - prevMax);
            store.put(stateKey, curr);
        }

        // Fallback: if cumulative delta is zero but we have a last trade size, use it
        if (add == 0 && tick.getLastQuantity() > 0) {
            add = tick.getLastQuantity();
        }

        tick.setDeltaVolume(add);
        return KeyValue.pair(key, tick);
    }

    @Override public void close() {}
}
