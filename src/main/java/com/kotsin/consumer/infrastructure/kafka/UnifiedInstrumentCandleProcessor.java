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
import com.kotsin.consumer.service.InstrumentMetadataService;
import com.kotsin.consumer.util.MarketTimeAligner;
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
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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

    @Autowired(required = false)
    private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;

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
                
                // Create internal topic for OI aggregation before building topology
                createInternalTopicIfNeeded(props.getProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG));
                
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
        .filter((key, tick) -> {
            boolean withinHours = withinTradingHours(tick);
            if (!withinHours && traceLogger != null) {
                java.util.Map<String, Object> indicators = new java.util.HashMap<>();
                indicators.put("timestamp", tick.getTimestamp());
                indicators.put("exchange", tick.getExchange());
                traceLogger.logSignalRejected("TICK", tick.getScripCode(),
                    tick.getTimestamp(), "Outside trading hours", indicators);
            }
            return withinHours;
        })
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
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(1),
            Duration.ofSeconds(graceSeconds)
        );

        KTable<Windowed<String>, TickAggregate> tickCandles = ticks
            .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
            .windowedBy(windows)
            .aggregate(
                TickAggregate::new,
                (key, tick, agg) -> {
                    if (log.isDebugEnabled() && tick != null && agg.getTickCount() == 0) {
                        // Log first tick in window to see the key
                        log.debug("[TICK-AGG] key={} token={} scripCode={} exch={} exchType={}", 
                            key, tick.getToken(), tick.getScripCode(), tick.getExchange(), 
                            tick.getExchangeType());
                    }
                    return agg.update(tick);
                },
                Materialized.<String, TickAggregate, WindowStore<Bytes, byte[]>>as("tick-aggregate-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(TickAggregate.serde())
                    .withRetention(Duration.ofDays(14))  // 2 weeks retention for replay scenarios
            );

        // ========== 5. AGGREGATE ORDERBOOK INTO OFI/LAMBDA ==========
        KTable<Windowed<String>, OrderbookAggregate> obAggregates = orderbooks
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
            );

        // ========== 6. AGGREGATE OI (KTABLE -> TOPIC -> GLOBAL KTABLE) ==========
        // FIX: Use GlobalKTable to fix partition/threading issue
        // Problem: KTable state stores are partitioned - each thread only sees its own partition
        // Solution: Aggregate OI into KTable, write to topic, then read as GlobalKTable
        // GlobalKTable replicates entire table to all threads, making OI accessible from any thread
        String oiAggregateTopic = "oi-aggregate-internal"; // Internal topic for OI aggregation
        
        // Step 1: Aggregate OI into KTable
        KTable<String, OIAggregate> oiAggregateTable = ois
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
                Materialized.<String, OIAggregate, KeyValueStore<Bytes, byte[]>>as("oi-aggregate-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(OIAggregate.serde())
            );
        
        // Step 2: Write aggregated OI to internal topic (changelog stream)
        oiAggregateTable.toStream().to(oiAggregateTopic, Produced.with(Serdes.String(), OIAggregate.serde()));
        
        // Step 3: Read as GlobalKTable - this replicates to all threads
        GlobalKTable<String, OIAggregate> oiLatestTable = builder.globalTable(
            oiAggregateTopic,
            Consumed.with(Serdes.String(), OIAggregate.serde()),
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
                    
                    @Override
                    public void init(org.apache.kafka.streams.processor.ProcessorContext context) {
                        this.context = context;
                        // Get state store - handle both ValueAndTimestamp<V> and direct V
                        this.oiStore = (org.apache.kafka.streams.state.ReadOnlyKeyValueStore<String, ?>) context.getStateStore("oi-latest-store");
                    }
                    
                    @Override
                    public InstrumentCandleData transform(Windowed<String> windowedKey, TickWithOrderbook tickOb) {
                        OIAggregate oi = null;
                        
                        if (tickOb != null && tickOb.tick != null && oiStore != null) {
                            // Build OI key from tick data
                            String tickExch = tickOb.tick.getExchange() != null ? tickOb.tick.getExchange() : "N";
                            String tickExchType = tickOb.tick.getExchangeType() != null ? tickOb.tick.getExchangeType() : "C";
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
                                        log.warn("[OI-LOOKUP-TYPE] {} | Unexpected store value type: {} | value={}", 
                                            oiKey, storeValue.getClass().getName(), storeValue);
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
                        
                        return new InstrumentCandleData(tickOb.tick, tickOb.orderbook, oi);
                    }
                    
                    @Override
                    public void close() {
                        // No cleanup needed
                    }
                },
                Named.as("oi-lookup-transformer") // Name the transformer
                // Note: GlobalKTable state store is automatically available, don't pass it explicitly
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
     * Build InstrumentCandle from aggregated data
     */
    private InstrumentCandle buildInstrumentCandle(Windowed<String> windowedKey, InstrumentCandleData data) {
        TickAggregate tick = data.tick;
        OrderbookAggregate orderbook = data.orderbook;
        OIAggregate oi = data.oi;

        // Determine instrument type
        InstrumentType type = InstrumentType.detect(
            tick.getExchange(), 
            tick.getExchangeType(), 
            tick.getCompanyName()
        );

        // Build base candle
        InstrumentCandle.InstrumentCandleBuilder builder = InstrumentCandle.builder()
            .scripCode(tick.getScripCode())
            .symbol(tick.getScripCode())  // Use scripCode as symbol
            .companyName(tick.getCompanyName())
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
        
        // Get or create VPIN calculator
        AdaptiveVPINCalculator vpinCalc = vpinCalculators.get(vpinKey, k -> new AdaptiveVPINCalculator(avgDailyVolume));
        
        // Reset VPIN if we've crossed into a new trading day (market open)
        LocalDate lastTradingDay = vpinLastTradingDay.getIfPresent(vpinKey);
        if (lastTradingDay != null && !lastTradingDay.equals(currentTradingDay)) {
            // New trading day detected - reset VPIN to prevent cross-day contamination
            log.debug("VPIN reset for {}: {} -> {}", vpinKey, lastTradingDay, currentTradingDay);
            vpinCalc.reset();
        }
        vpinLastTradingDay.put(vpinKey, currentTradingDay);
        
        // Update VPIN with candle data
        vpinCalc.updateFromCandle(tick.getBuyVolume(), tick.getSellVolume());
        builder.vpin(vpinCalc.calculate());
        builder.vpinBucketSize(vpinCalc.getBucketSize());
        builder.vpinBucketCount(vpinCalc.getBucketCount());

        // Add orderbook metrics if available
        builder.orderbookPresent(orderbook != null);
        if (orderbook != null) {
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
            // Iceberg detection would need separate fields
        }

        // Add OI metrics if available
        builder.oiPresent(oi != null);
        if (oi != null) {
            oi.calculateDerivedMetrics();
            builder.openInterest(oi.getOiClose());
            builder.oiOpen(oi.getOiOpen());
            builder.oiHigh(oi.getOiHigh());
            builder.oiLow(oi.getOiLow());
            builder.oiClose(oi.getOiClose());
            
            // Calculate OI change from PREVIOUS window (not within current window)
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
                // First window or no previous OI - use within-window change
                builder.oiChange(oi.getOiChange());
                builder.oiChangePercent(oi.getOiChangePercent());
            }
            
            // Update cache with current OI close for next window
            if (oi.getOiClose() != null) {
                previousOICloseCache.put(oiKey, oi.getOiClose());
            }
        }

        // Add option-specific fields
        if (type.isOption()) {
            parseOptionDetails(builder, tick.getCompanyName());
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
     * Parse option details from company name
     * e.g., "UNOMINDA 30 DEC 2025 CE 1280.00"
     */
    private void parseOptionDetails(InstrumentCandle.InstrumentCandleBuilder builder, String companyName) {
        if (companyName == null) return;

        String[] parts = companyName.split("\\s+");
        if (parts.length >= 5) {
            // Try to extract strike price (last part)
            try {
                String lastPart = parts[parts.length - 1];
                builder.strikePrice(Double.parseDouble(lastPart));
            } catch (NumberFormatException e) {
                // Ignore
            }

            // Extract option type
            for (String part : parts) {
                if (part.equalsIgnoreCase("CE")) {
                    builder.optionType("CE");
                    break;
                } else if (part.equalsIgnoreCase("PE")) {
                    builder.optionType("PE");
                    break;
                }
            }

            // Extract expiry (3 parts: DD MON YYYY)
            if (parts.length >= 4) {
                builder.expiry(parts[1] + " " + parts[2] + " " + parts[3]);
            }
        }
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
     * Create internal topic for OI aggregation if it doesn't exist
     */
    private void createInternalTopicIfNeeded(String bootstrapServers) {
        String topicName = "oi-aggregate-internal";
        int partitions = 6; // Match OpenInterest topic partitions
        short replicationFactor = 1;
        
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            // Check if topic exists
            boolean topicExists = adminClient.listTopics().names().get().contains(topicName);
            
            if (!topicExists) {
                log.info("Creating internal topic: {} with {} partitions", topicName, partitions);
                NewTopic newTopic = new NewTopic(topicName, partitions, replicationFactor);
                newTopic.configs(Collections.singletonMap(TopicConfig.RETENTION_MS_CONFIG, "86400000")); // 1 day retention
                
                adminClient.createTopics(Collections.singleton(newTopic)).all().get();
                log.info("✅ Successfully created internal topic: {}", topicName);
            } else {
                log.debug("Internal topic {} already exists", topicName);
            }
        } catch (InterruptedException | ExecutionException e) {
            log.warn("Failed to create internal topic {}: {}. Will try to use existing topic.", topicName, e.getMessage());
            // Don't fail startup - topic might be created externally or already exist
        } catch (Exception e) {
            log.error("Unexpected error creating internal topic {}: {}", topicName, e.getMessage(), e);
            // Don't fail startup - topic might be created externally
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

    // ========== INNER CLASSES ==========

    /**
     * Tick aggregate for windowed processing
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class TickAggregate {
        private String scripCode;
        private String symbol;
        private String companyName;
        private String exchange;
        private String exchangeType;
        private double open;
        private double high;
        private double low;
        private double close;
        private long volume;
        private long buyVolume;
        private long sellVolume;
        private double vwap;
        private int tickCount;
        private double totalValue;

        public TickAggregate update(TickData tick) {
            if (tick == null) return this;

            if (tickCount == 0) {
                // First tick
                scripCode = tick.getScripCode();
                symbol = tick.getScripCode();  // Use scripCode as symbol (TickData has no getSymbol)
                companyName = tick.getCompanyName();
                exchange = tick.getExchange();
                exchangeType = tick.getExchangeType();
                open = tick.getLastRate();
                high = tick.getLastRate();
                low = tick.getLastRate();
            }

            close = tick.getLastRate();
            high = Math.max(high, tick.getLastRate());
            low = Math.min(low, tick.getLastRate());

            // FIX: Use lastQuantity as primary volume source (last trade quantity)
            // deltaVolume is only set by external delta computation (which may not exist)
            long deltaVol = 0;
            
            // Priority 1: Use computed deltaVolume if available
            if (tick.getDeltaVolume() != null && tick.getDeltaVolume() > 0) {
                deltaVol = tick.getDeltaVolume();
            } 
            // Priority 2: Use lastQuantity (last trade quantity from exchange)
            else if (tick.getLastQuantity() > 0) {
                deltaVol = tick.getLastQuantity();
            }
            // Priority 3: If no volume data available, at least count the tick
            // (volume stays 0 but tickCount increments)
            
            volume += deltaVol;
            totalValue += deltaVol * tick.getLastRate();

            // REMOVED: Buy/Sell classification using stale BBO from TickData
            // Problem: Bid/Ask in TickData may be from different timestamp than trade
            // Without quote-stamped trade data, classification is unreliable
            // Solution: Split volume 50/50 (neutral assumption) for VPIN calculation
            // Note: This makes VPIN less accurate, but avoids misleading classifications
            if (deltaVol > 0) {
                // Simple 50/50 split - no classification based on stale BBO
                    buyVolume += deltaVol / 2;
                    sellVolume += deltaVol / 2;
                // Handle odd volumes (add remainder to buyVolume)
                if (deltaVol % 2 == 1) {
                    buyVolume += 1;
                }
            }

            tickCount++;
            vwap = volume > 0 ? totalValue / volume : close;

            return this;
        }

        public static org.apache.kafka.common.serialization.Serde<TickAggregate> serde() {
            return new org.springframework.kafka.support.serializer.JsonSerde<>(TickAggregate.class);
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
    }
}
