package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.Candlestick;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.model.TimeRangeFilterTransformer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.To;
import org.apache.kafka.streams.kstream.Suppressed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Properties;

import static com.kotsin.consumer.config.KafkaConfig.END_3RD_APRIL_1530;
import static com.kotsin.consumer.config.KafkaConfig.START_3RD_APRIL_915;

/**
 * Generic Kafka Streams processor to convert tick data or smaller candles into bigger candlestick windows.
 */
@Component
public class CandlestickProcessor {

    @Autowired
    private KafkaConfig kafkaConfig;

    /**
     * Entry method to build & start a Kafka Streams pipeline for candlesticks.
     *
     * @param appId       Kafka application ID.
     * @param inputTopic  The input Kafka topic.
     * @param outputTopic The output Kafka topic.
     * @param windowSize  Time window size in minutes (1, 2, 5, 15, 30, etc.).
     */
    public void process(String appId, String inputTopic, String outputTopic, int windowSize) {
        Properties props = kafkaConfig.getStreamProperties(appId);
        StreamsBuilder builder = new StreamsBuilder();

        if (windowSize == 1) {
            // Directly aggregate raw TickData into 1-minute candles (no alignment offset).
            processTickData(builder, inputTopic, outputTopic);
        } else {
            // Aggregate multi-minute candles from smaller candles, aligned to 9:15 AM.
            processCandlestickAligned(builder, inputTopic, outputTopic, windowSize);
        }

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();

        // Ensure a graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }

    /**
     * Aggregates raw TickData into **1-minute** candles.
     * Uses **suppression** with zero grace so each candle is emitted **as soon as the window ends**.
     */
    private void processTickData(StreamsBuilder builder, String inputTopic, String outputTopic) {
        KStream<String, TickData> inputStream = builder.stream(
                inputTopic, Consumed.with(Serdes.String(), TickData.serde())
        );


        // 1) Use transform() to filter by date/time range
        inputStream = inputStream.transform(() ->
                new TimeRangeFilterTransformer<>(
                        START_3RD_APRIL_915,  // 2025-04-03T09:15+05:30
                        END_3RD_APRIL_1530    // 2025-04-03T15:30+05:30
                )
        );
        // 1-minute window with zero grace => emits final window result at exactly window end
        TimeWindows timeWindows = TimeWindows.of(Duration.ofMinutes(1))
                .grace(Duration.ZERO);

        KTable<Windowed<String>, Candlestick> candlestickTable = inputStream
                .groupBy((key, tick) -> tick.getCompanyName(),
                        Grouped.with(Serdes.String(), TickData.serde()))
                .windowedBy(timeWindows)
                .aggregate(
                        Candlestick::new,
                        (key, tick, candlestick) -> {
                            candlestick.update(tick);
                            return candlestick;
                        },
                        Materialized.with(Serdes.String(), Candlestick.serde())
                )
                // Only emit the final candle at the end of the 1-minute window
                .suppress(
                        Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded())
                );

        candlestickTable
                .toStream()
                .map((windowedKey, candleValue) ->
                        KeyValue.pair(windowedKey.key(), candleValue))
                .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
    }

    /**
     * Aggregates **multi-minute** (2,3,5,15,30, etc.) candles from **1-minute** candles,
     * with optional alignment to 9:15 AM IST, and emits only the final candle once each window ends.
     */
    private void processCandlestickAligned(StreamsBuilder builder,
                                           String inputTopic,
                                           String outputTopic,
                                           int windowSize) {

        // Read smaller candles (e.g. 1-min) from input
        KStream<String, Candlestick> inputStream =
                builder.stream(inputTopic, Consumed.with(Serdes.String(), Candlestick.serde()));

        // Calculate how to shift each record's timestamp so that 9:15 AM is effectively "time 0"
        long offsetTo915 = compute915Offset(windowSize);

        // Transform timestamps
        KStream<String, Candlestick> shiftedStream = inputStream.transform(() ->
                new TimestampShifter(offsetTo915)
        );

        // Multi-minute windows, zero grace => final candle as soon as the window ends
        TimeWindows windows = TimeWindows.of(Duration.ofMinutes(windowSize))
                .advanceBy(Duration.ofMinutes(windowSize))
                .grace(Duration.ZERO);

        KTable<Windowed<String>, Candlestick> candlestickTable = shiftedStream
                .groupByKey(Grouped.with(Serdes.String(), Candlestick.serde()))
                .windowedBy(windows)
                .aggregate(
                        Candlestick::new,
                        (key, newCandle, aggCandle) -> {
                            aggCandle.updateCandle(newCandle);
                            return aggCandle;
                        },
                        Materialized.with(Serdes.String(), Candlestick.serde())
                )
                // Only emit final candle once each multi-minute window is closed
                .suppress(
                        Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded())
                );

        candlestickTable
                .toStream()
                .map((windowedKey, candleValue) ->
                        KeyValue.pair(windowedKey.key(), candleValue))
                .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
    }

    /**
     * Aligns windows to 9:15 AM by subtracting an offset from each record's timestamp.
     */
    private long compute915Offset(int windowSize) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        ZonedDateTime nineFifteen = now.withHour(9).withMinute(15).withSecond(0).withNano(0);

        long epochMillis = nineFifteen.toInstant().toEpochMilli();
        long windowMillis = windowSize * 60_000L;

        // remainder so 9:15 sits exactly on a boundary (0-based alignment)
        return epochMillis % windowMillis;
    }

    /**
     * Transformer that subtracts a fixed offset from each record's timestamp
     * so that 9:15 AM becomes the zero boundary for the time window.
     */
    private static class TimestampShifter
            implements Transformer<String, Candlestick, KeyValue<String, Candlestick>> {

        private final long offset;
        private ProcessorContext context;

        public TimestampShifter(long offset) {
            this.offset = offset;
        }

        @Override
        public void init(ProcessorContext context) {
            this.context = context;
        }

        @Override
        public KeyValue<String, Candlestick> transform(String key, Candlestick value) {
            if (value == null) {
                return null;
            }
            long originalTs = context.timestamp();
            long newTs = originalTs - offset;

            // Forward with the adjusted timestamp
            context.forward(key, value, To.all().withTimestamp(newTs));
            return null; // we've already forwarded
        }

        @Override
        public void close() {
            // No cleanup needed
        }
    }
}
