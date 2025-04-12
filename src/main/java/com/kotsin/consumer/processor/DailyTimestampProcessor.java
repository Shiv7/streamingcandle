package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.Candlestick;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class DailyTimestampProcessor implements Processor<String, Candlestick, String, Candlestick> {

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
        // Convert the record's timestamp into Asia/Kolkata time
        ZonedDateTime recordTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(originalTs), ZoneId.of("Asia/Kolkata"));
        // Compute that day's 09:15 AM (if the record time is before 9:15, this will be later than the record)
        ZonedDateTime nineFifteen = recordTime.withHour(9).withMinute(15).withSecond(0).withNano(0);
        long shift = nineFifteen.toInstant().toEpochMilli();
        long newTs = originalTs - shift;
        // Ensure the new timestamp is not negative.
        if (newTs < 0) {
            newTs = 0;
        }
        // Forward the record with the adjusted timestamp.
        context.forward(record.withTimestamp(newTs));
    }

    @Override
    public void close() {
        // No cleanup required.
    }
}
