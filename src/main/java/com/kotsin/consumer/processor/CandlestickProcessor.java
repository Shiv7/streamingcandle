package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.Candlestick;
import com.kotsin.consumer.model.TickData;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.kstream.Suppressed;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.To;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Properties;

/**
 * Production-ready Kafka Streams processor to aggregate tick data (or 1-minute candles)
 * into candlestick windows of various durations (e.g. 1m, 2m, 3m, 5m, 15m, 30m) aligned
 * to the NSE market open (9:15 AM IST). For multi-minute windows (i.e. windowSize > 1), we
 * adjust each record’s timestamp such that the day's 9:15 AM maps to time zero.
 */
@Component
public class CandlestickProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CandlestickProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    /**
     * Entry method to build & start a Kafka Streams pipeline for candlesticks.
     *
     * @param appId       Kafka application ID.
     * @param inputTopic  The input Kafka topic.
     * @param outputTopic The output Kafka topic.
     * @param windowSize  Candle duration in minutes (e.g. 1, 2, 3, 5, 15, 30).
     */
    public void process(String appId, String inputTopic, String outputTopic, int windowSize) {
        Properties props = kafkaConfig.getStreamProperties(appId);
        StreamsBuilder builder = new StreamsBuilder();

        if (windowSize == 1) {
            // Directly aggregate raw TickData into 1-minute candles (no alignment required)
            processTickData(builder, inputTopic, outputTopic);
        } else {
            // For multi-minute candles (2, 3, 5, 15, 30), adjust record timestamps so that for each day
            // 9:15 AM becomes the "zero" boundary. Then apply a tumbling window of the given duration.
            processMultiMinuteCandlestickAligned(builder, inputTopic, outputTopic, windowSize);
        }

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();
        LOGGER.info("Started Kafka Streams application with id: {}", appId);

        // Ensure a graceful shutdown.
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }

    /**
     * Aggregates raw TickData into **1-minute** candles.
     * Uses suppression so each candle is emitted immediately after the 1-minute window ends.
     */
    private void processTickData(StreamsBuilder builder, String inputTopic, String outputTopic) {
        KStream<String, TickData> inputStream = builder.stream(
                inputTopic, Consumed.with(Serdes.String(), TickData.serde())
        );

        TimeWindows timeWindows = TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ZERO);

        KTable<Windowed<String>, Candlestick> candlestickTable = inputStream
                .groupBy((key, tick) -> tick.getCompanyName(),
                        Grouped.with(Serdes.String(), TickData.serde()))
                .windowedBy(timeWindows)
                .aggregate(
                        Candlestick::new,
                        (key, tick, candle) -> {
                            candle.update(tick);
                            return candle;
                        },
                        Materialized.with(Serdes.String(), Candlestick.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        candlestickTable.toStream()
                .map((windowedKey, candle) -> KeyValue.pair(windowedKey.key(), candle))
                .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
    }

    /**
     * Aggregates multi-minute candles (e.g. 2m, 3m, 5m, 15m, 30m) from 1-minute candles,
     * after adjusting record timestamps so that each day's 9:15 AM becomes time zero. This alignment
     * ensures that windows correctly cover, for example, [9:15 – 9:45] for a 30m window.
     *
     * @param builder    Kafka Streams builder.
     * @param inputTopic Input topic (e.g. "1-min-candle").
     * @param outputTopic Output topic (e.g. "30-min-candle", "5-min-candle", etc.).
     * @param windowSize Candle duration in minutes (must be > 1).
     */
    private void processMultiMinuteCandlestickAligned(StreamsBuilder builder,
                                                      String inputTopic,
                                                      String outputTopic,
                                                      int windowSize) {

        // Read pre-aggregated (1-minute) candlestick records from input.
        KStream<String, Candlestick> inputStream = builder.stream(
                inputTopic, Consumed.with(Serdes.String(), Candlestick.serde())
        );

        // Use the new processor API to adjust timestamps.
        // This will apply our DailyTimestampProcessor to every record.
        KStream<String, Candlestick> adjustedStream = inputStream.process(() -> new DailyTimestampProcessor());

        // Define a tumbling window of the desired size with no grace period.
        TimeWindows windows = TimeWindows.ofSizeAndGrace(Duration.ofMinutes(windowSize), Duration.ZERO);

        // Group, aggregate, and suppress final window records.
        KTable<Windowed<String>, Candlestick> candlestickTable = adjustedStream
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
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        candlestickTable.toStream()
                .map((windowedKey, candle) -> KeyValue.pair(windowedKey.key(), candle))
                .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
    }

    /**
     * A custom Transformer that adjusts each record's timestamp so that for each record's day,
     * the 9:15 AM time (in Asia/Kolkata) is treated as time zero.
     *
     * For example, if a record has an original timestamp corresponding to 9:30 AM IST,
     * the adjusted timestamp will be 15 minutes (9:30 - 9:15). This facilitates correct windowing.
     */
    private static class DailyTimestampShifter implements Transformer<String, Candlestick, KeyValue<String, Candlestick>> {
        private ProcessorContext context;

        @Override
        public void init(ProcessorContext context) {
            this.context = context;
        }

        @Override
        public KeyValue<String, Candlestick> transform(String key, Candlestick value) {
            if (value == null) return null;
            long originalTs = context.timestamp();
            // Convert the event timestamp to Asia/Kolkata time.
            ZonedDateTime recordTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(originalTs), ZoneId.of("Asia/Kolkata"));
            // Compute that day's 9:15 AM.
            ZonedDateTime nineFifteen = recordTime.withHour(9).withMinute(15).withSecond(0).withNano(0);
            long shift = nineFifteen.toInstant().toEpochMilli();
            long newTs = originalTs - shift;
            // Forward the record with the adjusted timestamp.
            context.forward(key, value, To.all().withTimestamp(newTs));
            return null; // Already forwarded.
        }

        @Override
        public void close() {
            // No cleanup required.
        }
    }
}
