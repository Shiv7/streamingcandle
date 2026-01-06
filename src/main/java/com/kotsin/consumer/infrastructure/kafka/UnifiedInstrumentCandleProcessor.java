package com.kotsin.consumer.infrastructure.kafka;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.domain.calculator.AdaptiveVPINCalculator;
import com.kotsin.consumer.domain.model.*;
import com.kotsin.consumer.domain.validator.TradeOrderbookValidator;
import com.kotsin.consumer.model.OIAggregate;
import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.OrderbookAggregate;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.infrastructure.kafka.aggregate.TickAggregate;
import com.kotsin.consumer.service.InstrumentMetadataService;
import com.kotsin.consumer.util.MarketTimeAligner;
import com.kotsin.consumer.util.BlackScholesGreeks;
import com.kotsin.consumer.repository.ScripRepository;
import com.kotsin.consumer.entity.Scrip;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ValueAndTimestamp;
import org.apache.kafka.streams.state.WindowStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * UnifiedInstrumentCandleProcessor - Joins tick, orderbook, and OI data.
 * 
 * Data Flow:
 * 1. Consume from forwardtesting-data, Orderbook, OpenInterest (3 input topics)
 * 2. Join by scripCode within 1-minute window
 * 3. Build InstrumentCandle with whatever data is available
 * 4. Apply LEFT JOIN: tick data is mandatory, orderbook/OI are optional
 * 5. Output to instrument-candle-1m
 * 
 * Key Design:
 * - NEVER discard candles - emit with whatever data is available
 * - hasOrderbook and hasOI flags indicate data availability
 * - Adaptive VPIN based on instrument's average daily volume
 */
@Component
@Slf4j
public class UnifiedInstrumentCandleProcessor {

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private InstrumentMetadataService instrumentMetadataService;

    @Autowired
    private ScripRepository scripRepository;

    @Autowired(required = false)
    private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
    
    // PHASE 2: Gap Analysis Services
    @Autowired
    private com.kotsin.consumer.service.PreviousCloseStore previousCloseStore;
    
    @Autowired
    private com.kotsin.consumer.service.GapFieldPopulator gapFieldPopulator;

    @Value("${unified.input.topic.ticks:forwardtesting-data}")
    private String tickTopic;

    @Value("${unified.input.topic.orderbook:Orderbook}")
    private String orderbookTopic;

    @Value("${unified.input.topic.oi:OpenInterest}")
    private String oiTopic;

    @Value("${unified.output.topic.instrument:instrument-candle-1m}")
    private String outputTopic;

    @Value("${unified.window.grace.seconds:5}")
    private int graceSeconds;

    @Value("${unified.processor.enabled:true}")
    private boolean processorEnabled;

    // Consumer fetch optimization for high-throughput orderbook consumption
    @Value("${unified.processor.max.poll.records:2000}")
    private int maxPollRecords;  // Max records per poll (higher = faster consumption)

    @Value("${unified.processor.fetch.min.bytes:2097152}")
    private int fetchMinBytes;  // 2MB - wait for more data before returning

    @Value("${unified.processor.fetch.max.wait.ms:50}")
    private int fetchMaxWaitMs;  // Max wait time for fetch.min.bytes

    @Value("${unified.flashcrash.tick.acceleration.threshold:100}")
    private int flashCrashThreshold;  // Tick acceleration threshold for flash crash detection

    @Value("${unified.volumeprofile.value.area.percent:0.70}")
    private double valueAreaPercent;  // Value area percentage (70% of total volume)

    private KafkaStreams streams;

    /**
     * 🛡️ CRITICAL FIX: VPIN Memory Leak Prevention
     *
     * BEFORE (BROKEN):
     * - ConcurrentHashMap never cleaned up
     * - Every new instrument adds a VPINCalculator
     * - With 5000+ instruments daily, causes OOM in 48 hours
     *
     * AFTER (FIXED):
     * - Caffeine cache with automatic eviction
     * - Max 10,000 instruments (sufficient for all NSE/BSE instruments)
     * - Evict after 2 hours of inactivity
     * - Prevents memory leak while maintaining performance
     * 
     * NEW: VPIN reset at market open
     * - Tracks last trading day per instrument
     * - Resets VPIN calculator when crossing into new trading day
     */
    private final Cache<String, AdaptiveVPINCalculator> vpinCalculators = Caffeine.newBuilder()
            .maximumSize(10_000)  // Max instruments to cache
            .expireAfterAccess(2, TimeUnit.HOURS)  // Evict if not accessed for 2 hours
            .recordStats()  // Enable cache statistics
            .build();
    
    // Track last trading day per instrument for VPIN reset detection
    private final Cache<String, LocalDate> vpinLastTradingDay = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .build();

    /**
     * Cache to track previous window's OI close per instrument key
     * Used to calculate OI change from previous window to current window
     */
    private final Cache<String, Long> previousOICloseCache = Caffeine.newBuilder()
            .maximumSize(10_000)  // Max instruments to cache
            .expireAfterWrite(1, TimeUnit.DAYS)  // Clear after 1 day
            .build();

    /**
     * Build and start the unified processor
     */
    @PostConstruct
    public void start() {
        if (!processorEnabled) {
            log.info("UnifiedInstrumentCandleProcessor is DISABLED");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                log.info("🚀 Starting UnifiedInstrumentCandleProcessor...");
                
                Properties props = kafkaConfig.getStreamProperties("unified-instrument-candle-processor");
                
                // Optimize consumer settings for high-throughput orderbook consumption
                // These settings help process orderbook data faster to avoid missing JOINs
                props.put(StreamsConfig.CONSUMER_PREFIX + "max.poll.records", maxPollRecords);
                props.put(StreamsConfig.CONSUMER_PREFIX + "fetch.min.bytes", fetchMinBytes);
                props.put(StreamsConfig.CONSUMER_PREFIX + "fetch.max.wait.ms", fetchMaxWaitMs);
                props.put(StreamsConfig.CONSUMER_PREFIX + "fetch.max.bytes", 52428800);  // 50MB max fetch
                props.put(StreamsConfig.CONSUMER_PREFIX + "max.partition.fetch.bytes", 10485760);  // 10MB per partition
                
                log.info("Consumer optimization applied: maxPollRecords={}, fetchMinBytes={} bytes, fetchMaxWaitMs={} ms", 
                    maxPollRecords, fetchMinBytes, fetchMaxWaitMs);
                
                // No need to create internal topics - using simple KTable approach
                
                StreamsBuilder builder = new StreamsBuilder();
                
                buildTopology(builder);
                
                streams = new KafkaStreams(builder.build(), props);
                setupExceptionHandling();
                
                streams.start();
                log.info("✅ UnifiedInstrumentCandleProcessor started successfully");
                
            } catch (Exception e) {
                log.error("❌ Failed to start UnifiedInstrumentCandleProcessor", e);
            }
        });
    }

    /**
     * Build the stream topology
     */
    private void buildTopology(StreamsBuilder builder) {
        // PHASE 2: Gap Analysis - Add RocksDB state store for previous day close prices
        previousCloseStore.addToTopology(builder);
        log.info("💾 Gap Analysis: PreviousCloseStore added to topology");
        
        // 🛡️ CRITICAL FIX: Event-Time Processing for Replay & Live Consistency
        //
        // BEFORE (BROKEN):
        // - All streams used Kafka record timestamp (ingestion time = wall clock)
        // - Replay of Dec 24 data couldn't aggregate properly because wall clock is Jan 1
        // - LEFT JOIN windows never aligned correctly during replay
        //
        // AFTER (FIXED):
        // - TickData uses tick.timestamp (actual trade time)
        // - OrderBookSnapshot uses snapshot.getTimestamp() (actual snapshot time)
        // - OpenInterest uses oi.receivedTimestamp (actual OI update time)
        // - All streams align in time → JOIN works correctly for both replay and live
        //
        // ========== 1. CONSUME TICK DATA WITH EVENT-TIME ==========
        KStream<String, TickData> ticks = builder.stream(
            tickTopic,
            Consumed.with(Serdes.String(), TickData.serde())
                .withTimestampExtractor(new com.kotsin.consumer.timeExtractor.TickDataTimestampExtractor())
        )
        .filter((key, tick) -> {
            boolean valid = tick != null && tick.getToken() > 0;
            if (!valid && traceLogger != null && tick != null) {
                java.util.Map<String, Object> indicators = new java.util.HashMap<>();
                indicators.put("token", tick.getToken());
                indicators.put("price", tick.getLastRate());
                indicators.put("volume", tick.getLastQuantity());
                traceLogger.logSignalRejected("TICK", tick.getScripCode(),
                    tick.getTimestamp(), "Invalid token", indicators);
            }
            return valid;
        })
        // REMOVED: Trading hours filter that rejected ticks with timestamp=0
        // TickDataTimestampExtractor already handles invalid timestamps by using Kafka record timestamp
        // This allows MCX and NSE data with timestamp=0 to be processed correctly
        .peek((key, tick) -> {
            if (traceLogger != null) {
                traceLogger.logInputReceived("TICK", tick.getScripCode(), tick.getCompanyName(),
                    tick.getTimestamp(),
                    String.format("price=%.2f vol=%d ltp=%.2f",
                        tick.getLastRate(), tick.getLastQuantity(), tick.getLastRate()));
            }
        })
        .selectKey((key, tick) -> buildInstrumentKey(tick));

        // ========== 2. CONSUME ORDERBOOK DATA WITH EVENT-TIME ==========
        KStream<String, OrderBookSnapshot> orderbooks = builder.stream(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
                .withTimestampExtractor(new com.kotsin.consumer.timeExtractor.OrderBookSnapshotTimestampExtractor())
        )
        .filter((key, ob) -> {
            boolean valid = ob != null && ob.isValid();
            if (!valid && traceLogger != null && ob != null) {
                java.util.Map<String, Object> indicators = new java.util.HashMap<>();
                indicators.put("token", ob.getToken());
                indicators.put("bestBid", ob.getBestBid());
                indicators.put("bestAsk", ob.getBestAsk());
                traceLogger.logSignalRejected("OB", String.valueOf(ob.getToken()),
                    ob.getTimestamp(), "Invalid orderbook data", indicators);
            }
            return valid;
        })
        .peek((key, ob) -> {
            if (traceLogger != null) {
                traceLogger.logInputReceived("OB", String.valueOf(ob.getToken()), ob.getCompanyName(),
                    ob.getTimestamp(),
                    String.format("bid=%.2f ask=%.2f spread=%.2f imbalance=%.2f",
                        ob.getBestBid(), ob.getBestAsk(),
                        ob.getBestAsk() - ob.getBestBid(),
                        (ob.getTotalBidQty() != null && ob.getTotalOffQty() != null ?
                        (ob.getTotalBidQty() - ob.getTotalOffQty()) /
                        (double)(ob.getTotalBidQty() + ob.getTotalOffQty() + 1) : 0.0)));
            }
        })
        .selectKey((key, ob) -> buildOrderbookKey(ob));

        // ========== 3. CONSUME OI DATA WITH EVENT-TIME ==========
        KStream<String, OpenInterest> ois = builder.stream(
            oiTopic,
            Consumed.with(Serdes.String(), OpenInterest.serde())
                .withTimestampExtractor(new com.kotsin.consumer.timeExtractor.OpenInterestTimestampExtractor())
        )
        .filter((key, oi) -> {
            boolean valid = oi != null && oi.getOpenInterest() != null;
            if (!valid && traceLogger != null && oi != null) {
                java.util.Map<String, Object> indicators = new java.util.HashMap<>();
                indicators.put("token", oi.getToken());
                indicators.put("OI", oi.getOpenInterest());
                traceLogger.logSignalRejected("OI", String.valueOf(oi.getToken()),
                    oi.getReceivedTimestamp(), "Null OI value", indicators);
            }
            return valid;
        })
        .peek((key, oi) -> {
            if (traceLogger != null) {
                traceLogger.logInputReceived("OI", String.valueOf(oi.getToken()), oi.getCompanyName(),
                    oi.getReceivedTimestamp(),
                    String.format("OI=%d change=%d changePct=%.2f",
                        oi.getOpenInterest(),
                        oi.getOiChange() != null ? oi.getOiChange() : 0,
                        oi.getOiChangePercent() != null ? oi.getOiChangePercent() : 0.0));
            }
        })
        .selectKey((key, oi) -> {
            String newKey = buildOIKey(oi);
            if (log.isDebugEnabled() && oi != null && (oi.getToken() == 49229 || oi.getToken() == 49614 || oi.getToken() == 49992)) {
                log.debug("[OI-KEY] token={} oldKey={} newKey={} exch={} exchType={}", 
                    oi.getToken(), key, newKey, oi.getExchange(), oi.getExchangeType());
            }
            return newKey;
        });

        // ========== 4. AGGREGATE TICKS INTO CANDLES ==========
        // FIX: Repartition all streams to ensure co-partitioning
        // Source topics have different partition counts (tick=15, OI=6, orderbook=20)
        // Repartitioning to 20 ensures same key -> same partition across all streams
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(1),
            Duration.ofSeconds(graceSeconds)
        );

        // PHASE 1-4: Inject Kafka timestamp into each tick for enhanced processing
        // This allows TickAggregate to access record timestamp for temporal tracking
        KStream<String, TickWithTimestamp> ticksWithTimestamp = ticks
            .transformValues(() -> new org.apache.kafka.streams.kstream.ValueTransformerWithKey<String, TickData, TickWithTimestamp>() {
                private org.apache.kafka.streams.processor.ProcessorContext context;

                @Override
                public void init(org.apache.kafka.streams.processor.ProcessorContext context) {
                    this.context = context;
                }

                @Override
                public TickWithTimestamp transform(String key, TickData tick) {
                    if (tick == null) return null;
                    return new TickWithTimestamp(tick, context.timestamp());
                }

                @Override
                public void close() {}
            });

        // Repartition ticks to 20 partitions for co-partitioning
        KStream<String, TickWithTimestamp> ticksRepartitioned = ticksWithTimestamp
            .repartition(Repartitioned.<String, TickWithTimestamp>as("tick-repartitioned")
                .withKeySerde(Serdes.String())
                .withValueSerde(TickWithTimestamp.serde())
                .withNumberOfPartitions(20));

        KTable<Windowed<String>, TickAggregate> tickCandles = ticksRepartitioned
            .groupByKey(Grouped.with(Serdes.String(), TickWithTimestamp.serde()))
            .windowedBy(windows)
            .aggregate(
                TickAggregate::new,
                (key, tickWithTs, agg) -> {
                    if (log.isDebugEnabled() && tickWithTs != null && tickWithTs.tick != null && agg.getTickCount() == 0) {
                        // Log first tick in window to see the key
                        log.debug("[TICK-AGG] key={} token={} scripCode={} exch={} exchType={} kafkaTs={}",
                            key, tickWithTs.tick.getToken(), tickWithTs.tick.getScripCode(),
                            tickWithTs.tick.getExchange(), tickWithTs.tick.getExchangeType(),
                            tickWithTs.kafkaTimestamp);
                    }
                    return tickWithTs != null ? agg.update(tickWithTs.tick, tickWithTs.kafkaTimestamp) : agg;
                },
                Materialized.<String, TickAggregate, WindowStore<Bytes, byte[]>>as("tick-aggregate-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(TickAggregate.serde())
                    .withRetention(Duration.ofDays(14))  // 2 weeks retention for replay scenarios
            )
            // FIX: Suppress intermediate emissions - emit only when window closes
            // BEFORE: Emitted on EVERY tick update during grace period (duplicate candles)
            // AFTER: Emit exactly ONCE per window at close (correct behavior)
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        // ========== 5. AGGREGATE ORDERBOOK INTO OFI/LAMBDA ==========
        // Repartition orderbooks to 20 partitions for co-partitioning
        KStream<String, OrderBookSnapshot> orderbooksRepartitioned = orderbooks
            .repartition(Repartitioned.<String, OrderBookSnapshot>as("orderbook-repartitioned")
                .withKeySerde(Serdes.String())
                .withValueSerde(OrderBookSnapshot.serde())
                .withNumberOfPartitions(20));

        KTable<Windowed<String>, OrderbookAggregate> obAggregates = orderbooksRepartitioned
            .groupByKey(Grouped.with(Serdes.String(), OrderBookSnapshot.serde()))
            .windowedBy(windows)
            .aggregate(
                OrderbookAggregate::new,
                (key, ob, agg) -> {
                    agg.updateWithSnapshot(ob);
                    return agg;
                },
                Materialized.<String, OrderbookAggregate, WindowStore<Bytes, byte[]>>as("orderbook-aggregate-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(OrderbookAggregate.serde())
                    .withRetention(Duration.ofDays(14))  // 2 weeks retention for replay scenarios
            )
            // FIX: Suppress intermediate emissions - emit only when window closes
            // Matches tickCandles suppress for consistent join behavior
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        // ========== 5.5. CREATE NON-WINDOWED ORDERBOOK LATEST STORE (FALLBACK) ==========
        // This store keeps the latest orderbook aggregate for each instrument
        // Used as fallback when windowed join fails (e.g., during replay when windows expire)
        // Similar pattern to OI latest store below
        KTable<String, OrderbookAggregate> obLatestTable = orderbooksRepartitioned
            .groupByKey(Grouped.with(Serdes.String(), OrderBookSnapshot.serde()))
            .aggregate(
                OrderbookAggregate::new,
                (key, ob, agg) -> {
                    if (log.isDebugEnabled() && ob != null) {
                        log.debug("[OB-LATEST-AGG] key={} token={} exch={} exchType={} updateCount={}",
                            key, ob.getToken(), ob.getExchange(), ob.getExchangeType(),
                            agg.getOrderbookUpdateCount());
                    }
                    agg.updateWithSnapshot(ob);
                    return agg;
                },
                Materialized.<String, OrderbookAggregate, KeyValueStore<Bytes, byte[]>>as("orderbook-latest-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(OrderbookAggregate.serde())
            );

        // ========== 6. AGGREGATE OI (NON-WINDOWED KTABLE) ==========
        // Repartition OI to 20 partitions for co-partitioning with tick stream
        // This ensures OI data lands on the same partition as its corresponding tick data
        KStream<String, OpenInterest> oisRepartitioned = ois
            .repartition(Repartitioned.<String, OpenInterest>as("oi-repartitioned")
                .withKeySerde(Serdes.String())
                .withValueSerde(OpenInterest.serde())
                .withNumberOfPartitions(20));

        KTable<String, OIAggregate> oiLatestTable = oisRepartitioned
            .groupByKey(Grouped.with(Serdes.String(), OpenInterest.serde()))
            .aggregate(
                OIAggregate::new,
                (key, oi, agg) -> {
                    if (log.isDebugEnabled() && oi != null) {
                        log.debug("[OI-AGG] key={} token={} exch={} exchType={} OI={} updateCount={}", 
                            key, oi.getToken(), oi.getExchange(), oi.getExchangeType(), 
                            oi.getOpenInterest(), agg.getUpdateCount());
                    }
                    agg.updateWithOI(oi);
                    return agg;
                },
                Materialized.<String, OIAggregate, KeyValueStore<Bytes, byte[]>>as("oi-latest-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(OIAggregate.serde())
            );

        // ========== 7. LEFT JOIN: TICK (mandatory) + ORDERBOOK (optional) ==========
        KTable<Windowed<String>, TickWithOrderbook> tickCandlesWithOb = tickCandles.leftJoin(
            obAggregates,
            (tick, ob) -> {
                if (log.isDebugEnabled() && tick != null) {
                    String scripCode = tick.getScripCode();
                    log.debug("[JOIN] {} | TICK+OB | hasOB={}", scripCode, ob != null);
                }
                return new TickWithOrderbook(tick, ob);
            }
        );

        // ========== 8. LEFT JOIN: TICK+OB + OI (optional) ==========
        // FIX: Use transformValues with state store lookup for OI
        // This allows OI to be available regardless of window timing
        // The state store is properly accessible from the transformer context
        // Note: OI may arrive after window closes - this is expected behavior with sparse OI updates
        KStream<Windowed<String>, InstrumentCandleData> fullDataStream = tickCandlesWithOb
            .toStream()
            .transformValues(
                () -> new org.apache.kafka.streams.kstream.ValueTransformerWithKey<Windowed<String>, TickWithOrderbook, InstrumentCandleData>() {
                    private org.apache.kafka.streams.processor.ProcessorContext context;
                    private org.apache.kafka.streams.state.ReadOnlyKeyValueStore<String, ?> oiStore;
                    private org.apache.kafka.streams.state.ReadOnlyKeyValueStore<String, ?> obLatestStore;

                    @Override
                    public void init(org.apache.kafka.streams.processor.ProcessorContext context) {
                        this.context = context;
                        // Get state stores - handle both ValueAndTimestamp<V> and direct V
                        this.oiStore = (org.apache.kafka.streams.state.ReadOnlyKeyValueStore<String, ?>) context.getStateStore("oi-latest-store");
                        this.obLatestStore = (org.apache.kafka.streams.state.ReadOnlyKeyValueStore<String, ?>) context.getStateStore("orderbook-latest-store");
                    }
                    
                    @Override
                    public InstrumentCandleData transform(Windowed<String> windowedKey, TickWithOrderbook tickOb) {
                        OIAggregate oi = null;
                        
                        if (tickOb != null && tickOb.tick != null && oiStore != null) {
                            // Build OI key from tick data
                            // FIX: Default exchType to "D" (derivative) to match buildOIKey() storage
                            // BEFORE: "C" (cash/equity) - caused key mismatch for derivatives with null exchType
                            // AFTER: "D" (derivative) - matches how OI is stored
                            String tickExch = tickOb.tick.getExchange() != null ? tickOb.tick.getExchange() : "N";
                            String tickExchType = tickOb.tick.getExchangeType() != null ? tickOb.tick.getExchangeType() : "D";
                            String scripCode = tickOb.tick.getScripCode();
                            String oiKey = tickExch + ":" + tickExchType + ":" + scripCode;
                            
                            // Lookup OI from state store
                            // KTable stores may return ValueAndTimestamp<V> or V directly
                            try {
                                Object storeValue = oiStore.get(oiKey);
                                if (storeValue == null) {
                                    // Store value is null - OI hasn't arrived yet or key mismatch
                                    // This is expected when OI arrives after the tick window closes
                                    if (log.isDebugEnabled()) {
                                        log.debug("[OI-LOOKUP-NULL] {} | No OI found in store. OI may arrive later.", oiKey);
                                    }
                                } else {
                                    if (storeValue instanceof ValueAndTimestamp) {
                                        // Unwrap ValueAndTimestamp
                                        @SuppressWarnings("unchecked")
                                        ValueAndTimestamp<OIAggregate> valueAndTimestamp = (ValueAndTimestamp<OIAggregate>) storeValue;
                                        oi = valueAndTimestamp.value();
                                        if (log.isDebugEnabled() && oi != null) {
                                            log.debug("[OI-LOOKUP-SUCCESS-VAT] {} | Found OI wrapped in ValueAndTimestamp: OI={}", 
                                                oiKey, oi.getOiClose());
                                        }
                                    } else if (storeValue instanceof OIAggregate) {
                                        // Direct value
                                        oi = (OIAggregate) storeValue;
                                        if (log.isDebugEnabled()) {
                                            log.debug("[OI-LOOKUP-SUCCESS-DIRECT] {} | Found OI directly: OI={}", 
                                                oiKey, oi.getOiClose());
                                        }
                                    } else {
                                        log.error("[OI-LOOKUP-TYPE] {} | Unexpected store value type: {}, expected OIAggregate or ValueAndTimestamp. Setting oi to null.",
                                            oiKey, storeValue.getClass().getName());
                                        oi = null;  // Explicit null assignment for clarity
                                    }
                                }
                            } catch (Exception e) {
                                // Log error but continue without OI
                                log.warn("[OI-LOOKUP-ERROR] {} | Exception getting OI from store: {} | {}", 
                                    oiKey, e.getClass().getSimpleName(), e.getMessage());
                                oi = null;
                            }
                            
                            // Enhanced logging
                            if (log.isDebugEnabled()) {
                                log.debug("[JOIN-OI] {} | hasOB={} hasOI={} | key={}", 
                                    scripCode, 
                                    tickOb.orderbook != null, 
                                    oi != null, 
                                    oiKey);
                            }
                            
                            if (oi == null && tickExchType.equals("D")) {
                                log.warn("[JOIN-MISS] {} | OI missing for derivative! tickExch={} tickExchType={} scripCode={} | OI key used: {}", 
                                    scripCode, tickExch, tickExchType, scripCode, oiKey);
                            } else if (oi != null) {
                                if (log.isDebugEnabled()) {
                                    log.debug("[JOIN-SUCCESS] {} | OI joined! OI={} OIChange={} OIChangePct={}", 
                                        scripCode, 
                                        oi.getOiClose(), 
                                        oi.getOiChange() != null ? oi.getOiChange() : 0,
                                        oi.getOiChangePercent() != null ? String.format("%.2f%%", oi.getOiChangePercent()) : "N/A");
                                }
                            }
                        }

                        // ========== ORDERBOOK FALLBACK LOGIC ==========
                        // If orderbook is null from windowed join, try fallback to latest store
                        // This helps during replay when orderbook arrives late and window already expired
                        OrderbookAggregate orderbook = tickOb.orderbook;
                        boolean isOrderbookFallback = false;

                        if (orderbook == null && tickOb != null && tickOb.tick != null && obLatestStore != null) {
                            // Use the windowed key directly (format: "exchange:exchangeType:token")
                            // This is the same key used for orderbook aggregation
                            String obKey = windowedKey.key();

                            // Lookup orderbook from latest state store
                            try {
                                Object storeValue = obLatestStore.get(obKey);
                                if (storeValue == null) {
                                    if (log.isDebugEnabled()) {
                                        log.debug("[OB-FALLBACK-NULL] {} | No orderbook found in latest store (key: {})",
                                            tickOb.tick.getScripCode(), obKey);
                                    }
                                } else {
                                    if (storeValue instanceof ValueAndTimestamp) {
                                        // Unwrap ValueAndTimestamp
                                        @SuppressWarnings("unchecked")
                                        ValueAndTimestamp<OrderbookAggregate> valueAndTimestamp = (ValueAndTimestamp<OrderbookAggregate>) storeValue;
                                        orderbook = valueAndTimestamp.value();
                                        isOrderbookFallback = true;
                                        if (log.isDebugEnabled() && orderbook != null) {
                                            log.debug("[OB-FALLBACK-SUCCESS] {} | Using latest orderbook (fallback) | window=[{} - {}] | obTimestamp={}",
                                                tickOb.tick.getScripCode(),
                                                new java.util.Date(windowedKey.window().start()),
                                                new java.util.Date(windowedKey.window().end()),
                                                orderbook.getLastUpdateTimestamp() > 0 ?
                                                    new java.util.Date(orderbook.getLastUpdateTimestamp()) : "N/A");
                                        }
                                    } else if (storeValue instanceof OrderbookAggregate) {
                                        // Direct value
                                        orderbook = (OrderbookAggregate) storeValue;
                                        isOrderbookFallback = true;
                                        if (log.isDebugEnabled()) {
                                            log.debug("[OB-FALLBACK-SUCCESS] {} | Using latest orderbook (fallback, direct) | window=[{} - {}]",
                                                tickOb.tick.getScripCode(),
                                                new java.util.Date(windowedKey.window().start()),
                                                new java.util.Date(windowedKey.window().end()));
                                        }
                                    } else {
                                        log.warn("[OB-FALLBACK-TYPE] {} | Unexpected store value type: {} | value={}",
                                            obKey, storeValue.getClass().getName(), storeValue);
                                    }
                                }
                            } catch (Exception e) {
                                // Log error but continue without orderbook fallback
                                log.warn("[OB-FALLBACK-ERROR] {} | Exception getting orderbook from latest store: {} | {}",
                                    obKey, e.getClass().getSimpleName(), e.getMessage());
                                orderbook = null;
                            }
                        }

                        // OI always comes from latest store (fallback), so isOIFallback = true when OI is present
                        // Orderbook may come from windowed join (isOrderbookFallback=false) or latest store (isOrderbookFallback=true)
                        return new InstrumentCandleData(
                            tickOb.tick,
                            orderbook,  // Use fallback orderbook if windowed join failed
                            oi,
                            isOrderbookFallback,  // true if orderbook came from latest store (fallback)
                            oi != null  // isOIFallback - always true since OI uses latest store
                        );
                    }
                    
                    @Override
                    public void close() {
                        // No cleanup needed
                    }
                },
                Named.as("oi-ob-lookup-transformer"), // Name the transformer (handles both OI and OB fallback)
                "oi-latest-store",  // State store name for OI lookup
                "orderbook-latest-store"  // State store name for orderbook fallback lookup
            );
        
        // ========== 9. EMIT ON WINDOW CLOSE ==========
        // Note: We work directly with the stream since we need to convert to stream anyway
        // The windowed key is preserved through the join
        fullDataStream
            .filter((windowedKey, data) -> {
                if (data == null || data.tick == null) {
                    return false;
                }
                // Additional validation: log if OI is missing for derivatives
                if (data.tick != null && "D".equals(data.tick.getExchangeType()) && data.oi == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("[OI-MISS-DEBUG] {} | window=[{} - {}] | OI not found in KTable lookup",
                            data.tick.getScripCode(),
                            new java.util.Date(windowedKey.window().start()),
                            new java.util.Date(windowedKey.window().end()));
                    }
                }
                return true;
            })
            .mapValues((windowedKey, data) -> buildInstrumentCandle(windowedKey, data))
            .map((windowedKey, candle) -> KeyValue.pair(windowedKey.key(), candle))
            .peek((key, candle) -> {
                if (traceLogger != null) {
                    // Log candle with ALL indicator values
                    java.util.Map<String, Object> indicators = new java.util.HashMap<>();
                    indicators.put("open", candle.getOpen());
                    indicators.put("high", candle.getHigh());
                    indicators.put("low", candle.getLow());
                    indicators.put("close", candle.getClose());
                    indicators.put("volume", candle.getVolume());
                    indicators.put("vwap", candle.getVwap());
                    indicators.put("vpin", candle.getVpin() > 0 ? candle.getVpin() : 0.0);

                    if (candle.hasOrderbook()) {
                        indicators.put("ofi", candle.getOfi() != null ? candle.getOfi() : 0.0);
                        indicators.put("kyle_lambda", candle.getKyleLambda() != null ? candle.getKyleLambda() : 0.0);
                        indicators.put("depth_imbalance", candle.getDepthImbalance() != null ? candle.getDepthImbalance() : 0.0);
                    }

                    if (candle.hasOI()) {
                        indicators.put("OI", candle.getOpenInterest());
                        indicators.put("OI_change", candle.getOiChange());
                        indicators.put("OI_change_pct", candle.getOiChangePercent() != null ? candle.getOiChangePercent() : 0.0);
                    }

                    String reason = String.format("hasOB=%s hasOI=%s quality=%s",
                        candle.hasOrderbook() ? "✓" : "✗",
                        candle.hasOI() ? "✓" : "✗",
                        candle.getQuality() != null ? candle.getQuality() : "UNKNOWN");

                    traceLogger.logSignalAccepted("CANDLE", candle.getScripCode(),
                        candle.getWindowStartMillis(), reason, indicators);
                }
            })
            // PHASE 2: Gap Analysis - Populate gap fields from previous day close
            .transform(() -> new org.apache.kafka.streams.kstream.Transformer<String, InstrumentCandle, org.apache.kafka.streams.KeyValue<String, InstrumentCandle>>() {
                private com.kotsin.consumer.service.PreviousCloseStore.StoreAccessor store;
                
                @Override
                public void init(org.apache.kafka.streams.processor.ProcessorContext context) {
                    store = new com.kotsin.consumer.service.PreviousCloseStore.StoreAccessor(context);
                }
                
                @Override
                public org.apache.kafka.streams.KeyValue<String, InstrumentCandle> transform(String key, InstrumentCandle candle) {
                    if (candle == null) return org.apache.kafka.streams.KeyValue.pair(key, candle);
                    
                    if (gapFieldPopulator.isFirstCandleOfDay(candle.getWindowStartMillis(), candle.getExchange())) {
                        // Load previous day close
                        Double prevClose = store.getPreviousClose(candle.getScripCode());
                        
                        if (prevClose != null && prevClose > 0 && candle.getOpen() > 0) {
                            // Calculate gap
                            double gapPercent = ((candle.getOpen() - prevClose) / prevClose) * 100;
                            
                            // Set gap fields
                            candle.setPreviousClose(prevClose);
                            candle.setOvernightGap(gapPercent);
                            candle.setIsGapUp(gapPercent > 0.3);
                            candle.setIsGapDown(gapPercent < -0.3);
                            
                            // Log significant gaps
                            if (Math.abs(gapPercent) > 0.3) {
                                log.info("📊 GAP {} for {}: {:.2f}%",
                                         gapPercent > 0 ? "UP" : "DOWN", candle.getScripCode(), gapPercent);
                            }
                        }
                    }
                    
                    // Save current close for next day
                    if (candle.getClose() > 0) {
                        store.savePreviousClose(candle.getScripCode(), candle.getClose());
                    }
                    
                    return org.apache.kafka.streams.KeyValue.pair(key, candle);
                }
                
                @Override
                public void close() {}
            }, com.kotsin.consumer.service.PreviousCloseStore.STORE_NAME)
            .to(outputTopic, Produced.with(Serdes.String(), InstrumentCandle.serde()));

        log.info("Topology built: {} + {} + {} -> {}", tickTopic, orderbookTopic, oiTopic, outputTopic);
    }

    /**
     * Build instrument key from tick data
     */
    private String buildInstrumentKey(TickData tick) {
        String exch = tick.getExchange() != null ? tick.getExchange() : "N";
        String exchType = tick.getExchangeType() != null ? tick.getExchangeType() : "C";
        return exch + ":" + exchType + ":" + tick.getToken();
    }

    /**
     * Build key from orderbook
     * Fixed: token is now int, no need for String conversion
     */
    private String buildOrderbookKey(OrderBookSnapshot ob) {
        String exch = ob.getExchange() != null ? ob.getExchange() : "N";
        String exchType = ob.getExchangeType() != null ? ob.getExchangeType() : "C";
        return exch + ":" + exchType + ":" + ob.getToken();  // int token auto-converts to String in concatenation
    }

    /**
     * Build key from OI
     */
    private String buildOIKey(OpenInterest oi) {
        String exch = oi.getExchange() != null ? oi.getExchange() : "N";
        String exchType = oi.getExchangeType() != null ? oi.getExchangeType() : "D";
        return exch + ":" + exchType + ":" + oi.getToken();
    }

    /**
     * Build OI key from TickAggregate and windowed key
     * Used to look up previous OI close in cache
     */
    private String buildOIKeyFromCandle(TickAggregate tick, String windowedKey) {
        // windowedKey format is "exchange:exchangeType:token"
        // We can use it directly, or extract from tick
        if (windowedKey != null && windowedKey.contains(":")) {
            return windowedKey;  // Already in correct format
        }
        // Fallback: build from tick
        String exch = tick.getExchange() != null ? tick.getExchange() : "N";
        String exchType = tick.getExchangeType() != null ? tick.getExchangeType() : "C";
        return exch + ":" + exchType + ":" + tick.getScripCode();
    }

    /**
     * Calculate Volume Profile (POC, VAH, VAL) from volume-at-price histogram
     *
     * POC = Point of Control (price with maximum volume)
     * VAH/VAL = Value Area High/Low (boundaries containing 70% of volume)
     */
    private VolumeProfile calculateVolumeProfile(Map<Double, Long> volumeAtPrice) {
        if (volumeAtPrice == null || volumeAtPrice.isEmpty()) {
            return new VolumeProfile(null, null, null);
        }

        // 1. Find POC (price with max volume)
        Map.Entry<Double, Long> pocEntry = volumeAtPrice.entrySet().stream()
            .max(Comparator.comparingLong(Map.Entry::getValue))
            .orElse(null);

        if (pocEntry == null) {
            return new VolumeProfile(null, null, null);
        }

        double poc = pocEntry.getKey();
        long totalVolume = volumeAtPrice.values().stream().mapToLong(Long::longValue).sum();
        long valueAreaVolume = (long) (totalVolume * valueAreaPercent);  // Configurable value area

        // 2. Find VAH/VAL by expanding from POC
        List<Map.Entry<Double, Long>> sortedByPrice = volumeAtPrice.entrySet().stream()
            .sorted(Comparator.comparingDouble(Map.Entry::getKey))
            .collect(Collectors.toList());

        // Find POC index in sorted list
        int pocIndex = -1;
        for (int i = 0; i < sortedByPrice.size(); i++) {
            if (Math.abs(sortedByPrice.get(i).getKey() - poc) < 0.001) {
                pocIndex = i;
                break;
            }
        }

        if (pocIndex == -1) {
            return new VolumeProfile(poc, null, null);
        }

        // Expand from POC up and down until we cover 70% of volume
        long accumulatedVolume = sortedByPrice.get(pocIndex).getValue();
        int lowIndex = pocIndex;
        int highIndex = pocIndex;

        while (accumulatedVolume < valueAreaVolume &&
               (lowIndex > 0 || highIndex < sortedByPrice.size() - 1)) {

            // Check which direction has more volume
            long volBelow = (lowIndex > 0) ? sortedByPrice.get(lowIndex - 1).getValue() : 0;
            long volAbove = (highIndex < sortedByPrice.size() - 1) ? sortedByPrice.get(highIndex + 1).getValue() : 0;

            if (volBelow >= volAbove && lowIndex > 0) {
                // Expand downward
                lowIndex--;
                accumulatedVolume += sortedByPrice.get(lowIndex).getValue();
            } else if (highIndex < sortedByPrice.size() - 1) {
                // Expand upward
                highIndex++;
                accumulatedVolume += sortedByPrice.get(highIndex).getValue();
            } else {
                break;  // Can't expand anymore
            }
        }

        double val = sortedByPrice.get(lowIndex).getKey();
        double vah = sortedByPrice.get(highIndex).getKey();

        return new VolumeProfile(poc, vah, val);
    }

    /**
     * Volume Profile result holder
     */
    private static class VolumeProfile {
        final Double poc;
        final Double vah;
        final Double val;

        VolumeProfile(Double poc, Double vah, Double val) {
            this.poc = poc;
            this.vah = vah;
            this.val = val;
        }
    }

    /**
     * Build InstrumentCandle from aggregated data
     */
    private InstrumentCandle buildInstrumentCandle(Windowed<String> windowedKey, InstrumentCandleData data) {
        TickAggregate tick = data.tick;
        OrderbookAggregate orderbook = data.orderbook;
        OIAggregate oi = data.oi;

        // FIX: Multi-level fallback for companyName with comprehensive logging
        // 1. Try tick.getCompanyName()
        // 2. Fallback to orderbook.getCompanyName()
        // 3. Final fallback: Query ScripRepository using scripCode, exch, exchType
        String scripCode = tick.getScripCode();
        String tickCompanyName = tick.getCompanyName();
        String orderbookCompanyName = orderbook != null ? orderbook.getCompanyName() : null;
        
        log.debug("[COMPANY-NAME-START] scripCode: {} | tick.companyName: '{}' | orderbook.companyName: '{}' | orderbookNotNull: {}",
            scripCode, tickCompanyName != null ? tickCompanyName : "null", 
            orderbookCompanyName != null ? orderbookCompanyName : "null", orderbook != null);
        
        String companyName = tickCompanyName;
        String companyNameSource = "tick";
        Scrip scripFromDb = null;  // FIX: Store Scrip for direct access to strikeRate, scripType

        // Level 2: Fallback to orderbook
        if ((companyName == null || companyName.isEmpty()) && orderbook != null) {
            companyName = orderbookCompanyName;
            companyNameSource = "orderbook";
            if (companyName != null && !companyName.isEmpty()) {
                log.info("[COMPANY-NAME-FALLBACK-ORDERBOOK] scripCode: {} | Using orderbook companyName: '{}' (tick was null/empty)",
                    scripCode, companyName);
            } else {
                log.debug("[COMPANY-NAME-FALLBACK-ORDERBOOK] scripCode: {} | Orderbook companyName also null/empty",
                    scripCode);
            }
        }
        
        // Level 3: Final fallback - Query ScripRepository from MongoDB
        if ((companyName == null || companyName.isEmpty()) && scripRepository != null) {
            try {
                String exch = tick.getExchange();
                String exchType = tick.getExchangeType();
                
                log.info("[COMPANY-NAME-DB-LOOKUP-START] scripCode: {} | exch: {} | exchType: {} | Querying ScripRepository...",
                    scripCode, exch, exchType);
                
                if (scripCode != null && exch != null && exchType != null) {
                    long dbStartTime = System.currentTimeMillis();
                    Optional<Scrip> scrip = scripRepository.findFirstByExchAndExchTypeAndScripCode(exch, exchType, scripCode);
                    long dbQueryTime = System.currentTimeMillis() - dbStartTime;
                    
                    if (scrip.isPresent()) {
                        Scrip s = scrip.get();
                        String dbName = s.getName();
                        String dbFullName = s.getFullName();

                        log.info("[COMPANY-NAME-DB-LOOKUP-FOUND] scripCode: {} | Found in DB | Name: '{}' | FullName: '{}' | strikeRate: '{}' | scripType: '{}' | queryTime: {}ms",
                            scripCode, dbName != null ? dbName : "null",
                            dbFullName != null ? dbFullName : "null",
                            s.getStrikeRate() != null ? s.getStrikeRate() : "null",
                            s.getScripType() != null ? s.getScripType() : "null",
                            dbQueryTime);

                        // Prefer Name field, fallback to FullName
                        companyName = dbName;
                        if ((companyName == null || companyName.isEmpty()) && dbFullName != null) {
                            companyName = dbFullName;
                            log.debug("[COMPANY-NAME-DB-LOOKUP] scripCode: {} | Using FullName (Name was null/empty): '{}'",
                                scripCode, companyName);
                        }

                        // FIX: Store Scrip entity for direct access to strikeRate, scripType, expiry
                        // This is used later to populate option fields directly instead of parsing companyName
                        scripFromDb = s;

                        if (companyName != null && !companyName.isEmpty()) {
                            companyNameSource = "scripRepository";
                            log.info("[COMPANY-NAME-DB-LOOKUP-SUCCESS] scripCode: {} | Final companyName: '{}' | source: {} | queryTime: {}ms",
                                scripCode, companyName, companyNameSource, dbQueryTime);
                        } else {
                            log.warn("[COMPANY-NAME-DB-LOOKUP-EMPTY] scripCode: {} | Scrip found but both Name and FullName are null/empty",
                                scripCode);
                        }
                    } else {
                        log.warn("[COMPANY-NAME-DB-LOOKUP-NOT-FOUND] scripCode: {} | exch: {} | exchType: {} | Not found in ScripRepository | queryTime: {}ms",
                            scripCode, exch, exchType, dbQueryTime);
                    }
                } else {
                    log.warn("[COMPANY-NAME-DB-LOOKUP-SKIP] scripCode: {} | exch: {} | exchType: {} | Skipping DB lookup (missing required fields)",
                        scripCode, exch, exchType);
                }
            } catch (Exception e) {
                log.error("[COMPANY-NAME-DB-LOOKUP-ERROR] scripCode: {} | Failed to query ScripRepository: {} | Exception: {}",
                    scripCode, e.getMessage(), e.getClass().getSimpleName(), e);
            }
        }
        
        // Final summary log
        if (companyName == null || companyName.isEmpty()) {
            log.warn("[COMPANY-NAME-FINAL-NULL] scripCode: {} | Final companyName is NULL/EMPTY after all fallbacks | tick: '{}' | orderbook: '{}'",
                scripCode, tickCompanyName != null ? tickCompanyName : "null",
                orderbookCompanyName != null ? orderbookCompanyName : "null");
        } else {
            log.debug("[COMPANY-NAME-FINAL] scripCode: {} | Final companyName: '{}' | source: {}",
                scripCode, companyName, companyNameSource);
        }

        // Determine instrument type (use fallback companyName for better type detection)
        InstrumentType type = InstrumentType.detect(
            tick.getExchange(), 
            tick.getExchangeType(), 
            companyName
        );


        // Build base candle
        log.debug("[INSTRUMENT-CANDLE-BUILD] scripCode: {} | Building InstrumentCandle with companyName: '{}' | source: {}",
            tick.getScripCode(), companyName != null ? companyName : "null", companyNameSource);
        
        InstrumentCandle.InstrumentCandleBuilder builder = InstrumentCandle.builder()
            .scripCode(tick.getScripCode())
            .symbol(tick.getScripCode())  // Use scripCode as symbol
            .companyName(companyName)
            .exchange(tick.getExchange())
            .exchangeType(tick.getExchangeType())
            .instrumentType(type)
            .windowStartMillis(windowedKey.window().start())
            .windowEndMillis(windowedKey.window().end())
            .timeframe("1m")
            // OHLCV
            .open(tick.getOpen())
            .high(tick.getHigh())
            .low(tick.getLow())
            .close(tick.getClose())
            .volume(tick.getVolume())
            .buyVolume(tick.getBuyVolume())
            .sellVolume(tick.getSellVolume())
            .vwap(tick.getVwap())
            .tickCount(tick.getTickCount());

        // ========== VOLUME PROFILE (POC, VAH, VAL) ==========
        // Calculate Point of Control (POC) and Value Area High/Low (VAH/VAL)
        // from volume-at-price histogram
        Map<Double, Long> volumeAtPrice = tick.getVolumeAtPrice();
        VolumeProfile volumeProfile = calculateVolumeProfile(volumeAtPrice);

        builder.volumeAtPrice(volumeAtPrice)
            .poc(volumeProfile.poc)
            .vah(volumeProfile.vah)
            .val(volumeProfile.val);

        if (log.isDebugEnabled() && volumeProfile.poc != null) {
            log.debug("[VOLUME-PROFILE] {} | POC={} VAH={} VAL={} | {} price levels",
                tick.getScripCode(),
                String.format("%.2f", volumeProfile.poc),
                volumeProfile.vah != null ? String.format("%.2f", volumeProfile.vah) : "null",
                volumeProfile.val != null ? String.format("%.2f", volumeProfile.val) : "null",
                volumeAtPrice != null ? volumeAtPrice.size() : 0);
        }

        // Calculate adaptive VPIN with market open reset
        double avgDailyVolume = instrumentMetadataService.getAverageDailyVolume(
            tick.getExchange(), tick.getExchangeType(), tick.getScripCode()
        );
        String vpinKey = tick.getScripCode();

        // Detect trading day from window start time
        LocalDate currentTradingDay = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(windowedKey.window().start()),
            ZoneId.of("Asia/Kolkata")
        ).toLocalDate();

        // FIX: Use minute-average volume instead of daily volume for bucket sizing
        // BEFORE: bucketSize = dailyVolume / 50 = 200K for 10M daily (7+ minutes to fill 1 bucket)
        // AFTER: bucketSize = minuteAvgVolume / 50 ≈ 533 for 10M daily (fills properly per minute)
        // Trading day = 375 minutes (9:15-15:30), so minuteAvg = dailyVolume / 375
        // This ensures buckets fill within reasonable timeframes for toxic flow detection
        final int TRADING_MINUTES_PER_DAY = 375;  // NSE trading hours: 9:15 AM to 3:30 PM
        double minuteAvgVolume = avgDailyVolume / TRADING_MINUTES_PER_DAY;

        // Get or create VPIN calculator using minute-average volume
        AdaptiveVPINCalculator vpinCalc = vpinCalculators.get(vpinKey, k -> new AdaptiveVPINCalculator(minuteAvgVolume));
        
        // Reset VPIN if we've crossed into a new trading day (market open)
        LocalDate lastTradingDay = vpinLastTradingDay.getIfPresent(vpinKey);
        if (lastTradingDay != null && !lastTradingDay.equals(currentTradingDay)) {
            // New trading day detected - reset VPIN to prevent cross-day contamination
            log.debug("VPIN reset for {}: {} -> {}", vpinKey, lastTradingDay, currentTradingDay);
            vpinCalc.reset();
        }
        vpinLastTradingDay.put(vpinKey, currentTradingDay);
        
        // Update VPIN with candle data
        // #region agent log
        double vpinBefore = vpinCalc.calculate();
        long buyVol = tick.getBuyVolume();
        long sellVol = tick.getSellVolume();
        // #endregion
        
        vpinCalc.updateFromCandle(buyVol, sellVol);
        double vpinAfter = vpinCalc.calculate();
        builder.vpin(vpinAfter);
        builder.vpinBucketSize(vpinCalc.getBucketSize());
        builder.vpinBucketCount(vpinCalc.getBucketCount());
        


        // Add orderbook metrics if available
        builder.orderbookPresent(orderbook != null);
        if (orderbook != null) {
            // Set orderbook metadata: timestamp and fallback flag
            // Use lastUpdateTimestamp if available, otherwise windowEndMillis
            Long obTimestamp = orderbook.getLastUpdateTimestamp() > 0 
                ? orderbook.getLastUpdateTimestamp() 
                : orderbook.getWindowEndMillis();
            builder.orderbookDataTimestamp(obTimestamp);
            builder.isOrderbookFallback(data.isOrderbookFallback != null ? data.isOrderbookFallback : false);
            // Orderbook metrics are already calculated during updateWithSnapshot
            builder.ofi(orderbook.getOfi());
            builder.kyleLambda(orderbook.getKyleLambda());
            builder.microprice(orderbook.getMicroprice());
            builder.bidAskSpread(orderbook.getBidAskSpread());
            builder.depthImbalance(orderbook.getDepthImbalance());
            // These fields may not exist - use safe getters
            builder.averageBidDepth(getAverageBidDepth(orderbook));
            builder.averageAskDepth(getAverageAskDepth(orderbook));
            builder.spoofingCount(orderbook.getSpoofingEvents() != null ? orderbook.getSpoofingEvents().size() : 0);

            // ========== PHASE 3: ORDERBOOK DEPTH FRAGMENTATION ==========
            builder.totalBidOrders(orderbook.getTotalBidOrders());
            builder.totalAskOrders(orderbook.getTotalAskOrders());
            builder.ordersAtBestBid(orderbook.getOrdersAtBestBid());
            builder.ordersAtBestAsk(orderbook.getOrdersAtBestAsk());
            builder.avgBidOrderSize(orderbook.getAvgBidOrderSize());
            builder.avgAskOrderSize(orderbook.getAvgAskOrderSize());
            builder.depthConcentration(orderbook.getDepthConcentration());
            builder.maxDepthLevels(orderbook.getMaxDepthLevels());

            // Iceberg detection (enhanced from existing recentBidQuantities logic)
            builder.icebergBidDetected(orderbook.detectIcebergBid() || orderbook.detectIcebergAtBestBid());
            builder.icebergAskDetected(orderbook.detectIcebergAsk() || orderbook.detectIcebergAtBestAsk());
            builder.icebergAtBestBid(orderbook.detectIcebergAtBestBid());
            builder.icebergAtBestAsk(orderbook.detectIcebergAtBestAsk());

            // Log iceberg detection
            if (orderbook.detectIcebergAtBestBid() || orderbook.detectIcebergAtBestAsk()) {
                log.warn("[ICEBERG-DETECTED] {} | Bid: {} orders x {} avg | Ask: {} orders x {} avg",
                    tick.getScripCode(),
                    orderbook.getOrdersAtBestBid(),
                    String.format("%.0f", orderbook.getAvgBidOrderSize()),
                    orderbook.getOrdersAtBestAsk(),
                    String.format("%.0f", orderbook.getAvgAskOrderSize()));
            }

            // ========== PHASE 7: ORDERBOOK UPDATE DYNAMICS ==========
            orderbook.calculateSpreadVolatility();

            builder.spreadVolatility(orderbook.getSpreadVolatility());
            builder.maxSpread(orderbook.getMaxSpread());
            builder.minSpread(orderbook.getMinSpread());
            builder.orderbookUpdateCount(orderbook.getOrderbookUpdateCount());
            builder.spreadChangeRate(orderbook.getSpreadChangeRate());
            builder.orderbookMomentum(orderbook.getOrderbookMomentum());

            // Detect rapid spread changes (potential manipulation)
            if (orderbook.getSpreadChangeRate() > 1.0) {  // 1 rupee per second
                log.warn("[RAPID-SPREAD-CHANGE] {} | Rate: {}/sec | Max: {} Min: {}",
                    tick.getScripCode(),
                    String.format("%.2f", orderbook.getSpreadChangeRate()),
                    orderbook.getMaxSpread(),
                    orderbook.getMinSpread());
            }
        }

        // Add OI metrics if available
        builder.oiPresent(oi != null);
        if (oi != null) {
            // Set OI metadata: timestamp and fallback flag
            // Use lastUpdateTimestamp if available, otherwise windowEndMillis
            Long oiTimestamp = oi.getLastUpdateTimestamp() > 0 
                ? oi.getLastUpdateTimestamp() 
                : oi.getWindowEndMillis();
            builder.oiDataTimestamp(oiTimestamp);
            builder.isOIFallback(data.isOIFallback != null ? data.isOIFallback : true);  // OI always uses latest store
        }

        // #endregion
        if (oi != null) {
            oi.calculateDerivedMetrics();

            // ========== PHASE 5: UPDATE OI WITH MARKET CONTEXT ==========
            // Capture current market state at the time of join (when OI is being used)
            // This allows correlation analysis: "What was the price/volume when we used this OI value?"
            // Example: OI increasing with price up = Long buildup (bullish signal)
            double currentPrice = tick.getClose();
            Long currentVolume = tick.getVolume();
            Double currentSpread = orderbook != null ? orderbook.getBidAskSpread() : null;
            
            // Update market context (price/volume/spread at join time)
            // Note: lastUpdateTimestamp is already set in updateWithOI() from the OI stream's receivedTimestamp
            // This tracks when OI was actually updated, not when we joined it
            oi.updateMarketContext(currentPrice, currentVolume, currentSpread);

            builder.openInterest(oi.getOiClose());

            // #endregion
            builder.oiOpen(oi.getOiOpen());
            builder.oiHigh(oi.getOiHigh());
            builder.oiLow(oi.getOiLow());
            builder.oiClose(oi.getOiClose());
            
            // Calculate OI change from PREVIOUS window (not within current window)
            // FIX: Always use cross-window change for meaningful OI analysis
            String oiKey = buildOIKeyFromCandle(tick, windowedKey.key());
            Long previousOIClose = previousOICloseCache.getIfPresent(oiKey);

            if (previousOIClose != null && previousOIClose > 0 && oi.getOiClose() != null) {
                // Calculate change from previous window's close to current window's close
                Long oiChange = oi.getOiClose() - previousOIClose;
                Double oiChangePercent = (double) oiChange / previousOIClose * 100.0;

                builder.oiChange(oiChange);
                builder.oiChangePercent(oiChangePercent);

                if (log.isDebugEnabled()) {
                    log.debug("[OI-CHANGE] {} | prevOI={} currOI={} change={} changePct={}%",
                        oiKey, previousOIClose, oi.getOiClose(), oiChange,
                        String.format("%.2f", oiChangePercent));
                }
            } else {
                // FIX: First window or no previous OI - set to null (not enough data for cross-window change)
                // Within-window OI change (oiClose - oiOpen in same window) is meaningless for sparse OI updates
                // Traders need cross-window change to see actual OI buildups/reductions
                builder.oiChange(null);
                builder.oiChangePercent(null);

                if (log.isDebugEnabled() && oi.getOiClose() != null) {
                    log.debug("[OI-CHANGE-INIT] {} | currOI={} | No previous OI - change set to null (first window)",
                        oiKey, oi.getOiClose());
                }
            }
            
            // Update cache with current OI close for next window
            if (oi.getOiClose() != null) {
                previousOICloseCache.put(oiKey, oi.getOiClose());
            }

            // ========== PHASE 5: OI CORRELATION METRICS ==========
            builder.priceAtOIUpdate(oi.getPriceAtUpdate());
            builder.volumeAtOIUpdate(oi.getVolumeAtUpdate());
            builder.spreadAtOIUpdate(oi.getSpreadAtUpdate());
            builder.oiUpdateLatency(oi.getOIUpdateLatency());
            builder.oiUpdateCount(oi.getUpdateCount());

            // Detect OI buildup patterns (OI increasing with price)
            if (oi.getOiChange() != null && oi.getOiChange() > 0 && oi.getPriceAtUpdate() != null) {
                // OI building with price movement indicates directional positioning
                if (log.isDebugEnabled()) {
                    log.debug("[OI-BUILDUP] {} | OI change: {} | Price: {} | Volume: {}",
                        tick.getScripCode(),
                        oi.getOiChange(),
                        oi.getPriceAtUpdate(),
                        oi.getVolumeAtUpdate());
                }
            }
            
            // P1: OI Velocity = OI change per minute (window is 60 seconds)
            Long oiChangeVal = oi.getOiChange();
            if (oiChangeVal != null) {
                double oiVelocity = (double) oiChangeVal;  // Already per-minute since window = 1min
                builder.oiVelocity(oiVelocity);
                // Note: oiAcceleration requires previous window's velocity - would need cross-window state
            }
        }

        // P2: Level-weighted depth imbalance from orderbook
        if (orderbook != null) {
            // Use existing OrderbookAggregate fields for depth analysis
            double bidDepth = orderbook.getTotalBidDepthSum() / Math.max(1, orderbook.getTotalBidDepthCount());
            double askDepth = orderbook.getTotalAskDepthSum() / Math.max(1, orderbook.getTotalAskDepthCount());
            double total = bidDepth + askDepth;
            double levelWeightedImbalance = total > 0 ? (bidDepth - askDepth) / total : 0.0;
            
            builder.levelWeightedBidDepth(bidDepth);
            builder.levelWeightedAskDepth(askDepth);
            builder.levelWeightedImbalance(levelWeightedImbalance);
            
            // Fragmentation: avg order size = total qty / total orders
            double bidFrag = orderbook.getAvgBidOrderSize();
            double askFrag = orderbook.getAvgAskOrderSize();
            builder.bidFragmentation(bidFrag);
            builder.askFragmentation(askFrag);
            builder.institutionalBias(bidFrag > 0 ? askFrag / bidFrag : 0.0);

            // PHASE 8: Market Depth Slope (Liquidity Curve)
            builder.bidDepthSlope(orderbook.getBidDepthSlope());
            builder.askDepthSlope(orderbook.getAskDepthSlope());
        }

        // Add option-specific fields (including days to expiry and greeks)
        if (type.isOption()) {
            // FIX: Pass Scrip entity for direct access to strikeRate, scripType
            // This is more reliable than parsing from companyName
            parseOptionDetails(builder, companyName, scripFromDb);
            // PHASE 9: Calculate option greeks (Delta, Gamma, Vega, Theta)
            // Note: We need to build a temporary candle to access parsed fields, then recalculate
            InstrumentCandle tempCandle = builder.build();
            calculateOptionGreeks(builder, tick,
                tempCandle.getStrikePrice(),
                tempCandle.getOptionType(),
                tempCandle.getHoursToExpiry());
        }

        // ========== PHASE 1-4: CALCULATE AND POPULATE ENHANCED METRICS ==========

        // PHASE 2: Calculate temporal metrics
        tick.calculateTemporalMetrics();

        // PHASE 4: Calculate trade size distribution
        tick.calculateTradeSizeDistribution();

        
        builder.aggressiveBuyVolume(tick.getAggressiveBuyVolume());
        builder.aggressiveSellVolume(tick.getAggressiveSellVolume());
        builder.midpointVolume(tick.getMidpointVolume());
        builder.classificationReliability(tick.getClassificationReliability());

        // Calculate buy/sell pressure
        if (tick.getVolume() > 0) {
            builder.buyPressure((double) tick.getAggressiveBuyVolume() / tick.getVolume());
            builder.sellPressure((double) tick.getAggressiveSellVolume() / tick.getVolume());
        }

        // PHASE 2: Temporal Metrics
        builder.firstTickTimestamp(tick.getFirstTickTimestamp());
        builder.lastTickTimestamp(tick.getLastTickTimestamp());
        builder.minTickGap(tick.getMinTickGap());
        builder.maxTickGap(tick.getMaxTickGap());
        builder.avgTickGap(tick.getAvgTickGap());
        builder.ticksPerSecond(tick.getTicksPerSecond());
        builder.tickAcceleration(tick.getTickAcceleration());

        // Flash crash detection (configurable threshold)
        if (tick.getTickAcceleration() > flashCrashThreshold) {
            log.warn("[FLASH-CRASH-RISK] {} | Tick acceleration: {} (threshold: {}) | tps: {} → {}",
                tick.getScripCode(),
                tick.getTickAcceleration(),
                flashCrashThreshold,
                tick.getTicksPerSecond() - (int)tick.getTickAcceleration(),
                tick.getTicksPerSecond());
        }

        // Stale data detection
        if (tick.getLastTickTimestamp() > 0) {
            long dataAge = System.currentTimeMillis() - tick.getLastTickTimestamp();
            if (dataAge > 5000) {  // 5 seconds
                log.warn("[STALE-DATA] {} | Last tick {}ms ago", tick.getScripCode(), dataAge);
            }
        }

        // PHASE 4: Trade Size Distribution
        builder.maxTradeSize(tick.getMaxTradeSize());
        builder.minTradeSize(tick.getMinTradeSize());
        builder.avgTradeSize(tick.getAvgTradeSize());
        builder.medianTradeSize(tick.getMedianTradeSize());
        builder.largeTradeCount(tick.getLargeTradeCount());
        builder.priceImpactPerUnit(tick.getPriceImpactPerUnit());

        // Block trade detection
        if (tick.getLargeTradeCount() > 0) {
            log.info("[BLOCK-TRADE] {} | Count: {} | Max size: {} (avg: {})",
                tick.getScripCode(),
                tick.getLargeTradeCount(),
                tick.getMaxTradeSize(),
                String.format("%.0f", tick.getAvgTradeSize()));
        }

        // ========== IMBALANCE BARS (Fix #1) ==========
        builder.volumeImbalance(tick.getVolumeImbalance());
        builder.dollarImbalance(tick.getDollarImbalance());
        builder.tickRuns(tick.getTickRuns());
        builder.volumeRuns(tick.getVolumeRuns());
        builder.vibTriggered(tick.isVibTriggered());
        builder.dibTriggered(tick.isDibTriggered());
        builder.trbTriggered(tick.isTrbTriggered());
        builder.vrbTriggered(tick.isVrbTriggered());

        // Log imbalance bar triggers for monitoring
        if (tick.isVibTriggered() || tick.isDibTriggered() || tick.isTrbTriggered() || tick.isVrbTriggered()) {
            java.util.List<String> triggered = new java.util.ArrayList<>();
            if (tick.isVibTriggered()) triggered.add("VIB");
            if (tick.isDibTriggered()) triggered.add("DIB");
            if (tick.isTrbTriggered()) triggered.add("TRB");
            if (tick.isVrbTriggered()) triggered.add("VRB");
            log.info("[IMBALANCE-TRIGGER] {} | {} | VIB={} DIB={} TRB={} VRB={}",
                tick.getScripCode(),
                String.join(",", triggered),
                tick.getVolumeImbalance(),
                String.format("%.0f", tick.getDollarImbalance()),
                tick.getTickRuns(),
                tick.getVolumeRuns());
        }

        // ========== P0 FIX: TICK-LEVEL BID/ASK IMBALANCE ==========
        builder.sumTotalBidQty(tick.getSumTotalBidQty());
        builder.sumTotalOffQty(tick.getSumTotalOffQty());
        builder.tickBidAskImbalance(tick.getTickBidAskImbalance());

        // ========== P0 FIX: OVERNIGHT GAP ANALYSIS ==========
        builder.previousClose(tick.getPreviousClose());
        double gap = tick.getOvernightGap();
        builder.overnightGap(gap);
        builder.isGapUp(tick.isGapUp());
        builder.isGapDown(tick.isGapDown());

        // Log significant gaps
        if (Math.abs(gap) > 1.0) {  // > 1% gap
            log.info("[GAP-DETECT] {} | gap={}% prevClose={} open={} {}",
                tick.getScripCode(),
                String.format("%.2f", gap),
                String.format("%.2f", tick.getPreviousClose()),
                String.format("%.2f", tick.getOpen()),
                gap > 0 ? "⬆ GAP_UP" : "⬇ GAP_DOWN");
        }

        // ========== P1: VWAP VALIDATION ==========
        builder.exchangeVwap(tick.getExchangeVwap());
        double vwapDrift = tick.getVwapDrift();
        builder.vwapDrift(vwapDrift);

        // Warn if VWAP drifts > 1% from exchange
        if (Math.abs(vwapDrift) > 1.0 && tick.getExchangeVwap() > 0) {
            log.warn("[VWAP-DRIFT] {} | calculated={} exchange={} drift={}%",
                tick.getScripCode(),
                String.format("%.2f", tick.getVwap()),
                String.format("%.2f", tick.getExchangeVwap()),
                String.format("%.2f", vwapDrift));
        }

        // ========== TICK-LEVEL SPREAD METRICS (Execution Cost) ==========
        builder.averageTickSpread(tick.getAverageTickSpread());
        builder.minTickSpread(tick.getMinTickSpread());
        builder.maxTickSpread(tick.getMaxTickSpread());
        builder.spreadVolatilityTick(tick.getSpreadVolatility());
        builder.tightSpreadPercent(tick.getTightSpreadPercent());

        // ========== QUANT FIX P0: EFFECTIVE SPREAD (Actual Execution Cost) ==========
        // Effective spread = 2 * |trade_price - midpoint| measures ACTUAL slippage
        // Unlike quoted spread (bid-ask), this captures real execution quality
        builder.averageEffectiveSpread(tick.getAverageEffectiveSpread());
        builder.minEffectiveSpread(tick.getMinEffectiveSpread());
        builder.maxEffectiveSpread(tick.getMaxEffectiveSpread());
        builder.priceImprovementRatio(tick.getPriceImprovementRatio());

        if (log.isDebugEnabled() && tick.getAverageTickSpread() > 0) {
            log.debug("[SPREAD-METRICS] {} | avg={} min={} max={} vol={} tight={}%",
                tick.getScripCode(),
                String.format("%.4f", tick.getAverageTickSpread()),
                String.format("%.4f", tick.getMinTickSpread()),
                String.format("%.4f", tick.getMaxTickSpread()),
                String.format("%.4f", tick.getSpreadVolatility()),
                String.format("%.1f", tick.getTightSpreadPercent()));
        }

        // ========== VWAP BANDS (Trading Signals) ==========
        builder.vwapStdDev(tick.getVWAPStdDev());
        builder.vwapUpperBand(tick.getVWAPUpperBand());
        builder.vwapLowerBand(tick.getVWAPLowerBand());
        String vwapSignal = tick.getVWAPSignal();
        builder.vwapSignal(vwapSignal);

        if (log.isDebugEnabled() && tick.getVWAPStdDev() > 0) {
            log.debug("[VWAP-BANDS] {} | VWAP={} Upper={} Lower={} σ={} Signal={}",
                tick.getScripCode(),
                String.format("%.2f", tick.getVwap()),
                String.format("%.2f", tick.getVWAPUpperBand()),
                String.format("%.2f", tick.getVWAPLowerBand()),
                String.format("%.4f", tick.getVWAPStdDev()),
                vwapSignal);
        }

        // Warn on overbought/oversold signals
        if ("OVERBOUGHT".equals(vwapSignal) || "OVERSOLD".equals(vwapSignal)) {
            log.info("[VWAP-SIGNAL] {} | {} at price={} (VWAP={})",
                tick.getScripCode(),
                vwapSignal,
                String.format("%.2f", tick.getClose()),
                String.format("%.2f", tick.getVwap()));
        }

        // ========== P2: TICK INTENSITY ZONES ==========
        builder.maxTicksInAnySecond(tick.getMaxTicksInAnySecond());
        builder.secondsWithTicks(tick.getSecondsWithTicks());
        builder.tickBurstRatio(tick.getTickBurstRatio());
        builder.algoActivityDetected(tick.isAlgoActivityDetected());
        
        // Log algo detection
        if (tick.isAlgoActivityDetected()) {
            log.info("[ALGO-DETECTED] {} | maxTicks/sec={} burstRatio={} (threshold=3.0)",
                tick.getScripCode(), tick.getMaxTicksInAnySecond(), String.format("%.2f", tick.getTickBurstRatio()));
        }

        // ========== PHASE 6: CROSS-STREAM LATENCY MEASUREMENT ==========
        long currentTime = System.currentTimeMillis();
        long windowEndTime = windowedKey.window().end();
        long tickAge = tick.getLastTickTimestamp() > 0 ?
            currentTime - tick.getLastTickTimestamp() : 0;
        // For replay scenarios, compare against window end, not wall-clock time
        long tickAgeFromWindow = tick.getLastTickTimestamp() > 0 ?
            windowEndTime - tick.getLastTickTimestamp() : 0;
        long maxAge = tickAge;



        // FIX: Use window-relative age for staleness check (handles replay correctly)
        // If processing historical data (window end is far in past), use window-relative age
        boolean isReplay = (currentTime - windowEndTime) > 60000; // Window end > 1 min ago = replay
        long effectiveTickAge = isReplay ? tickAgeFromWindow : tickAge;
        builder.tickStale(effectiveTickAge > 5000);  // 5 seconds threshold

        // Initialize effective ages (will be set in if blocks)
        long effectiveObAge = 0;
        long effectiveOiAge = 0;

        if (orderbook != null) {
            // Measure latency between tick and orderbook
            // Use last tick event time vs orderbook window end
            if (tick.getLastTickEventTime() > 0 && orderbook.getWindowEndMillis() > 0) {
                long obLatency = Math.abs(tick.getLastTickEventTime() - orderbook.getWindowEndMillis());
                builder.tickToOrderbookLatency(obLatency);

                // Alert if latency too high
                if (obLatency > 1000) {
                    log.warn("[HIGH-LATENCY] {} | Tick-OB latency: {}ms",
                        tick.getScripCode(), obLatency);
                }
            }

            // Check orderbook staleness
            long obAge = orderbook.getWindowEndMillis() > 0 ?
                currentTime - orderbook.getWindowEndMillis() : 0;
            long obAgeFromWindow = orderbook.getWindowEndMillis() > 0 ?
                windowEndTime - orderbook.getWindowEndMillis() : 0;
            effectiveObAge = isReplay ? obAgeFromWindow : obAge;
            builder.orderbookStale(effectiveObAge > 5000);
            maxAge = Math.max(maxAge, effectiveObAge);
        }

        if (oi != null) {
            // Measure latency between tick and OI
            if (tick.getLastTickEventTime() > 0 && oi.getWindowEndMillis() > 0) {
                long oiLatency = Math.abs(tick.getLastTickEventTime() - oi.getWindowEndMillis());
                builder.tickToOILatency(oiLatency);
            }

            // OI can be delayed up to 3 minutes (NSE rules)
            long oiAge = oi.getWindowEndMillis() > 0 ?
                currentTime - oi.getWindowEndMillis() : 0;
            long oiAgeFromWindow = oi.getWindowEndMillis() > 0 ?
                windowEndTime - oi.getWindowEndMillis() : 0;
            effectiveOiAge = isReplay ? oiAgeFromWindow : oiAge;
            builder.oiStale(effectiveOiAge > 300000);  // 5 minutes threshold
            maxAge = Math.max(maxAge, effectiveOiAge);
        }

        builder.maxDataAge(maxAge);

        // Determine staleness reason (use effective ages) - MUST be before first log that uses it
        StringBuilder stalenessReason = new StringBuilder();
        if (effectiveTickAge > 5000) {
            stalenessReason.append("Tick stale (").append(effectiveTickAge).append("ms"); 
            if (isReplay) stalenessReason.append(" from window");
            stalenessReason.append("); ");
        }
        if (orderbook != null && effectiveObAge > 5000) {
            stalenessReason.append("Orderbook stale (").append(effectiveObAge).append("ms");
            if (isReplay) stalenessReason.append(" from window");
            stalenessReason.append("); ");
        }
        if (oi != null && effectiveOiAge > 300000) {
            stalenessReason.append("OI stale (").append(effectiveOiAge).append("ms");
            if (isReplay) stalenessReason.append(" from window");
            stalenessReason.append("); ");
        }
        
        if (stalenessReason.length() > 0) {
            builder.stalenessReason(stalenessReason.toString());

            log.warn("[STALE-DATA] {} | {} | MaxAge: {}ms",
                tick.getScripCode(),
                stalenessReason.toString(),
                maxAge);
        }

        if (stalenessReason.length() > 0) {
            builder.stalenessReason(stalenessReason.toString());

            log.warn("[STALE-DATA] {} | {} | MaxAge: {}ms",
                tick.getScripCode(),
                stalenessReason.toString(),
                maxAge);
        }

        InstrumentCandle candle = builder.build();
        candle.updateHumanReadableTime();

        // Validate data quality if orderbook present
        if (candle.hasOrderbook()) {
            TradeOrderbookValidator.validateAndUpdate(candle);
        } else {
            candle.setQuality(DataQuality.VALID);
        }
        return candle;
    }

    /**
     * Parse option details from Scrip entity OR company name
     * FIX: Prefer direct Scrip fields (strikeRate, scripType, expiry) over parsing companyName
     *
     * @param builder InstrumentCandle builder
     * @param companyName Company name string (fallback)
     * @param scrip Scrip entity from MongoDB (preferred source)
     */
    private void parseOptionDetails(InstrumentCandle.InstrumentCandleBuilder builder, String companyName, Scrip scrip) {
        boolean foundStrike = false;
        boolean foundOptionType = false;
        boolean foundExpiry = false;

        // ═══════════════════════════════════════════════════════════════════════════════
        // PRIORITY 1: Use Scrip entity fields directly (most reliable)
        // ═══════════════════════════════════════════════════════════════════════════════
        if (scrip != null) {
            // Strike price from StrikeRate field
            if (scrip.getStrikeRate() != null && !scrip.getStrikeRate().isEmpty()) {
                try {
                    double strike = Double.parseDouble(scrip.getStrikeRate());
                    builder.strikePrice(strike);
                    foundStrike = true;
                    log.debug("[OPTION-PARSE] Strike from Scrip.StrikeRate: {}", strike);
                } catch (NumberFormatException e) {
                    log.warn("[OPTION-PARSE] Failed to parse Scrip.StrikeRate '{}': {}", scrip.getStrikeRate(), e.getMessage());
                }
            }

            // Option type from ScripType field (CE/PE)
            if (scrip.getScripType() != null && !scrip.getScripType().isEmpty()) {
                String scripType = scrip.getScripType().toUpperCase();
                if (scripType.equals("CE") || scripType.equals("PE")) {
                    builder.optionType(scripType);
                    foundOptionType = true;
                    log.debug("[OPTION-PARSE] OptionType from Scrip.ScripType: {}", scripType);
                } else if (scripType.contains("CALL") || scripType.contains("CE")) {
                    builder.optionType("CE");
                    foundOptionType = true;
                } else if (scripType.contains("PUT") || scripType.contains("PE")) {
                    builder.optionType("PE");
                    foundOptionType = true;
                }
            }

            // Expiry from Expiry field
            if (scrip.getExpiry() != null && !scrip.getExpiry().isEmpty()) {
                builder.expiry(scrip.getExpiry());
                foundExpiry = true;
                calculateDaysToExpiry(builder, scrip.getExpiry());
                log.debug("[OPTION-PARSE] Expiry from Scrip.Expiry: {}", scrip.getExpiry());
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════════
        // PRIORITY 2: Parse from companyName as fallback
        // Format: "SYMBOL DD MON YYYY CE/PE STRIKE" e.g., "TITAN 30 JAN 2026 CE 4100.00"
        // ═══════════════════════════════════════════════════════════════════════════════
        if (companyName != null && (!foundStrike || !foundOptionType || !foundExpiry)) {
            String[] parts = companyName.split("\\s+");
            if (parts.length >= 5) {
                // Strike price (last part)
                if (!foundStrike) {
                    try {
                        String lastPart = parts[parts.length - 1];
                        builder.strikePrice(Double.parseDouble(lastPart));
                        foundStrike = true;
                        log.debug("[OPTION-PARSE] Strike from companyName: {}", lastPart);
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }

                // Option type (CE/PE)
                if (!foundOptionType) {
                    for (String part : parts) {
                        if (part.equalsIgnoreCase("CE")) {
                            builder.optionType("CE");
                            foundOptionType = true;
                            break;
                        } else if (part.equalsIgnoreCase("PE")) {
                            builder.optionType("PE");
                            foundOptionType = true;
                            break;
                        }
                    }
                }

                // Expiry (DD MON YYYY)
                if (!foundExpiry && parts.length >= 4) {
                    String expiryStr = parts[1] + " " + parts[2] + " " + parts[3];
                    builder.expiry(expiryStr);
                    foundExpiry = true;
                    calculateDaysToExpiry(builder, expiryStr);
                }
            }
        }

        // Log warning if critical fields missing
        if (!foundStrike || !foundOptionType) {
            log.warn("[OPTION-PARSE-INCOMPLETE] strike={} optionType={} expiry={} | companyName='{}' | scrip={}",
                foundStrike, foundOptionType, foundExpiry,
                companyName != null ? companyName : "null",
                scrip != null ? "present" : "null");
        }
    }

    /**
     * PHASE 8: Calculate days to expiry for options
     * Expiry format: "DD MON YYYY" (e.g., "27 FEB 2025")
     */
    private void calculateDaysToExpiry(InstrumentCandle.InstrumentCandleBuilder builder, String expiryStr) {
        if (expiryStr == null || expiryStr.isEmpty()) return;

        try {
            // Parse expiry date: "DD MON YYYY" - CASE INSENSITIVE for uppercase months like "27 JAN 2026"
            java.time.format.DateTimeFormatter formatter = new java.time.format.DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("d MMM yyyy")
                .toFormatter(java.util.Locale.ENGLISH);
            LocalDate expiryDate = LocalDate.parse(expiryStr, formatter);

            // Get current date in IST
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
            LocalDate today = now.toLocalDate();

            // Calculate days to expiry
            long daysToExpiry = java.time.temporal.ChronoUnit.DAYS.between(today, expiryDate);

            // Calculate hours to expiry (more precise)
            // Assume expiry happens at 15:30 IST (NSE F&O expiry time)
            ZonedDateTime expiryDateTime = expiryDate.atTime(15, 30).atZone(ZoneId.of("Asia/Kolkata"));
            long hoursToExpiry = java.time.temporal.ChronoUnit.HOURS.between(now, expiryDateTime);

            // Set fields
            builder.daysToExpiry((int) daysToExpiry);
            builder.hoursToExpiry((double) hoursToExpiry);
            builder.isNearExpiry(daysToExpiry <= 7 && daysToExpiry >= 0);

            // Log near expiry options
            if (daysToExpiry <= 2 && daysToExpiry >= 0) {
                log.info("[NEAR-EXPIRY] Option expiring in {} days ({} hours)",
                    daysToExpiry, hoursToExpiry);
            }

        } catch (Exception e) {
            // Failed to parse expiry - not critical, just skip
            log.warn("[EXPIRY-PARSE-FAIL] Could not parse expiry: {}", expiryStr);
        }
    }

    /**
     * PHASE 9: Calculate option greeks (Delta, Gamma, Vega, Theta)
     * Uses Black-Scholes model with estimated volatility
     */
    private void calculateOptionGreeks(InstrumentCandle.InstrumentCandleBuilder builder, TickAggregate tick,
                                       Double strikePrice, String optionType, Double hoursToExpiry) {
        try {
            // Get required inputs
            Double underlyingPrice = tick.getClose(); // Use candle close as underlying price
            
            // Validate inputs
            if (strikePrice == null || strikePrice <= 0) {
                return; // Strike not available
            }
            if (optionType == null || (!optionType.equals("CE") && !optionType.equals("PE"))) {
                return; // Invalid option type
            }
            if (hoursToExpiry == null || hoursToExpiry <= 0) {
                return; // Expiry not calculated
            }
            if (underlyingPrice <= 0) {
                return; // No underlying price
            }
            
            // Convert hours to years
            double timeToExpiryYears = hoursToExpiry / (365.0 * 24.0);
            
            // Estimate volatility (simplified approach)
            // Option 1: Use default 30% (common for Indian markets)
            // Option 2: Estimate from price range (high-low) in current candle
            double volatility = estimateVolatility(tick, underlyingPrice);
            
            // Calculate greeks
            boolean isCall = optionType.equals("CE");
            BlackScholesGreeks.GreeksResult greeks = BlackScholesGreeks.calculateGreeks(
                underlyingPrice,
                strikePrice,
                timeToExpiryYears,
                volatility,
                isCall
            );
            
            // Set greeks in builder
            builder.delta(greeks.delta);
            builder.gamma(greeks.gamma);
            builder.vega(greeks.vega);
            builder.theta(greeks.theta);
            builder.impliedVolatility(volatility);
            
            // Log for near-expiry options (high gamma risk)
            if (hoursToExpiry != null && hoursToExpiry <= 24 && Math.abs(greeks.gamma) > 0.01) {
                log.info("[HIGH-GAMMA] Option {} | Strike: {} | Gamma: {:.4f} | Hours to expiry: {}",
                    tick.getCompanyName(), strikePrice, greeks.gamma, hoursToExpiry);
            }
            
        } catch (Exception e) {
            // Failed to calculate greeks - not critical, just skip
            log.warn("[GREEKS-CALC-FAIL] Could not calculate greeks for {}: {}", 
                tick.getCompanyName(), e.getMessage());
        }
    }
    
    /**
     * Estimate volatility from price data
     * Simple approach: Use high-low range as volatility proxy
     * More sophisticated: Use historical returns standard deviation
     */
    private double estimateVolatility(TickAggregate tick, double underlyingPrice) {
        // Default volatility for Indian markets
        double defaultVol = 0.30; // 30% annualized
        
        if (tick.getHigh() > 0 && tick.getLow() > 0 && underlyingPrice > 0) {
            // Estimate from intraday range
            double range = tick.getHigh() - tick.getLow();
            double rangePercent = range / underlyingPrice;
            
            // Annualize: assume 1-minute window represents 1/390 of trading day (6.5 hours = 390 minutes)
            // Then scale to 252 trading days
            double intradayVol = rangePercent * Math.sqrt(390 * 252);
            
            // Clamp to reasonable range (10% to 100%)
            double estimatedVol = Math.max(0.10, Math.min(1.0, intradayVol));
            
            // Use average of default and estimated (more stable)
            return (defaultVol + estimatedVol) / 2.0;
        }
        
        return defaultVol;
    }

    /**
     * Check if within trading hours
     * FIX: MCX handles overnight trading (some commodities trade past midnight)
     * 
     * Trading Hours:
     * - NSE/BSE Cash: 09:15 - 15:30
     * - NSE/BSE F&O: 09:15 - 15:30
     * - MCX: 09:00 - 23:30 (some commodities 23:55)
     * - MCX Overnight: Can extend past midnight for certain contracts
     */
    private boolean withinTradingHours(TickData tick) {
        try {
            long ts = tick.getTimestamp();
            if (ts <= 0) return false;

            ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.of("Asia/Kolkata"));
            LocalTime t = zdt.toLocalTime();
            String exch = tick.getExchange();

            if ("N".equalsIgnoreCase(exch)) {
                // NSE: 09:15 - 15:30
                return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
            } else if ("M".equalsIgnoreCase(exch)) {
                // MCX: 09:00 - 23:55, OR overnight session (00:00 - 02:30 for some commodities)
                // Session 1: 09:00 - 23:55
                // Session 2 (overnight): 00:00 - 02:30 (next day continuation)
                boolean inDaySession = !t.isBefore(LocalTime.of(9, 0)) && !t.isAfter(LocalTime.of(23, 55));
                boolean inOvernightSession = !t.isBefore(LocalTime.of(0, 0)) && !t.isAfter(LocalTime.of(2, 30));
                return inDaySession || inOvernightSession;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Log candle for debugging
     */
    private void logCandle(InstrumentCandle candle) {
        if (candle == null) return;
        if (log.isDebugEnabled()) {
            log.debug("📊 {} {} OHLC={}/{}/{}/{} vol={} hasOB={} hasOI={}", 
                candle.getInstrumentType(),
                candle.getCompanyName(),
                candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(),
                candle.getVolume(),
                candle.hasOrderbook(),
                candle.hasOI()
            );
        }
    }

    /**
     * Setup exception handling
     */
    private void setupExceptionHandling() {
        streams.setStateListener((newState, oldState) -> {
            log.info("UnifiedInstrumentCandleProcessor state: {} -> {}", oldState, newState);
            if (newState == KafkaStreams.State.ERROR) {
                log.error("❌ Processor entered ERROR state!");
            }
        });

        streams.setUncaughtExceptionHandler(exception -> {
            log.error("Uncaught exception in processor", exception);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });
    }

    @PreDestroy
    public void stop() {
        if (streams != null) {
            log.info("🛑 Stopping UnifiedInstrumentCandleProcessor...");
            logVpinCacheStats();
            streams.close(Duration.ofSeconds(30));
            vpinCalculators.invalidateAll();  // Clear cache on shutdown
            vpinLastTradingDay.invalidateAll();  // Clear trading day tracking cache
            previousOICloseCache.invalidateAll();  // Clear previous OI cache on shutdown
            log.info("✅ UnifiedInstrumentCandleProcessor stopped");
        }
    }


    /**
     * Log VPIN cache statistics for monitoring
     */
    public void logVpinCacheStats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats stats = vpinCalculators.stats();
        log.info("VPIN Cache Stats: size={} hitRate={} evictionCount={} loadSuccessCount={}",
                vpinCalculators.estimatedSize(),
                String.format("%.2f%%", stats.hitRate() * 100),
                stats.evictionCount(),
                stats.loadSuccessCount());
    }

    /**
     * Helper to get average bid depth from orderbook aggregate
     */
    private Double getAverageBidDepth(OrderbookAggregate ob) {
        if (ob == null || ob.getTotalBidDepthCount() <= 0) return null;
        return ob.getTotalBidDepthSum() / ob.getTotalBidDepthCount();
    }

    /**
     * Helper to get average ask depth from orderbook aggregate
     */
    private Double getAverageAskDepth(OrderbookAggregate ob) {
        if (ob == null || ob.getTotalAskDepthCount() <= 0) return null;
        return ob.getTotalAskDepthSum() / ob.getTotalAskDepthCount();
    }

    // ========== HELPER CLASSES ==========
    // NOTE: TickAggregate extracted to infrastructure/kafka/aggregate/TickAggregate.java

    /**
     * Wrapper to carry TickData with its Kafka timestamp
     * Used for temporal tracking in Phase 2
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TickWithTimestamp {
        public TickData tick;
        public long kafkaTimestamp;

        public static org.apache.kafka.common.serialization.Serde<TickWithTimestamp> serde() {
            return new org.springframework.kafka.support.serializer.JsonSerde<>(TickWithTimestamp.class);
        }
    }

    /**
     * Intermediate join result
     */
    @lombok.AllArgsConstructor
    public static class TickWithOrderbook {
        public final TickAggregate tick;
        public final OrderbookAggregate orderbook;
    }

    /**
     * Full data after all joins
     */
    @lombok.AllArgsConstructor
    public static class InstrumentCandleData {
        public final TickAggregate tick;
        public final OrderbookAggregate orderbook;
        public final OIAggregate oi;
        public final Boolean isOrderbookFallback;  // true if orderbook came from latest store (fallback)
        public final Boolean isOIFallback;         // true if OI came from latest store (always true currently)
    }
}
