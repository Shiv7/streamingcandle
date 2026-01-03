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
            );

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
                Named.as("oi-lookup-transformer"), // Name the transformer
                "oi-latest-store"  // State store name for OI lookup
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
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"TYPE-DETECT\",\"location\":\"UnifiedInstrumentCandleProcessor.java:621\",\"message\":\"Instrument type detection\",\"data\":{\"scripCode\":\"%s\",\"exchange\":\"%s\",\"exchangeType\":\"%s\",\"companyName\":\"%s\",\"detectedType\":\"%s\",\"hasOI\":%s},\"timestamp\":%d}\n",
                tick.getScripCode(), tick.getExchange() != null ? tick.getExchange() : "null", tick.getExchangeType() != null ? tick.getExchangeType() : "null", tick.getCompanyName() != null ? tick.getCompanyName() : "null", type != null ? type.name() : "null", oi != null, System.currentTimeMillis());
            fw.write(json);
            fw.close();
        } catch (Exception e) {}
        // #endregion

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
        
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"VPIN-CALC\",\"location\":\"UnifiedInstrumentCandleProcessor.java:700\",\"message\":\"VPIN calculation\",\"data\":{\"scripCode\":\"%s\",\"buyVolume\":%d,\"sellVolume\":%d,\"vpinBefore\":%.6f,\"vpinAfter\":%.6f,\"vpinChange\":%.6f,\"bucketSize\":%.0f,\"bucketCount\":%d},\"timestamp\":%d}\n",
                tick.getScripCode(), buyVol, sellVol, vpinBefore, vpinAfter, vpinAfter - vpinBefore, vpinCalc.getBucketSize(), vpinCalc.getBucketCount(), System.currentTimeMillis());
            fw.write(json);
            fw.close();
        } catch (Exception e) {}
        // #endregion

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
                log.warn("[ICEBERG-DETECTED] {} | Bid: {} orders x {:.0f} avg | Ask: {} orders x {:.0f} avg",
                    tick.getScripCode(),
                    orderbook.getOrdersAtBestBid(),
                    orderbook.getAvgBidOrderSize(),
                    orderbook.getOrdersAtBestAsk(),
                    orderbook.getAvgAskOrderSize());
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
                log.warn("[RAPID-SPREAD-CHANGE] {} | Rate: {:.2f}/sec | Max: {} Min: {}",
                    tick.getScripCode(),
                    orderbook.getSpreadChangeRate(),
                    orderbook.getMaxSpread(),
                    orderbook.getMinSpread());
            }
        }

        // Add OI metrics if available
        builder.oiPresent(oi != null);
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"OI-A\",\"location\":\"UnifiedInstrumentCandleProcessor.java:692\",\"message\":\"OI join result\",\"data\":{\"scripCode\":\"%s\",\"oiNotNull\":%s,\"oiClose\":%s,\"oiPresentSet\":%s},\"timestamp\":%d}\n",
                tick.getScripCode(), oi != null, oi != null ? oi.getOiClose() : "null", oi != null, System.currentTimeMillis());
            fw.write(json);
            fw.close();
        } catch (Exception e) {}
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
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"OI-B\",\"location\":\"UnifiedInstrumentCandleProcessor.java:695\",\"message\":\"OI data set on InstrumentCandle\",\"data\":{\"scripCode\":\"%s\",\"openInterest\":%s,\"oiPresent\":true},\"timestamp\":%d}\n",
                    tick.getScripCode(), oi.getOiClose() != null ? oi.getOiClose() : "null", System.currentTimeMillis());
                fw.write(json);
                fw.close();
            } catch (Exception e) {}
            // #endregion
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
        }

        // Add option-specific fields
        if (type.isOption()) {
            parseOptionDetails(builder, tick.getCompanyName());
        }

        // ========== PHASE 1-4: CALCULATE AND POPULATE ENHANCED METRICS ==========

        // PHASE 2: Calculate temporal metrics
        tick.calculateTemporalMetrics();

        // PHASE 4: Calculate trade size distribution
        tick.calculateTradeSizeDistribution();

        // PHASE 1.2: Trade Classification
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"VOLUME-CLASSIFICATION\",\"location\":\"UnifiedInstrumentCandleProcessor.java:870\",\"message\":\"Volume classification summary\",\"data\":{\"scripCode\":\"%s\",\"volume\":%d,\"aggressiveBuyVolume\":%d,\"aggressiveSellVolume\":%d,\"midpointVolume\":%d,\"buyVolume\":%d,\"sellVolume\":%d,\"classificationReliability\":%.2f},\"timestamp\":%d}\n",
                tick.getScripCode(), tick.getVolume(), tick.getAggressiveBuyVolume(), tick.getAggressiveSellVolume(), tick.getMidpointVolume(), tick.getBuyVolume(), tick.getSellVolume(), tick.getClassificationReliability(), System.currentTimeMillis());
            fw.write(json);
            fw.close();
        } catch (Exception e) {}
        // #endregion
        
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

        // Flash crash detection
        if (tick.getTickAcceleration() > 100) {
            log.warn("[FLASH-CRASH-RISK] {} | Tick acceleration: {} | tps: {} → {}",
                tick.getScripCode(),
                tick.getTickAcceleration(),
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
            log.info("[BLOCK-TRADE] {} | Count: {} | Max size: {} (avg: {:.0f})",
                tick.getScripCode(),
                tick.getLargeTradeCount(),
                tick.getMaxTradeSize(),
                tick.getAvgTradeSize());
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
            log.info("[IMBALANCE-TRIGGER] {} | {} | VIB={} DIB={:.0f} TRB={} VRB={}",
                tick.getScripCode(),
                String.join(",", triggered),
                tick.getVolumeImbalance(),
                tick.getDollarImbalance(),
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
            log.info("[GAP-DETECT] {} | gap={:.2f}% prevClose={:.2f} open={:.2f} {}",
                tick.getScripCode(),
                gap,
                tick.getPreviousClose(),
                tick.getOpen(),
                gap > 0 ? "⬆ GAP_UP" : "⬇ GAP_DOWN");
        }

        // ========== P1: VWAP VALIDATION ==========
        builder.exchangeVwap(tick.getExchangeVwap());
        double vwapDrift = tick.getVwapDrift();
        builder.vwapDrift(vwapDrift);
        
        // Warn if VWAP drifts > 1% from exchange
        if (Math.abs(vwapDrift) > 1.0 && tick.getExchangeVwap() > 0) {
            log.warn("[VWAP-DRIFT] {} | calculated={:.2f} exchange={:.2f} drift={:.2f}%",
                tick.getScripCode(), tick.getVwap(), tick.getExchangeVwap(), vwapDrift);
        }

        // ========== P2: TICK INTENSITY ZONES ==========
        builder.maxTicksInAnySecond(tick.getMaxTicksInAnySecond());
        builder.secondsWithTicks(tick.getSecondsWithTicks());
        builder.tickBurstRatio(tick.getTickBurstRatio());
        builder.algoActivityDetected(tick.isAlgoActivityDetected());
        
        // Log algo detection
        if (tick.isAlgoActivityDetected()) {
            log.info("[ALGO-DETECTED] {} | maxTicks/sec={} burstRatio={:.2f} (threshold=3.0)",
                tick.getScripCode(), tick.getMaxTicksInAnySecond(), tick.getTickBurstRatio());
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

        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"STALE-TIMESTAMP\",\"location\":\"UnifiedInstrumentCandleProcessor.java:925\",\"message\":\"Staleness calculation\",\"data\":{\"scripCode\":\"%s\",\"currentTime\":%d,\"windowEndTime\":%d,\"tickLastTimestamp\":%d,\"tickAge\":%d,\"tickAgeFromWindow\":%d,\"isReplay\":%s},\"timestamp\":%d}\n",
                tick.getScripCode(), currentTime, windowEndTime, tick.getLastTickTimestamp(), tickAge, tickAgeFromWindow, (currentTime - windowEndTime > 60000), System.currentTimeMillis());
            fw.write(json);
            fw.close();
        } catch (Exception e) {}
        // #endregion

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
        
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"STALE-RESULT\",\"location\":\"UnifiedInstrumentCandleProcessor.java:967\",\"message\":\"Final staleness flags\",\"data\":{\"scripCode\":\"%s\",\"tickStale\":%s,\"orderbookStale\":%s,\"oiStale\":%s,\"maxDataAge\":%d,\"isReplay\":%s,\"stalenessReason\":\"%s\"},\"timestamp\":%d}\n",
                tick.getScripCode(), effectiveTickAge > 5000, orderbook != null && effectiveObAge > 5000, oi != null && effectiveOiAge > 300000, maxAge, isReplay, stalenessReason.toString().replace("\"", "\\\""), System.currentTimeMillis());
            fw.write(json);
            fw.close();
        } catch (Exception e) {}
        // #endregion

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

        // ========== COMPREHENSIVE VERIFICATION LOG - ALL 7 PHASES ==========
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"ALL-PHASES-VERIFY\",\"location\":\"UnifiedInstrumentCandleProcessor.java:994\",\"message\":\"COMPREHENSIVE VERIFICATION - ALL 7 PHASES\",\"data\":{" +
                // Phase 1: Trade Classification
                "\"phase1_tradeClassification\":{" +
                "\"aggressiveBuyVolume\":%d,\"aggressiveSellVolume\":%d,\"midpointVolume\":%d," +
                "\"classificationReliability\":%.4f,\"buyPressure\":%.4f,\"sellPressure\":%.4f," +
                "\"buyVolume\":%d,\"sellVolume\":%d,\"volume\":%d}," +
                // Phase 2: Temporal Tracking
                "\"phase2_temporalTracking\":{" +
                "\"firstTickTimestamp\":%d,\"lastTickTimestamp\":%d,\"minTickGap\":%d,\"maxTickGap\":%d," +
                "\"avgTickGap\":%.2f,\"ticksPerSecond\":%d,\"tickAcceleration\":%.2f,\"tickCount\":%d}," +
                // Phase 3: Orderbook Depth Fragmentation
                "\"phase3_orderbookDepth\":{" +
                "\"totalBidOrders\":%d,\"totalAskOrders\":%d,\"ordersAtBestBid\":%d,\"ordersAtBestAsk\":%d," +
                "\"avgBidOrderSize\":%.2f,\"avgAskOrderSize\":%.2f,\"depthConcentration\":%.4f," +
                "\"maxDepthLevels\":%d,\"icebergAtBestBid\":%s,\"icebergAtBestAsk\":%s}," +
                // Phase 4: Trade Size Distribution
                "\"phase4_tradeSize\":{" +
                "\"maxTradeSize\":%d,\"minTradeSize\":%d,\"avgTradeSize\":%.2f,\"medianTradeSize\":%.2f," +
                "\"largeTradeCount\":%d,\"priceImpactPerUnit\":%.6f}," +
                // Phase 5: OI Correlation
                "\"phase5_oiCorrelation\":{" +
                "\"priceAtOIUpdate\":%s,\"volumeAtOIUpdate\":%s,\"spreadAtOIUpdate\":%s," +
                "\"oiUpdateLatency\":%d,\"oiUpdateCount\":%d,\"oiChange\":%s,\"oiChangePercent\":%s}," +
                // Phase 6: Cross-Stream Latency
                "\"phase6_crossStreamLatency\":{" +
                "\"tickStale\":%s,\"orderbookStale\":%s,\"oiStale\":%s,\"maxDataAge\":%d," +
                "\"tickToOrderbookLatency\":%s,\"tickToOILatency\":%s,\"stalenessReason\":\"%s\"}," +
                // Phase 7: Orderbook Update Dynamics
                "\"phase7_orderbookDynamics\":{" +
                "\"spreadVolatility\":%s,\"maxSpread\":%s,\"minSpread\":%s,\"orderbookUpdateCount\":%d," +
                "\"spreadChangeRate\":%s,\"orderbookMomentum\":%s}," +
                // Metadata
                "\"metadata\":{" +
                "\"scripCode\":\"%s\",\"windowStartMillis\":%d,\"windowEndMillis\":%d," +
                "\"hasOrderbook\":%s,\"hasOI\":%s,\"vpin\":%.6f,\"vpinBucketCount\":%d," +
                "\"isReplay\":%s,\"quality\":\"%s\"}" +
                "},\"timestamp\":%d}\n",
                // Phase 1 values
                candle.getAggressiveBuyVolume() != null ? candle.getAggressiveBuyVolume() : 0,
                candle.getAggressiveSellVolume() != null ? candle.getAggressiveSellVolume() : 0,
                candle.getMidpointVolume() != null ? candle.getMidpointVolume() : 0,
                candle.getClassificationReliability() != null ? candle.getClassificationReliability() : 0.0,
                candle.getBuyPressure() != null ? candle.getBuyPressure() : 0.0,
                candle.getSellPressure() != null ? candle.getSellPressure() : 0.0,
                candle.getBuyVolume(),
                candle.getSellVolume(),
                candle.getVolume(),
                // Phase 2 values
                candle.getFirstTickTimestamp() != null ? candle.getFirstTickTimestamp().longValue() : 0L,
                candle.getLastTickTimestamp() != null ? candle.getLastTickTimestamp().longValue() : 0L,
                candle.getMinTickGap() != null ? candle.getMinTickGap().longValue() : 0L,
                candle.getMaxTickGap() != null ? candle.getMaxTickGap().longValue() : 0L,
                candle.getAvgTickGap() != null ? candle.getAvgTickGap() : 0.0,
                candle.getTicksPerSecond() != null ? candle.getTicksPerSecond().intValue() : 0,
                candle.getTickAcceleration() != null ? candle.getTickAcceleration() : 0.0,
                candle.getTickCount(),
                // Phase 3 values
                candle.getTotalBidOrders() != null ? candle.getTotalBidOrders() : 0,
                candle.getTotalAskOrders() != null ? candle.getTotalAskOrders() : 0,
                candle.getOrdersAtBestBid() != null ? candle.getOrdersAtBestBid() : 0,
                candle.getOrdersAtBestAsk() != null ? candle.getOrdersAtBestAsk() : 0,
                candle.getAvgBidOrderSize() != null ? candle.getAvgBidOrderSize() : 0.0,
                candle.getAvgAskOrderSize() != null ? candle.getAvgAskOrderSize() : 0.0,
                candle.getDepthConcentration() != null ? candle.getDepthConcentration() : 0.0,
                candle.getMaxDepthLevels() != null ? candle.getMaxDepthLevels() : 0,
                candle.getIcebergAtBestBid() != null ? candle.getIcebergAtBestBid() : false,
                candle.getIcebergAtBestAsk() != null ? candle.getIcebergAtBestAsk() : false,
                // Phase 4 values
                candle.getMaxTradeSize() != null ? candle.getMaxTradeSize() : 0,
                candle.getMinTradeSize() != null ? candle.getMinTradeSize() : 0,
                candle.getAvgTradeSize() != null ? candle.getAvgTradeSize() : 0.0,
                candle.getMedianTradeSize() != null ? candle.getMedianTradeSize() : 0.0,
                candle.getLargeTradeCount() != null ? candle.getLargeTradeCount() : 0,
                candle.getPriceImpactPerUnit() != null ? candle.getPriceImpactPerUnit() : 0.0,
                // Phase 5 values
                candle.getPriceAtOIUpdate() != null ? String.valueOf(candle.getPriceAtOIUpdate()) : "null",
                candle.getVolumeAtOIUpdate() != null ? String.valueOf(candle.getVolumeAtOIUpdate()) : "null",
                candle.getSpreadAtOIUpdate() != null ? String.valueOf(candle.getSpreadAtOIUpdate()) : "null",
                candle.getOiUpdateLatency() != null ? candle.getOiUpdateLatency() : 0,
                candle.getOiUpdateCount() != null ? candle.getOiUpdateCount() : 0,
                candle.getOiChange() != null ? String.valueOf(candle.getOiChange()) : "null",
                candle.getOiChangePercent() != null ? String.valueOf(candle.getOiChangePercent()) : "null",
                // Phase 6 values
                candle.getTickStale() != null ? candle.getTickStale() : false,
                candle.getOrderbookStale() != null ? candle.getOrderbookStale() : false,
                candle.getOiStale() != null ? candle.getOiStale() : false,
                candle.getMaxDataAge() != null ? candle.getMaxDataAge() : 0,
                candle.getTickToOrderbookLatency() != null ? String.valueOf(candle.getTickToOrderbookLatency()) : "null",
                candle.getTickToOILatency() != null ? String.valueOf(candle.getTickToOILatency()) : "null",
                candle.getStalenessReason() != null ? candle.getStalenessReason().replace("\"", "\\\"") : "",
                // Phase 7 values
                candle.getSpreadVolatility() != null ? String.valueOf(candle.getSpreadVolatility()) : "null",
                candle.getMaxSpread() != null ? String.valueOf(candle.getMaxSpread()) : "null",
                candle.getMinSpread() != null ? String.valueOf(candle.getMinSpread()) : "null",
                candle.getOrderbookUpdateCount() != null ? candle.getOrderbookUpdateCount() : 0,
                candle.getSpreadChangeRate() != null ? String.valueOf(candle.getSpreadChangeRate()) : "null",
                candle.getOrderbookMomentum() != null ? String.valueOf(candle.getOrderbookMomentum()) : "null",
                // Metadata
                candle.getScripCode() != null ? candle.getScripCode() : "unknown",
                candle.getWindowStartMillis(),
                candle.getWindowEndMillis(),
                candle.hasOrderbook(),
                candle.hasOI(),
                candle.getVpin(),
                candle.getVpinBucketCount(),
                isReplay,
                candle.getQuality() != null ? candle.getQuality().name() : "UNKNOWN"
            );
            fw.write(json);
            fw.close();
        } catch (Exception e) {
            log.error("Failed to write comprehensive verification log", e);
        }
        // #endregion

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
    }
}
