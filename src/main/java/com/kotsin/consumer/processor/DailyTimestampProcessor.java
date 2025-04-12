package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.Candlestick;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class DailyTimestampProcessor
        implements Processor<String, Candlestick, String, Candlestick> {

    private ProcessorContext<String, Candlestick> context;

    @Override
    public void init(ProcessorContext<String, Candlestick> context) {
        this.context = context;
    }

    @Override
    public void process(Record<String, Candlestick> record) {
        if (record.value() == null) {
            return;
        }
        long originalTs = record.timestamp();
        // Convert to Asia/Kolkata time
        ZonedDateTime recordTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(originalTs), ZoneId.of("Asia/Kolkata"));
        // Compute that day’s 09:15 AM
        ZonedDateTime nineFifteen = recordTime.withHour(9).withMinute(15).withSecond(0).withNano(0);
        long shift = nineFifteen.toInstant().toEpochMilli();
        long newTs = originalTs - shift;
        // Forward the record with the adjusted timestamp (using newTs)
        context.forward(record.withTimestamp(newTs));
    }

    @Override
    public void close() {
        // No cleanup necessary.
    }
}
