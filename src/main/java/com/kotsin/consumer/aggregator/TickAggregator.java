package com.kotsin.consumer.aggregator;

import com.kotsin.consumer.logging.TraceContext;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.model.TickCandle;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.repository.TickCandleRepository;
import com.kotsin.consumer.service.RedisCacheService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TickAggregator - Independent Kafka consumer for tick data.
 *
 * Responsibilities:
 * 1. Consume from forwardtesting-data topic
 * 2. Aggregate ticks into 1-minute candles
 * 3. Write to MongoDB (tick_candles_1m)
 * 4. Write to Redis (hot cache)
 *
 * Key Design:
 * - SIMPLE: Single responsibility - only tick aggregation
 * - NO JOINS: Does not join with orderbook or OI
 * - INDEPENDENT: Runs in its own thread, no Kafka Streams state stores
 * - EVENT-TIME: Uses tick event time for window assignment (v2.1)
 *
 * v2.1 Quant Fixes:
 * - Event time based windowing (uses tickDt, not wall clock)
 * - Lee-Ready trade classification (uses previous midpoint)
 * - Volume-based VPIN (10k share buckets, not time-based)
 * - Data quality validation (rejects bad ticks)
 */
@Component
@Slf4j
public class TickAggregator {

    private static final String LOG_PREFIX = "[TICK-AGG]";

    @Value("${v2.tick.aggregator.enabled:true}")
    private boolean enabled;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${v2.tick.input.topic:forwardtesting-data}")
    private String inputTopic;

    @Value("${v2.tick.consumer.group:tick-aggregator-v2}")
    private String consumerGroup;

    @Value("${v2.tick.aggregator.threads:4}")
    private int numThreads;

    @Value("${v2.tick.output.topic:tick-candles-1m}")
    private String outputTopic;

    @Value("${logging.trace.symbols:}")
    private String traceSymbolsStr;
    private Set<String> traceSymbols;
    private final java.util.concurrent.atomic.AtomicLong tickTraceCounter = new java.util.concurrent.atomic.AtomicLong(0);

    @Autowired
    private TickCandleRepository tickCandleRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    // Aggregation state: key = "exchange:scripCode", value = TickAggregateState
    private final ConcurrentHashMap<String, TickAggregateState> aggregationState = new ConcurrentHashMap<>();

    // Executor for consumer threads
    private ExecutorService consumerExecutor;

    // Executor for window emission
    private ScheduledExecutorService emissionScheduler;

    // Shutdown flag
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Current window
    private volatile Instant currentWindowStart;
    private volatile Instant currentWindowEnd;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("{} Disabled by configuration", LOG_PREFIX);
            return;
        }

        log.info("{} Starting with threads={}, topic={}", LOG_PREFIX, numThreads, inputTopic);

        running.set(true);

        // Initialize current window
        Instant now = Instant.now();
        currentWindowStart = Timeframe.M1.alignToWindowStart(now);
        currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);

        // Start consumer threads
        consumerExecutor = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numThreads; i++) {
            consumerExecutor.submit(this::consumeLoop);
        }

        // Start window emission scheduler (runs every second to check window close)
        emissionScheduler = Executors.newSingleThreadScheduledExecutor();
        emissionScheduler.scheduleAtFixedRate(this::checkWindowEmission, 1, 1, TimeUnit.SECONDS);

        log.info("{} Started successfully", LOG_PREFIX);

        // Parse trace symbols
        this.traceSymbols = new HashSet<>();
        if (traceSymbolsStr != null && !traceSymbolsStr.isBlank()) {
            String[] parts = traceSymbolsStr.split(",");
            for (String part : parts) {
                traceSymbols.add(part.trim().toUpperCase());
            }
            log.info("{} Trace logging enabled for symbols: {}", LOG_PREFIX, traceSymbols);
        } else {
            log.info("{} Trace logging enabled for ALL symbols (FULL - NO SAMPLING)", LOG_PREFIX);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("{} Stopping...", LOG_PREFIX);
        running.set(false);

        if (emissionScheduler != null) {
            emissionScheduler.shutdown();
        }
        if (consumerExecutor != null) {
            consumerExecutor.shutdown();
            try {
                consumerExecutor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Emit any remaining data
        emitCurrentWindow();

        log.info("{} Stopped", LOG_PREFIX);
    }

    /**
     * Main consumer loop - runs in thread pool.
     */
    private void consumeLoop() {
        String threadName = Thread.currentThread().getName();
        log.info("{} Consumer thread {} starting, connecting to {}", LOG_PREFIX, threadName, bootstrapServers);

        KafkaConsumer<String, TickData> consumer = createConsumer();
        consumer.subscribe(Collections.singletonList(inputTopic));
        log.info("{} Thread {} subscribed to topic: {}", LOG_PREFIX, threadName, inputTopic);

        long pollCount = 0;
        long lastLogTime = System.currentTimeMillis();
        int recordsReceived = 0;

        try {
            while (running.get()) {
                ConsumerRecords<String, TickData> records = consumer.poll(Duration.ofMillis(100));
                pollCount++;
                recordsReceived += records.count();

                // Log every 30 seconds
                if (System.currentTimeMillis() - lastLogTime > 30000) {
                    log.info("{} Thread {} stats: polls={}, recordsReceived={}, activeInstruments={}",
                        LOG_PREFIX, threadName, pollCount, recordsReceived, aggregationState.size());
                    lastLogTime = System.currentTimeMillis();
                    pollCount = 0;
                    recordsReceived = 0;
                }

                for (ConsumerRecord<String, TickData> record : records) {
                    try {
                        processTick(record.value(), record.timestamp());
                    } catch (Exception e) {
                        log.error("{} Error processing tick: {}", LOG_PREFIX, e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("{} Thread {} consumer loop failed: {}", LOG_PREFIX, threadName, e.getMessage(), e);
        } finally {
            consumer.close();
            log.info("{} Thread {} consumer closed", LOG_PREFIX, threadName);
        }
    }

    /**
     * Process a single tick with data quality validation.
     */
    private void processTick(TickData tick, long kafkaTimestamp) {
        if (tick == null || tick.getScripCode() == null) return;


      //  log.info("tick data  is : {}",tick);
        // DATA QUALITY VALIDATION (v2.1)
        String validationError = validateTick(tick);
        if (validationError != null) {
            log.debug("{} Rejected tick {}: {}", LOG_PREFIX, tick.getScripCode(), validationError);
            return;
        }

        if (validationError != null) {
            log.debug("{} Rejected tick {}: {}", LOG_PREFIX, tick.getScripCode(), validationError);
            return;
        }

        String key = buildKey(tick);
        // Extract symbol for logging check
        String symbol = TickAggregateState.extractSymbol(tick.getCompanyName(), tick.getScripCode());

        // [TICK-TRACE] Log sampling
        long count = tickTraceCounter.incrementAndGet();
        // [TICK-TRACE] Log everything if list is empty, or specific symbol
        boolean specificSymbol = traceSymbols.contains(symbol);
        boolean shouldLog = specificSymbol || traceSymbols.isEmpty();

        if (shouldLog) {
            log.info("[TICK-TRACE] Received tick for {}: Price={}, Vol={}, Time={}", 
                symbol, tick.getLastRate(), tick.getLastQuantity(), tick.getTickDt());
        }

        // Start trace context
        TraceContext.start(symbol, "1m");
        try {
            // USE EVENT TIME from tick, not Kafka timestamp (v2.1)
            Instant tickTime = parseEventTime(tick, kafkaTimestamp);

            // Get or create aggregation state
            TickAggregateState state = aggregationState.computeIfAbsent(key,
                k -> new TickAggregateState(tick, currentWindowStart, currentWindowEnd));

            // Update aggregation
            state.update(tick, tickTime);
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * Parse event time from tick's tickDt field.
     * Falls back to Kafka timestamp if parsing fails.
     */
    private Instant parseEventTime(TickData tick, long kafkaFallback) {
        String tickDt = tick.getTickDt();
        if (tickDt != null && tickDt.contains("Date(")) {
            try {
                String num = tickDt.replaceAll("[^0-9]", "");
                long eventTime = Long.parseLong(num);
                // Sanity check: within reasonable range (2020-2050)
                if (eventTime >= 1577836800000L && eventTime <= 2524608000000L) {
                    return Instant.ofEpochMilli(eventTime);
                }
            } catch (NumberFormatException e) {
                // Fall through to use Kafka timestamp
            }
        }
        return Instant.ofEpochMilli(kafkaFallback);
    }

    /**
     * Validate tick data quality.
     * Returns error message if invalid, null if valid.
     */
    private String validateTick(TickData tick) {
        // Check price > 0
        if (tick.getLastRate() <= 0) {
            return "Price <= 0";
        }
        return null; // Valid
    }

    /**
     * Check if current window should be emitted (wall clock based).
     */
    private void checkWindowEmission() {
        Instant now = Instant.now();

        // Check if current window has closed (with 2 second grace)
        if (now.isAfter(currentWindowEnd.plusSeconds(2))) {
            log.info("{} Window closed at {}, emitting {} instruments",
                LOG_PREFIX, formatTime(currentWindowEnd), aggregationState.size());

            emitCurrentWindow();

            // Move to next window
            currentWindowStart = currentWindowEnd;
            currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);

            log.info("{} Advanced to window: {} - {}",
                LOG_PREFIX, formatTime(currentWindowStart), formatTime(currentWindowEnd));
        }
    }

    /**
     * Emit all candles for current window.
     */
    private void emitCurrentWindow() {
        if (aggregationState.isEmpty()) return;

        Instant windowStart = currentWindowStart;
        Instant windowEnd = currentWindowEnd;

        log.info("{} Emitting window {} - {} with {} instruments",
            LOG_PREFIX, formatTime(windowStart), formatTime(windowEnd), aggregationState.size());

        List<TickCandle> candlesToSave = new ArrayList<>();

        for (Map.Entry<String, TickAggregateState> entry : aggregationState.entrySet()) {
            TickAggregateState state = entry.getValue();

            // Only emit if state is for current window and has data
            if (state.getWindowStart().equals(windowStart) && state.getTickCount() > 0) {
                TickCandle candle = state.toTickCandle();
                candlesToSave.add(candle);

                // Reset state for next window
                state.reset(currentWindowEnd, Timeframe.M1.getWindowEnd(currentWindowEnd));
            }
        }

        if (!candlesToSave.isEmpty()) {
            // Batch save to MongoDB
            try {
                tickCandleRepository.saveAll(candlesToSave);
                log.info("{} Saved {} candles to MongoDB", LOG_PREFIX, candlesToSave.size());
                
                // Trace individual saves if enabled
                if (traceSymbols != null && !traceSymbols.isEmpty()) {
                    for (TickCandle c : candlesToSave) {
                        if (traceSymbols.contains(c.getSymbol()) || traceSymbols.contains(c.getScripCode())) {
                            log.info("[MONGO-WRITE] TickCandle saved for {} @ {} | Close: {} | Vol: {}", 
                                c.getSymbol(), formatTime(c.getWindowEnd()), c.getClose(), c.getVolume());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("{} Failed to save to MongoDB: {}", LOG_PREFIX, e.getMessage());
            }

            // Save to Redis (hot cache)
            try {
                for (TickCandle candle : candlesToSave) {
                    redisCacheService.cacheTickCandle(candle);
                    // v2.1: Cache price for OI interpretation
                    redisCacheService.cachePrice(candle.getSymbol(), candle.getClose());
                }
                log.debug("{} Cached {} candles in Redis", LOG_PREFIX, candlesToSave.size());
            } catch (Exception e) {
                log.error("{} Failed to cache in Redis: {}", LOG_PREFIX, e.getMessage());
            }

            // Publish to Kafka topic
            try {
                for (TickCandle candle : candlesToSave) {
                    kafkaTemplate.send(outputTopic, candle.getSymbol(), candle);
                }
                log.info("{} Published {} candles to Kafka topic {}", LOG_PREFIX, candlesToSave.size(), outputTopic);
            } catch (Exception e) {
                log.error("{} Failed to publish to Kafka: {}", LOG_PREFIX, e.getMessage());
            }
        }

        // Clean up old states (instruments with no activity for 5 minutes)
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
        aggregationState.entrySet().removeIf(e -> e.getValue().getLastUpdate().isBefore(cutoff));
    }

    /**
     * Create Kafka consumer.
     */
    private KafkaConsumer<String, TickData> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.kotsin.consumer.model,com.kotsin.optionDataProducer.model");
        // FIX: Specify the target type explicitly since producer doesn't send type headers
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TickData.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2000);

        return new KafkaConsumer<>(props);
    }

    private String buildKey(TickData tick) {
        return tick.getExchange() + ":" + tick.getScripCode();
    }

    private String formatTime(Instant instant) {
        return ZonedDateTime.ofInstant(instant, IST).format(TIME_FMT);
    }

    /**
     * Check if the aggregator is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get aggregator stats.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("enabled", enabled);
        stats.put("running", running.get());
        stats.put("activeInstruments", aggregationState.size());
        stats.put("currentWindowStart", currentWindowStart != null ? currentWindowStart.toString() : null);
        stats.put("currentWindowEnd", currentWindowEnd != null ? currentWindowEnd.toString() : null);
        return stats;
    }

    /**
     * Internal class to track aggregation state for a single instrument.
     */
    private static class TickAggregateState {
        private final String symbol;
        private final String scripCode;
        private final String exchange;
        private final String exchangeType;
        private final String companyName;

        private Instant windowStart;
        private Instant windowEnd;
        private Instant lastUpdate;

        // OHLCV
        private double open;
        private double high;
        private double low;
        private double close;
        private long volume;
        private double valueSum;  // For VWAP calculation

        // Trade classification (Lee-Ready algorithm v2.1)
        private long buyVolume;
        private long sellVolume;
        private long midpointVolume;
        private double previousMidpoint;  // For Lee-Ready: compare to PREVIOUS midpoint

        // Volume profile (key = price in paise as string to avoid MongoDB dot issues)
        private final Map<String, Long> volumeAtPrice = new ConcurrentHashMap<>();

        // Imbalance tracking
        private double volumeImbalance;
        private double dollarImbalance;
        private int tickRuns;
        private int lastTickDirection;  // 1 = up, -1 = down, 0 = neutral

        // VPIN calculation (volume-based buckets v2.1)
        private static final int VPIN_BUCKET_SIZE = 10000;  // 10k shares per bucket
        private static final int VPIN_NUM_BUCKETS = 50;     // Average over 50 buckets
        private long currentBucketVolume;
        private long currentBucketBuyVolume;
        private final java.util.LinkedList<Double> vpinBuckets = new java.util.LinkedList<>();

        // Stats
        private int tickCount;
        private int largeTradeCount;
        private double lastPrice;

        public TickAggregateState(TickData firstTick, Instant windowStart, Instant windowEnd) {
            this.symbol = extractSymbol(firstTick.getCompanyName(), firstTick.getScripCode());
            this.scripCode = firstTick.getScripCode();
            this.exchange = firstTick.getExchange();
            this.exchangeType = firstTick.getExchangeType();
            this.companyName = firstTick.getCompanyName();
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.lastUpdate = Instant.now();

            // Initialize OHLC - use lastRate (the correct field name in TickData)
            double price = firstTick.getLastRate();
            this.open = price;
            this.high = price;
            this.low = price;
            this.close = price;
            this.lastPrice = price;
        }

        public synchronized void update(TickData tick, Instant tickTime) {
            double price = tick.getLastRate();
            long qty = tick.getLastQuantity();  // lastQuantity is int in TickData

            // Update OHLC
            if (tickCount == 0) {
                open = price;
                high = price;
                low = price;
            } else {
                high = Math.max(high, price);
                low = Math.min(low, price);
            }
            close = price;

            // Update volume
            volume += qty;
            valueSum += price * qty;

            // Calculate current midpoint
            double bid = tick.getBidRate() > 0 ? tick.getBidRate() : price;
            double ask = tick.getOfferRate() > 0 ? tick.getOfferRate() : price;
            double midpoint = (bid + ask) / 2;

            // LEE-READY TRADE CLASSIFICATION (v2.1)
            // Compare price to PREVIOUS midpoint, not current midpoint
            boolean isBuy = false;
            if (previousMidpoint > 0) {
                if (price > previousMidpoint) {
                    buyVolume += qty;  // Uptick = aggressive buy
                    isBuy = true;
                } else if (price < previousMidpoint) {
                    sellVolume += qty;  // Downtick = aggressive sell
                } else {
                    // At previous midpoint - use tick direction as tiebreaker
                    if (lastTickDirection > 0) {
                        buyVolume += qty;
                        isBuy = true;
                    } else if (lastTickDirection < 0) {
                        sellVolume += qty;
                    } else {
                        midpointVolume += qty;  // True midpoint trade
                    }
                }
            } else {
                // First tick - use current midpoint comparison
                if (price > midpoint + 0.001) {
                    buyVolume += qty;
                    isBuy = true;
                } else if (price < midpoint - 0.001) {
                    sellVolume += qty;
                } else {
                    midpointVolume += qty;
                }
            }
            previousMidpoint = midpoint;  // Store for next tick

            // VOLUME-BASED VPIN UPDATE (v2.1)
            updateVpinBucket(qty, isBuy);

            // Volume profile (store price in paise as string key to avoid MongoDB dot issues)
            long priceInPaise = Math.round(price * 100);
            volumeAtPrice.merge(String.valueOf(priceInPaise), qty, Long::sum);

            // Imbalance tracking
            int direction = price > lastPrice ? 1 : (price < lastPrice ? -1 : 0);
            if (direction == lastTickDirection && direction != 0) {
                tickRuns++;
            } else {
                tickRuns = 1;
            }
            lastTickDirection = direction;

            long signedQty = direction * qty;
            volumeImbalance += signedQty;
            dollarImbalance += signedQty * price;

            // Large trade detection (> 10000 qty considered large)
            if (qty > 10000) {
                largeTradeCount++;
            }

            tickCount++;
            lastPrice = price;
            lastUpdate = tickTime;
        }

        /**
         * Update VPIN volume bucket (v2.1).
         * When bucket fills, calculate imbalance and add to rolling list.
         */
        private void updateVpinBucket(long qty, boolean isBuy) {
            currentBucketVolume += qty;
            if (isBuy) currentBucketBuyVolume += qty;
            
            if (currentBucketVolume >= VPIN_BUCKET_SIZE) {
                // Bucket complete - calculate imbalance
                double imbalance = Math.abs(2.0 * currentBucketBuyVolume - currentBucketVolume) 
                                   / currentBucketVolume;
                vpinBuckets.addLast(imbalance);
                
                // Keep rolling window
                while (vpinBuckets.size() > VPIN_NUM_BUCKETS) {
                    vpinBuckets.removeFirst();
                }
                
                // Reset bucket
                currentBucketVolume = 0;
                currentBucketBuyVolume = 0;
            }
        }

        /**
         * Calculate VPIN as average imbalance across buckets (v2.1).
         */
        private double calculateVPIN() {
            if (vpinBuckets.isEmpty()) return 0.0;
            return vpinBuckets.stream().mapToDouble(d -> d).average().orElse(0.0);
        }

        public TickCandle toTickCandle() {
            double vwap = volume > 0 ? valueSum / volume : close;

            // Calculate POC, VAH, VAL from volume profile
            double poc = close;
            double vah = high;
            double val = low;

            if (!volumeAtPrice.isEmpty()) {
                // POC = price with max volume (convert from paise string back to price)
                poc = volumeAtPrice.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(e -> Long.parseLong(e.getKey()) / 100.0)
                    .orElse(close);

                // VAH/VAL = 70% value area (simplified)
                // For full implementation, sort by price and find 70% boundaries
                vah = volumeAtPrice.keySet().stream()
                    .mapToLong(Long::parseLong)
                    .max()
                    .orElse((long)(high * 100)) / 100.0;
                val = volumeAtPrice.keySet().stream()
                    .mapToLong(Long::parseLong)
                    .min()
                    .orElse((long)(low * 100)) / 100.0;
            }

            double buyPressure = volume > 0 ? (double) buyVolume / volume : 0.5;
            double sellPressure = volume > 0 ? (double) sellVolume / volume : 0.5;

            // Parse option metadata if applicable
            OptionMetadata optionMeta = parseOptionMetadata(companyName);

            TickCandle.TickCandleBuilder builder = TickCandle.builder()
                .symbol(symbol)
                .scripCode(scripCode)
                .exchange(exchange)
                .exchangeType(exchangeType)
                .companyName(companyName)
                .instrumentType(TickCandle.InstrumentType.detect(exchange, exchangeType, companyName))
                .timestamp(windowEnd)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(volume)
                .value(valueSum)
                .vwap(vwap)
                .typicalPrice((high + low + close) / 3)
                .buyVolume(buyVolume)
                .sellVolume(sellVolume)
                .midpointVolume(midpointVolume)
                .volumeDelta(buyVolume - sellVolume)
                .buyPressure(buyPressure)
                .sellPressure(sellPressure)
                .vpin(calculateVPIN())  // Volume-based VPIN (v2.1)
                .vpinBucketSize((double) VPIN_BUCKET_SIZE)
                .poc(poc)
                .vah(vah)
                .val(val)
                .volumeAtPrice(new HashMap<>(volumeAtPrice))
                .volumeImbalance(volumeImbalance)
                .dollarImbalance(dollarImbalance)
                .tickRuns(tickRuns)
                .vibTriggered(Math.abs(volumeImbalance) > volume * 0.3)
                .dibTriggered(Math.abs(dollarImbalance) > valueSum * 0.3)
                .trbTriggered(tickRuns > 10)
                .tickCount(tickCount)
                .ticksPerSecond(tickCount / 60.0)
                .largeTradeCount(largeTradeCount)
                .quality("VALID")
                .processingLatencyMs(System.currentTimeMillis() - windowEnd.toEpochMilli())
                .createdAt(Instant.now());

            // Add option metadata if parsed
            if (optionMeta != null) {
                builder.strikePrice(optionMeta.strikePrice)
                       .optionType(optionMeta.optionType)
                       .expiry(optionMeta.expiry)
                       .daysToExpiry(optionMeta.daysToExpiry);
            }

            return builder.build();
        }

        public void reset(Instant newWindowStart, Instant newWindowEnd) {
            this.windowStart = newWindowStart;
            this.windowEnd = newWindowEnd;

            // Reset OHLCV (open = previous close for continuity)
            this.open = this.close;
            this.high = this.close;
            this.low = this.close;
            this.volume = 0;
            this.valueSum = 0;

            // Reset classification
            this.buyVolume = 0;
            this.sellVolume = 0;
            this.midpointVolume = 0;

            // Reset volume profile
            this.volumeAtPrice.clear();

            // Reset imbalance
            this.volumeImbalance = 0;
            this.dollarImbalance = 0;
            this.tickRuns = 0;

            // Reset stats
            this.tickCount = 0;
            this.largeTradeCount = 0;
        }

        public Instant getWindowStart() { return windowStart; }
        public Instant getLastUpdate() { return lastUpdate; }
        public int getTickCount() { return tickCount; }

        private static String extractSymbol(String companyName, String scripCode) {
            if (companyName == null || companyName.isEmpty()) return scripCode;

            String upper = companyName.toUpperCase().trim();

            // Handle special cases
            if (upper.startsWith("BANK NIFTY") || upper.startsWith("BANKNIFTY")) return "BANKNIFTY";
            if (upper.startsWith("NIFTY BANK")) return "BANKNIFTY";
            if (upper.startsWith("FIN NIFTY") || upper.startsWith("FINNIFTY")) return "FINNIFTY";

            // Extract first word
            String[] parts = upper.split("\\s+");
            if (parts.length > 0 && !parts[0].matches("^\\d+$")) {
                return parts[0];
            }

            return scripCode;
        }



        /**
         * Parse option metadata from company name.
         * Format examples:
         * - "ICICIBANK 24 FEB 2026 CE 1360.00"
         * - "NIFTY 30 JAN 2026 PE 23500.00"
         * - "BANKNIFTY 29 JAN 2026 CE 50000.00"
         */
        private static OptionMetadata parseOptionMetadata(String companyName) {
            if (companyName == null || companyName.isEmpty()) {
                return null;
            }

            String upper = companyName.toUpperCase().trim();

            // Check if it's an option (contains CE or PE)
            if (!upper.contains(" CE ") && !upper.contains(" PE ") &&
                !upper.endsWith(" CE") && !upper.endsWith(" PE")) {
                return null;
            }

            OptionMetadata metadata = new OptionMetadata();

            // Extract option type (CE or PE)
            if (upper.contains(" CE ") || upper.endsWith(" CE")) {
                metadata.optionType = "CE";
            } else if (upper.contains(" PE ") || upper.endsWith(" PE")) {
                metadata.optionType = "PE";
            }

            // Pattern to match: DD MMM YYYY (CE|PE) STRIKE
            // Example: "24 FEB 2026 CE 1360.00"
            Pattern pattern = Pattern.compile(
                "(\\d{1,2})\\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\\s+(\\d{4})\\s+(CE|PE)\\s+([\\d.]+)"
            );
            Matcher matcher = pattern.matcher(upper);

            if (matcher.find()) {
                try {
                    // Parse expiry date
                    int day = Integer.parseInt(matcher.group(1));
                    String monthStr = matcher.group(2);
                    int year = Integer.parseInt(matcher.group(3));

                    Month month = switch (monthStr) {
                        case "JAN" -> Month.JANUARY;
                        case "FEB" -> Month.FEBRUARY;
                        case "MAR" -> Month.MARCH;
                        case "APR" -> Month.APRIL;
                        case "MAY" -> Month.MAY;
                        case "JUN" -> Month.JUNE;
                        case "JUL" -> Month.JULY;
                        case "AUG" -> Month.AUGUST;
                        case "SEP" -> Month.SEPTEMBER;
                        case "OCT" -> Month.OCTOBER;
                        case "NOV" -> Month.NOVEMBER;
                        case "DEC" -> Month.DECEMBER;
                        default -> null;
                    };

                    if (month != null) {
                        LocalDate expiryDate = LocalDate.of(year, month, day);
                        metadata.expiry = expiryDate.toString(); // ISO format: "2026-02-24"

                        // Calculate days to expiry
                        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
                        metadata.daysToExpiry = (int) ChronoUnit.DAYS.between(today, expiryDate);
                    }

                    // Parse strike price
                    metadata.strikePrice = Double.parseDouble(matcher.group(5));

                } catch (Exception e) {
                    // Parsing failed, metadata will have partial data
                }
            } else {
                // Try alternative pattern: just extract strike from end
                // Pattern: ends with number after CE/PE
                Pattern strikePattern = Pattern.compile("(CE|PE)\\s+([\\d.]+)$");
                Matcher strikeMatcher = strikePattern.matcher(upper);
                if (strikeMatcher.find()) {
                    try {
                        metadata.strikePrice = Double.parseDouble(strikeMatcher.group(2));
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            }

            return metadata;
        }
    }

    /**
     * Helper class to hold parsed option metadata.
     */
    private static class OptionMetadata {
        Double strikePrice;
        String optionType;  // "CE" or "PE"
        String expiry;      // ISO date format "2026-02-24"
        Integer daysToExpiry;
    }
}
