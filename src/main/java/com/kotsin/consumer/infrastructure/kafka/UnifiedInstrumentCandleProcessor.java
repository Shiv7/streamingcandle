package com.kotsin.consumer.infrastructure.kafka;

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
import org.apache.kafka.streams.state.WindowStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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

    private KafkaStreams streams;
    private final Map<String, AdaptiveVPINCalculator> vpinCalculators = new ConcurrentHashMap<>();

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
        // ========== 1. CONSUME TICK DATA ==========
        KStream<String, TickData> ticks = builder.stream(
            tickTopic,
            Consumed.with(Serdes.String(), TickData.serde())
        )
        .filter((key, tick) -> tick != null && tick.getToken() > 0)
        .filter((key, tick) -> withinTradingHours(tick))
        .selectKey((key, tick) -> buildInstrumentKey(tick));

        // ========== 2. CONSUME ORDERBOOK DATA ==========
        KStream<String, OrderBookSnapshot> orderbooks = builder.stream(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
        )
        .filter((key, ob) -> ob != null && ob.isValid())
        .selectKey((key, ob) -> buildOrderbookKey(ob));

        // ========== 3. CONSUME OI DATA ==========
        KStream<String, OpenInterest> ois = builder.stream(
            oiTopic,
            Consumed.with(Serdes.String(), OpenInterest.serde())
        )
        .filter((key, oi) -> oi != null && oi.getOpenInterest() != null)
        .selectKey((key, oi) -> buildOIKey(oi));

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
                (key, tick, agg) -> agg.update(tick),
                Materialized.<String, TickAggregate, WindowStore<Bytes, byte[]>>as("tick-aggregate-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(TickAggregate.serde())
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
            );

        // ========== 6. AGGREGATE OI ==========
        KTable<Windowed<String>, OIAggregate> oiAggregates = ois
            .groupByKey(Grouped.with(Serdes.String(), OpenInterest.serde()))
            .windowedBy(windows)
            .aggregate(
                OIAggregate::new,
                (key, oi, agg) -> {
                    agg.updateWithOI(oi);
                    return agg;
                },
                Materialized.<String, OIAggregate, WindowStore<Bytes, byte[]>>as("oi-aggregate-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(OIAggregate.serde())
            );

        // ========== 7. LEFT JOIN: TICK (mandatory) + ORDERBOOK (optional) ==========
        KTable<Windowed<String>, TickWithOrderbook> tickWithOb = tickCandles.leftJoin(
            obAggregates,
            (tick, ob) -> new TickWithOrderbook(tick, ob)
        );

        // ========== 8. LEFT JOIN: TICK+OB + OI (optional) ==========
        KTable<Windowed<String>, InstrumentCandleData> fullData = tickWithOb.leftJoin(
            oiAggregates,
            (tickOb, oi) -> new InstrumentCandleData(tickOb.tick, tickOb.orderbook, oi)
        );

        // ========== 9. EMIT ON WINDOW CLOSE ==========
        // Note: Instead of suppress() which requires custom Serdes for complex types,
        // we rely on the windowed aggregate's natural behavior with grace period
        fullData
            .toStream()
            .filter((windowedKey, data) -> data != null && data.tick != null)
            .mapValues((windowedKey, data) -> buildInstrumentCandle(windowedKey, data))
            .peek((windowedKey, candle) -> logCandle(candle))
            .map((windowedKey, candle) -> KeyValue.pair(windowedKey.key(), candle))
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
     */
    private String buildOrderbookKey(OrderBookSnapshot ob) {
        String exch = ob.getExchange() != null ? ob.getExchange() : "N";
        String exchType = ob.getExchangeType() != null ? ob.getExchangeType() : "C";
        return exch + ":" + exchType + ":" + ob.getToken();
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

        // Calculate adaptive VPIN
        double avgDailyVolume = instrumentMetadataService.getAverageDailyVolume(
            tick.getExchange(), tick.getExchangeType(), tick.getScripCode()
        );
        String vpinKey = tick.getScripCode();
        AdaptiveVPINCalculator vpinCalc = vpinCalculators.computeIfAbsent(
            vpinKey, k -> new AdaptiveVPINCalculator(avgDailyVolume)
        );
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
            builder.oiChange(oi.getOiChange());
            builder.oiChangePercent(oi.getOiChangePercent());
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
     */
    private boolean withinTradingHours(TickData tick) {
        try {
            long ts = tick.getTimestamp();
            if (ts <= 0) return false;

            ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.of("Asia/Kolkata"));
            LocalTime t = zdt.toLocalTime();
            String exch = tick.getExchange();

            if ("N".equalsIgnoreCase(exch)) {
                return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
            } else if ("M".equalsIgnoreCase(exch)) {
                return !t.isBefore(LocalTime.of(9, 0)) && !t.isAfter(LocalTime.of(23, 30));
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
            streams.close(Duration.ofSeconds(30));
            log.info("✅ UnifiedInstrumentCandleProcessor stopped");
        }
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

            // Classify buy/sell using bid/offer rates from TickData
            double askRate = tick.getOfferRate();
            double bidRate = tick.getBidRate();
            if (deltaVol > 0) {
                if (askRate > 0 && tick.getLastRate() >= askRate) {
                    buyVolume += deltaVol;
                } else if (bidRate > 0 && tick.getLastRate() <= bidRate) {
                    sellVolume += deltaVol;
                } else {
                    // Mid - split 50/50
                    buyVolume += deltaVol / 2;
                    sellVolume += deltaVol / 2;
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
