package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.config.RecordTimestampOverrideProcessor;
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
 * Production-ready Kafka Streams processor that aggregates candlestick data for various durations:
 * 1m, 2m, 3m, 5m, 15m, and 30m. For the 30-minute window, a custom NseTimestampExtractor is applied
 * so that the effective event time is computed as milliseconds since 09:15 IST. Then, the final record's
 * timestamp is overridden (using RecordTimestampOverrideProcessor) with the window's end time (e.g. 09:45)
 * so that the aggregated candle represents the proper window.
 */
@Component
public class CandlestickProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CandlestickProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    /**
     * Starts the processing pipeline.
     *
     * @param appId       Kafka application ID.
     * @param inputTopic  Input topic.
     * @param outputTopic Output topic.
     * @param windowSize  Candle duration in minutes.
     */
    public void process(String appId, String inputTopic, String outputTopic, int windowSize) {
        Properties props = kafkaConfig.getStreamProperties(appId);
        StreamsBuilder builder = new StreamsBuilder();

        if (windowSize == 1) {
            processTickData(builder, inputTopic, outputTopic);
        } else {
            // For 30-minute candles, apply alignment via custom TimestampExtractor.
            boolean applyAlignment = (windowSize == 30);
            processMultiMinuteCandlestickAligned(builder, inputTopic, outputTopic, windowSize, applyAlignment);
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
     * Aggregates multi-minute candles (2m, 3m, 5m, 15m, 30m) from 1-minute candles.
     *
     * @param builder         Kafka Streams builder.
     * @param inputTopic      Input topic (e.g. "1-min-candle").
     * @param outputTopic     Output topic (e.g. "30-min-candle").
     * @param windowSize      Candle duration in minutes.
     * @param applyAlignment  If true, use NseTimestampExtractor (for the 30-minute case).
     */
    private void processMultiMinuteCandlestickAligned(StreamsBuilder builder,
                                                      String inputTopic,
                                                      String outputTopic,
                                                      int windowSize,
                                                      boolean applyAlignment) {
        KStream<String, Candlestick> inputStream;
        if (applyAlignment) {
            inputStream = builder.stream(
                    inputTopic,
                    Consumed.with(Serdes.String(), Candlestick.serde())
                            .withTimestampExtractor(new com.kotsin.consumer.processor.NseTimestampExtractor())
            );
        } else {
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

        if (applyAlignment) {
            // For the 30-minute case, override the record's timestamp with the window's end.
            candlestickTable.toStream()
                    .process(() -> new RecordTimestampOverrideProcessor())
                    .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
        } else {
            candlestickTable.toStream()
                    .map((windowedKey, candle) -> KeyValue.pair(windowedKey.key(), candle))
                    .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
        }
    }
}
