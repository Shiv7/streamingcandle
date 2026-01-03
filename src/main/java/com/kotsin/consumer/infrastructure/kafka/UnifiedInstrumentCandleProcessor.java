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
            Double currentSpread = orderbook != null && orderbook.getBidAskSpread() != null ? 
                orderbook.getBidAskSpread() : null;
            
            // Update market context (price/volume/spread at join time)
            // Note: lastUpdateTimestamp is already set in updateWithOI() from the OI stream's receivedTimestamp
            // This tracks when OI was actually updated, not when we joined it
            oi.updateMarketContext(currentPrice, currentVolume, currentSpread);

            builder.openInterest(oi.getOiClose());
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter(".cursor/debug.log", true);
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

        // ========== PHASE 6: CROSS-STREAM LATENCY MEASUREMENT ==========
        long currentTime = System.currentTimeMillis();
        long tickAge = tick.getLastTickTimestamp() > 0 ?
            currentTime - tick.getLastTickTimestamp() : 0;
        long maxAge = tickAge;

        builder.tickStale(tickAge > 5000);  // 5 seconds threshold

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
            builder.orderbookStale(obAge > 5000);
            maxAge = Math.max(maxAge, obAge);
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
            builder.oiStale(oiAge > 300000);  // 5 minutes threshold
            maxAge = Math.max(maxAge, oiAge);
        }

        builder.maxDataAge(maxAge);

        // Determine staleness reason
        StringBuilder stalenessReason = new StringBuilder();
        if (tickAge > 5000) {
            stalenessReason.append("Tick stale (").append(tickAge).append("ms); ");
        }
        if (orderbook != null && Boolean.TRUE.equals(builder.build().getOrderbookStale())) {
            long obAge = orderbook.getWindowEndMillis() > 0 ?
                currentTime - orderbook.getWindowEndMillis() : 0;
            stalenessReason.append("Orderbook stale (").append(obAge).append("ms); ");
        }
        if (oi != null && Boolean.TRUE.equals(builder.build().getOiStale())) {
            long oiAge = oi.getWindowEndMillis() > 0 ?
                currentTime - oi.getWindowEndMillis() : 0;
            stalenessReason.append("OI stale (").append(oiAge).append("ms); ");
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

    // ========== INNER CLASSES ==========

    /**
     * Enhanced Tick aggregate for windowed processing
     *
     * PHASE 1-4 ENHANCEMENTS:
     * - Accurate volume delta calculation with TotalQty reset detection
     * - Lee-Ready trade classification using TickData BBO
     * - Complete temporal tracking (tick velocity, gaps, acceleration)
     * - Trade-level history (last 100 trades with full context)
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class TickAggregate {
        // ==================== BASIC OHLCV ====================
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

        // ==================== VOLUME DELTA TRACKING (Phase 1.1) ====================
        private long previousTotalQty = 0;  // Track previous TotalQty for delta calculation

        // ==================== TRADE CLASSIFICATION (Phase 1.2) ====================
        private long aggressiveBuyVolume = 0;    // Trades at/above ask
        private long aggressiveSellVolume = 0;   // Trades at/below bid
        private long midpointVolume = 0;         // Trades between bid/ask
        private int classifiedTradeCount = 0;    // Trades with valid BBO
        private int unclassifiedTradeCount = 0;  // Trades without BBO
        private double previousTradePrice = 0.0; // For tick rule
        private String previousTradeClassification = null;

        // ==================== TEMPORAL TRACKING (Phase 2) ====================
        private long firstTickTimestamp;        // Kafka timestamp of first tick
        private long lastTickTimestamp;         // Kafka timestamp of last tick
        private long firstTickEventTime;        // TickDt of first tick
        private long lastTickEventTime;         // TickDt of last tick
        private transient java.util.List<Long> tickTimestamps;  // Kafka timestamps (last 100)
        private long minTickGap = Long.MAX_VALUE;
        private long maxTickGap = 0;
        private double avgTickGap = 0.0;
        private int ticksPerSecond = 0;
        private double tickAcceleration = 0.0;
        private long previousTickTimestamp = 0;
        private int previousTicksPerSecond = 0;

        // ==================== TRADE-LEVEL TRACKING (Phase 4) ====================
        private transient java.util.List<TradeInfo> tradeHistory;  // Last 100 trades
        private static final int MAX_TRADE_HISTORY = 100;
        private long maxTradeSize = 0;
        private long minTradeSize = Long.MAX_VALUE;
        private double avgTradeSize = 0.0;
        private double medianTradeSize = 0.0;
        private int largeTradeCount = 0;
        private double priceImpactPerUnit = 0.0;

        /**
         * Enhanced update method with all Phase 1-4 features
         *
         * @param tick TickData from Kafka
         * @param kafkaTimestamp Kafka record timestamp
         * @return this (for chaining)
         */
        public TickAggregate update(TickData tick, long kafkaTimestamp) {
            if (tick == null) return this;

            // ========== INITIALIZATION (First tick) ==========
            if (tickCount == 0) {
                scripCode = tick.getScripCode();
                symbol = tick.getScripCode();
                companyName = tick.getCompanyName();
                exchange = tick.getExchange();
                exchangeType = tick.getExchangeType();
                open = tick.getLastRate();
                high = tick.getLastRate();
                low = tick.getLastRate();

                // Initialize temporal tracking
                firstTickTimestamp = kafkaTimestamp;
                firstTickEventTime = tick.getTimestamp();
                tickTimestamps = new java.util.ArrayList<>(100);

                // Initialize trade history
                tradeHistory = new java.util.ArrayList<>(MAX_TRADE_HISTORY);
            }

            // ========== UPDATE OHLC ==========
            close = tick.getLastRate();
            high = Math.max(high, tick.getLastRate());
            low = Math.min(low, tick.getLastRate());

            // ========== PHASE 1.1: VOLUME DELTA CALCULATION ==========
            long currentTotalQty = tick.getTotalQuantity();
            long deltaVol = 0;

            // Detect volume reset (new day or feed restart)
            if (currentTotalQty < previousTotalQty && previousTotalQty > 0) {
                // Reset detected - use LastQty as delta
                deltaVol = tick.getLastQuantity();
                log.info("[VOLUME-RESET] {} | TotalQty reset: {} -> {} | Using LastQty: {}",
                    tick.getScripCode(), previousTotalQty, currentTotalQty, deltaVol);
            }
            // Normal case: calculate delta
            else if (previousTotalQty > 0) {
                deltaVol = currentTotalQty - previousTotalQty;

                // Sanity check: delta should not exceed LastQty by much
                if (deltaVol > tick.getLastQuantity() * 10 && tick.getLastQuantity() > 0) {
                    log.warn("[VOLUME-ANOMALY] {} | Delta {} >> LastQty {} | Using LastQty",
                        tick.getScripCode(), deltaVol, tick.getLastQuantity());
                    deltaVol = tick.getLastQuantity();
                }
            }
            // First tick in window
            else {
                deltaVol = tick.getLastQuantity();
            }

            // Fallback: if delta is still 0, use LastQty
            if (deltaVol == 0 && tick.getLastQuantity() > 0) {
                deltaVol = tick.getLastQuantity();
            }

            // Update previous for next iteration
            previousTotalQty = currentTotalQty;

            // Update volume and value
            volume += deltaVol;
            totalValue += deltaVol * tick.getLastRate();

            // ========== PHASE 1.2: TRADE CLASSIFICATION ==========
            String classification = classifyTrade(tick, deltaVol);

            // ========== PHASE 2: TEMPORAL TRACKING ==========
            lastTickTimestamp = kafkaTimestamp;
            lastTickEventTime = tick.getTimestamp();

            // Store timestamp
            tickTimestamps.add(kafkaTimestamp);
            if (tickTimestamps.size() > 100) {
                tickTimestamps.remove(0);
            }

            // Calculate gap from previous tick
            if (previousTickTimestamp > 0) {
                long gap = kafkaTimestamp - previousTickTimestamp;
                minTickGap = Math.min(minTickGap, gap);
                maxTickGap = Math.max(maxTickGap, gap);
            }
            previousTickTimestamp = kafkaTimestamp;

            // ========== PHASE 4: STORE TRADE INFO ==========
            if (deltaVol > 0) {
                TradeInfo trade = new TradeInfo(
                    kafkaTimestamp,
                    tick.getTimestamp(),
                    tick.getLastRate(),
                    deltaVol,
                    classification,
                    tick.getBidRate(),
                    tick.getOffRate()
                );

                tradeHistory.add(trade);

                // Keep only last 100 trades
                if (tradeHistory.size() > MAX_TRADE_HISTORY) {
                    tradeHistory.remove(0);
                }

                // Update size tracking
                maxTradeSize = Math.max(maxTradeSize, deltaVol);
                minTradeSize = Math.min(minTradeSize, deltaVol);
            }

            tickCount++;
            vwap = volume > 0 ? totalValue / volume : close;

            return this;
        }

        /**
         * PHASE 1.2: Classify trade using TickData BBO (Lee-Ready algorithm)
         *
         * Classification Rules:
         * 1. If trade >= ask: Aggressive BUY
         * 2. If trade <= bid: Aggressive SELL
         * 3. If bid < trade < ask: MIDPOINT → use tick rule
         * 4. Tick Rule: Compare with previous trade price
         *
         * @return classification string
         */
        private String classifyTrade(TickData tick, long deltaVol) {
            if (deltaVol <= 0) return null;

            double tradePrice = tick.getLastRate();
            double bidPrice = tick.getBidRate();
            double askPrice = tick.getOffRate();

            // Check if BBO is valid
            boolean bboValid = bidPrice > 0 && askPrice > 0 && askPrice > bidPrice;

            if (!bboValid) {
                // No valid BBO - use previous classification if available
                if (previousTradeClassification != null) {
                    applyClassification(previousTradeClassification, deltaVol);
                    unclassifiedTradeCount++;
                    return previousTradeClassification;
                } else {
                    // Truly unknown - split 50/50
                    buyVolume += deltaVol / 2;
                    sellVolume += deltaVol / 2;
                    if (deltaVol % 2 == 1) buyVolume += 1;
                    unclassifiedTradeCount++;
                    return "UNKNOWN";
                }
            }

            // ========== Lee-Ready Classification ==========
            String classification;

            // Rule 1: Trade at/above ask = Aggressive BUY
            if (tradePrice >= askPrice) {
                classification = "AGGRESSIVE_BUY";
            }
            // Rule 2: Trade at/below bid = Aggressive SELL
            else if (tradePrice <= bidPrice) {
                classification = "AGGRESSIVE_SELL";
            }
            // Rule 3: Midpoint trade - use tick rule
            else {
                classification = applyTickRule(tradePrice, deltaVol);
            }

            applyClassification(classification, deltaVol);
            previousTradeClassification = classification;
            previousTradePrice = tradePrice;
            classifiedTradeCount++;

            if (log.isDebugEnabled()) {
                log.debug("[TRADE-CLASSIFY] {} | {} | price={} bid={} ask={} | vol={}",
                    tick.getScripCode(), classification, tradePrice, bidPrice, askPrice, deltaVol);
            }

            return classification;
        }

        /**
         * Apply tick rule for midpoint trades
         */
        private String applyTickRule(double currentPrice, long deltaVol) {
            if (previousTradePrice <= 0) {
                // First trade - cannot use tick rule
                midpointVolume += deltaVol;
                return "MIDPOINT";
            }

            // Compare with previous trade price
            if (currentPrice > previousTradePrice) {
                return "AGGRESSIVE_BUY";  // Uptick
            } else if (currentPrice < previousTradePrice) {
                return "AGGRESSIVE_SELL";  // Downtick
            } else {
                // Zero tick: use previous classification
                if (previousTradeClassification != null &&
                    !previousTradeClassification.equals("MIDPOINT")) {
                    return previousTradeClassification;
                } else {
                    midpointVolume += deltaVol;
                    return "MIDPOINT";
                }
            }
        }

        /**
         * Apply classification to volumes
         */
        private void applyClassification(String classification, long deltaVol) {
            switch (classification) {
                case "AGGRESSIVE_BUY":
                    aggressiveBuyVolume += deltaVol;
                    buyVolume += deltaVol;
                    break;
                case "AGGRESSIVE_SELL":
                    aggressiveSellVolume += deltaVol;
                    sellVolume += deltaVol;
                    break;
                case "MIDPOINT":
                case "UNKNOWN":
                default:
                    // Split 50/50 for midpoint/unknown trades
                    buyVolume += deltaVol / 2;
                    sellVolume += deltaVol / 2;
                    if (deltaVol % 2 == 1) buyVolume += 1;
                    midpointVolume += deltaVol;
                    break;
            }
        }

        /**
         * PHASE 2: Calculate temporal metrics (call at end of window)
         */
        public void calculateTemporalMetrics() {
            // Calculate average tick gap
            if (tickTimestamps != null && tickTimestamps.size() >= 2) {
                long totalGaps = 0;
                for (int i = 1; i < tickTimestamps.size(); i++) {
                    totalGaps += tickTimestamps.get(i) - tickTimestamps.get(i - 1);
                }
                avgTickGap = (double) totalGaps / (tickTimestamps.size() - 1);
            }

            // Calculate ticks per second
            long windowDuration = lastTickTimestamp - firstTickTimestamp;
            if (windowDuration > 0) {
                ticksPerSecond = (int) ((long) tickCount * 1000 / windowDuration);
            }

            // Calculate tick acceleration
            if (previousTicksPerSecond > 0) {
                tickAcceleration = ticksPerSecond - previousTicksPerSecond;
            }
            previousTicksPerSecond = ticksPerSecond;
        }

        /**
         * PHASE 4: Calculate trade size distribution (call at end of window)
         */
        public void calculateTradeSizeDistribution() {
            if (tradeHistory == null || tradeHistory.isEmpty()) return;

            // Calculate average trade size
            long totalVolume = tradeHistory.stream()
                .mapToLong(t -> t.quantity)
                .sum();
            avgTradeSize = (double) totalVolume / tradeHistory.size();

            // Calculate median trade size
            java.util.List<Long> sizes = tradeHistory.stream()
                .map(t -> t.quantity)
                .sorted()
                .collect(java.util.stream.Collectors.toList());

            int mid = sizes.size() / 2;
            if (sizes.size() % 2 == 0) {
                medianTradeSize = (sizes.get(mid - 1) + sizes.get(mid)) / 2.0;
            } else {
                medianTradeSize = sizes.get(mid);
            }

            // Count large trades (> 10x average)
            largeTradeCount = (int) tradeHistory.stream()
                .filter(t -> t.quantity > avgTradeSize * 10)
                .count();

            // Calculate price impact per unit volume
            if (totalVolume > 0) {
                double priceChange = close - open;
                priceImpactPerUnit = priceChange / totalVolume * 1000000;  // Per million units
            }
        }

        // ========== GETTERS ==========

        public double getClassificationReliability() {
            int total = classifiedTradeCount + unclassifiedTradeCount;
            return total > 0 ? (double) classifiedTradeCount / total : 0.0;
        }

        public long getAggressiveBuyVolume() { return aggressiveBuyVolume; }
        public long getAggressiveSellVolume() { return aggressiveSellVolume; }
        public long getMidpointVolume() { return midpointVolume; }

        public long getFirstTickTimestamp() { return firstTickTimestamp; }
        public long getLastTickTimestamp() { return lastTickTimestamp; }
        public long getFirstTickEventTime() { return firstTickEventTime; }
        public long getLastTickEventTime() { return lastTickEventTime; }
        public long getMinTickGap() { return minTickGap == Long.MAX_VALUE ? 0 : minTickGap; }
        public long getMaxTickGap() { return maxTickGap; }
        public double getAvgTickGap() { return avgTickGap; }
        public int getTicksPerSecond() { return ticksPerSecond; }
        public double getTickAcceleration() { return tickAcceleration; }

        public long getMaxTradeSize() { return maxTradeSize; }
        public long getMinTradeSize() { return minTradeSize == Long.MAX_VALUE ? 0 : minTradeSize; }
        public double getAvgTradeSize() { return avgTradeSize; }
        public double getMedianTradeSize() { return medianTradeSize; }
        public int getLargeTradeCount() { return largeTradeCount; }
        public double getPriceImpactPerUnit() { return priceImpactPerUnit; }
        public java.util.List<TradeInfo> getTradeHistory() {
            return tradeHistory != null ? new java.util.ArrayList<>(tradeHistory) : new java.util.ArrayList<>();
        }

        /**
         * Trade information holder (Phase 4)
         */
        @lombok.Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class TradeInfo {
            public long kafkaTimestamp;
            public long eventTimestamp;
            public double price;
            public long quantity;
            public String classification;
            public double bidPrice;
            public double askPrice;
        }

        public static org.apache.kafka.common.serialization.Serde<TickAggregate> serde() {
            return new org.springframework.kafka.support.serializer.JsonSerde<>(TickAggregate.class);
        }
    }

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
