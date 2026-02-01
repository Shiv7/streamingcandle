package com.kotsin.consumer.aggregator;

import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.OrderbookMetrics;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.repository.OrderbookMetricsRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OrderbookAggregator - Independent Kafka consumer for orderbook data.
 *
 * Responsibilities:
 * 1. Consume from Orderbook topic
 * 2. Aggregate orderbook snapshots into 1-minute metrics
 * 3. Calculate OFI, Kyle's Lambda, depth metrics
 * 4. Write to MongoDB (orderbook_metrics_1m)
 * 5. Write to Redis (hot cache)
 *
 * Key Design:
 * - INDEPENDENT: No joins with tick or OI data
 * - SIMPLE: Only orderbook-derived metrics
 * - WALL-CLOCK: Emits based on wall clock time
 *
 * v2.1 Quant Fixes:
 * - Rolling Kyle's Lambda (50-observation window, persists across minutes)
 */
@Component
@Slf4j
public class OrderbookAggregator {

    private static final String LOG_PREFIX = "[OB-AGG]";

    @Value("${v2.orderbook.aggregator.enabled:true}")
    private boolean enabled;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${v2.orderbook.input.topic:Orderbook}")
    private String inputTopic;

    @Value("${v2.orderbook.consumer.group:orderbook-aggregator-v2}")
    private String consumerGroup;

    @Value("${v2.orderbook.aggregator.threads:2}")
    private int numThreads;

    @Value("${v2.orderbook.output.topic:orderbook-metrics-1m}")
    private String outputTopic;

    @Autowired
    private OrderbookMetricsRepository orderbookRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    // Aggregation state
    private final ConcurrentHashMap<String, OrderbookAggregateState> aggregationState = new ConcurrentHashMap<>();

    // Max event time seen (Atomic for thread safety)
    private final AtomicReference<Instant> maxEventTime = new AtomicReference<>(Instant.EPOCH);
    
    // Track wall-clock time of last data arrival (for idle flushing)
    private final AtomicLong lastDataArrivalMillis = new AtomicLong(System.currentTimeMillis());
    
    @Value("${logging.trace.symbols:}")
    private String traceSymbolsStr;
    private Set<String> traceSymbols;
    private final java.util.concurrent.atomic.AtomicLong traceCounter = new java.util.concurrent.atomic.AtomicLong(0);

    private ExecutorService consumerExecutor;
    private ScheduledExecutorService emissionScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Current window end (global tracker for emission progress)
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

        log.info("{} Starting with threads={}, topic={}, MODE=STRICT_EVENT_TIME", LOG_PREFIX, numThreads, inputTopic);

        running.set(true);

        // Initialize current window from epoch - will catch up based on data
        currentWindowStart = Instant.EPOCH;
        currentWindowEnd = Instant.EPOCH;
        maxEventTime.set(Instant.EPOCH);
        lastDataArrivalMillis.set(System.currentTimeMillis());
        
        log.info("{} [STRICT-EVENT-TIME] Window will be initialized from first Orderbook event time", LOG_PREFIX);

        consumerExecutor = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numThreads; i++) {
            consumerExecutor.submit(this::consumeLoop);
        }

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

        if (emissionScheduler != null) emissionScheduler.shutdown();
        if (consumerExecutor != null) {
            consumerExecutor.shutdown();
            try {
                consumerExecutor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        emitCurrentWindow();
        log.info("{} Stopped", LOG_PREFIX);
    }

    private void consumeLoop() {
        KafkaConsumer<String, OrderBookSnapshot> consumer = createConsumer();
        consumer.subscribe(Collections.singletonList(inputTopic));

        try {
            while (running.get()) {
                ConsumerRecords<String, OrderBookSnapshot> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, OrderBookSnapshot> record : records) {
                    try {
                        processOrderbook(record.value(), record.timestamp());
                    } catch (Exception e) {
                        log.error("{} Error processing orderbook: {}", LOG_PREFIX, e.getMessage());
                    }
                }
            }
        } finally {
            consumer.close();
        }
    }

    private void processOrderbook(OrderBookSnapshot ob, long kafkaTimestamp) {
        if (ob == null || ob.getToken() <= 0) return;

        String key = ob.getExchange() + ":" + ob.getToken();
        Instant obTime = Instant.ofEpochMilli(kafkaTimestamp);
        
        // [OB-TRACE] Log everything if list is empty, or specific symbol
        boolean isSpecific = (traceSymbols != null && !traceSymbols.isEmpty() && traceSymbols.contains(String.valueOf(ob.getToken())));
        boolean shouldLog = isSpecific || (traceSymbols == null || traceSymbols.isEmpty());
                           
        if (shouldLog) {
            int bidCount = ob.getBids() != null ? ob.getBids().size() : 0;
            int askCount = ob.getAsks() != null ? ob.getAsks().size() : 0;
            log.info("[OB-TRACE] Received Orderbook for {}: Bids={}, Asks={}, Time={}", 
                ob.getToken(), bidCount, askCount, obTime);
        }

        // Update last data arrival time (Wall Clock)
        lastDataArrivalMillis.set(System.currentTimeMillis());

        // Track max event time ATOMICALLY
        // accumulateAndGet: update only if new time is after current
        Instant currentMax = maxEventTime.accumulateAndGet(obTime, 
            (current, newTime) -> newTime.isAfter(current) ? newTime : current);

        // Initialize current window tracker if needed
        if (currentWindowStart.equals(Instant.EPOCH) ||
            obTime.isBefore(currentWindowStart.minusSeconds(60))) {
            currentWindowStart = Timeframe.M1.alignToWindowStart(obTime);
            currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);
            log.info("{} [STRICT-EVENT-TIME] Initialized window from OB tick: {} - {}",
                LOG_PREFIX, formatTime(currentWindowStart), formatTime(currentWindowEnd));
        }

        // Determine which window this OB tick belongs to (STRICT EVENT TIME)
        final Instant tickWindowStart = Timeframe.M1.alignToWindowStart(obTime);
        final Instant tickWindowEnd = Timeframe.M1.getWindowEnd(tickWindowStart);

        // Composite key: exchange:token:windowStartMillis
        String stateKey = key + ":" + tickWindowStart.toEpochMilli();

        OrderbookAggregateState state = aggregationState.computeIfAbsent(stateKey,
            k -> new OrderbookAggregateState(ob, tickWindowStart, tickWindowEnd));

        state.update(ob, obTime);
    }

    /**
     * Check if current window should be emitted.
     * STRICT EVENT TIME MODE: Reference is always maxEventTime
     */
    private void checkWindowEmission() {
        // STRICT EVENT TIME: Reference is typically maxEventTime
        Instant referenceTime = maxEventTime.get();

        // IDLE FLUSH LOGIC (Critique Fix #3)
        long timeSinceLastData = System.currentTimeMillis() - lastDataArrivalMillis.get();
        if (timeSinceLastData > 60000) { // 1 minute idle
            Instant now = Instant.now();
            if (now.isAfter(referenceTime)) {
                referenceTime = now;
                if (timeSinceLastData > 61000 && timeSinceLastData < 62000) { 
                    log.info("{} [IDLE-FLUSH] System idle for {}ms, forcing time to {}", 
                        LOG_PREFIX, timeSinceLastData, formatTime(referenceTime));
                }
            }
        }

        // Skip if no data yet (and not idle)
        if (referenceTime.equals(Instant.EPOCH)) {
            return;
        }

        // Use WHILE loop to emit ALL closed windows up to referenceTime
        int windowsEmitted = 0;
        while (referenceTime.isAfter(currentWindowEnd.plusSeconds(2))) {
            if (windowsEmitted == 0) {
                log.info("{} [STRICT-EVENT-TIME] Catching up: current window {} -> reference time {}",
                    LOG_PREFIX, formatTime(currentWindowEnd), formatTime(referenceTime));
            }

            emitCurrentWindow();
            windowsEmitted++;

            // Move to next window
            currentWindowStart = currentWindowEnd;
            currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);

            // Safety: limit iterations to prevent infinite loop
            if (windowsEmitted > 500) {
                log.warn("{} [STRICT-EVENT-TIME] Too many windows to emit ({}), breaking. Current: {}, Reference: {}",
                    LOG_PREFIX, windowsEmitted, formatTime(currentWindowEnd), formatTime(referenceTime));
                break;
            }
        }

        if (windowsEmitted > 0) {
            log.debug("{} [STRICT-EVENT-TIME] Emitted {} windows, now at: {} - {}",
                LOG_PREFIX, windowsEmitted, formatTime(currentWindowStart), formatTime(currentWindowEnd));
        }
    }

    private void emitCurrentWindow() {
        if (aggregationState.isEmpty()) return;

        Instant windowEnd = currentWindowEnd;
        List<OrderbookMetrics> metricsToSave = new ArrayList<>();
        List<String> keysToRemove = new ArrayList<>();

        for (Map.Entry<String, OrderbookAggregateState> entry : aggregationState.entrySet()) {
            String stateKey = entry.getKey();
            OrderbookAggregateState state = entry.getValue();

            // STRICT EVENT TIME CHECK:
            // Emit if state's window has fully passed relative to our current tracking window
            boolean shouldEmit = state.getUpdateCount() > 0 &&
                                 state.getWindowEnd() != null &&
                                 !state.getWindowEnd().isAfter(windowEnd);

            if (shouldEmit) {
                OrderbookMetrics metrics = state.toOrderbookMetrics();
                metricsToSave.add(metrics);
                keysToRemove.add(stateKey);
            }
        }

        if (!metricsToSave.isEmpty()) {
            log.info("{} Emitting {} OB metrics for window end {}", 
                LOG_PREFIX, metricsToSave.size(), formatTime(windowEnd));

            try {
                orderbookRepository.saveAll(metricsToSave);
                
                // Trace individual saves if enabled
                if (traceSymbols != null && !traceSymbols.isEmpty()) {
                    for (OrderbookMetrics m : metricsToSave) {
                        if (traceSymbols.contains(m.getSymbol()) || traceSymbols.contains(m.getScripCode())) {
                            log.info("[MONGO-WRITE] OrderbookMetrics saved for {} @ {} | OFI: {} | Spread: {}", 
                                m.getSymbol(), formatTime(m.getWindowEnd()), m.getOfi(), m.getBidAskSpread());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("{} Failed to save to MongoDB: {}", LOG_PREFIX, e.getMessage());
            }

            try {
                for (OrderbookMetrics m : metricsToSave) {
                    redisCacheService.cacheOrderbookMetrics(m);
                }
            } catch (Exception e) {
                log.error("{} Failed to cache in Redis: {}", LOG_PREFIX, e.getMessage());
            }

            // Publish to Kafka topic
            try {
                for (OrderbookMetrics m : metricsToSave) {
                    kafkaTemplate.send(outputTopic, m.getScripCode(), m);
                }
                log.info("{} Published {} OB metrics", LOG_PREFIX, metricsToSave.size());
            } catch (Exception e) {
                log.error("{} Failed to publish to Kafka: {}", LOG_PREFIX, e.getMessage());
            }
        }

        // Remove emitted states
        for (String key : keysToRemove) {
            aggregationState.remove(key);
        }

        // Cleanup stale states
        Instant cutoff = currentWindowEnd.minus(Duration.ofMinutes(5));
        aggregationState.entrySet().removeIf(e -> e.getValue().getLastUpdate().isBefore(cutoff));
    }

    private KafkaConsumer<String, OrderBookSnapshot> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.kotsin.consumer.model,com.kotsin.optionDataProducer.model");
        // FIX: Specify the target type explicitly since producer doesn't send type headers
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderBookSnapshot.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2000);

        return new KafkaConsumer<>(props);
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
     * Internal state for orderbook aggregation.
     */
    private static class OrderbookAggregateState {
        private final String symbol;
        private final String scripCode;
        private final String exchange;
        private final String exchangeType;

        private Instant windowStart;
        private Instant windowEnd;
        private Instant lastUpdate;

        // OFI calculation
        private double ofiSum;
        private double previousBidQty;
        private double previousAskQty;
        private double previousBid;
        private double previousAsk;

        // Kyle's Lambda - rolling window (v2.1: persists across resets)
        private static final int KYLE_LAMBDA_WINDOW = 50;  // Rolling window size
        private final List<Double> priceChanges = new ArrayList<>();
        private final List<Double> signedVolumes = new ArrayList<>();
        private double lastMidPrice;

        // Spread metrics
        private double spreadSum;
        private double spreadSqSum;
        private int tightSpreadCount;

        // Depth metrics
        private double bidDepthSum;
        private double askDepthSum;
        private double depthImbalanceSum;

        // Anomaly detection
        private int spoofingCount;
        private boolean icebergBidDetected;
        private boolean icebergAskDetected;
        private int cancelCount;
        private int totalOrders;

        private int updateCount;

        public OrderbookAggregateState(OrderBookSnapshot ob, Instant windowStart, Instant windowEnd) {
            this.symbol = ob.getCompanyName() != null ? extractSymbol(ob.getCompanyName()) : String.valueOf(ob.getToken());
            this.scripCode = String.valueOf(ob.getToken());
            this.exchange = ob.getExchange();
            this.exchangeType = ob.getExchangeType();
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.lastUpdate = Instant.now();

            // Initialize previous values using correct method names
            this.previousBid = ob.getBestBid();
            this.previousAsk = ob.getBestAsk();
            this.previousBidQty = getBidQty(ob);
            this.previousAskQty = getAskQty(ob);
            this.lastMidPrice = (previousBid + previousAsk) / 2;
        }

        public synchronized void update(OrderBookSnapshot ob, Instant obTime) {
            double bid = ob.getBestBid();
            double ask = ob.getBestAsk();
            double bidQty = getBidQty(ob);
            double askQty = getAskQty(ob);

            // Calculate OFI
            double bidDelta = 0;
            double askDelta = 0;

            if (bid >= previousBid) {
                bidDelta = bidQty - (bid == previousBid ? previousBidQty : 0);
            }
            if (ask <= previousAsk) {
                askDelta = askQty - (ask == previousAsk ? previousAskQty : 0);
            }

            ofiSum += bidDelta - askDelta;

            // Kyle's Lambda - track price change vs signed volume
            // v2.1: Keep ROLLING window of observations
            double midPrice = (bid + ask) / 2;
            if (lastMidPrice > 0 && midPrice > 0) {
                double priceChange = midPrice - lastMidPrice;
                double signedVol = bidDelta - askDelta;
                if (Math.abs(signedVol) > 0) {
                    priceChanges.add(priceChange);
                    signedVolumes.add(signedVol);
                    
                    // Keep rolling window capped (v2.1)
                    while (priceChanges.size() > KYLE_LAMBDA_WINDOW) {
                        priceChanges.remove(0);
                        signedVolumes.remove(0);
                    }
                }
            }
            lastMidPrice = midPrice;

            // Spread metrics
            double spread = ask - bid;
            if (spread > 0) {
                spreadSum += spread;
                spreadSqSum += spread * spread;
                if (spread <= 0.05) {  // Tight spread threshold
                    tightSpreadCount++;
                }
            }

            // Depth metrics
            Long totalBidDepthLong = ob.getTotalBidQty();
            Long totalAskDepthLong = ob.getTotalOffQty();
            double totalBidDepth = totalBidDepthLong != null ? totalBidDepthLong : bidQty;
            double totalAskDepth = totalAskDepthLong != null ? totalAskDepthLong : askQty;
            bidDepthSum += totalBidDepth;
            askDepthSum += totalAskDepth;

            double totalDepth = totalBidDepth + totalAskDepth;
            if (totalDepth > 0) {
                depthImbalanceSum += (totalBidDepth - totalAskDepth) / totalDepth;
            }

            // Update previous values
            previousBid = bid;
            previousAsk = ask;
            previousBidQty = bidQty;
            previousAskQty = askQty;

            updateCount++;
            lastUpdate = obTime;
        }

        private static double getBidQty(OrderBookSnapshot ob) {
            if (ob.getBids() != null && !ob.getBids().isEmpty()) {
                return ob.getBids().get(0).getQuantity();
            }
            return 0;
        }

        private static double getAskQty(OrderBookSnapshot ob) {
            if (ob.getAsks() != null && !ob.getAsks().isEmpty()) {
                return ob.getAsks().get(0).getQuantity();
            }
            return 0;
        }

        public OrderbookMetrics toOrderbookMetrics() {
            double avgSpread = updateCount > 0 ? spreadSum / updateCount : 0;
            double spreadVariance = updateCount > 1 ?
                (spreadSqSum - spreadSum * spreadSum / updateCount) / (updateCount - 1) : 0;
            double spreadVolatility = Math.sqrt(Math.max(0, spreadVariance));

            double avgBidDepth = updateCount > 0 ? bidDepthSum / updateCount : 0;
            double avgAskDepth = updateCount > 0 ? askDepthSum / updateCount : 0;
            double avgDepthImbalance = updateCount > 0 ? depthImbalanceSum / updateCount : 0;

            double kyleLambda = calculateKyleLambda();

            double microprice = 0;
            if (avgBidDepth + avgAskDepth > 0) {
                microprice = (previousBid * avgAskDepth + previousAsk * avgBidDepth) /
                             (avgBidDepth + avgAskDepth);
            }

            return OrderbookMetrics.builder()
                .id(scripCode + "_" + windowEnd.toEpochMilli()) // IDEMPOTENCY FIX
                .symbol(symbol)
                .scripCode(scripCode)
                .exchange(exchange)
                .exchangeType(exchangeType)
                .timestamp(windowEnd)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .ofi(ofiSum)
                .ofiMomentum(0.0)  // Calculated by comparing with previous window
                .kyleLambda(kyleLambda)
                .microprice(microprice)
                .bidAskSpread(avgSpread)
                .spreadPercent(lastMidPrice > 0 ? avgSpread / lastMidPrice * 100 : 0)
                .spreadVolatility(spreadVolatility)
                .tightSpreadPercent(updateCount > 0 ? (double) tightSpreadCount / updateCount : 0)
                .depthImbalance(avgDepthImbalance)
                .weightedDepthImbalance(avgDepthImbalance)  // Simplified
                .avgBidDepth(avgBidDepth)
                .avgAskDepth(avgAskDepth)
                .bidDepthSlope(0.0)  // Requires multi-level data
                .askDepthSlope(0.0)
                .depthConcentration(0.0)
                .spoofingCount(spoofingCount)
                .icebergBidDetected(icebergBidDetected)
                .icebergAskDetected(icebergAskDetected)
                .cancelRate(totalOrders > 0 ? (double) cancelCount / totalOrders : 0)
                .updateCount(updateCount)
                .lastUpdateTimestamp(lastUpdate)
                .quality("VALID")
                .staleness(System.currentTimeMillis() - lastUpdate.toEpochMilli())
                .createdAt(Instant.now())
                .build();
        }

        private double calculateKyleLambda() {
            if (priceChanges.size() < 5) return 0;

            // Simple linear regression: priceChange = lambda * signedVolume
            double sumXY = 0, sumX2 = 0;
            for (int i = 0; i < priceChanges.size(); i++) {
                double x = signedVolumes.get(i);
                double y = priceChanges.get(i);
                sumXY += x * y;
                sumX2 += x * x;
            }

            return sumX2 > 0 ? sumXY / sumX2 : 0;
        }

        public void reset(Instant newWindowStart, Instant newWindowEnd) {
            this.windowStart = newWindowStart;
            this.windowEnd = newWindowEnd;
            this.ofiSum = 0;
            // v2.1: DON'T clear priceChanges/signedVolumes - keep rolling window
            // this.priceChanges.clear();
            // this.signedVolumes.clear();
            this.spreadSum = 0;
            this.spreadSqSum = 0;
            this.tightSpreadCount = 0;
            this.bidDepthSum = 0;
            this.askDepthSum = 0;
            this.depthImbalanceSum = 0;
            this.spoofingCount = 0;
            this.icebergBidDetected = false;
            this.icebergAskDetected = false;
            this.cancelCount = 0;
            this.totalOrders = 0;
            this.updateCount = 0;
        }

        public Instant getWindowStart() { return windowStart; }
        public Instant getWindowEnd() { return windowEnd; }
        public Instant getLastUpdate() { return lastUpdate; }
        public int getUpdateCount() { return updateCount; }

        private static String extractSymbol(String companyName) {
            if (companyName == null || companyName.isEmpty()) return null;
            String[] parts = companyName.toUpperCase().trim().split("\\s+");
            return parts.length > 0 ? parts[0] : null;
        }
    }
}
