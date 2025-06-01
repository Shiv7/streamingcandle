package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.config.RecordTimestampOverrideProcessor;
import com.kotsin.consumer.model.Candlestick;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.util.TickBuffer;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.time.temporal.ChronoUnit;

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
    
    // Buffer ticks for minimal delay to handle late-arriving data
    private static final long TICK_BUFFER_DELAY_MS = 50;
    
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
        
        // IMPORTANT: Restore the original Kafka Streams processing pipeline
        // This is needed to ensure data flows to the multi-minute candles
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(1), 
            Duration.ofSeconds(30)
        ).advanceBy(Duration.ofMinutes(1));
        
        // Group by company name, window, and aggregate ticks into candles
        KTable<Windowed<String>, Candlestick> candlestickTable = inputStream
                // Filter out any ticks outside trading hours
                .filter((key, tick) -> isWithinTradingHours(tick))
                .groupBy((key, tick) -> tick.getCompanyName(), Grouped.with(Serdes.String(), TickData.serde()))
                .windowedBy(windows)
                .aggregate(
                        Candlestick::new,  // Initialize a new empty candle
                        (key, tick, candle) -> {
                            candle.update(tick);  // Update candle with tick data
                            return candle;
                        },
                        Materialized.<String, Candlestick, WindowStore<Bytes, byte[]>>as("candlestick-store")
                        .withKeySerde(Serdes.String())
                        .withValueSerde(Candlestick.serde())
                )
                // CRITICAL FIX: Emit candles immediately instead of waiting for window close
                .suppress(Suppressed.untilTimeLimit(
                    Duration.ofMillis(100), 
                    Suppressed.BufferConfig.maxRecords(1000)
                ));

        // Stream the finalized candles to the output topic
        candlestickTable.toStream()
            .map((windowedKey, candle) -> {
                // Add window boundary timestamps
                candle.setWindowStartMillis(windowedKey.window().start());
                candle.setWindowEndMillis(windowedKey.window().end());
                
                // Ensure the right alignment with exchange trading hours
                if ("N".equals(candle.getExchange())) {
                    // For NSE, ensure alignment with 9:15 trading start
                    ensureNseAlignment(candle);
                } else {
                    // For MCX, ensure alignment with 9:00 trading start
                    ensureMcxAlignment(candle);
                }
                
                // Log candle details for debugging 
                logCandleDetails(candle, 1);
                
                return KeyValue.pair(windowedKey.key(), candle);
            })
            .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
            
        LOGGER.info("Configured 1-minute candles with both buffered processing and Kafka Streams pipeline");
    }
    
    /**
     * Ensures that candle window timestamps align perfectly with NSE trading hours
     * (starting at 9:15 AM)
     */
    private void ensureNseAlignment(Candlestick candle) {
        ZonedDateTime startTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getWindowStartMillis()), 
                ZoneId.of("Asia/Kolkata"));
        
        // Get the reference trading day start (9:15 AM)
        ZonedDateTime marketOpen = startTime.toLocalDate()
                .atTime(9, 15, 0)
                .atZone(ZoneId.of("Asia/Kolkata"));
        
        // Calculate minutes elapsed since market open
        int minutesElapsed = (int) ((startTime.toEpochSecond() - marketOpen.toEpochSecond()) / 60);
        
        // Calculate the proper window start time based on trading hours
        int windowNum = minutesElapsed / 1; // For 1-minute candles
        ZonedDateTime correctedStart = marketOpen.plusMinutes(windowNum);
        ZonedDateTime correctedEnd = correctedStart.plusMinutes(1);
        
        // Apply the corrected timestamps
        candle.setWindowStartMillis(correctedStart.toInstant().toEpochMilli());
        candle.setWindowEndMillis(correctedEnd.toInstant().toEpochMilli());
    }
    
    /**
     * Ensures that candle window timestamps align perfectly with MCX trading hours
     * (starting at 9:00 AM)
     */
    private void ensureMcxAlignment(Candlestick candle) {
        ZonedDateTime startTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getWindowStartMillis()), 
                ZoneId.of("Asia/Kolkata"));
        
        // Get the reference trading day start (9:00 AM)
        ZonedDateTime marketOpen = startTime.toLocalDate()
                .atTime(9, 0, 0)
                .atZone(ZoneId.of("Asia/Kolkata"));
        
        // Calculate minutes elapsed since market open
        int minutesElapsed = (int) ((startTime.toEpochSecond() - marketOpen.toEpochSecond()) / 60);
        
        // Calculate the proper window start time based on trading hours
        int windowNum = minutesElapsed / 1; // For 1-minute candles
        ZonedDateTime correctedStart = marketOpen.plusMinutes(windowNum);
        ZonedDateTime correctedEnd = correctedStart.plusMinutes(1);
        
        // Apply the corrected timestamps
        candle.setWindowStartMillis(correctedStart.toInstant().toEpochMilli());
        candle.setWindowEndMillis(correctedEnd.toInstant().toEpochMilli());
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
        
        // Group ticks by minute for metrics tracking
        Map<Long, Integer> ticksPerMinute = new HashMap<>();
        
        // Track all ticks for metrics purposes only (actual processing is done via the Kafka Streams pipeline)
        for (TickData tick : ticks) {
            // Skip ticks outside trading hours
            if (!isWithinTradingHours(tick)) {
                metrics.incrementSkippedTicks();
                continue;
            }
            
            // Get window start time for this tick
            long windowStartTime = calculateWindowStartTime(tick, 1);
            
            // Count ticks per minute for metrics
            ticksPerMinute.compute(windowStartTime, (k, v) -> (v == null) ? 1 : v + 1);
            
            // Update metrics
            metrics.incrementProcessedTicks();
        }
        
        // Log metrics about tick distribution
        if (!ticksPerMinute.isEmpty() && LOGGER.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Tick distribution for ").append(symbol).append(":\n");
            
            ticksPerMinute.forEach((windowStart, count) -> {
                ZonedDateTime windowTime = ZonedDateTime.ofInstant(
                        Instant.ofEpochMilli(windowStart), 
                        ZoneId.of("Asia/Kolkata"));
                sb.append("  ").append(windowTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                  .append(": ").append(count).append(" ticks\n");
            });
            
            LOGGER.debug(sb.toString());
        }
    }
    
    /**
     * Checks if the tick is within valid trading hours for the corresponding exchange
     * NSE: 9:15 AM to 3:30 PM STRICTLY
     * MCX: 9:00 AM to 11:30 PM
     */
    private boolean isWithinTradingHours(TickData tick) {
        // Parse tick timestamp
        ZonedDateTime time;
        String tickDt = tick.getTickDt();
        
        try {
            // Handle Microsoft JSON date format: /Date(1746430676000)/
            if (tickDt != null && tickDt.startsWith("/Date(") && tickDt.endsWith(")/")) {
                // Extract the timestamp (milliseconds since epoch)
                String millisStr = tickDt.substring(6, tickDt.length() - 2);
                long millis = Long.parseLong(millisStr);
                time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.of("Asia/Kolkata"));
            } else {
                // Standard format
                time = ZonedDateTime.parse(tickDt, 
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata")));
            }
            
            // ENHANCED: Check exchange-specific trading hours with strict filtering
            if ("N".equals(tick.getExchange())) {
                boolean withinHours = isWithinNseTradingHours(time);
                if (!withinHours) {
                    LOGGER.debug("BLOCKED NSE tick outside market hours. Time: {}, Exchange: {}, Token: {}", 
                            time.format(DateTimeFormatter.ofPattern("HH:mm:ss")), 
                            tick.getExchange(), tick.getToken());
                }
                return withinHours;
            } else if ("M".equals(tick.getExchange())) {
                boolean withinHours = isWithinMcxTradingHours(time);
                if (!withinHours) {
                    LOGGER.debug("BLOCKED MCX tick outside market hours. Time: {}, Exchange: {}, Token: {}", 
                            time.format(DateTimeFormatter.ofPattern("HH:mm:ss")), 
                            tick.getExchange(), tick.getToken());
                }
                return withinHours;
            }
            
            // For other exchanges, default to true
            return true;
        } catch (Exception e) {
            LOGGER.warn("Error parsing tick datetime '{}' for token {}: {}. Defaulting to allowing the tick.", 
                     tickDt, tick.getToken(), e.getMessage());
            // In case of parsing error, default to true to allow the tick through
            return true;
        }
    }
    
    /**
     * Check if the given time is within NSE trading hours (9:15 AM to 3:30 PM)
     */
    private boolean isWithinNseTradingHours(ZonedDateTime time) {
        // Get just the time part
        LocalTime localTime = time.toLocalTime();
        
        // Define NSE market hours: 9:15 AM to 3:30 PM
        LocalTime marketOpen = LocalTime.of(9, 15);
        LocalTime marketClose = LocalTime.of(15, 30);
        
        // Check if within market hours
        return !localTime.isBefore(marketOpen) && !localTime.isAfter(marketClose);
    }
    
    /**
     * Check if the given time is within MCX/Commodity trading hours (9:00 AM to 11:30 PM)
     */
    private boolean isWithinMcxTradingHours(ZonedDateTime time) {
        // Get just the time part
        LocalTime localTime = time.toLocalTime();
        
        // Define MCX market hours: 9:00 AM to 11:30 PM
        LocalTime marketOpen = LocalTime.of(9, 0);
        LocalTime marketClose = LocalTime.of(23, 30);
        
        // Check if within market hours
        return !localTime.isBefore(marketOpen) && !localTime.isAfter(marketClose);
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
     * Adjusts the window boundaries for NSE 30-minute candles to align with 9:15 market open
     * Creates candles like 9:15-9:45, 9:45-10:15 instead of 9:30-10:00
     */
    private void adjustNseWindowAlignment(Candlestick candle, int windowSizeMinutes) {
        // Get the window start time
        ZonedDateTime startTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getWindowStartMillis()), 
                ZoneId.of("Asia/Kolkata"));
        
        // Reference NSE market open (9:15 AM)
        ZonedDateTime marketOpen = startTime.toLocalDate()
                .atTime(9, 15, 0)
                .atZone(ZoneId.of("Asia/Kolkata"));
        
        // If the start time is before today's market open, use previous day
        if (startTime.isBefore(marketOpen)) {
            marketOpen = marketOpen.minusDays(1);
        }
        
        // ✅ FIXED: Use ChronoUnit for precise minute calculation
        long minutesElapsed = ChronoUnit.MINUTES.between(marketOpen, startTime);
        
        // ✅ FIXED: Ensure proper integer window calculation with bounds checking
        int windowNum = (int) Math.max(0, minutesElapsed / windowSizeMinutes);
        
        // Calculate properly aligned window boundaries based on 9:15 AM start
        ZonedDateTime correctedStart = marketOpen.plusMinutes(windowNum * windowSizeMinutes);
        ZonedDateTime correctedEnd = correctedStart.plusMinutes(windowSizeMinutes);
        
        // Apply the corrected timestamps
        candle.setWindowStartMillis(correctedStart.toInstant().toEpochMilli());
        candle.setWindowEndMillis(correctedEnd.toInstant().toEpochMilli());
        
        LOGGER.debug("Adjusted NSE window: original=[{}] corrected=[{}-{}], windowNum={}, minutesElapsed={}",
                startTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                correctedStart.format(DateTimeFormatter.ofPattern("HH:mm")),
                correctedEnd.format(DateTimeFormatter.ofPattern("HH:mm")),
                windowNum, minutesElapsed);
    }

    /**
     * Adjusts the window boundaries for MCX 30-minute candles to align with 9:00 market open
     */
    private void adjustMcxWindowAlignment(Candlestick candle, int windowSizeMinutes) {
        // Get the window start time
        ZonedDateTime startTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getWindowStartMillis()), 
                ZoneId.of("Asia/Kolkata"));
        
        // Reference MCX market open (9:00 AM)
        ZonedDateTime marketOpen = startTime.toLocalDate()
                .atTime(9, 0, 0)
                .atZone(ZoneId.of("Asia/Kolkata"));
        
        // If the start time is before today's market open, use previous day
        if (startTime.isBefore(marketOpen)) {
            marketOpen = marketOpen.minusDays(1);
        }
        
        // ✅ FIXED: Use ChronoUnit for precise minute calculation
        long minutesElapsed = ChronoUnit.MINUTES.between(marketOpen, startTime);
        
        // ✅ FIXED: Ensure proper integer window calculation with bounds checking
        int windowNum = (int) Math.max(0, minutesElapsed / windowSizeMinutes);
        
        // Calculate properly aligned window boundaries based on 9:00 AM start
        ZonedDateTime correctedStart = marketOpen.plusMinutes(windowNum * windowSizeMinutes);
        ZonedDateTime correctedEnd = correctedStart.plusMinutes(windowSizeMinutes);
        
        // Apply the corrected timestamps
        candle.setWindowStartMillis(correctedStart.toInstant().toEpochMilli());
        candle.setWindowEndMillis(correctedEnd.toInstant().toEpochMilli());
        
        LOGGER.debug("Adjusted MCX window: original=[{}] corrected=[{}-{}], windowNum={}, minutesElapsed={}",
                startTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                correctedStart.format(DateTimeFormatter.ofPattern("HH:mm")),
                correctedEnd.format(DateTimeFormatter.ofPattern("HH:mm")),
                windowNum, minutesElapsed);
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
        LOGGER.info("Configuring multi-minute candles for {} minutes", windowSize);
        
        // Create input stream from the 1-minute candle topic
        KStream<String, Candlestick> inputStream = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), Candlestick.serde())
                        .withTimestampExtractor(new ExchangeTimestampExtractor(windowSize))
        );
        
        // Create a window that aligns with trading hours
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(windowSize), 
            Duration.ofSeconds(30)
        ).advanceBy(Duration.ofMinutes(windowSize)); // Advance by window size to prevent overlapping windows
        
        // Group by symbol and window, then aggregate candles
        KTable<Windowed<String>, Candlestick> aggregatedCandles = inputStream
                .groupByKey(Grouped.with(Serdes.String(), Candlestick.serde()))
                .windowedBy(windows)
                .aggregate(
                        Candlestick::new,
                        (key, candle, aggCandle) -> {
                            // For the first candle in a window, initialize with its values
                            if (aggCandle.getOpen() == 0) {
                                aggCandle.setOpen(candle.getOpen());
                                aggCandle.setLow(candle.getLow());
                                aggCandle.setHigh(candle.getHigh());
                                aggCandle.setVolume(0); // We'll add the volume below
                            }
                            
                            // Update the aggregate candle
                            aggCandle.setClose(candle.getClose());
                            aggCandle.setLow(Math.min(aggCandle.getLow(), candle.getLow()));
                            aggCandle.setHigh(Math.max(aggCandle.getHigh(), candle.getHigh()));
                            aggCandle.setVolume(aggCandle.getVolume() + candle.getVolume());
                            aggCandle.setCompanyName(candle.getCompanyName());
                            aggCandle.setExchange(candle.getExchange());
                            
                            // Set exchangeType with a default if it's null
                            if (candle.getExchangeType() != null) {
                                aggCandle.setExchangeType(candle.getExchangeType());
                            } else if (aggCandle.getExchangeType() == null) {
                                // Derive default from exchange
                                if ("N".equals(candle.getExchange())) {
                                    aggCandle.setExchangeType("EQUITY");
                                } else if ("M".equals(candle.getExchange())) {
                                    aggCandle.setExchangeType("COMMODITY");
                                } else {
                                    aggCandle.setExchangeType("UNKNOWN");
                                }
                            }
                            
                            aggCandle.setScripCode(candle.getScripCode());
                            
                            return aggCandle;
                        },
                        Materialized.<String, Candlestick, WindowStore<Bytes, byte[]>>as("candle-store-" + windowSize + "m")
                        .withKeySerde(Serdes.String())
                        .withValueSerde(Candlestick.serde())
                )
                // CRITICAL FIX: Emit candles immediately instead of waiting for window close
                .suppress(Suppressed.untilTimeLimit(
                    Duration.ofMillis(100), 
                    Suppressed.BufferConfig.maxRecords(1000)
                ));
                
        // Stream the finalized candles to the output topic
        aggregatedCandles.toStream()
            .map((windowedKey, candle) -> {
                // Add window boundary timestamps
                candle.setWindowStartMillis(windowedKey.window().start());
                candle.setWindowEndMillis(windowedKey.window().end());
                
                // Apply NSE/MCX specific window alignment ONLY for 30-minute candles
                // This keeps existing timeframes unchanged while fixing the 30-minute candles
                if (windowSize == 30) {
                    if ("N".equals(candle.getExchange())) {
                        adjustNseWindowAlignment(candle, windowSize);
                    } else if ("M".equals(candle.getExchange())) {
                        adjustMcxWindowAlignment(candle, windowSize);
                    }
                }
                
                // Log candle details for debugging
                logCandleDetails(candle, windowSize);
                
                return KeyValue.pair(windowedKey.key(), candle);
            })
            .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
            
        LOGGER.info("Completed configuration for {}-minute candles", windowSize);
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

    /**
     * Main method to start the Kafka Streams topology for all timeframes
     */
    @PostConstruct
    public void start() {
        try {
            LOGGER.info("Starting Realtime Candlestick Processor with bootstrap servers: {}", kafkaConfig.getBootstrapServers());
            
            // Process 1-minute candles from tick data
            process("realtime-candle-1min", "forwardtesting-data", "1-min-candle", 1);
            
            // Process multi-minute candles from 1-minute candles
            process("realtime-candle-2min", "1-min-candle", "2-min-candle", 2);
            process("realtime-candle-3min", "1-min-candle", "3-min-candle", 3);
            process("realtime-candle-5min", "1-min-candle", "5-min-candle", 5);
            process("realtime-candle-15min", "1-min-candle", "15-min-candle", 15);
            process("realtime-candle-30min", "1-min-candle", "30-min-candle", 30);
            
            LOGGER.info("All Realtime Candlestick Processors started successfully");
            
        } catch (Exception e) {
            LOGGER.error("Error starting Realtime Candlestick Processors", e);
        }
    }
}
