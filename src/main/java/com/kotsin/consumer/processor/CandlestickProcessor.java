package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.Candlestick;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.util.NseTimestampExtractor;
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
 * For 30-minute candles only, a custom TimestampExtractor (NseTimestampExtractor) is used so that
 * the effective event time is computed as the number of milliseconds since the NSE trading open (09:15 IST).
 * This ensures that the 30-minute window [0, 30) corresponds to 09:15–09:45 IST, [30, 60) corresponds to 09:45–10:15, etc.
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
            // For 1-minute candles, process raw TickData.
            processTickData(builder, inputTopic, outputTopic);
        } else if (windowSize == 30) {
            // For 30-minute candles, use the custom NseTimestampExtractor for alignment.
            processMultiMinuteCandlestickAligned(builder, inputTopic, outputTopic, windowSize, true);
        } else {
            // For other multi-minute candles, use the raw event timestamp.
            processMultiMinuteCandlestickAligned(builder, inputTopic, outputTopic, windowSize, false);
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
        TimeWindows windows = TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ZERO);
        KTable<Windowed<String>, Candlestick> candlestickTable = inputStream
                .groupBy((key, tick) -> tick.getCompanyName(), Grouped.with(Serdes.String(), TickData.serde()))
                .windowedBy(windows)
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
     * Aggregates multi-minute candles (for 2m, 3m, 5m, 15m, and 30m).
     * For the 30-minute case (applyAlignment = true), a custom TimestampExtractor is used.
     *
     * @param builder         Kafka Streams builder.
     * @param inputTopic      Input topic (typically "1-min-candle").
     * @param outputTopic     Output topic (e.g., "30-min-candle", "5-min-candle", etc.).
     * @param windowSize      Candle duration in minutes.
     * @param applyAlignment  If true, the custom NseTimestampExtractor is used.
     */
    private void processMultiMinuteCandlestickAligned(StreamsBuilder builder,
                                                      String inputTopic,
                                                      String outputTopic,
                                                      int windowSize,
                                                      boolean applyAlignment) {
        KStream<String, Candlestick> inputStream;
        if (applyAlignment) {
            // For 30-minute candles, apply the NSE timestamp alignment.
            inputStream = builder.stream(
                    inputTopic,
                    Consumed.with(Serdes.String(), Candlestick.serde())
                            .withTimestampExtractor(new NseTimestampExtractor())
            );
        } else {
            // For other window sizes, use the default event timestamp.
            inputStream = builder.stream(
                    inputTopic,
                    Consumed.with(Serdes.String(), Candlestick.serde())
            );
        }
        TimeWindows windows = TimeWindows.ofSizeAndGrace(Duration.ofMinutes(windowSize), Duration.ZERO);
        KTable<Windowed<String>, Candlestick> candlestickTable = inputStream
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
}
