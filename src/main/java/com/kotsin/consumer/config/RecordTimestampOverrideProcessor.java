package com.kotsin.consumer.config;

import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.kstream.Windowed;


import com.kotsin.consumer.model.Candlestick;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.kstream.Windowed;

public class RecordTimestampOverrideProcessor implements Processor<Windowed<String>, Candlestick, String, Candlestick> {

    private ProcessorContext<String, Candlestick> context;

    @Override
    public void init(ProcessorContext<String, Candlestick> context) {
        this.context = context;
    }

    @Override
    public void process(Record<Windowed<String>, Candlestick> record) {
        // Use the window's end as the final timestamp.
        long windowEnd = record.key().window().end();

        // Optionally, update the candlestick payload with window boundaries.
        Candlestick candle = record.value();
        candle.setWindowStartMillis(record.key().window().start());
        candle.setWindowEndMillis(windowEnd);

        // Forward a new record with the original key (extracted from the Windowed key),
        // the updated payload, and a new timestamp of windowEnd.
        Record<String, Candlestick> newRecord = record.withKey(record.key().key()).withTimestamp(windowEnd);
        context.forward(newRecord);
    }

    @Override
    public void close() {
        // No cleanup needed.
    }
}
