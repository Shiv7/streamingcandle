package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.Candlestick;
import com.kotsin.consumer.util.MarketTimeAligner;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class MarketAlignedVolumeProcessor implements Processor<String, Candlestick, String, Candlestick> {

    private KeyValueStore<String, Integer> lastVolumeStore;
    private final String storeName;
    private final int windowSize;
    private ProcessorContext<String, Candlestick> context;

    public MarketAlignedVolumeProcessor(int windowSize) {
        this.windowSize = windowSize;
        this.storeName = "last-volume-store-" + windowSize + "m";
    }

    @Override
    public void init(ProcessorContext<String, Candlestick> context) {
        this.context = context;
        this.lastVolumeStore = context.getStateStore(storeName);
    }

    @Override
    public void process(Record<String, Candlestick> record) {
        String key = record.key();
        Candlestick candle = record.value();

        // Step 1: Calculate correct per-minute volume
        Integer lastVolume = lastVolumeStore.get(key);
        if (lastVolume == null) {
            lastVolume = 0;
        }
        int currentCumulativeVolume = candle.getVolume();
        int minuteVolume = currentCumulativeVolume - lastVolume;
        if (minuteVolume < 0) {
            minuteVolume = currentCumulativeVolume;
        }
        candle.setVolume(minuteVolume);
        lastVolumeStore.put(key, currentCumulativeVolume);

        // Step 2: Align the timestamp for correct windowing
        ZonedDateTime recordTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(record.timestamp()), ZoneId.of("Asia/Kolkata"));
        long alignedTimestamp = MarketTimeAligner.getAlignedWindowStart(recordTime, candle.getExchange(), windowSize);

        // The new key is the original key plus the aligned timestamp
        String newKey = key + "@" + alignedTimestamp;

        // Forward the record with the new key and the aligned timestamp
        context.forward(record.withKey(newKey).withTimestamp(alignedTimestamp));
    }

    @Override
    public void close() {
        // No-op
    }
}
