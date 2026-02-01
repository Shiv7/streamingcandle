package com.kotsin.consumer.service;

import com.kotsin.consumer.model.*;
import com.kotsin.consumer.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CandleService - Query layer for unified candle data.
 *
 * This is the main interface for strategies to fetch candle data.
 *
 * Responsibilities:
 * 1. Merge TickCandle + OrderbookMetrics + OIMetrics into UnifiedCandle
 * 2. Aggregate 1m candles to higher timeframes (5m, 15m, 30m, etc.)
 * 3. Calculate OI interpretation with price data
 * 4. Use Redis cache first, fallback to MongoDB
 *
 * Key Design:
 * - QUERY-TIME JOIN: No pre-joined data; merge at query time
 * - ON-DEMAND AGGREGATION: Higher timeframes computed from 1m candles
 * - CACHE-FIRST: Redis for hot data, MongoDB for historical
 * - EXPLICIT DATA AVAILABILITY: UnifiedCandle.hasOrderbook, hasOI flags
 */
@Service
@Slf4j
public class CandleService {

    @Autowired
    private TickCandleRepository tickCandleRepository;

    @Autowired
    private OrderbookMetricsRepository orderbookMetricsRepository;

    @Autowired
    private OIMetricsRepository oiMetricsRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    // Thresholds for OI interpretation
    private static final double PRICE_CHANGE_THRESHOLD = 0.1;  // 0.1%
    private static final double OI_CHANGE_THRESHOLD = 0.5;     // 0.5%

    // ==================== LATEST CANDLE ====================

    /**
     * Get latest unified candle for a symbol.
     *
     * @param symbol Symbol (e.g., "NIFTY", "RELIANCE")
     * @return Latest UnifiedCandle with all available data
     */
    public UnifiedCandle getLatestCandle(String symbol) {
        return getLatestCandle(symbol, Timeframe.M1);
    }

    /**
     * Get latest unified candle for a symbol at specific timeframe.
     *
     * For 1m: returns latest 1m candle
     * For higher: aggregates last N 1m candles where N = timeframe.getMinutes()
     *
     * @param symbol    Symbol
     * @param timeframe Desired timeframe
     * @return Latest UnifiedCandle for the timeframe
     */
    public UnifiedCandle getLatestCandle(String symbol, Timeframe timeframe) {
        if (timeframe == Timeframe.M1) {
            return getLatest1mCandle(symbol);
        }

        // For higher timeframes, aggregate from 1m candles
        return aggregateLatestToTimeframe(symbol, timeframe);
    }

    /**
     * Get latest 1m unified candle (merged from tick + orderbook + OI).
     */
    private UnifiedCandle getLatest1mCandle(String symbol) {
        // Try Redis cache first
        TickCandle tick = redisCacheService.getLatestTickCandle(symbol);

        if (tick == null) {
            // Fallback to MongoDB
            tick = tickCandleRepository.findTopBySymbolOrderByTimestampDesc(symbol)
                .orElse(null);
        }

        if (tick == null) {
            log.debug("[CANDLE-SVC] No tick data for {}", symbol);
            return null;
        }

        UnifiedCandle candle = UnifiedCandle.fromTick(tick, Timeframe.M1);

        // Merge orderbook metrics
        OrderbookMetrics ob = redisCacheService.getLatestOrderbookMetrics(symbol);
        if (ob == null) {
            ob = orderbookMetricsRepository.findTopBySymbolOrderByTimestampDesc(symbol)
                .orElse(null);
        }
        candle.withOrderbook(ob);

        // Merge OI metrics (for derivatives)
        if (tick.getInstrumentType() != null && tick.getInstrumentType().isDerivative()) {
            OIMetrics oi = redisCacheService.getLatestOIMetrics(symbol);
            if (oi == null) {
                oi = oiMetricsRepository.findTopBySymbolOrderByTimestampDesc(symbol)
                    .orElse(null);
            }

            if (oi != null) {
                // Calculate OI interpretation with price data
                oi = calculateOIInterpretation(oi, tick);
            }
            candle.withOI(oi);
        }

        return candle;
    }

    /**
     * Aggregate latest 1m candles to a higher timeframe.
     */
    private UnifiedCandle aggregateLatestToTimeframe(String symbol, Timeframe timeframe) {
        int candlesNeeded = timeframe.getMinutes();

        // Get 1m candle history from Redis
        List<TickCandle> ticks = redisCacheService.getTickHistory(symbol, candlesNeeded);

        if (ticks.isEmpty()) {
            // Fallback to MongoDB
            ticks = tickCandleRepository.findBySymbolOrderByTimestampDesc(
                symbol, PageRequest.of(0, candlesNeeded));
        }

        if (ticks.isEmpty()) {
            log.debug("[CANDLE-SVC] No tick data for {} timeframe {}", symbol, timeframe);
            return null;
        }

        // Sort by timestamp ascending for proper aggregation
        ticks.sort(Comparator.comparing(TickCandle::getTimestamp));

        // Aggregate ticks
        UnifiedCandle aggregated = aggregateTickCandles(ticks, timeframe);

        // Get aggregated orderbook and OI metrics
        if (!ticks.isEmpty()) {
            Instant windowStart = ticks.get(0).getWindowStart();
            Instant windowEnd = ticks.get(ticks.size() - 1).getWindowEnd();

            // Aggregate orderbook
            List<OrderbookMetrics> obs = orderbookMetricsRepository
                .findBySymbolAndTimestampBetween(symbol, windowStart, windowEnd);
            if (!obs.isEmpty()) {
                OrderbookMetrics aggregatedOb = aggregateOrderbookMetrics(obs);
                aggregated.withOrderbook(aggregatedOb);
            }

            // Aggregate OI (for derivatives)
            if (aggregated.isDerivative()) {
                List<OIMetrics> ois = oiMetricsRepository
                    .findBySymbolAndTimestampBetween(symbol, windowStart, windowEnd);
                if (!ois.isEmpty()) {
                    // Use the last OI value (OI is cumulative)
                    OIMetrics latestOi = ois.get(ois.size() - 1);

                    // Calculate price change over the window
                    double priceChange = ticks.isEmpty() ? 0 :
                        (ticks.get(ticks.size() - 1).getClose() - ticks.get(0).getOpen()) /
                        ticks.get(0).getOpen() * 100;

                    // Calculate OI interpretation
                    latestOi = calculateOIInterpretationWithPriceChange(latestOi, priceChange);
                    aggregated.withOI(latestOi);
                }
            }
        }

        return aggregated;
    }

    // ==================== CANDLE AT TIMESTAMP ====================

    /**
     * Get unified candle at specific timestamp.
     *
     * @param symbol    Symbol
     * @param timestamp Timestamp (will be aligned to window)
     * @param timeframe Desired timeframe
     * @return UnifiedCandle at the timestamp
     */
    public UnifiedCandle getCandle(String symbol, Instant timestamp, Timeframe timeframe) {
        Instant windowStart = timeframe.alignToWindowStart(timestamp);
        Instant windowEnd = timeframe.getWindowEnd(windowStart);

        if (timeframe == Timeframe.M1) {
            return get1mCandle(symbol, windowStart, windowEnd);
        }

        return getAggregatedCandle(symbol, windowStart, windowEnd, timeframe);
    }

    private UnifiedCandle get1mCandle(String symbol, Instant windowStart, Instant windowEnd) {
        // Get tick candle
        TickCandle tick = tickCandleRepository.findBySymbolAndTimestamp(symbol, windowEnd)
            .orElse(null);

        if (tick == null) {
            return null;
        }

        UnifiedCandle candle = UnifiedCandle.fromTick(tick, Timeframe.M1);

        // Merge orderbook
        orderbookMetricsRepository.findBySymbolAndTimestamp(symbol, windowEnd)
            .ifPresent(candle::withOrderbook);

        // Merge OI
        if (candle.isDerivative()) {
            OIMetrics oi = oiMetricsRepository.findBySymbolAndTimestamp(symbol, windowEnd)
                .orElse(null);
            if (oi != null) {
                oi = calculateOIInterpretation(oi, tick);
                candle.withOI(oi);
            }
        }

        return candle;
    }

    private UnifiedCandle getAggregatedCandle(String symbol, Instant windowStart,
                                               Instant windowEnd, Timeframe timeframe) {
        // Get all 1m candles in the window
        List<TickCandle> ticks = tickCandleRepository
            .findBySymbolAndTimestampBetween(symbol, windowStart, windowEnd);

        if (ticks.isEmpty()) {
            return null;
        }

        ticks.sort(Comparator.comparing(TickCandle::getTimestamp));

        UnifiedCandle aggregated = aggregateTickCandles(ticks, timeframe);

        // Aggregate orderbook
        List<OrderbookMetrics> obs = orderbookMetricsRepository
            .findBySymbolAndTimestampBetween(symbol, windowStart, windowEnd);
        if (!obs.isEmpty()) {
            aggregated.withOrderbook(aggregateOrderbookMetrics(obs));
        }

        // Aggregate OI
        if (aggregated.isDerivative()) {
            List<OIMetrics> ois = oiMetricsRepository
                .findBySymbolAndTimestampBetween(symbol, windowStart, windowEnd);
            if (!ois.isEmpty()) {
                OIMetrics latestOi = ois.get(ois.size() - 1);

                double priceChange = ticks.isEmpty() ? 0 :
                    (ticks.get(ticks.size() - 1).getClose() - ticks.get(0).getOpen()) /
                    ticks.get(0).getOpen() * 100;

                latestOi = calculateOIInterpretationWithPriceChange(latestOi, priceChange);
                aggregated.withOI(latestOi);
            }
        }

        return aggregated;
    }

    // ==================== CANDLE HISTORY ====================

    /**
     * Get candle history for a symbol.
     *
     * @param symbol    Symbol
     * @param timeframe Desired timeframe
     * @param count     Number of candles
     * @return List of UnifiedCandles (most recent first)
     */
    public List<UnifiedCandle> getCandleHistory(String symbol, Timeframe timeframe, int count) {
        if (timeframe == Timeframe.M1) {
            return get1mHistory(symbol, count);
        }

        return getAggregatedHistory(symbol, timeframe, count);
    }

    private List<UnifiedCandle> get1mHistory(String symbol, int count) {
        List<TickCandle> ticks = tickCandleRepository.findBySymbolOrderByTimestampDesc(
            symbol, PageRequest.of(0, count));

        if (ticks.isEmpty()) {
            return Collections.emptyList();
        }

        // Batch fetch orderbook and OI data
        Instant startTime = ticks.get(ticks.size() - 1).getWindowStart();
        Instant endTime = ticks.get(0).getWindowEnd();

        Map<Instant, OrderbookMetrics> obMap = orderbookMetricsRepository
            .findBySymbolAndTimestampBetween(symbol, startTime, endTime)
            .stream()
            .collect(Collectors.toMap(OrderbookMetrics::getTimestamp, m -> m, (a, b) -> b));

        Map<Instant, OIMetrics> oiMap = oiMetricsRepository
            .findBySymbolAndTimestampBetween(symbol, startTime, endTime)
            .stream()
            .collect(Collectors.toMap(OIMetrics::getTimestamp, m -> m, (a, b) -> b));

        List<UnifiedCandle> result = new ArrayList<>();

        for (TickCandle tick : ticks) {
            UnifiedCandle candle = UnifiedCandle.fromTick(tick, Timeframe.M1);

            OrderbookMetrics ob = obMap.get(tick.getTimestamp());
            candle.withOrderbook(ob);

            if (candle.isDerivative()) {
                OIMetrics oi = oiMap.get(tick.getTimestamp());
                if (oi != null) {
                    oi = calculateOIInterpretation(oi, tick);
                    candle.withOI(oi);
                }
            }

            result.add(candle);
        }

        return result;
    }

    private List<UnifiedCandle> getAggregatedHistory(String symbol, Timeframe timeframe, int count) {
        // Calculate how many 1m candles we need
        int candlesPerWindow = timeframe.getMinutes();
        int totalCandlesNeeded = count * candlesPerWindow;

        List<TickCandle> allTicks = tickCandleRepository.findBySymbolOrderByTimestampDesc(
            symbol, PageRequest.of(0, totalCandlesNeeded));

        if (allTicks.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort ascending
        allTicks.sort(Comparator.comparing(TickCandle::getTimestamp));

        // Group by timeframe window
        Map<Instant, List<TickCandle>> windowGroups = allTicks.stream()
            .collect(Collectors.groupingBy(t -> timeframe.alignToWindowStart(t.getWindowStart())));

        List<UnifiedCandle> result = new ArrayList<>();

        for (Map.Entry<Instant, List<TickCandle>> entry : windowGroups.entrySet()) {
            List<TickCandle> windowTicks = entry.getValue();
            windowTicks.sort(Comparator.comparing(TickCandle::getTimestamp));

            UnifiedCandle aggregated = aggregateTickCandles(windowTicks, timeframe);

            // Add orderbook and OI if available
            Instant windowStart = windowTicks.get(0).getWindowStart();
            Instant windowEnd = windowTicks.get(windowTicks.size() - 1).getWindowEnd();

            List<OrderbookMetrics> obs = orderbookMetricsRepository
                .findBySymbolAndTimestampBetween(symbol, windowStart, windowEnd);
            if (!obs.isEmpty()) {
                aggregated.withOrderbook(aggregateOrderbookMetrics(obs));
            }

            if (aggregated.isDerivative()) {
                List<OIMetrics> ois = oiMetricsRepository
                    .findBySymbolAndTimestampBetween(symbol, windowStart, windowEnd);
                if (!ois.isEmpty()) {
                    OIMetrics latestOi = ois.get(ois.size() - 1);
                    double priceChange = (windowTicks.get(windowTicks.size() - 1).getClose() -
                                          windowTicks.get(0).getOpen()) /
                                         windowTicks.get(0).getOpen() * 100;
                    latestOi = calculateOIInterpretationWithPriceChange(latestOi, priceChange);
                    aggregated.withOI(latestOi);
                }
            }

            result.add(aggregated);
        }

        // Sort by timestamp descending (most recent first)
        result.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));

        return result.stream().limit(count).collect(Collectors.toList());
    }

    // ==================== AGGREGATION LOGIC ====================

    /**
     * Aggregate multiple TickCandles into a single UnifiedCandle.
     */
    private UnifiedCandle aggregateTickCandles(List<TickCandle> ticks, Timeframe timeframe) {
        if (ticks.isEmpty()) {
            return null;
        }

        TickCandle first = ticks.get(0);
        TickCandle last = ticks.get(ticks.size() - 1);

        // OHLCV aggregation
        double open = first.getOpen();
        double high = ticks.stream().mapToDouble(TickCandle::getHigh).max().orElse(0);
        double low = ticks.stream().mapToDouble(TickCandle::getLow).min().orElse(0);
        double close = last.getClose();
        long volume = ticks.stream().mapToLong(TickCandle::getVolume).sum();
        double value = ticks.stream().mapToDouble(TickCandle::getValue).sum();
        double vwap = volume > 0 ? value / volume : close;

        // Trade classification aggregation
        long buyVolume = ticks.stream().mapToLong(TickCandle::getBuyVolume).sum();
        long sellVolume = ticks.stream().mapToLong(TickCandle::getSellVolume).sum();
        long volumeDelta = buyVolume - sellVolume;
        double buyPressure = volume > 0 ? (double) buyVolume / volume : 0.5;
        double sellPressure = volume > 0 ? (double) sellVolume / volume : 0.5;

        // VPIN (average across candles)
        double vpin = ticks.stream().mapToDouble(TickCandle::getVpin).average().orElse(0);

        // Volume profile (use last candle's POC, VAH, VAL for simplicity)
        double poc = last.getPoc();
        double vah = last.getVah();
        double val = last.getVal();

        // Imbalance (sum across candles)
        double volumeImbalance = ticks.stream()
            .mapToDouble(TickCandle::getVolumeImbalance).sum();
        boolean vibTriggered = ticks.stream().anyMatch(TickCandle::isVibTriggered);
        boolean dibTriggered = ticks.stream().anyMatch(TickCandle::isDibTriggered);

        // Stats
        int tickCount = ticks.stream().mapToInt(TickCandle::getTickCount).sum();
        int largeTradeCount = ticks.stream().mapToInt(TickCandle::getLargeTradeCount).sum();

        // Completeness
        int expectedCandleCount = timeframe.getMinutes();
        int actualCandleCount = ticks.size();
        double completenessRatio = (double) actualCandleCount / expectedCandleCount;

        return UnifiedCandle.builder()
            .symbol(first.getSymbol())
            .scripCode(first.getScripCode())
            .exchange(first.getExchange())
            .exchangeType(first.getExchangeType())
            .companyName(first.getCompanyName())
            .instrumentType(first.getInstrumentType())
            .timeframe(timeframe)
            .timestamp(last.getWindowEnd())
            .windowStart(first.getWindowStart())
            .windowEnd(last.getWindowEnd())
            // OHLCV
            .open(open)
            .high(high)
            .low(low)
            .close(close)
            .volume(volume)
            .value(value)
            .vwap(vwap)
            // Trade classification
            .buyVolume(buyVolume)
            .sellVolume(sellVolume)
            .volumeDelta(volumeDelta)
            .buyPressure(buyPressure)
            .sellPressure(sellPressure)
            // VPIN
            .vpin(vpin)
            // Volume profile
            .poc(poc)
            .vah(vah)
            .val(val)
            // Imbalance
            .volumeImbalance(volumeImbalance)
            .vibTriggered(vibTriggered)
            .dibTriggered(dibTriggered)
            // Stats
            .tickCount(tickCount)
            .largeTradeCount(largeTradeCount)
            // Options
            .strikePrice(first.getStrikePrice())
            .optionType(first.getOptionType())
            .expiry(first.getExpiry())
            .daysToExpiry(last.getDaysToExpiry())
            // Quality
            .quality("VALID")
            .tickStaleness(last.getProcessingLatencyMs())
            // Flags
            .hasOrderbook(false)
            .hasOI(false)
            // Aggregation
            .aggregatedCandleCount(actualCandleCount)
            .expectedCandleCount(expectedCandleCount)
            .completenessRatio(completenessRatio)
            .build();
    }

    /**
     * Aggregate multiple OrderbookMetrics into one.
     */
    private OrderbookMetrics aggregateOrderbookMetrics(List<OrderbookMetrics> metrics) {
        if (metrics.isEmpty()) {
            return null;
        }

        OrderbookMetrics first = metrics.get(0);
        OrderbookMetrics last = metrics.get(metrics.size() - 1);

        // Sum OFI
        double ofi = metrics.stream().mapToDouble(OrderbookMetrics::getOfi).sum();

        // Average other metrics
        double avgSpread = metrics.stream()
            .mapToDouble(OrderbookMetrics::getBidAskSpread).average().orElse(0);
        double avgKyleLambda = metrics.stream()
            .mapToDouble(OrderbookMetrics::getKyleLambda).average().orElse(0);
        double avgMicroprice = metrics.stream()
            .mapToDouble(OrderbookMetrics::getMicroprice).average().orElse(0);
        double avgDepthImbalance = metrics.stream()
            .mapToDouble(OrderbookMetrics::getDepthImbalance).average().orElse(0);
        double avgBidDepth = metrics.stream()
            .mapToDouble(OrderbookMetrics::getAvgBidDepth).average().orElse(0);
        double avgAskDepth = metrics.stream()
            .mapToDouble(OrderbookMetrics::getAvgAskDepth).average().orElse(0);

        // Sum counts
        int spoofingCount = metrics.stream().mapToInt(OrderbookMetrics::getSpoofingCount).sum();

        return OrderbookMetrics.builder()
            .symbol(first.getSymbol())
            .scripCode(first.getScripCode())
            .exchange(first.getExchange())
            .exchangeType(first.getExchangeType())
            .timestamp(last.getTimestamp())
            .windowStart(first.getWindowStart())
            .windowEnd(last.getWindowEnd())
            .ofi(ofi)
            .kyleLambda(avgKyleLambda)
            .microprice(avgMicroprice)
            .bidAskSpread(avgSpread)
            .spreadPercent(avgMicroprice > 0 ? avgSpread / avgMicroprice * 100 : 0)
            .depthImbalance(avgDepthImbalance)
            .avgBidDepth(avgBidDepth)
            .avgAskDepth(avgAskDepth)
            .spoofingCount(spoofingCount)
            .updateCount(metrics.stream().mapToInt(OrderbookMetrics::getUpdateCount).sum())
            .lastUpdateTimestamp(last.getLastUpdateTimestamp())
            .quality("AGGREGATED")
            .staleness(last.getStaleness())
            .build();
    }

    // ==================== OI INTERPRETATION ====================

    /**
     * Calculate OI interpretation using tick price change.
     */
    private OIMetrics calculateOIInterpretation(OIMetrics oi, TickCandle tick) {
        if (oi == null || tick == null) return oi;

        double priceChange = tick.getOpen() > 0 ?
            (tick.getClose() - tick.getOpen()) / tick.getOpen() * 100 : 0;

        return calculateOIInterpretationWithPriceChange(oi, priceChange);
    }

    /**
     * Calculate OI interpretation with known price change.
     */
    private OIMetrics calculateOIInterpretationWithPriceChange(OIMetrics oi, double priceChange) {
        if (oi == null) return null;

        OIMetrics.OIInterpretation interpretation = OIMetrics.OIInterpretation.determine(
            priceChange,
            oi.getOiChangePercent(),
            PRICE_CHANGE_THRESHOLD,
            OI_CHANGE_THRESHOLD
        );

        // Calculate confidence based on change magnitudes
        double priceConfidence = Math.min(1.0, Math.abs(priceChange) / 1.0);  // 1% = full confidence
        double oiConfidence = Math.min(1.0, Math.abs(oi.getOiChangePercent()) / 5.0);  // 5% = full
        double confidence = (priceConfidence + oiConfidence) / 2;

        // Detect potential reversal
        boolean suggestsReversal = interpretation.suggestsExhaustion();

        // Update OI metrics (create copy with interpretation)
        return OIMetrics.builder()
            .id(oi.getId())
            .symbol(oi.getSymbol())
            .scripCode(oi.getScripCode())
            .exchange(oi.getExchange())
            .exchangeType(oi.getExchangeType())
            .underlyingSymbol(oi.getUnderlyingSymbol())
            .strikePrice(oi.getStrikePrice())
            .optionType(oi.getOptionType())
            .expiry(oi.getExpiry())
            .timestamp(oi.getTimestamp())
            .windowStart(oi.getWindowStart())
            .windowEnd(oi.getWindowEnd())
            .openInterest(oi.getOpenInterest())
            .oiOpen(oi.getOiOpen())
            .oiClose(oi.getOiClose())
            .oiChange(oi.getOiChange())
            .oiChangePercent(oi.getOiChangePercent())
            .previousDayOI(oi.getPreviousDayOI())
            .dailyOIChange(oi.getDailyOIChange())
            .dailyOIChangePercent(oi.getDailyOIChangePercent())
            .interpretation(interpretation)
            .interpretationConfidence(confidence)
            .suggestsReversal(suggestsReversal)
            .oiVelocity(oi.getOiVelocity())
            .oiAcceleration(oi.getOiAcceleration())
            .updateCount(oi.getUpdateCount())
            .lastUpdateTimestamp(oi.getLastUpdateTimestamp())
            .quality(oi.getQuality())
            .staleness(oi.getStaleness())
            .createdAt(oi.getCreatedAt())
            .build();
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Check if data exists for a symbol.
     */
    public boolean hasData(String symbol) {
        return tickCandleRepository.findTopBySymbolOrderByTimestampDesc(symbol).isPresent();
    }

    /**
     * Get available symbols with data.
     */
    public Set<String> getAvailableSymbols() {
        return redisCacheService.getCachedSymbols();
    }

    /**
     * Get latest candles for multiple symbols (batch query).
     */
    public Map<String, UnifiedCandle> getLatestCandles(List<String> symbols) {
        Map<String, UnifiedCandle> result = new HashMap<>();

        for (String symbol : symbols) {
            UnifiedCandle candle = getLatestCandle(symbol);
            if (candle != null) {
                result.put(symbol, candle);
            }
        }

        return result;
    }

    /**
     * Get latest candles for multiple symbols at specific timeframe.
     */
    public Map<String, UnifiedCandle> getLatestCandles(List<String> symbols, Timeframe timeframe) {
        Map<String, UnifiedCandle> result = new HashMap<>();

        for (String symbol : symbols) {
            UnifiedCandle candle = getLatestCandle(symbol, timeframe);
            if (candle != null) {
                result.put(symbol, candle);
            }
        }

        return result;
    }
}
