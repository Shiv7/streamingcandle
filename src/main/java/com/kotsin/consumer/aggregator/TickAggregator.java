package com.kotsin.consumer.aggregator;

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
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
 * - WALL-CLOCK: Emits based on wall clock time, not stream time
 *
 * This replaces the tick aggregation part of UnifiedInstrumentCandleProcessor.
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

    @Autowired
    private TickCandleRepository tickCandleRepository;

    @Autowired
    private RedisCacheService redisCacheService;

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
        KafkaConsumer<String, TickData> consumer = createConsumer();
        consumer.subscribe(Collections.singletonList(inputTopic));

        try {
            while (running.get()) {
                ConsumerRecords<String, TickData> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, TickData> record : records) {
                    try {
                        processTick(record.value(), record.timestamp());
                    } catch (Exception e) {
                        log.error("{} Error processing tick: {}", LOG_PREFIX, e.getMessage());
                    }
                }
            }
        } finally {
            consumer.close();
        }
    }

    /**
     * Process a single tick.
     */
    private void processTick(TickData tick, long kafkaTimestamp) {
        if (tick == null || tick.getScripCode() == null) return;

        String key = buildKey(tick);
        Instant tickTime = Instant.ofEpochMilli(kafkaTimestamp);

        // Get or create aggregation state
        TickAggregateState state = aggregationState.computeIfAbsent(key,
            k -> new TickAggregateState(tick, currentWindowStart, currentWindowEnd));

        // Update aggregation
        state.update(tick, tickTime);
    }

    /**
     * Check if current window should be emitted (wall clock based).
     */
    private void checkWindowEmission() {
        Instant now = Instant.now();

        // Check if current window has closed (with 2 second grace)
        if (now.isAfter(currentWindowEnd.plusSeconds(2))) {
            emitCurrentWindow();

            // Move to next window
            currentWindowStart = currentWindowEnd;
            currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);

            log.debug("{} Advanced to window: {} - {}",
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
            } catch (Exception e) {
                log.error("{} Failed to save to MongoDB: {}", LOG_PREFIX, e.getMessage());
            }

            // Save to Redis (hot cache)
            try {
                for (TickCandle candle : candlesToSave) {
                    redisCacheService.cacheTickCandle(candle);
                }
                log.debug("{} Cached {} candles in Redis", LOG_PREFIX, candlesToSave.size());
            } catch (Exception e) {
                log.error("{} Failed to cache in Redis: {}", LOG_PREFIX, e.getMessage());
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
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.kotsin.consumer.model");
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

        // Trade classification
        private long buyVolume;
        private long sellVolume;
        private long midpointVolume;

        // Volume profile
        private final Map<Double, Long> volumeAtPrice = new ConcurrentHashMap<>();

        // Imbalance tracking
        private double volumeImbalance;
        private double dollarImbalance;
        private int tickRuns;
        private int lastTickDirection;  // 1 = up, -1 = down, 0 = neutral

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

            // Trade classification (simplified Lee-Ready)
            // Use bidRate and offerRate (the correct field names in TickData)
            double bid = tick.getBidRate() > 0 ? tick.getBidRate() : price;
            double ask = tick.getOfferRate() > 0 ? tick.getOfferRate() : price;
            double midpoint = (bid + ask) / 2;

            if (price > midpoint + 0.001) {
                buyVolume += qty;  // Aggressive buy
            } else if (price < midpoint - 0.001) {
                sellVolume += qty;  // Aggressive sell
            } else {
                midpointVolume += qty;  // At midpoint
            }

            // Volume profile
            double roundedPrice = Math.round(price * 100) / 100.0;  // Round to 2 decimals
            volumeAtPrice.merge(roundedPrice, qty, Long::sum);

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

        public TickCandle toTickCandle() {
            double vwap = volume > 0 ? valueSum / volume : close;

            // Calculate POC, VAH, VAL from volume profile
            double poc = close;
            double vah = high;
            double val = low;

            if (!volumeAtPrice.isEmpty()) {
                // POC = price with max volume
                poc = volumeAtPrice.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(close);

                // VAH/VAL = 70% value area (simplified)
                // For full implementation, sort by price and find 70% boundaries
                vah = volumeAtPrice.keySet().stream().max(Double::compare).orElse(high);
                val = volumeAtPrice.keySet().stream().min(Double::compare).orElse(low);
            }

            double buyPressure = volume > 0 ? (double) buyVolume / volume : 0.5;
            double sellPressure = volume > 0 ? (double) sellVolume / volume : 0.5;

            return TickCandle.builder()
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
                .vpin(0.0)  // VPIN requires bucket tracking, simplified here
                .vpinBucketSize(0.0)
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
                .createdAt(Instant.now())
                .build();
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
    }
}
