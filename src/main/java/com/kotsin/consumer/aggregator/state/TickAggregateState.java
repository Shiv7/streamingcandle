package com.kotsin.consumer.aggregator.state;

import com.kotsin.consumer.model.TickCandle;
import com.kotsin.consumer.model.TickData;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TickAggregateState - Manages aggregation state for a single instrument within a time window.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>Track OHLCV (Open, High, Low, Close, Volume) for tick aggregation</li>
 *   <li>Perform Lee-Ready trade classification for buy/sell pressure analysis</li>
 *   <li>Calculate VPIN (Volume-Synchronized Probability of Informed Trading)</li>
 *   <li>Track volume profile at discrete price levels</li>
 *   <li>Detect imbalance patterns (VIB, DIB, TRB triggers)</li>
 *   <li>Convert aggregated state to TickCandle for persistence</li>
 * </ol>
 *
 * <h2>Key Design Principles</h2>
 * <ul>
 *   <li><b>THREAD-SAFE</b>: Uses synchronized update() method for concurrent tick processing</li>
 *   <li><b>EVENT-TIME</b>: Associates state with specific window boundaries (windowStart, windowEnd)</li>
 *   <li><b>QUANT-ACCURATE</b>: Implements Lee-Ready algorithm with previous midpoint reference</li>
 *   <li><b>MEMORY-BOUNDED</b>: Limits volume profile entries to prevent unbounded growth</li>
 * </ul>
 *
 * <h2>Lee-Ready Trade Classification (v2.1)</h2>
 * <p>Classifies each trade as buyer-initiated or seller-initiated:</p>
 * <ul>
 *   <li>Price above previous midpoint → Aggressive buy (lifted offer)</li>
 *   <li>Price below previous midpoint → Aggressive sell (hit bid)</li>
 *   <li>Price at midpoint → Use tick direction as tiebreaker</li>
 * </ul>
 *
 * <h2>VPIN Calculation (v2.1)</h2>
 * <p>Volume-based VPIN uses fixed-size volume buckets instead of time-based windows:</p>
 * <ul>
 *   <li>Bucket size: 10,000 shares</li>
 *   <li>Rolling window: 50 buckets</li>
 *   <li>VPIN = average absolute imbalance across buckets</li>
 * </ul>
 *
 * <h2>Bug Fixes</h2>
 * <ul>
 *   <li><b>Bug #1</b>: Consistent Lee-Ready logic for first tick and subsequent ticks</li>
 *   <li><b>Bug #8</b>: Volume profile size limit (MAX_VOLUME_PROFILE_ENTRIES = 500)</li>
 *   <li><b>Bug #11</b>: Buy/sell pressure calculation with validation</li>
 *   <li><b>Bug #15</b>: processingLatencyMs measures actual staleness</li>
 * </ul>
 *
 * @see com.kotsin.consumer.aggregator.TickAggregator
 * @see TickCandle
 */
public class TickAggregateState {

    // ==================== CONSTANTS ====================

    /**
     * Maximum number of price levels to track in volume profile.
     * <p>Bug #8 FIX: Prevents unbounded memory growth during high volatility.</p>
     */
    private static final int MAX_VOLUME_PROFILE_ENTRIES = 500;

    /**
     * VPIN bucket size in shares.
     * <p>Each bucket accumulates this many shares before calculating imbalance.</p>
     */
    private static final int VPIN_BUCKET_SIZE = 10000;

    /**
     * Number of VPIN buckets in rolling average.
     * <p>VPIN = average imbalance across this many buckets.</p>
     */
    private static final int VPIN_NUM_BUCKETS = 50;

    /**
     * Threshold for large trade detection.
     * <p>Trades with quantity > this value are counted as large trades.</p>
     */
    private static final int LARGE_TRADE_THRESHOLD = 10000;

    /**
     * Tolerance for midpoint comparison in Lee-Ready classification.
     * <p>Prices within this tolerance of midpoint use tick direction tiebreaker.</p>
     */
    private static final double MIDPOINT_TOLERANCE = 0.001;

    // ==================== IDENTITY ====================

    /**
     * Extracted symbol from company name (e.g., "NIFTY", "RELIANCE", "BANKNIFTY").
     */
    private final String symbol;

    /**
     * Exchange scrip code (unique instrument identifier).
     */
    private final String scripCode;

    /**
     * Exchange code: "N" (NSE), "B" (BSE), "M" (MCX).
     */
    private final String exchange;

    /**
     * Exchange type: "C" (Cash), "D" (Derivative).
     */
    private final String exchangeType;

    /**
     * Full company name from TickData (includes option details if applicable).
     */
    private final String companyName;

    // ==================== WINDOW TIMING ====================

    /**
     * Start of aggregation window (inclusive).
     */
    private Instant windowStart;

    /**
     * End of aggregation window (exclusive, canonical timestamp).
     */
    private Instant windowEnd;

    /**
     * Timestamp of last tick update (event time).
     */
    private Instant lastUpdate;

    // ==================== OHLCV ====================

    /**
     * Opening price (first tick in window).
     */
    private double open;

    /**
     * Highest price in window.
     */
    private double high;

    /**
     * Lowest price in window.
     */
    private double low;

    /**
     * Closing price (last tick in window).
     */
    private double close;

    /**
     * Total volume traded in window.
     */
    private long volume;

    /**
     * Sum of (price * quantity) for VWAP calculation.
     * <p>VWAP = valueSum / volume</p>
     */
    private double valueSum;

    // ==================== TRADE CLASSIFICATION (Lee-Ready v2.1) ====================

    /**
     * Volume classified as buyer-initiated (aggressive buys).
     * <p>Trades executed above previous midpoint.</p>
     */
    private long buyVolume;

    /**
     * Volume classified as seller-initiated (aggressive sells).
     * <p>Trades executed below previous midpoint.</p>
     */
    private long sellVolume;

    /**
     * Volume executed at midpoint (neutral/iceberg indicator).
     * <p>Trades exactly at midpoint with no tick direction.</p>
     */
    private long midpointVolume;

    /**
     * Previous tick's midpoint for Lee-Ready classification.
     * <p>Bug #1 FIX: Compare to PREVIOUS midpoint, not current.</p>
     */
    private double previousMidpoint;

    // ==================== VOLUME PROFILE ====================

    /**
     * Volume at each price level (sparse map).
     * <p>Key: price in paise as string (avoids MongoDB dot issues)</p>
     * <p>Value: total volume at that price</p>
     * <p>Bug #8 FIX: Limited to MAX_VOLUME_PROFILE_ENTRIES.</p>
     */
    private final Map<String, Long> volumeAtPrice = new ConcurrentHashMap<>();

    // ==================== IMBALANCE TRACKING ====================

    /**
     * Net signed volume imbalance.
     * <p>Positive = more uptick volume, Negative = more downtick volume.</p>
     */
    private double volumeImbalance;

    /**
     * Net signed dollar imbalance (volume * price * direction).
     */
    private double dollarImbalance;

    /**
     * Consecutive ticks in same direction (run length).
     */
    private int tickRuns;

    /**
     * Direction of last tick: 1 = up, -1 = down, 0 = unchanged.
     */
    private int lastTickDirection;

    // ==================== VPIN (Volume-Based v2.1) ====================

    /**
     * Volume accumulated in current VPIN bucket.
     */
    private long currentBucketVolume;

    /**
     * Buy volume accumulated in current VPIN bucket.
     */
    private long currentBucketBuyVolume;

    /**
     * Rolling list of bucket imbalances for VPIN calculation.
     * <p>Each entry = |2 * buyVolume - totalVolume| / totalVolume</p>
     */
    private final LinkedList<Double> vpinBuckets = new LinkedList<>();

    // ==================== STATISTICS ====================

    /**
     * Total number of ticks processed in this window.
     */
    private int tickCount;

    /**
     * Number of large trades (qty > LARGE_TRADE_THRESHOLD).
     */
    private int largeTradeCount;

    /**
     * Last traded price (for tick direction calculation).
     */
    private double lastPrice;

    // ==================== OPTION METADATA ====================

    /**
     * Option metadata from Scrip database.
     * <p>Null for non-option instruments.</p>
     * <p>Contains authoritative data: strike, expiry, lot size, etc.</p>
     */
    private OptionMetadata optionMetadata;

    // ==================== CONSTRUCTOR ====================

    /**
     * Create new aggregation state from first tick (legacy constructor).
     *
     * <p>Initializes identity fields from tick data and sets initial OHLC
     * from the first tick's price.</p>
     *
     * @param firstTick   first tick in this aggregation window
     * @param windowStart start of aggregation window
     * @param windowEnd   end of aggregation window
     * @param symbol      pre-resolved symbol from ScripMetadataService
     * @deprecated Use constructor with OptionMetadata parameter for options
     */
    public TickAggregateState(TickData firstTick, Instant windowStart, Instant windowEnd, String symbol) {
        this(firstTick, windowStart, windowEnd, symbol, null);
    }

    /**
     * Create new aggregation state with option metadata from Scrip database.
     *
     * <p>Preferred constructor for options as it uses authoritative metadata
     * from the Scrip database instead of parsing from company name.</p>
     *
     * @param firstTick      first tick in this aggregation window
     * @param windowStart    start of aggregation window
     * @param windowEnd      end of aggregation window
     * @param symbol         pre-resolved symbol from ScripMetadataService
     * @param optionMetadata option metadata from Scrip database (null for non-options)
     */
    public TickAggregateState(TickData firstTick, Instant windowStart, Instant windowEnd,
                               String symbol, OptionMetadata optionMetadata) {
        this.symbol = symbol;
        this.scripCode = firstTick.getScripCode();
        this.exchange = firstTick.getExchange();
        this.exchangeType = firstTick.getExchangeType();
        this.companyName = firstTick.getCompanyName();
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.lastUpdate = Instant.now();
        this.optionMetadata = optionMetadata;

        // Initialize OHLC from first tick
        double price = firstTick.getLastRate();
        this.open = price;
        this.high = price;
        this.low = price;
        this.close = price;
        this.lastPrice = price;
    }

    // ==================== TICK PROCESSING ====================

    /**
     * Update aggregation state with a new tick.
     *
     * <p>Thread-safe method that performs:</p>
     * <ol>
     *   <li>OHLC update (high/low/close)</li>
     *   <li>Volume accumulation</li>
     *   <li>Lee-Ready trade classification</li>
     *   <li>VPIN bucket update</li>
     *   <li>Volume profile update (with size limit)</li>
     *   <li>Imbalance tracking</li>
     *   <li>Large trade detection</li>
     * </ol>
     *
     * @param tick     incoming tick data
     * @param tickTime event time of the tick
     */
    public synchronized void update(TickData tick, Instant tickTime) {
        double price = tick.getLastRate();
        long qty = tick.getLastQuantity();

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

        // Update volume and value sum (for VWAP)
        volume += qty;
        valueSum += price * qty;

        // Calculate current midpoint from bid/ask
        double bid = tick.getBidRate() > 0 ? tick.getBidRate() : price;
        double ask = tick.getOfferRate() > 0 ? tick.getOfferRate() : price;
        double midpoint = (bid + ask) / 2;

        // LEE-READY TRADE CLASSIFICATION (v2.1)
        // Bug #1 FIX: Use consistent logic for first tick and subsequent ticks
        boolean isBuy = classifyTrade(price, qty, midpoint);
        previousMidpoint = midpoint;

        // VOLUME-BASED VPIN UPDATE (v2.1)
        updateVpinBucket(qty, isBuy);

        // Bug #8 FIX: Volume profile with size limit
        updateVolumeProfile(price, qty);

        // Imbalance tracking
        updateImbalance(price, qty);

        // Large trade detection
        if (qty > LARGE_TRADE_THRESHOLD) {
            largeTradeCount++;
        }

        tickCount++;
        lastPrice = price;
        lastUpdate = tickTime;
    }

    /**
     * Classify trade as buy or sell using Lee-Ready algorithm.
     *
     * <p>Classification rules:</p>
     * <ul>
     *   <li>Price > previous midpoint + tolerance → Buy</li>
     *   <li>Price < previous midpoint - tolerance → Sell</li>
     *   <li>At midpoint → Use tick direction as tiebreaker</li>
     * </ul>
     *
     * @param price    trade price
     * @param qty      trade quantity
     * @param midpoint current bid/ask midpoint
     * @return true if classified as buy, false otherwise
     */
    private boolean classifyTrade(double price, long qty, double midpoint) {
        boolean isBuy = false;
        double referenceMidpoint = previousMidpoint > 0 ? previousMidpoint : midpoint;

        if (price > referenceMidpoint + MIDPOINT_TOLERANCE) {
            buyVolume += qty;
            isBuy = true;
        } else if (price < referenceMidpoint - MIDPOINT_TOLERANCE) {
            sellVolume += qty;
        } else {
            // At midpoint - use tick direction as tiebreaker
            if (lastTickDirection > 0) {
                buyVolume += qty;
                isBuy = true;
            } else if (lastTickDirection < 0) {
                sellVolume += qty;
            } else {
                midpointVolume += qty;
            }
        }

        return isBuy;
    }

    /**
     * Update VPIN volume bucket.
     *
     * <p>When bucket fills (reaches VPIN_BUCKET_SIZE), calculate imbalance
     * and add to rolling bucket list.</p>
     *
     * @param qty   trade quantity
     * @param isBuy true if trade was classified as buy
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
     * Update volume profile at price level.
     *
     * <p>Bug #8 FIX: Skips new price levels if at MAX_VOLUME_PROFILE_ENTRIES
     * to prevent unbounded memory growth.</p>
     *
     * @param price trade price
     * @param qty   trade quantity
     */
    private void updateVolumeProfile(double price, long qty) {
        // Store price in paise as string key to avoid MongoDB dot issues
        long priceInPaise = Math.round(price * 100);
        String priceKey = String.valueOf(priceInPaise);

        if (volumeAtPrice.containsKey(priceKey) || volumeAtPrice.size() < MAX_VOLUME_PROFILE_ENTRIES) {
            volumeAtPrice.merge(priceKey, qty, Long::sum);
        }
        // If at max entries and new price, skip to prevent unbounded growth
    }

    /**
     * Update imbalance tracking (volume and dollar).
     *
     * @param price trade price
     * @param qty   trade quantity
     */
    private void updateImbalance(double price, long qty) {
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
    }

    /**
     * Calculate VPIN as average imbalance across completed buckets.
     *
     * @return VPIN value between 0 and 1, or 0 if no buckets completed
     */
    private double calculateVPIN() {
        if (vpinBuckets.isEmpty()) return 0.0;
        return vpinBuckets.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    // ==================== CONVERSION ====================

    /**
     * Convert aggregation state to TickCandle for persistence.
     *
     * <p>Builds a complete TickCandle with:</p>
     * <ul>
     *   <li>Identity fields (symbol, scripCode, exchange)</li>
     *   <li>OHLCV data</li>
     *   <li>Trade classification (buy/sell pressure)</li>
     *   <li>Volume profile (POC, VAH, VAL)</li>
     *   <li>VPIN metric</li>
     *   <li>Imbalance indicators</li>
     *   <li>Option metadata (if applicable)</li>
     * </ul>
     *
     * @return TickCandle ready for MongoDB persistence
     */
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

            // VAH/VAL = price range boundaries (simplified 70% value area)
            vah = volumeAtPrice.keySet().stream()
                .mapToLong(Long::parseLong)
                .max()
                .orElse((long)(high * 100)) / 100.0;
            val = volumeAtPrice.keySet().stream()
                .mapToLong(Long::parseLong)
                .min()
                .orElse((long)(low * 100)) / 100.0;
        }

        // Bug #11 FIX: Calculate buy/sell pressure with validation
        long classifiedVolume = buyVolume + sellVolume + midpointVolume;
        double buyPressure, sellPressure;
        if (classifiedVolume > 0) {
            buyPressure = (double) buyVolume / classifiedVolume;
            sellPressure = (double) sellVolume / classifiedVolume;
        } else if (volume > 0) {
            // Fallback: use total volume if classification failed
            buyPressure = (double) buyVolume / volume;
            sellPressure = (double) sellVolume / volume;
        } else {
            // No volume: neutral pressure
            buyPressure = 0.5;
            sellPressure = 0.5;
        }

        // Use stored option metadata from Scrip database, fallback to parsing if not available
        OptionMetadata optionMeta = this.optionMetadata != null
            ? this.optionMetadata
            : OptionMetadata.parse(companyName);

        TickCandle.TickCandleBuilder builder = TickCandle.builder()
            .id(scripCode + "_" + windowEnd.toEpochMilli()) // IDEMPOTENCY FIX
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
            .vpin(calculateVPIN())
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
            // Bug #15 FIX: processingLatencyMs measures actual staleness
            .processingLatencyMs(System.currentTimeMillis() - lastUpdate.toEpochMilli())
            .createdAt(Instant.now());

        // Add option metadata if parsed
        if (optionMeta != null) {
            builder.strikePrice(optionMeta.getStrikePrice())
                   .optionType(optionMeta.getOptionType())
                   .expiry(optionMeta.getExpiry())
                   .daysToExpiry(optionMeta.getDaysToExpiry());
        }

        return builder.build();
    }

    // ==================== STATE RESET ====================

    /**
     * Reset state for a new window while preserving continuity.
     *
     * <p>Preserves close price as new open for price continuity.</p>
     *
     * @param newWindowStart start of new aggregation window
     * @param newWindowEnd   end of new aggregation window
     */
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

    // ==================== ACCESSORS ====================

    /**
     * Get window start timestamp.
     *
     * @return start of aggregation window
     */
    public Instant getWindowStart() {
        return windowStart;
    }

    /**
     * Get window end timestamp.
     *
     * @return end of aggregation window
     */
    public Instant getWindowEnd() {
        return windowEnd;
    }

    /**
     * Get last update timestamp.
     *
     * @return timestamp of last tick update
     */
    public Instant getLastUpdate() {
        return lastUpdate;
    }

    /**
     * Get total tick count in this window.
     *
     * @return number of ticks processed
     */
    public int getTickCount() {
        return tickCount;
    }

}

