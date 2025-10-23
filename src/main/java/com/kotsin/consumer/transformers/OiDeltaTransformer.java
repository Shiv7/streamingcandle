package com.kotsin.consumer.transformers;

import com.kotsin.consumer.model.InstrumentCandle;
import org.apache.kafka.streams.kstream.ValueTransformerWithKey;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Computes per-timeframe open interest deltas by remembering the last emitted OI.
 */
public class OiDeltaTransformer implements ValueTransformerWithKey<String, InstrumentCandle, InstrumentCandle> {

    private final String storeName;
    private KeyValueStore<String, Long> store;

    public OiDeltaTransformer(String storeName) {
        this.storeName = storeName;
    }

    @Override
    public void init(ProcessorContext context) {
        this.store = context.getStateStore(storeName);
    }

    @Override
    public InstrumentCandle transform(String key, InstrumentCandle candle) {
        if (candle == null) {
            return null;
        }

        Long currentOi = candle.getOpenInterest();
        if (currentOi == null) {
            return candle;
        }

        String timeframe = candle.getTimeframe() != null ? candle.getTimeframe() : "unknown";
        String compositeKey = key + "|" + timeframe;

        Long previousOi = store.get(compositeKey);
        if (previousOi != null) {
            long delta = currentOi - previousOi;
            candle.setOiChange(delta);
            if (previousOi != 0) {
                candle.setOiChangePercent((delta / (double) previousOi) * 100.0);
            } else {
                candle.setOiChangePercent(null);
            }
        }

        store.put(compositeKey, currentOi);
        return candle;
    }

    @Override
    public void close() {
        // no-op
    }
}
