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

        int curr = Math.max(0, tick.getTotalQuantity());   // cumulative day volume
        Integer prevMax = store.get(key);
        int add = (prevMax == null) ? 0 : Math.max(0, curr - prevMax);

        tick.setDeltaVolume(add);
        store.put(key, (prevMax == null) ? curr : Math.max(prevMax, curr));
        return KeyValue.pair(key, tick);
    }

    @Override public void close() {}
}
