package com.kotsin.consumer.aggregator.state;

import com.kotsin.consumer.model.OIMetrics;
import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * OIAggregateState - Manages aggregation state for Open Interest within a time window.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>Track OI OHLC (Open, High, Low, Close) for OI aggregation</li>
 *   <li>Calculate OI velocity and acceleration</li>
 *   <li>Determine OI interpretation (LONG_BUILDUP, SHORT_COVERING, etc.)</li>
 *   <li>Parse option metadata from company name (strike, expiry, option type)</li>
 *   <li>Convert aggregated state to OIMetrics for persistence</li>
 * </ol>
 *
 * <h2>Key Design Principles</h2>
 * <ul>
 *   <li><b>THREAD-SAFE</b>: Uses synchronized update() method for concurrent OI processing</li>
 *   <li><b>EVENT-TIME</b>: Associates state with specific window boundaries</li>
 *   <li><b>INTERPRETATION</b>: Calculates OI interpretation using cached price from Redis</li>
 * </ul>
 *
 * <h2>OI Interpretation Matrix</h2>
 * <pre>
 * OI ↑ + Price ↑ = LONG_BUILDUP   (Bullish)
 * OI ↓ + Price ↑ = SHORT_COVERING (Bullish)
 * OI ↑ + Price ↓ = SHORT_BUILDUP  (Bearish)
 * OI ↓ + Price ↓ = LONG_UNWINDING (Bearish)
 * </pre>
 *
 * @see com.kotsin.consumer.aggregator.OIAggregator
 * @see OIMetrics
 */
@Slf4j
public class OIAggregateState implements com.kotsin.consumer.aggregator.WindowedState {

    // ==================== IDENTITY ====================

    /**
     * Extracted symbol from company name (e.g., "NIFTY", "BANKNIFTY").
     * <p>Resolved by ScripMetadataService for accuracy.</p>
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
     * Full company name from OpenInterest data.
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
     * Timestamp of last OI update (event time).
     */
    private Instant lastUpdate;

    // ==================== OI OHLC ====================

    /**
     * Opening OI (first value in window).
     */
    private long oiOpen;

    /**
     * Closing OI (last value in window).
     */
    private long oiClose;

    /**
     * Highest OI in window.
     */
    private long oiHigh;

    /**
     * Lowest OI in window.
     */
    private long oiLow;

    /**
     * Flag indicating if first update has been processed.
     */
    private boolean firstUpdate;

    // ==================== OPTION METADATA ====================

    /**
     * Underlying symbol for options (e.g., "NIFTY" for NIFTY options).
     */
    private String underlyingSymbol;

    /**
     * Strike price for options.
     */
    private Double strikePrice;

    /**
     * Option type: "CE" (Call) or "PE" (Put).
     */
    private String optionType;

    /**
     * Expiry date string (e.g., "23JAN", "25JAN2025").
     */
    private String expiry;

    // ==================== OPTION METADATA ====================

    /**
     * Parsed option metadata from Scrip database (preferred) or company name (fallback).
     * <p>Contains strike, expiry, lotSize, tickSize, daysToExpiry, etc.</p>
     */
    private OptionMetadata optionMetadata;

    // ==================== VELOCITY TRACKING ====================

    /**
     * Previous window's closing OI for velocity calculation.
     */
    private long previousWindowOI;

    /**
     * Previous window's velocity for acceleration calculation.
     */
    private double previousVelocity;

    /**
     * Previous day's OI for daily change calculation.
     */
    private long previousDayOI;

    // ==================== STATISTICS ====================

    /**
     * Number of OI updates processed in this window.
     */
    private int updateCount;

    // ==================== CONSTRUCTOR ====================

    /**
     * Create new OI aggregation state from first OI snapshot.
     *
     * @param oi          first OI snapshot in this window
     * @param windowStart start of aggregation window
     * @param windowEnd   end of aggregation window
     * @param symbol      pre-resolved symbol from ScripMetadataService
     */
    public OIAggregateState(OpenInterest oi, Instant windowStart, Instant windowEnd, String symbol) {
        this.scripCode = String.valueOf(oi.getToken());
        this.exchange = oi.getExchange();
        this.exchangeType = oi.getExchangeType();
        this.companyName = oi.getCompanyName();
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.lastUpdate = Instant.now();
        this.firstUpdate = true;

        // Symbol is resolved by ScripMetadataService (authoritative source from database)
        this.symbol = symbol;
        this.underlyingSymbol = symbol;

        // Parse option details (expiry, strike, optionType) from companyName
        parseOptionDetails(oi.getCompanyName());

        // Initialize OI values
        long currentOI = oi.getOpenInterest() != null ? oi.getOpenInterest() : 0;
        this.oiOpen = currentOI;
        this.oiClose = currentOI;
        this.oiHigh = currentOI;
        this.oiLow = currentOI;

        // Calculate previous day OI from oiChange if available from producer
        // oiChange from producer = currentOI - previousDayOI, so previousDayOI = currentOI - oiChange
        if (oi.getOiChange() != null && oi.getOiChange() != 0) {
            this.previousDayOI = currentOI - oi.getOiChange();
        } else {
            // Fallback: use current OI (daily change will be 0)
            this.previousDayOI = currentOI;
        }
        this.previousWindowOI = currentOI;
    }

    /**
     * Create new OI aggregation state with pre-resolved option metadata.
     *
     * <p>PREFERRED CONSTRUCTOR when Scrip data is available. Uses authoritative
     * data from database instead of parsing from company name.</p>
     *
     * @param oi             first OI snapshot in this window
     * @param windowStart    start of aggregation window
     * @param windowEnd      end of aggregation window
     * @param symbol         pre-resolved symbol from ScripMetadataService
     * @param optionMetadata option metadata from Scrip (null for non-options)
     */
    public OIAggregateState(OpenInterest oi, Instant windowStart, Instant windowEnd,
                            String symbol, OptionMetadata optionMetadata) {
        this.scripCode = String.valueOf(oi.getToken());
        this.exchange = oi.getExchange();
        this.exchangeType = oi.getExchangeType();
        this.companyName = oi.getCompanyName();
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.lastUpdate = Instant.now();
        this.firstUpdate = true;

        // Symbol is resolved by ScripMetadataService (authoritative source from database)
        this.symbol = symbol;
        this.underlyingSymbol = symbol;

        // Store option metadata if provided
        this.optionMetadata = optionMetadata;

        // Use OptionMetadata from Scrip if available (PREFERRED)
        if (optionMetadata != null) {
            this.strikePrice = optionMetadata.getStrikePrice();
            this.optionType = optionMetadata.getOptionType();
            this.expiry = optionMetadata.getExpiry();
            if (optionMetadata.getUnderlyingSymbol() != null) {
                this.underlyingSymbol = optionMetadata.getUnderlyingSymbol();
            }
        } else {
            // Fallback: Parse option details from companyName (legacy)
            parseOptionDetails(oi.getCompanyName());
        }

        // Initialize OI values
        long currentOI = oi.getOpenInterest() != null ? oi.getOpenInterest() : 0;
        this.oiOpen = currentOI;
        this.oiClose = currentOI;
        this.oiHigh = currentOI;
        this.oiLow = currentOI;

        // Calculate previous day OI from oiChange if available from producer
        if (oi.getOiChange() != null && oi.getOiChange() != 0) {
            this.previousDayOI = currentOI - oi.getOiChange();
        } else {
            this.previousDayOI = currentOI;
        }
        this.previousWindowOI = currentOI;
    }

    // ==================== OI PROCESSING ====================

    /**
     * Update aggregation state with a new OI snapshot.
     *
     * <p>Thread-safe method that updates OI OHLC values.</p>
     *
     * @param oi     incoming OI snapshot
     * @param oiTime event time of the snapshot
     */
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

    // ==================== CONVERSION ====================

    /**
     * Convert aggregation state to OIMetrics for persistence.
     *
     * <p>Calculates OI interpretation using cached price from Redis.</p>
     *
     * @param redisCacheService service for retrieving cached prices
     * @return OIMetrics ready for MongoDB persistence
     */
    public OIMetrics toOIMetrics(RedisCacheService redisCacheService) {
        long oiChange = oiClose - oiOpen;
        double oiChangePercent = oiOpen > 0 ? (double) oiChange / oiOpen * 100 : 0;

        long dailyOIChange = oiClose - previousDayOI;
        double dailyOIChangePercent = previousDayOI > 0 ?
            (double) dailyOIChange / previousDayOI * 100 : 0;

        // OI velocity = change per minute (1-minute window, so change = velocity)
        double oiVelocity = oiChange;

        // OI acceleration = change in velocity
        double oiAcceleration = oiVelocity - previousVelocity;

        // Calculate interpretation using cached price (keyed by exchange:scripCode)
        OIMetrics.OIInterpretation interpretation = calculateInterpretation(
            oiChange, exchange, scripCode, redisCacheService);

        // Calculate interpretation confidence based on OI change magnitude
        double interpretationConfidence = Math.min(1.0, Math.abs(oiChangePercent) / 5.0);

        // suggestsReversal based on short covering or long unwinding
        boolean suggestsReversal = (interpretation == OIMetrics.OIInterpretation.SHORT_COVERING ||
                                    interpretation == OIMetrics.OIInterpretation.LONG_UNWINDING);

        return OIMetrics.builder()
            .id(exchange + ":" + scripCode + "_" + windowEnd.toEpochMilli()) // IDEMPOTENCY FIX + Bug #6: exchange in ID
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

    // ==================== STATE RESET ====================

    /**
     * Reset state for a new window while preserving continuity.
     *
     * @param newWindowStart start of new aggregation window
     * @param newWindowEnd   end of new aggregation window
     */
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

    // ==================== INTERPRETATION ====================

    /**
     * Calculate OI interpretation using cached price from Redis.
     *
     * <p>Uses standard OI interpretation matrix:</p>
     * <ul>
     *   <li>OI ↑ + Price ↑ = LONG_BUILDUP (bullish)</li>
     *   <li>OI ↓ + Price ↑ = SHORT_COVERING (bullish)</li>
     *   <li>OI ↑ + Price ↓ = SHORT_BUILDUP (bearish)</li>
     *   <li>OI ↓ + Price ↓ = LONG_UNWINDING (bearish)</li>
     * </ul>
     *
     * @param oiChange          change in OI
     * @param scripCode         instrument identifier for price lookup
     * @param redisCacheService service for cached price retrieval
     * @return OI interpretation enum
     */
    private OIMetrics.OIInterpretation calculateInterpretation(
            long oiChange, String exchange, String scripCode, RedisCacheService redisCacheService) {

        if (redisCacheService == null) {
            return OIMetrics.OIInterpretation.NEUTRAL;
        }

        Double lastPrice = redisCacheService.getLastPrice(exchange, scripCode);
        Double prevPrice = redisCacheService.getPreviousPrice(exchange, scripCode);

        // Log cache misses for debugging
        if (lastPrice == null || prevPrice == null || prevPrice <= 0) {
            if (lastPrice == null && prevPrice == null) {
                log.debug("[OI-INTERP] Cache miss for scripCode={}: no price data available", scripCode);
            } else if (lastPrice == null) {
                log.debug("[OI-INTERP] Cache miss for scripCode={}: lastPrice=null, prevPrice={}", scripCode, prevPrice);
            } else {
                log.debug("[OI-INTERP] Cache miss for scripCode={}: lastPrice={}, prevPrice={}", scripCode, lastPrice, prevPrice);
            }
            return OIMetrics.OIInterpretation.NEUTRAL;
        }

        double priceChange = lastPrice - prevPrice;

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

    // ==================== OPTION PARSING ====================

    /**
     * Parse option details (expiry, strike, optionType) from companyName.
     *
     * <p>Symbol/underlying is already resolved by ScripMetadataService.</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>"NIFTY 23JAN 21000 CE" → strike=21000, type=CE, expiry=23JAN</li>
     *   <li>"BANKNIFTY 25JAN 48000 PE" → strike=48000, type=PE, expiry=25JAN</li>
     * </ul>
     *
     * @param companyName full company name containing option details
     */
    private void parseOptionDetails(String companyName) {
        if (companyName == null || companyName.isEmpty()) {
            return;
        }

        String upper = companyName.toUpperCase().trim();
        String[] parts = upper.split("\\s+");

        if (parts.length < 2) {
            return;
        }

        // Check if option (has CE or PE at end)
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
     * @return timestamp of last OI update
     */
    public Instant getLastUpdate() {
        return lastUpdate;
    }

    /**
     * Get total update count in this window.
     *
     * @return number of OI updates processed
     */
    public int getUpdateCount() {
        return updateCount;
    }

    @Override
    public boolean hasData() {
        return updateCount > 0;
    }

    /**
     * Get option metadata (from Scrip database).
     *
     * @return option metadata, or null if not an option or not available
     */
    public OptionMetadata getOptionMetadata() {
        return optionMetadata;
    }

    /**
     * Get lot size for this instrument.
     *
     * @return lot size from option metadata, or 1 if not available
     */
    public int getLotSize() {
        return optionMetadata != null ? optionMetadata.getLotSizeOrDefault() : 1;
    }

    /**
     * Check if this is an option instrument.
     *
     * @return true if option type is CE or PE
     */
    public boolean isOption() {
        return "CE".equals(optionType) || "PE".equals(optionType);
    }
}
