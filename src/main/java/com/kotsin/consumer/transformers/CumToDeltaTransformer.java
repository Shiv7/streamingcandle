package com.kotsin.consumer.transformers;

import com.kotsin.consumer.model.TickData;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;

public class CumToDeltaTransformer implements Transformer<String, TickData, KeyValue<String, TickData>> {
    private final String storeName;
    private KeyValueStore<String, Long> store;  // ✅ P0-4 FIX: Changed from Integer to Long

    public CumToDeltaTransformer(String storeName) { this.storeName = storeName; }

    @Override @SuppressWarnings("unchecked")
    public void init(ProcessorContext context) {
        this.store = (KeyValueStore<String, Long>) context.getStateStore(storeName);
    }

    @Override
    public KeyValue<String, TickData> transform(String key, TickData tick) {
        if (tick == null) return null;

        // Ensure a stable per-instrument key for the delta store
        String stateKey = (key != null && !key.isEmpty()) ? key : tick.getScripCode();
        if (stateKey == null || stateKey.isEmpty()) {
            tick.setDeltaVolume(0);
            tick.setResetFlag(false);
            return KeyValue.pair(key, tick);
        }

        long curr = Math.max(0, tick.getTotalQuantity());   // ✅ Changed to long
        Long prevMax = store.get(stateKey);  // ✅ Changed to Long

        long add;  // ✅ Changed to long
        boolean isReset = false;
        
        if (prevMax == null) {
            // First observation in our store - treat as reset/startup with ZERO delta
            // Avoid dumping entire cumulative volume into first bar
            add = 0;
            isReset = true;
            store.put(stateKey, curr);
        } else if (curr < prevMax) {
            // CRITICAL: Day rollover or producer reset detected
            // DO NOT use curr as delta - this creates phantom volume
            // Instead, mark as reset and use 0 or null delta
            add = 0;  // No delta on reset
            isReset = true;
            store.put(stateKey, curr);
        } else {
            // Normal case: compute delta
            add = Math.max(0, curr - prevMax);
            isReset = false;
            store.put(stateKey, curr);
        }

        // Fallback for INDEX data (NIFTY, BANKNIFTY, etc.) where TotalQty is not meaningful
        // If cumulative delta is zero but we have a last trade size, use it
        // BUT only if this is NOT a reset
        if (add == 0 && !isReset && tick.getLastQuantity() > 0) {
            add = tick.getLastQuantity();
        }
        
        // ADDITIONAL FALLBACK: For instruments where TotalQty is non-cumulative
        // Apply only if this symbol consistently exhibits non-cumulative behavior within a session.
        // Heuristic: if prevMax exists and remains equal frequently, use lastQty sparingly.
        if (add == 0 && !isReset && prevMax != null && curr == prevMax && tick.getLastQuantity() > 0) {
            // Optional: limit to known segments (e.g., index) using exchangeType or token range
            // For now, keep fallback but be conservative
            add = tick.getLastQuantity();
        }

        // ✅ Safe cast: delta should not exceed int range in practice (incremental volume per tick)
        tick.setDeltaVolume((int) Math.min(add, Integer.MAX_VALUE));
        tick.setResetFlag(isReset);
        return KeyValue.pair(key, tick);
    }

    @Override public void close() {}
}
