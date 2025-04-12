package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.config.RecordTimestampOverrideProcessor;
import com.kotsin.consumer.model.Candlestick;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.util.TickBuffer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Production-ready Kafka Streams processor that aggregates market data into candlesticks of various durations.
 * 
 * Data Flow:
 * 1. Raw websocket tick data → 1-minute candles
 * 2. 1-minute candles → Multi-minute candles (2m, 3m, 5m, 15m, 30m)
 * 
 * Features:
 * - Exchange-specific time windows (NSE: 9:15-3:30, MCX: 9:00-23:30)
 * - Tick buffering to handle delayed data
 * - Data quality metrics for monitoring
 */
@Component
public class CandlestickProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CandlestickProcessor.class);
    
    // Buffer ticks for 500ms to handle late-arriving data
    private static final long TICK_BUFFER_DELAY_MS = 500;
    
    // Track metrics for data quality
    private final Map<String, CandleMetrics> metricsMap = new HashMap<>();
    
    @Autowired
    private KafkaConfig kafkaConfig;
    
    private TickBuffer tickBuffer;
    private KafkaStreams tickDataStream;

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
            processMultiMinuteCandlestickAligned(builder, inputTopic, outputTopic, windowSize);
        }

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        
        // For 1-minute candles from raw ticks, save the stream reference for cleanup
        if (windowSize == 1) {
            this.tickDataStream = streams;
        }
        
        streams.start();
        LOGGER.info("Started Kafka Streams application with id: {}, window size: {}m", appId, windowSize);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            streams.close();
            if (tickBuffer != null) {
                tickBuffer.shutdown();
            }
        }));
    }

    /**
     * Aggregates raw TickData into 1-minute candles.
     * 
     * @param builder      Kafka Streams builder.
     * @param inputTopic   Topic with raw tick data.
     * @param outputTopic  Topic for 1-minute candles.
     */
    private void processTickData(StreamsBuilder builder, String inputTopic, String outputTopic) {
        // Create input stream from the raw tick data topic with exchange-specific timestamp alignment
        KStream<String, TickData> inputStream = builder.stream(
                inputTopic, 
                Consumed.with(Serdes.String(), TickData.serde())
                        .withTimestampExtractor(new ExchangeTimestampExtractor(1))
        );
        
        // Initialize tick buffer for delayed tick handling
        tickBuffer = new TickBuffer(TICK_BUFFER_DELAY_MS, this::processBufferedTicks);
        
        // Send ticks to buffer before processing
        inputStream.foreach((key, value) -> {
            if (value != null) {
                tickBuffer.addTick(value);
            }
        });
        
        // Define proper topology for 1-minute candles
        // We're now handling this manually through the tick buffer and direct producer
        LOGGER.info("Configured 1-minute candles using buffered processing");
    }
    
    /**
     * Process buffered ticks into the Kafka Streams topology
     */
    private void processBufferedTicks(String symbol, List<TickData> ticks) {
        if (ticks == null || ticks.isEmpty()) {
            return;
        }
        
        // Get or create metrics for this symbol
        CandleMetrics metrics = metricsMap.computeIfAbsent(symbol, k -> new CandleMetrics(k));
        
        // Group ticks by minute for 1-minute candles
        Map<Long, Candlestick> minuteCandles = new HashMap<>();
        
        for (TickData tick : ticks) {
            // Skip ticks outside trading hours
            if (!isWithinTradingHours(tick)) {
                metrics.incrementSkippedTicks();
                continue;
            }
            
            // Get window start time for this tick using the ExchangeTimestampExtractor logic
            long windowStartTime = calculateWindowStartTime(tick, 1);
            
            // Get or create candlestick for this minute
            Candlestick candle = minuteCandles.computeIfAbsent(windowStartTime, k -> {
                Candlestick newCandle = new Candlestick();
                newCandle.setWindowStartMillis(windowStartTime);
                newCandle.setWindowEndMillis(windowStartTime + 60_000); // +1 minute
                return newCandle;
            });
            
            // Update the candle with this tick
            candle.update(tick);
            metrics.incrementProcessedTicks();
        }
        
        // Produce the completed candles to Kafka
        if (!minuteCandles.isEmpty()) {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getBootstrapServers());
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, Candlestick.CandlestickSerializer.class.getName());
            
            try (KafkaProducer<String, Candlestick> producer = new KafkaProducer<>(props)) {
                for (Map.Entry<Long, Candlestick> entry : minuteCandles.entrySet()) {
                    Candlestick candle = entry.getValue();
                    ProducerRecord<String, Candlestick> record = new ProducerRecord<>(
                            "1-minute-candle", // This should match your output topic config
                            symbol,
                            candle
                    );
                    producer.send(record);
                    
                    // Log candle details for debugging
                    logCandleDetails(candle, 1);
                    metrics.incrementProducedCandles();
                }
                producer.flush();
            }
        }
    }
    
    /**
     * Checks if a tick is within trading hours for its exchange
     */
    private boolean isWithinTradingHours(TickData tick) {
        ZonedDateTime tickTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(tick.getTimestamp()), 
                ZoneId.of("Asia/Kolkata"));
        
        if ("N".equals(tick.getExchange())) {
            // NSE: 9:15 AM - 3:30 PM
            return isWithinNseTradingHours(tickTime);
        } else {
            // MCX: 9:00 AM - 11:30 PM
            return isWithinMcxTradingHours(tickTime);
        }
    }
    
    /**
     * Checks if the given time is within NSE trading hours (9:15 AM - 3:30 PM)
     */
    private boolean isWithinNseTradingHours(ZonedDateTime time) {
        int hour = time.getHour();
        int minute = time.getMinute();
        
        // Before open time
        if (hour < 9 || (hour == 9 && minute < 15)) {
            return false;
        }
        
        // After close time
        if (hour > 15 || (hour == 15 && minute >= 30)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Checks if the given time is within MCX trading hours (9:00 AM - 11:30 PM)
     */
    private boolean isWithinMcxTradingHours(ZonedDateTime time) {
        int hour = time.getHour();
        int minute = time.getMinute();
        
        // Before open time
        if (hour < 9) {
            return false;
        }
        
        // After close time
        if (hour > 23 || (hour == 23 && minute >= 30)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Calculates the window start time for a tick based on exchange rules
     */
    private long calculateWindowStartTime(TickData tick, int windowSizeMinutes) {
        ZonedDateTime recordTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(tick.getTimestamp()), 
                ZoneId.of("Asia/Kolkata"));
        
        if ("N".equals(tick.getExchange())) {
            // NSE trading open time: 9:15 AM
            ZonedDateTime tradingOpen = recordTime.withHour(9).withMinute(15).withSecond(0).withNano(0);
            
            // If record is before today's trading open, use previous day
            if (recordTime.isBefore(tradingOpen)) {
                tradingOpen = tradingOpen.minusDays(1);
            }
            
            // Calculate minutes elapsed since 9:15
            int minutesElapsed = ((recordTime.getHour() - 9) * 60 + (recordTime.getMinute() - 15));
            
            // Calculate window index and start time
            int windowIndex = Math.max(0, minutesElapsed / windowSizeMinutes);
            ZonedDateTime windowStart = tradingOpen.plusMinutes(windowIndex * windowSizeMinutes);
            
            return windowStart.toInstant().toEpochMilli();
        } else {
            // MCX trading open time: 9:00 AM
            ZonedDateTime tradingOpen = recordTime.withHour(9).withMinute(0).withSecond(0).withNano(0);
            
            // If record is before today's trading open, use previous day
            if (recordTime.isBefore(tradingOpen)) {
                tradingOpen = tradingOpen.minusDays(1);
            }
            
            // Calculate minutes elapsed since 9:00
            int minutesElapsed = ((recordTime.getHour() - 9) * 60 + recordTime.getMinute());
            
            // Calculate window index and start time
            int windowIndex = Math.max(0, minutesElapsed / windowSizeMinutes);
            ZonedDateTime windowStart = tradingOpen.plusMinutes(windowIndex * windowSizeMinutes);
            
            return windowStart.toInstant().toEpochMilli();
        }
    }

    /**
     * Logs detailed information about a candle for debugging
     */
    private void logCandleDetails(Candlestick candle, int windowSizeMinutes) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        
        ZonedDateTime windowStart = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getWindowStartMillis()), 
                ZoneId.of("Asia/Kolkata"));
        ZonedDateTime windowEnd = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getWindowEndMillis()), 
                ZoneId.of("Asia/Kolkata"));
        
        LOGGER.debug("{}m candle for {}: {} window: {}-{}, OHLC: {}/{}/{}/{}, Volume: {}", 
                windowSizeMinutes,
                candle.getCompanyName(),
                candle.getExchange(),
                windowStart.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                windowEnd.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                candle.getOpen(),
                candle.getHigh(),
                candle.getLow(),
                candle.getClose(),
                candle.getVolume());
    }

    /**
     * Aggregates multi-minute candles (2m, 3m, 5m, 15m, 30m) from 1-minute candles.
     *
     * @param builder      Kafka Streams builder.
     * @param inputTopic   Topic with 1-minute candles.
     * @param outputTopic  Topic for the aggregated candles (e.g., "30-min-candle").
     * @param windowSize   Target candle duration in minutes (2, 3, 5, 15, or 30).
     */
    private void processMultiMinuteCandlestickAligned(StreamsBuilder builder,
                                                      String inputTopic,
                                                      String outputTopic,
                                                      int windowSize) {
        // Create input stream from the 1-minute candle topic
        KStream<String, Candlestick> inputStream = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), Candlestick.serde())
                        .withTimestampExtractor(new ExchangeTimestampExtractor(windowSize))
        );
        
        // Define tumbling windows of the target size
        TimeWindows windows = TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(windowSize))
                .advanceBy(Duration.ofMinutes(windowSize));
        
        // Log window configuration
        LOGGER.info("Configured {}-minute windows with exchange-specific alignment", windowSize);
        
        // Group by key (company name), window, and aggregate 1-minute candles into larger timeframe candles
        KTable<Windowed<String>, Candlestick> candlestickTable = inputStream
                .peek((key, value) -> {
                    // Get or create metrics for this symbol
                    CandleMetrics metrics = metricsMap.computeIfAbsent(key, k -> new CandleMetrics(key));
                    metrics.incrementProcessedCandles();
                })
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

        // Stream the finalized candles to the output topic
        candlestickTable.toStream()
            .map((windowedKey, candle) -> {
                // Add window boundary timestamps
                candle.setWindowStartMillis(windowedKey.window().start());
                candle.setWindowEndMillis(windowedKey.window().end());
                
                // Get metrics for this symbol
                CandleMetrics metrics = metricsMap.computeIfAbsent(windowedKey.key(), k -> new CandleMetrics(k));
                metrics.incrementProducedCandles();
                
                // Log detailed candle information
                logCandleDetails(candle, windowSize);
                
                return KeyValue.pair(windowedKey.key(), candle);
            })
            .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
    }
    
    /**
     * Class to track metrics for each symbol
     */
    private static class CandleMetrics {
        private final String symbol;
        private int processedTicks = 0;
        private int skippedTicks = 0;
        private int processedCandles = 0;
        private int producedCandles = 0;
        private long lastUpdateTime = System.currentTimeMillis();
        
        public CandleMetrics(String symbol) {
            this.symbol = symbol;
        }
        
        public void incrementProcessedTicks() {
            processedTicks++;
            updateLastUpdateTime();
        }
        
        public void incrementSkippedTicks() {
            skippedTicks++;
            updateLastUpdateTime();
        }
        
        public void incrementProcessedCandles() {
            processedCandles++;
            updateLastUpdateTime();
        }
        
        public void incrementProducedCandles() {
            producedCandles++;
            updateLastUpdateTime();
        }
        
        private void updateLastUpdateTime() {
            lastUpdateTime = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return String.format(
                "Metrics for %s: %d processed ticks, %d skipped ticks, %d processed candles, %d produced candles, last update: %s",
                symbol, processedTicks, skippedTicks, processedCandles, producedCandles,
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastUpdateTime), ZoneId.of("Asia/Kolkata"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            );
        }
    }
}
