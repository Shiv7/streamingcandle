package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.Candlestick;
import com.kotsin.consumer.model.TickData;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.kstream.Suppressed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;

/**
 * Production-ready Kafka Streams processor to aggregate raw tick data (or 1-minute candles)
 * into candlestick windows of various durations: 1m, 2m, 3m, 5m, 15m, and 30m.
 *
 * For the 30-minute window only, records are processed with a custom timestamp adjustment
 * so that for each day 09:15 AM IST becomes time zero, thus ensuring that the first window covers
 * 09:15–09:45, the next covers 09:45–10:15, and so on.
 */
@Component
public class CandlestickProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CandlestickProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    /**
     * Entry method to build and start a Kafka Streams pipeline for candlesticks.
     *
     * @param appId       Kafka application ID.
     * @param inputTopic  Input Kafka topic.
     * @param outputTopic Output Kafka topic.
     * @param windowSize  Candle duration in minutes (e.g. 1, 2, 3, 5, 15, 30).
     */
    public void process(String appId, String inputTopic, String outputTopic, int windowSize) {
        Properties props = kafkaConfig.getStreamProperties(appId);
        StreamsBuilder builder = new StreamsBuilder();

        if (windowSize == 1) {
            processTickData(builder, inputTopic, outputTopic);
        } else {
            // For multi-minute windows:
            // For 30-minute candles, use timestamp adjustment; for others, use raw timestamps.
            boolean applyShift = (windowSize == 30);
            processMultiMinuteCandlestickAligned(builder, inputTopic, outputTopic, windowSize, applyShift);
        }

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();
        LOGGER.info("Started Kafka Streams application with id: {}", appId);
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }

    /**
     * Aggregates raw TickData into 1-minute candles.
     */
    private void processTickData(StreamsBuilder builder, String inputTopic, String outputTopic) {
        KStream<String, TickData> inputStream = builder.stream(
                inputTopic, Consumed.with(Serdes.String(), TickData.serde())
        );

        TimeWindows timeWindows = TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ZERO);

        KTable<Windowed<String>, Candlestick> candlestickTable = inputStream
                .groupBy((key, tick) -> tick.getCompanyName(), Grouped.with(Serdes.String(), TickData.serde()))
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
                .map((windowKey, candle) -> KeyValue.pair(windowKey.key(), candle))
                .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
    }

    /**
     * Aggregates multi-minute candles (2m, 3m, 5m, 15m, 30m) from 1-minute candles.
     * For 30-minute candles (i.e. when applyShift is true), records’ timestamps are adjusted
     * such that each day’s 09:15 AM becomes time zero.
     *
     * @param builder    Kafka Streams builder.
     * @param inputTopic Input topic (e.g. "1-min-candle").
     * @param outputTopic Output topic (e.g. "30-min-candle", "5-min-candle", etc.).
     * @param windowSize Candle duration in minutes.
     * @param applyShift If true, adjust timestamps via DailyTimestampProcessor.
     */
    private void processMultiMinuteCandlestickAligned(StreamsBuilder builder,
                                                      String inputTopic,
                                                      String outputTopic,
                                                      int windowSize,
                                                      boolean applyShift) {
        KStream<String, Candlestick> inputStream = builder.stream(
                inputTopic, Consumed.with(Serdes.String(), Candlestick.serde())
        );

        // Apply timestamp adjustment for 30-minute candles.
        KStream<String, Candlestick> adjustedStream = applyShift
                ? inputStream.process(() -> new DailyTimestampProcessor())
                : inputStream;

        // Define tumbling windows of the desired size.
        TimeWindows windows = TimeWindows.ofSizeAndGrace(Duration.ofMinutes(windowSize), Duration.ZERO);

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
                .map((windowKey, candle) -> KeyValue.pair(windowKey.key(), candle))
                .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
    }
}
