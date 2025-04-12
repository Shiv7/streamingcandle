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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * Production-ready Kafka Streams processor that aggregates market data into candlesticks of various durations.
 * 
 * Data Flow:
 * 1. Raw websocket tick data → 1-minute candles
 * 2. 1-minute candles → Multi-minute candles (2m, 3m, 5m, 15m, 30m)
 * 
 * Special handling for NSE market hours:
 * - For 30-minute candles, we apply a custom NseTimestampExtractor to align with NSE trading hours
 *   that start at 09:15 IST, ensuring the first candle runs from 09:15 to 09:45.
 * - The timestamp in the output record is set to the window's end time using RecordTimestampOverrideProcessor
 *   (e.g., 09:45 for the first candle of the day).
 */
@Component
public class CandlestickProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CandlestickProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    /**
     * Initializes and starts the candlestick aggregation pipeline.
     *
     * @param appId       Unique Kafka Streams application ID.
     * @param inputTopic  Topic containing input data (raw ticks for 1m, or 1m candles for larger timeframes).
     * @param outputTopic Topic where aggregated candlesticks will be published.
     * @param windowSize  Target candle duration in minutes (1, 2, 3, 5, 15, or 30).
     */
    public void process(String appId, String inputTopic, String outputTopic, int windowSize) {
        Properties props = kafkaConfig.getStreamProperties(appId);
        StreamsBuilder builder = new StreamsBuilder();

        if (windowSize == 1) {
            // For 1-minute candles, aggregate directly from raw tick data
            processTickData(builder, inputTopic, outputTopic);
        } else {
            // For multi-minute candles, aggregate from 1-minute candles
            // Apply special NSE timestamp alignment only for 30-minute candles
            boolean applyAlignment = (windowSize == 30);
            processMultiMinuteCandlestickAligned(builder, inputTopic, outputTopic, windowSize, applyAlignment);
        }

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();
        LOGGER.info("Started Kafka Streams application with id: {}, window size: {}m", appId, windowSize);
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }

    /**
     * Aggregates raw TickData into 1-minute candles.
     * 
     * @param builder      Kafka Streams builder.
     * @param inputTopic   Topic with raw tick data.
     * @param outputTopic  Topic for 1-minute candles.
     */
    private void processTickData(StreamsBuilder builder, String inputTopic, String outputTopic) {
        // Create input stream from the raw tick data topic
        KStream<String, TickData> inputStream = builder.stream(
                inputTopic, Consumed.with(Serdes.String(), TickData.serde())
        );
        
        // Define 1-minute tumbling windows
        TimeWindows windows = TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ZERO);
        
        // Group by company name, window, and aggregate ticks into candles
        KTable<Windowed<String>, Candlestick> candlestickTable = inputStream
                .groupBy((key, tick) -> tick.getCompanyName(), Grouped.with(Serdes.String(), TickData.serde()))
                .windowedBy(windows)
                .aggregate(
                        Candlestick::new,  // Initialize a new empty candle
                        (key, tick, candle) -> {
                            candle.update(tick);  // Update candle with tick data
                            return candle;
                        },
                        Materialized.with(Serdes.String(), Candlestick.serde())
                )
                // Suppress intermediate updates until the window closes
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));
        
        // Stream the finalized candles to the output topic
        candlestickTable.toStream()
                .map((windowedKey, candle) -> KeyValue.pair(windowedKey.key(), candle))
                .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
    }

    /**
     * Aggregates multi-minute candles (2m, 3m, 5m, 15m, 30m) from 1-minute candles.
     *
     * @param builder         Kafka Streams builder.
     * @param inputTopic      Topic with 1-minute candles.
     * @param outputTopic     Topic for the aggregated candles (e.g., "30-min-candle").
     * @param windowSize      Target candle duration in minutes (2, 3, 5, 15, or 30).
     * @param applyAlignment  Whether to use NseTimestampExtractor for NSE market hour alignment.
     */
    private void processMultiMinuteCandlestickAligned(StreamsBuilder builder,
                                                      String inputTopic,
                                                      String outputTopic,
                                                      int windowSize,
                                                      boolean applyAlignment) {
        // Create input stream from the 1-minute candle topic
        KStream<String, Candlestick> inputStream;
        if (applyAlignment) {
            // For 30-minute candles, use the special NSE timestamp extractor
            inputStream = builder.stream(
                    inputTopic,
                    Consumed.with(Serdes.String(), Candlestick.serde())
                            .withTimestampExtractor(new NseTimestampExtractor())
            );
        } else {
            // For other timeframes, use default timestamp extraction
            inputStream = builder.stream(
                    inputTopic,
                    Consumed.with(Serdes.String(), Candlestick.serde())
            );
        }
        
        // Define tumbling windows of the target size
        TimeWindows windows = TimeWindows.ofSizeAndGrace(Duration.ofMinutes(windowSize), Duration.ZERO);
        
        // Group by key (company name), window, and aggregate 1-minute candles into larger timeframe candles
        KTable<Windowed<String>, Candlestick> candlestickTable = inputStream
                .groupByKey(Grouped.with(Serdes.String(), Candlestick.serde()))
                .windowedBy(windows)
                .aggregate(
                        Candlestick::new,  // Initialize a new empty candle
                        (key, newCandle, aggCandle) -> {
                            aggCandle.updateCandle(newCandle);  // Merge the new candle into aggregate
                            return aggCandle;
                        },
                        Materialized.with(Serdes.String(), Candlestick.serde())
                )
                // Suppress intermediate updates until the window closes
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        if (applyAlignment) {
            // For 30-minute candles, override the record timestamp with the window's end time (e.g., 09:45)
            KStream<String, Candlestick> outputStream = candlestickTable.toStream()
                    .process(() -> new RecordTimestampOverrideProcessor());
            
            // Add debug logging to verify the timestamp before writing to Kafka
            outputStream.peek((key, value) -> {
                ZonedDateTime outputTime = ZonedDateTime.ofInstant(
                        Instant.ofEpochMilli(value.getWindowEndMillis()), 
                        ZoneId.of("Asia/Kolkata"));
                LOGGER.info("Writing 30m candle for {}: window {}. Timestamp: {}",
                        key,
                        value.getFormattedTimeWindow(),
                        outputTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            })
            .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
        } else {
            // For other timeframes, use standard processing
            candlestickTable.toStream()
                    .map((windowedKey, candle) -> KeyValue.pair(windowedKey.key(), candle))
                    .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
        }
    }
}
