package com.kotsin.consumer.aggregator;

import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.model.OIMetrics;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.repository.OIMetricsRepository;
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

/**
 * OIAggregator - Independent Kafka consumer for Open Interest data.
 *
 * Responsibilities:
 * 1. Consume from OpenInterest topic
 * 2. Aggregate OI snapshots into 1-minute metrics
 * 3. Calculate OI interpretation (LONG_BUILDUP, SHORT_COVERING, etc.)
 * 4. Track OI velocity and acceleration
 * 5. Write to MongoDB (oi_metrics_1m)
 * 6. Write to Redis (hot cache)
 *
 * Key Design:
 * - INDEPENDENT: No joins with tick or orderbook data
 * - SIMPLE: Only OI-derived metrics
 * - WALL-CLOCK: Emits based on wall clock time
 *
 * v2.1 Quant Fixes:
 * - Immediate OI interpretation using cached price from Redis
 * - No longer defers interpretation to query time
 */
@Component
@Slf4j
public class OIAggregator {

    private static final String LOG_PREFIX = "[OI-AGG]";

    @Value("${v2.oi.aggregator.enabled:true}")
    private boolean enabled;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${v2.oi.input.topic:OpenInterest}")
    private String inputTopic;

    @Value("${v2.oi.consumer.group:oi-aggregator-v2}")
    private String consumerGroup;

    @Value("${v2.oi.aggregator.threads:2}")
    private int numThreads;

    @Value("${v2.oi.output.topic:oi-metrics-1m}")
    private String outputTopic;

    @Autowired
    private OIMetricsRepository oiRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

// Aggregation state: key = "exchange:token"
    private final ConcurrentHashMap<String, OIAggregateState> aggregationState = new ConcurrentHashMap<>();

    @Value("${logging.trace.symbols:}")
    private String traceSymbolsStr;
    private Set<String> traceSymbols;
    private final java.util.concurrent.atomic.AtomicLong traceCounter = new java.util.concurrent.atomic.AtomicLong(0);

    private ExecutorService consumerExecutor;
    private ScheduledExecutorService emissionScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

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

        Instant now = Instant.now();
        currentWindowStart = Timeframe.M1.alignToWindowStart(now);
        currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);

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
        KafkaConsumer<String, OpenInterest> consumer = createConsumer();
        consumer.subscribe(Collections.singletonList(inputTopic));

        try {
            while (running.get()) {
                ConsumerRecords<String, OpenInterest> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, OpenInterest> record : records) {
                    try {
                        processOI(record.value(), record.timestamp());
                    } catch (Exception e) {
                        log.error("{} Error processing OI: {}", LOG_PREFIX, e.getMessage());
                    }
                }
            }
        } finally {
            consumer.close();
        }
    }

    private void processOI(OpenInterest oi, long kafkaTimestamp) {
        if (oi == null || oi.getToken() == 0) return;

        String key = oi.getExchange() + ":" + oi.getToken();
        Instant oiTime = Instant.ofEpochMilli(kafkaTimestamp);
        
        // [OI-TRACE] Log everything if list is empty, or specific symbol
        boolean isSpecific = (traceSymbols != null && !traceSymbols.isEmpty() && traceSymbols.contains(String.valueOf(oi.getToken())));
        boolean shouldLog = isSpecific || (traceSymbols == null || traceSymbols.isEmpty());
                           
        if (shouldLog) {
            log.info("[OI-TRACE] Received OI for {}: OI={}, Time={}", 
                oi.getToken(), oi.getOpenInterest(), oiTime);
        }

        OIAggregateState state = aggregationState.computeIfAbsent(key,
            k -> new OIAggregateState(oi, currentWindowStart, currentWindowEnd));

        state.update(oi, oiTime);
    }

    private void checkWindowEmission() {
        Instant now = Instant.now();

        if (now.isAfter(currentWindowEnd.plusSeconds(2))) {
            emitCurrentWindow();

            currentWindowStart = currentWindowEnd;
            currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);

            log.debug("{} Advanced to window: {} - {}",
                LOG_PREFIX, formatTime(currentWindowStart), formatTime(currentWindowEnd));
        }
    }

    private void emitCurrentWindow() {
        if (aggregationState.isEmpty()) return;

        Instant windowStart = currentWindowStart;
        Instant windowEnd = currentWindowEnd;

        log.info("{} Emitting window {} - {} with {} instruments",
            LOG_PREFIX, formatTime(windowStart), formatTime(windowEnd), aggregationState.size());

        List<OIMetrics> metricsToSave = new ArrayList<>();

        for (Map.Entry<String, OIAggregateState> entry : aggregationState.entrySet()) {
            OIAggregateState state = entry.getValue();

            if (state.getWindowStart().equals(windowStart) && state.getUpdateCount() > 0) {
                // v2.1: Calculate interpretation NOW using cached price
                OIMetrics metrics = state.toOIMetrics(redisCacheService);
                metricsToSave.add(metrics);

                state.reset(currentWindowEnd, Timeframe.M1.getWindowEnd(currentWindowEnd));
            }
        }

        if (!metricsToSave.isEmpty()) {
            try {
                oiRepository.saveAll(metricsToSave);
                log.info("{} Saved {} OI metrics to MongoDB", LOG_PREFIX, metricsToSave.size());

                // Trace individual saves if enabled
                if (traceSymbols != null && !traceSymbols.isEmpty()) {
                    for (OIMetrics m : metricsToSave) {
                        if (traceSymbols.contains(m.getSymbol()) || traceSymbols.contains(m.getScripCode())) {
                            log.info("[MONGO-WRITE] OIMetrics saved for {} @ {} | OI: {} | Interp: {}", 
                                m.getSymbol(), formatTime(m.getWindowEnd()), m.getOpenInterest(), m.getInterpretation());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("{} Failed to save to MongoDB: {}", LOG_PREFIX, e.getMessage());
            }

            try {
                for (OIMetrics m : metricsToSave) {
                    redisCacheService.cacheOIMetrics(m);
                }
            } catch (Exception e) {
                log.error("{} Failed to cache in Redis: {}", LOG_PREFIX, e.getMessage());
            }

            // Publish to Kafka topic (keyed by scripCode for proper partitioning)
            try {
                for (OIMetrics m : metricsToSave) {
                    kafkaTemplate.send(outputTopic, m.getScripCode(), m);
                }
                log.info("{} Published {} OI metrics to Kafka topic {}", LOG_PREFIX, metricsToSave.size(), outputTopic);
            } catch (Exception e) {
                log.error("{} Failed to publish to Kafka: {}", LOG_PREFIX, e.getMessage());
            }
        }

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
        aggregationState.entrySet().removeIf(e -> e.getValue().getLastUpdate().isBefore(cutoff));
    }

    private KafkaConsumer<String, OpenInterest> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.kotsin.consumer.model,com.kotsin.optionDataProducer.model");
        // FIX: Specify the target type explicitly since producer doesn't send type headers
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OpenInterest.class.getName());
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
     * Internal state for OI aggregation.
     */
    private static class OIAggregateState {
        private final String symbol;
        private final String scripCode;
        private final String exchange;
        private final String exchangeType;
        private final String companyName;

        private Instant windowStart;
        private Instant windowEnd;
        private Instant lastUpdate;

        // OI tracking
        private long oiOpen;
        private long oiClose;
        private long oiHigh;
        private long oiLow;
        private boolean firstUpdate;

        // For option parsing
        private String underlyingSymbol;
        private Double strikePrice;
        private String optionType;
        private String expiry;

        // Previous window for velocity calculation
        private long previousWindowOI;
        private double previousVelocity;

        // Daily reference
        private long previousDayOI;

        private int updateCount;

        public OIAggregateState(OpenInterest oi, Instant windowStart, Instant windowEnd) {
            this.scripCode = String.valueOf(oi.getToken());
            this.exchange = oi.getExchange();
            this.exchangeType = oi.getExchangeType();
            this.companyName = oi.getCompanyName();
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.lastUpdate = Instant.now();
            this.firstUpdate = true;

            // Parse symbol and option details from companyName
            parseInstrumentDetails(oi.getCompanyName());
            this.symbol = underlyingSymbol != null ? underlyingSymbol : scripCode;

            // Initialize OI values
            long currentOI = oi.getOpenInterest() != null ? oi.getOpenInterest() : 0;
            this.oiOpen = currentOI;
            this.oiClose = currentOI;
            this.oiHigh = currentOI;
            this.oiLow = currentOI;

            // Set previous day OI from Kafka message if available
            // Otherwise set to current OI (will be overridden later)
            this.previousDayOI = currentOI;
            this.previousWindowOI = currentOI;
        }

        public synchronized void update(OpenInterest oi, Instant oiTime) {
            long currentOI = oi.getOpenInterest() != null ? oi.getOpenInterest() : 0;

            if (firstUpdate) {
                oiOpen = currentOI;
                oiHigh = currentOI;
                oiLow = currentOI;
                firstUpdate = false;
            } else {
                oiHigh = Math.max(oiHigh, currentOI);
                oiLow = Math.min(oiLow, currentOI);
            }
            oiClose = currentOI;

            updateCount++;
            lastUpdate = oiTime;
        }

        public OIMetrics toOIMetrics(RedisCacheService redisCacheService) {
            long oiChange = oiClose - oiOpen;
            double oiChangePercent = oiOpen > 0 ? (double) oiChange / oiOpen * 100 : 0;

            long dailyOIChange = oiClose - previousDayOI;
            double dailyOIChangePercent = previousDayOI > 0 ?
                (double) dailyOIChange / previousDayOI * 100 : 0;

            // OI velocity = change per minute
            double oiVelocity = oiChange;  // 1-minute window, so change = velocity

            // OI acceleration = change in velocity
            double oiAcceleration = oiVelocity - previousVelocity;

            // v2.1: Calculate interpretation IMMEDIATELY using cached price (keyed by scripCode)
            OIMetrics.OIInterpretation interpretation = calculateInterpretation(
                oiChange, scripCode, redisCacheService);

            // Calculate interpretation confidence based on OI change magnitude
            double interpretationConfidence = Math.min(1.0, Math.abs(oiChangePercent) / 5.0);
            
            // v2.1: suggestsReversal based on short covering or long unwinding
            boolean suggestsReversal = (interpretation == OIMetrics.OIInterpretation.SHORT_COVERING ||
                                        interpretation == OIMetrics.OIInterpretation.LONG_UNWINDING);

            return OIMetrics.builder()
                .symbol(symbol)
                .scripCode(scripCode)
                .exchange(exchange)
                .exchangeType(exchangeType)
                .underlyingSymbol(underlyingSymbol)
                .strikePrice(strikePrice)
                .optionType(optionType)
                .expiry(expiry)
                .timestamp(windowEnd)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .openInterest(oiClose)
                .oiOpen(oiOpen)
                .oiClose(oiClose)
                .oiChange(oiChange)
                .oiChangePercent(oiChangePercent)
                .previousDayOI(previousDayOI)
                .dailyOIChange(dailyOIChange)
                .dailyOIChangePercent(dailyOIChangePercent)
                .interpretation(interpretation)
                .interpretationConfidence(interpretationConfidence)
                .suggestsReversal(suggestsReversal)
                .oiVelocity(oiVelocity)
                .oiAcceleration(oiAcceleration)
                .updateCount(updateCount)
                .lastUpdateTimestamp(lastUpdate)
                .quality("VALID")
                .staleness(System.currentTimeMillis() - lastUpdate.toEpochMilli())
                .createdAt(Instant.now())
                .build();
        }

        public void reset(Instant newWindowStart, Instant newWindowEnd) {
            // Store previous values for velocity calculation
            this.previousWindowOI = this.oiClose;
            this.previousVelocity = this.oiClose - this.oiOpen;

            this.windowStart = newWindowStart;
            this.windowEnd = newWindowEnd;

            // Open = previous close for continuity
            this.oiOpen = this.oiClose;
            this.oiHigh = this.oiClose;
            this.oiLow = this.oiClose;
            this.firstUpdate = true;
            this.updateCount = 0;
        }

        /**
         * Calculate OI interpretation using cached price from Redis (v2.1).
         * Falls back to NEUTRAL if price not available.
         * Uses scripCode for price lookup (unique instrument identifier).
         */
        private OIMetrics.OIInterpretation calculateInterpretation(
                long oiChange, String scripCode, RedisCacheService redisCacheService) {

            if (redisCacheService == null) {
                return OIMetrics.OIInterpretation.NEUTRAL;
            }

            Double lastPrice = redisCacheService.getLastPrice(scripCode);
            Double prevPrice = redisCacheService.getPreviousPrice(scripCode);
            
            if (lastPrice == null || prevPrice == null || prevPrice <= 0) {
                return OIMetrics.OIInterpretation.NEUTRAL;
            }
            
            double priceChange = lastPrice - prevPrice;
            
            // Standard OI interpretation matrix:
            // OI ↑ + Price ↑ = LONG_BUILDUP (bullish)
            // OI ↓ + Price ↑ = SHORT_COVERING (bullish)
            // OI ↑ + Price ↓ = SHORT_BUILDUP (bearish)
            // OI ↓ + Price ↓ = LONG_UNWINDING (bearish)
            
            if (oiChange > 0 && priceChange > 0) {
                return OIMetrics.OIInterpretation.LONG_BUILDUP;
            } else if (oiChange < 0 && priceChange > 0) {
                return OIMetrics.OIInterpretation.SHORT_COVERING;
            } else if (oiChange > 0 && priceChange < 0) {
                return OIMetrics.OIInterpretation.SHORT_BUILDUP;
            } else if (oiChange < 0 && priceChange < 0) {
                return OIMetrics.OIInterpretation.LONG_UNWINDING;
            }
            
            return OIMetrics.OIInterpretation.NEUTRAL;
        }

        /**
         * Parse instrument details from companyName.
         *
         * Examples:
         * - "NIFTY 23JAN 21000 CE" -> underlying=NIFTY, strike=21000, type=CE
         * - "BANKNIFTY 25JAN 48000 PE" -> underlying=BANKNIFTY, strike=48000, type=PE
         * - "RELIANCE" -> underlying=RELIANCE (equity/futures)
         */
        private void parseInstrumentDetails(String companyName) {
            if (companyName == null || companyName.isEmpty()) {
                this.underlyingSymbol = null;
                return;
            }

            String upper = companyName.toUpperCase().trim();
            String[] parts = upper.split("\\s+");

            if (parts.length == 0) {
                this.underlyingSymbol = null;
                return;
            }

            // First part is always the underlying
            if (upper.startsWith("BANK NIFTY") || upper.startsWith("BANKNIFTY")) {
                this.underlyingSymbol = "BANKNIFTY";
            } else if (upper.startsWith("FIN NIFTY") || upper.startsWith("FINNIFTY")) {
                this.underlyingSymbol = "FINNIFTY";
            } else if (upper.startsWith("NIFTY")) {
                this.underlyingSymbol = "NIFTY";
            } else {
                this.underlyingSymbol = parts[0];
            }

            // Check if option (has CE or PE at end)
            if (parts.length >= 2) {
                String lastPart = parts[parts.length - 1];
                if ("CE".equals(lastPart) || "PE".equals(lastPart)) {
                    this.optionType = lastPart;

                    // Strike is second to last (if numeric)
                    if (parts.length >= 3) {
                        String strikePart = parts[parts.length - 2];
                        try {
                            this.strikePrice = Double.parseDouble(strikePart);
                        } catch (NumberFormatException e) {
                            // Not a valid strike
                        }
                    }

                    // Expiry is typically after underlying, before strike
                    if (parts.length >= 4) {
                        // Find expiry pattern (like 23JAN, 25JAN2025, etc.)
                        for (int i = 1; i < parts.length - 2; i++) {
                            if (parts[i].matches("\\d{1,2}[A-Z]{3}.*")) {
                                this.expiry = parts[i];
                                break;
                            }
                        }
                    }
                }
            }
        }

        public Instant getWindowStart() { return windowStart; }
        public Instant getLastUpdate() { return lastUpdate; }
        public int getUpdateCount() { return updateCount; }
    }
}
