package com.kotsin.consumer.enrichment.enricher;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import com.kotsin.consumer.model.UnifiedCandle;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TechnicalIndicatorEnricher - Calculates technical indicators for trading signals
 *
 * Indicators calculated:
 * 1. SuperTrend (trend following)
 * 2. Bollinger Bands (volatility and mean reversion)
 * 3. ATR (volatility for stops)
 * 4. Pivot levels (support/resistance)
 *
 * Uses Redis to store:
 * - Historical prices for indicator calculation
 * - Previous indicator values (for flip detection)
 * - Session data (VWAP, high, low)
 * - Daily/Weekly/Monthly pivots (calculated once per period)
 *
 * FIX: Now integrates with RedisCandleHistoryService for historical data bootstrap.
 * This ensures indicators have sufficient history even on first calculation.
 */
@Slf4j
@Service
public class TechnicalIndicatorEnricher {

    private final RedisTemplate<String, String> redisTemplate;
    private final MTFSuperTrendAggregator mtfAggregator;

    // ObjectMapper for deserializing historical candles
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // FIX: Track last stored timestamp per family/timeframe to prevent duplicate insertions
    // Key format: "familyId:timeframe" -> lastTimestamp
    private final ConcurrentHashMap<String, Long> lastStoredTimestamp = new ConcurrentHashMap<>();

    // FIX: Track which familyId+timeframe combos have been bootstrapped from historical data
    private final Set<String> bootstrappedKeys = ConcurrentHashMap.newKeySet();

    // SuperTrend parameters
    private static final int SUPERTREND_PERIOD = 10;
    private static final double SUPERTREND_MULTIPLIER = 3.0;

    // Bollinger Band parameters
    private static final int BB_PERIOD = 20;
    private static final double BB_STD_DEV = 2.0;
    private static final double BB_SQUEEZE_THRESHOLD = 0.02; // 2% width = squeeze

    // ATR parameters
    private static final int ATR_PERIOD = 14;

    // Redis key patterns
    private static final String KEY_PREFIX = "smtis:tech";
    private static final String HISTORICAL_KEY_PREFIX = "candles:";  // RedisCandleHistoryService key pattern
    private static final Duration HISTORY_TTL = Duration.ofHours(48);

    // Minimum candles required for valid indicators
    private static final int MIN_CANDLES_FOR_SUPERTREND = SUPERTREND_PERIOD + 1;  // 11
    private static final int MIN_CANDLES_FOR_BB = BB_PERIOD;  // 20

    public TechnicalIndicatorEnricher(RedisTemplate<String, String> redisTemplate,
                                       MTFSuperTrendAggregator mtfAggregator) {
        this.redisTemplate = redisTemplate;
        this.mtfAggregator = mtfAggregator;
    }

    @PostConstruct
    public void init() {
        log.info("[TECH] TechnicalIndicatorEnricher initialized with historical data integration. " +
                "Min candles: SuperTrend={}, BB={}", MIN_CANDLES_FOR_SUPERTREND, MIN_CANDLES_FOR_BB);
    }

    /**
     * Enrich FamilyCandle with technical indicators
     *
     * @param family FamilyCandle to enrich
     * @return TechnicalContext with all indicators
     */
    public TechnicalContext enrich(FamilyCandle family) {
        if (family == null) {
            return TechnicalContext.empty();
        }

        String familyId = family.getFamilyId();
        String timeframe = family.getTimeframe();
        double close = family.getPrimaryPrice();
        double open = getOpenPrice(family);
        double high = getHighPrice(family);
        double low = getLowPrice(family);
        long timestamp = getTimestamp(family);

        // FIX: Check for duplicate timestamp before storing (prevents double insertion)
        String dedupKey = familyId + ":" + timeframe;
        Long lastTs = lastStoredTimestamp.get(dedupKey);
        if (lastTs != null && lastTs == timestamp) {
            log.debug("[TECH] Skipping duplicate candle for {} {} ts={}", familyId, timeframe, timestamp);
            // Still calculate indicators from existing history, just don't store again
        } else {
            // Store current price in history WITH TIMESTAMP for deduplication
            // FIX: Use timestamp-based storage that integrates with historical data
            storePriceHistoryWithTimestamp(familyId, timeframe, high, low, close, timestamp);

            // Store full OHLC history for SMC analysis
            storeFullCandleHistory(familyId, timeframe, open, high, low, close, timestamp);

            // Update dedup tracker
            lastStoredTimestamp.put(dedupKey, timestamp);
        }

        // FIX: Update session data for pivot calculation
        updateSessionData(familyId, high, low, close);

        // Get price history
        List<double[]> history = getPriceHistory(familyId, timeframe, BB_PERIOD + 5);

        if (history.size() < 5) {
            // Not enough data yet - mark as INSUFFICIENT quality
            return TechnicalContext.builder()
                    .familyId(familyId)
                    .timeframe(timeframe)
                    .currentPrice(close)
                    .dataQualitySufficient(false)  // FIX: Mark as insufficient
                    .requiredCandleCount(BB_PERIOD)
                    .actualCandleCount(history.size())
                    .superTrendBullish(true)
                    .superTrendFlip(false)
                    .bbPercentB(Double.NaN)  // FIX: Use NaN instead of fake 0.5
                    .build();
        }

        // Calculate ATR first (needed for SuperTrend)
        double atr = calculateATR(history, ATR_PERIOD);
        double atrPct = close > 0 ? atr / close * 100 : 0;

        // Calculate 5m ATR for stop sizing (more meaningful than 1m ATR for swing trades)
        double atr5m = calculate5mATR(familyId);
        // If 5m ATR not available, estimate from current TF ATR
        // This is a rough estimate: 5m candle has ~5x the range of 1m candle
        if (atr5m <= 0 && atr > 0) {
            atr5m = atr * 5;
            log.debug("[TECH] Using estimated 5m ATR: {} (1m ATR {} * 5)", atr5m, atr);
        }

        // Calculate SuperTrend
        SuperTrendResult superTrend = calculateSuperTrend(history, familyId, timeframe, SUPERTREND_PERIOD, SUPERTREND_MULTIPLIER);

        // FIX: Only update MTF aggregator if SuperTrend is valid (not NaN)
        if (!Double.isNaN(superTrend.value)) {
            mtfAggregator.updateState(familyId, timeframe, superTrend.bullish, superTrend.value, superTrend.flipped);
        }

        // Get MTF analysis for this family
        MTFSuperTrendAggregator.MTFAnalysis mtfAnalysis = mtfAggregator.getAnalysis(familyId);

        // Calculate Bollinger Bands
        BollingerBandResult bb = calculateBollingerBands(history, BB_PERIOD, BB_STD_DEV);

        // Load pivot levels (from MongoDB or pre-calculated)
        Map<String, Double> pivots = getPivotLevels(familyId);

        // Get session data
        SessionData session = getSessionData(familyId, family);

        // FIX: Handle NaN BB values in support/resistance calculation
        Double nearestSupport = findNearestSupport(close, pivots, Double.isNaN(bb.lower) ? 0.0 : bb.lower);
        Double nearestResistance = findNearestResistance(close, pivots, Double.isNaN(bb.upper) ? Double.MAX_VALUE : bb.upper);

        double distToSupport = nearestSupport != null && nearestSupport > 0 ?
                (close - nearestSupport) / close * 100 : 99;
        double distToResistance = nearestResistance != null && nearestResistance < Double.MAX_VALUE ?
                (nearestResistance - close) / close * 100 : 99;

        // Calculate ATR percentile (compare to recent ATR values)
        double atrPercentile = calculateATRPercentile(familyId, timeframe, atr);
        boolean volatilityExpanding = atrPercentile > 70;

        // FIX: Check if indicators have valid data
        boolean hasSufficientHistory = history.size() >= BB_PERIOD;
        boolean superTrendValid = !Double.isNaN(superTrend.value);
        boolean bbValid = !Double.isNaN(bb.upper) && !Double.isNaN(bb.lower);

        // FIX: BB squeeze should only be true if BB is valid AND width is below threshold
        boolean bbSqueeze = bbValid && !Double.isNaN(bb.widthPct) && bb.widthPct < BB_SQUEEZE_THRESHOLD;

        // ENHANCED LOGGING: Output calculated indicator values for verification (BEFORE building context)
        log.debug("[TECH] {} [{}] INDICATORS | candles={} | ST={} ({}) | " +
                "BB=[{}/{}/{}] %B={} squeeze={} | ATR={} ({}%) | quality={}",
                familyId, timeframe, history.size(),
                superTrendValid ? String.format("%.2f", superTrend.value) : "NaN",
                superTrend.bullish ? "BULL" : "BEAR",
                bbValid ? String.format("%.2f", bb.upper) : "NaN",
                bbValid ? String.format("%.2f", bb.middle) : "NaN",
                bbValid ? String.format("%.2f", bb.lower) : "NaN",
                bbValid ? String.format("%.3f", bb.percentB) : "NaN",
                bbSqueeze,
                String.format("%.2f", atr),
                String.format("%.2f", atrPct),
                hasSufficientHistory ? "GOOD" : "INSUFFICIENT");

        // Build context
        return TechnicalContext.builder()
                .familyId(familyId)
                .timeframe(timeframe)
                .currentPrice(close)
                // Data Quality - FIX: Track if we have enough data for valid indicators
                .dataQualitySufficient(hasSufficientHistory)
                .requiredCandleCount(BB_PERIOD)
                .actualCandleCount(history.size())
                // SuperTrend - FIX: Use 0 or null indicator for invalid values
                .superTrendValue(superTrendValid ? superTrend.value : 0.0)  // Use 0 to indicate invalid
                .superTrendBullish(superTrend.bullish)
                .superTrendFlip(superTrendValid && superTrend.flipped)  // Only flip if valid
                .candlesSinceStFlip(superTrend.candlesSinceFlip)
                .superTrendMultiplier(SUPERTREND_MULTIPLIER)
                .superTrendPeriod(SUPERTREND_PERIOD)
                // Bollinger Bands - FIX: Use NaN for invalid (TechnicalContext uses primitive double)
                .bbUpper(bbValid ? bb.upper : Double.NaN)
                .bbMiddle(bbValid ? bb.middle : Double.NaN)
                .bbLower(bbValid ? bb.lower : Double.NaN)
                .bbWidth(bbValid ? bb.width : Double.NaN)
                .bbWidthPct(bbValid ? bb.widthPct : Double.NaN)
                .bbPercentB(bb.percentB)  // Keep NaN - explicitly signals invalid
                .bbSqueezing(bbSqueeze)  // FIX: false if BB invalid
                .bbSqueezeThreshold(BB_SQUEEZE_THRESHOLD)
                // ATR
                .atr(atr)
                .atr5m(atr5m > 0 ? atr5m : null)  // 5m ATR for stop calculation
                .atrPct(atrPct)
                .atrPercentile(atrPercentile)
                .volatilityExpanding(volatilityExpanding)
                // Pivots
                .dailyPivot(pivots.get("dailyPP"))
                .dailyR1(pivots.get("dailyR1"))
                .dailyR2(pivots.get("dailyR2"))
                .dailyR3(pivots.get("dailyR3"))
                .dailyS1(pivots.get("dailyS1"))
                .dailyS2(pivots.get("dailyS2"))
                .dailyS3(pivots.get("dailyS3"))
                .weeklyPivot(pivots.get("weeklyPP"))
                .weeklyR1(pivots.get("weeklyR1"))
                .weeklyS1(pivots.get("weeklyS1"))
                .monthlyPivot(pivots.get("monthlyPP"))
                .monthlyR1(pivots.get("monthlyR1"))
                .monthlyS1(pivots.get("monthlyS1"))
                // Camarilla pivots
                .camH4(pivots.get("camH4"))
                .camH3(pivots.get("camH3"))
                .camH2(pivots.get("camH2"))
                .camH1(pivots.get("camH1"))
                .camL1(pivots.get("camL1"))
                .camL2(pivots.get("camL2"))
                .camL3(pivots.get("camL3"))
                .camL4(pivots.get("camL4"))
                .nearestSupport(nearestSupport)
                .nearestResistance(nearestResistance)
                .distanceToSupportPct(distToSupport)
                .distanceToResistancePct(distToResistance)
                // Session
                .sessionVwap(session.vwap)
                .sessionHigh(session.high)
                .sessionLow(session.low)
                .sessionOpen(session.open)
                .vwapDeviation(session.vwap != null && session.vwap > 0 ?
                        (close - session.vwap) / session.vwap * 100 : null)
                // MTF SuperTrend Analysis
                .mtfAggregatedDirection(mtfAnalysis.getAggregatedDirection())
                .mtfBullishPercentage(mtfAnalysis.getBullishPercentage())
                .mtfHtfBullish(mtfAnalysis.isHtfBullish())
                .mtfHasConflict(mtfAnalysis.isHasConflict())
                .mtfTimeframeDirections(mtfAnalysis.getTimeframeDirections())
                .build();
    }

    // ======================== SUPERTREND CALCULATION ========================

    private SuperTrendResult calculateSuperTrend(List<double[]> history, String familyId,
                                                  String timeframe, int period, double multiplier) {
        if (history.size() < period + 1) {
            // FIX: Return null SuperTrend value (Double.NaN) instead of 0 to indicate insufficient data
            // This prevents API from serving fake "0" values that look valid but aren't
            log.debug("[TECH] Insufficient data for SuperTrend {} [{}]: have {} need {}",
                    familyId, timeframe, history.size(), period + 1);
            return new SuperTrendResult(Double.NaN, true, false, 0);
        }

        // Get previous SuperTrend state
        String stateKey = getStateKey(familyId, timeframe, "supertrend");
        SuperTrendState prevState = getSuperTrendState(stateKey);

        // Calculate ATR
        double atr = calculateATR(history, period);

        // Current bar
        double[] current = history.get(history.size() - 1);
        double high = current[0];
        double low = current[1];
        double close = current[2];
        double hl2 = (high + low) / 2;

        // Calculate bands
        double upperBand = hl2 + (multiplier * atr);
        double lowerBand = hl2 - (multiplier * atr);

        // Final bands with proper crossover check (TradingView standard formula)
        // Upper Band: reset if basicUB < prevUB OR prevClose crossed above prevUB
        double finalUpperBand;
        if (prevState.finalUpperBand <= 0) {
            finalUpperBand = upperBand;
        } else if (upperBand < prevState.finalUpperBand || prevState.previousClose > prevState.finalUpperBand) {
            finalUpperBand = upperBand;
        } else {
            finalUpperBand = prevState.finalUpperBand;
        }

        // Lower Band: reset if basicLB > prevLB OR prevClose crossed below prevLB
        double finalLowerBand;
        if (prevState.finalLowerBand <= 0) {
            finalLowerBand = lowerBand;
        } else if (lowerBand > prevState.finalLowerBand || prevState.previousClose < prevState.finalLowerBand) {
            finalLowerBand = lowerBand;
        } else {
            finalLowerBand = prevState.finalLowerBand;
        }

        // Determine SuperTrend value and direction
        double superTrend;
        boolean bullish;

        if (prevState.superTrend == 0) {
            // First calculation
            bullish = close > hl2;
            superTrend = bullish ? finalLowerBand : finalUpperBand;
        } else if (prevState.superTrend == prevState.finalUpperBand) {
            // Previous was bearish
            if (close > finalUpperBand) {
                bullish = true;
                superTrend = finalLowerBand;
            } else {
                bullish = false;
                superTrend = finalUpperBand;
            }
        } else {
            // Previous was bullish
            if (close < finalLowerBand) {
                bullish = false;
                superTrend = finalUpperBand;
            } else {
                bullish = true;
                superTrend = finalLowerBand;
            }
        }

        // Detect flip
        boolean flipped = prevState.bullish != bullish && prevState.superTrend > 0;
        int candlesSinceFlip = flipped ? 0 : prevState.candlesSinceFlip + 1;

        // Store new state (including close as previousClose for next calculation)
        SuperTrendState newState = new SuperTrendState(
                superTrend, finalUpperBand, finalLowerBand, bullish, candlesSinceFlip, close);
        storeSuperTrendState(stateKey, newState);

        if (flipped) {
            log.info("[TECH] SuperTrend FLIP for {} [{}] {} -> {}",
                    familyId, timeframe, bullish ? "BULLISH" : "BEARISH", superTrend);
        }

        return new SuperTrendResult(superTrend, bullish, flipped, candlesSinceFlip);
    }

    // ======================== BOLLINGER BANDS CALCULATION ========================

    private BollingerBandResult calculateBollingerBands(List<double[]> history, int period, double stdDevMult) {
        if (history.size() < period) {
            // FIX: Return NaN for ALL values when insufficient data
            // This ensures:
            // 1. API consumers know the data is invalid
            // 2. No fake squeeze detection (widthPct=NaN won't match any threshold)
            // 3. No fake band values that look plausible but are wrong
            log.debug("[TECH] Insufficient data for BB: have {} need {}", history.size(), period);
            return new BollingerBandResult(
                Double.NaN,  // upper - invalid
                Double.NaN,  // middle - invalid
                Double.NaN,  // lower - invalid
                Double.NaN,  // width - invalid
                Double.NaN,  // widthPct - invalid (prevents false squeeze detection)
                Double.NaN   // percentB - invalid
            );
        }

        // Get last 'period' closes
        List<Double> closes = new ArrayList<>();
        for (int i = Math.max(0, history.size() - period); i < history.size(); i++) {
            closes.add(history.get(i)[2]); // close price
        }

        // Calculate SMA (middle band)
        double sum = closes.stream().mapToDouble(Double::doubleValue).sum();
        double sma = sum / closes.size();

        // Calculate standard deviation
        double variance = closes.stream()
                .mapToDouble(c -> Math.pow(c - sma, 2))
                .sum() / closes.size();
        double stdDev = Math.sqrt(variance);

        // Calculate bands
        double upper = sma + (stdDevMult * stdDev);
        double lower = sma - (stdDevMult * stdDev);
        double width = upper - lower;
        double widthPct = sma > 0 ? width / sma : 0;

        // Calculate %B
        double currentClose = closes.get(closes.size() - 1);
        double percentB = width > 0 ? (currentClose - lower) / width : 0.5;

        return new BollingerBandResult(upper, sma, lower, width, widthPct, percentB);
    }

    // ======================== ATR CALCULATION ========================

    /**
     * Calculate ATR using Wilder's RMA (Running Moving Average) method
     * This matches TradingView's ATR calculation exactly.
     *
     * Formula:
     * - First ATR = SMA of first 'period' true ranges
     * - Subsequent ATR = prevATR + (currentTR - prevATR) / period
     */
    private double calculateATR(List<double[]> history, int period) {
        if (history.size() < 2) return 0;

        // Calculate all True Range values
        List<Double> trueRanges = new ArrayList<>();
        for (int i = 1; i < history.size(); i++) {
            double high = history.get(i)[0];
            double low = history.get(i)[1];
            double prevClose = history.get(i - 1)[2];

            double tr = Math.max(high - low,
                    Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            trueRanges.add(tr);
        }

        if (trueRanges.isEmpty()) return 0;

        // If we don't have enough data for the period, use SMA of what we have
        int actualPeriod = Math.min(period, trueRanges.size());

        // First ATR = SMA of first 'actualPeriod' true ranges
        double sum = 0;
        for (int i = 0; i < actualPeriod; i++) {
            sum += trueRanges.get(i);
        }
        double atr = sum / actualPeriod;

        // Apply Wilder's smoothing (RMA) for remaining values
        // ATR = prevATR + (currentTR - prevATR) / period
        for (int i = actualPeriod; i < trueRanges.size(); i++) {
            atr = atr + (trueRanges.get(i) - atr) / period;
        }

        return atr;
    }

    /**
     * Calculate 5-minute ATR for the given family.
     * This is used for stop calculation in swing trades to get meaningful stop distances.
     *
     * @param familyId Family ID
     * @return 5m ATR, or 0 if not enough data
     */
    public double calculate5mATR(String familyId) {
        List<double[]> history5m = getPriceHistory(familyId, "5m", ATR_PERIOD + 5);

        if (history5m.size() < ATR_PERIOD) {
            log.debug("[TECH] Not enough 5m history for ATR calc: {} candles (need {})",
                    history5m.size(), ATR_PERIOD);
            return 0;
        }

        return calculateATR(history5m, ATR_PERIOD);
    }

    private double calculateATRPercentile(String familyId, String timeframe, double currentATR) {
        String key = getHistoryKey(familyId, timeframe, "atr");
        String atrHistory = redisTemplate.opsForValue().get(key);

        List<Double> atrs = new ArrayList<>();
        if (atrHistory != null && !atrHistory.isEmpty()) {
            for (String val : atrHistory.split(",")) {
                try {
                    atrs.add(Double.parseDouble(val.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }

        atrs.add(currentATR);

        // Keep last 50 ATR values
        while (atrs.size() > 50) {
            atrs.remove(0);
        }

        // Store back
        String newHistory = String.join(",", atrs.stream().map(String::valueOf).toList());
        redisTemplate.opsForValue().set(key, newHistory, HISTORY_TTL);

        // Calculate percentile
        if (atrs.size() < 5) return 50;

        long below = atrs.stream().filter(a -> a < currentATR).count();
        return (double) below / atrs.size() * 100;
    }

    // ======================== PIVOT CALCULATION ========================

    private Map<String, Double> getPivotLevels(String familyId) {
        // Try to get from Redis cache
        String pivotKey = KEY_PREFIX + ":pivots:" + familyId;
        Map<Object, Object> cached = redisTemplate.opsForHash().entries(pivotKey);

        Map<String, Double> pivots = new HashMap<>();

        if (cached != null && !cached.isEmpty()) {
            cached.forEach((k, v) -> {
                try {
                    pivots.put(k.toString(), Double.parseDouble(v.toString()));
                } catch (NumberFormatException ignored) {}
            });
            log.debug("[TECH_DIAG] {} Loaded {} pivots from Redis | dailyPP={} | dailyS1={} | dailyR1={}",
                    familyId, pivots.size(),
                    pivots.get("dailyPP"),
                    pivots.get("dailyS1"),
                    pivots.get("dailyR1"));
        } else {
            log.debug("[TECH_DIAG] {} No pivots in Redis cache (key={})", familyId, pivotKey);
        }

        // FIX: If no cached pivots, try to calculate from session data
        if (pivots.isEmpty() || pivots.get("dailyPP") == null) {
            log.debug("[TECH_DIAG] {} Calculating pivots from session data (cache empty or missing dailyPP)", familyId);
            Map<String, Double> sessionPivots = calculatePivotsFromSessionData(familyId);
            if (!sessionPivots.isEmpty()) {
                pivots.putAll(sessionPivots);
                log.debug("[TECH_DIAG] {} Calculated {} pivots from session | dailyPP={} | dailyS1={} | dailyR1={}",
                        familyId, sessionPivots.size(),
                        pivots.get("dailyPP"),
                        pivots.get("dailyS1"),
                        pivots.get("dailyR1"));
            } else {
                log.warn("[TECH_DIAG] {} NO pivots available! Both Redis and session data empty", familyId);
            }
        }

        return pivots;
    }

    /**
     * Calculate pivots from accumulated session high/low/close data
     * This is a fallback when scheduled job hasn't run yet
     */
    private Map<String, Double> calculatePivotsFromSessionData(String familyId) {
        Map<String, Double> pivots = new HashMap<>();

        // Get session data stored during price updates
        String sessionKey = KEY_PREFIX + ":session:" + familyId;
        Map<Object, Object> sessionData = redisTemplate.opsForHash().entries(sessionKey);

        if (sessionData == null || sessionData.isEmpty()) {
            return pivots;
        }

        try {
            Double high = sessionData.get("high") != null ?
                    Double.parseDouble(sessionData.get("high").toString()) : null;
            Double low = sessionData.get("low") != null ?
                    Double.parseDouble(sessionData.get("low").toString()) : null;
            Double close = sessionData.get("close") != null ?
                    Double.parseDouble(sessionData.get("close").toString()) : null;

            if (high != null && low != null && close != null && high > 0 && low > 0 && close > 0) {
                // Calculate standard (Floor Trader) pivots
                calculateStandardPivots(pivots, high, low, close, "daily");

                // Calculate Camarilla pivots
                calculateCamarillaPivots(pivots, high, low, close);

                // Store in Redis for future use
                storePivots(familyId, pivots);

                log.debug("[TECH] Auto-calculated pivots for {} from session data: PP={}",
                        familyId, pivots.get("dailyPP"));
            }
        } catch (Exception e) {
            log.warn("[TECH] Failed to calculate pivots from session data for {}: {}",
                    familyId, e.getMessage());
        }

        return pivots;
    }

    /**
     * Calculate standard floor trader pivot levels
     */
    private void calculateStandardPivots(Map<String, Double> pivots, double high, double low, double close, String prefix) {
        double pp = (high + low + close) / 3;
        double r1 = 2 * pp - low;
        double r2 = pp + (high - low);
        double r3 = high + 2 * (pp - low);
        double s1 = 2 * pp - high;
        double s2 = pp - (high - low);
        double s3 = low - 2 * (high - pp);

        pivots.put(prefix + "PP", pp);
        pivots.put(prefix + "R1", r1);
        pivots.put(prefix + "R2", r2);
        pivots.put(prefix + "R3", r3);
        pivots.put(prefix + "S1", s1);
        pivots.put(prefix + "S2", s2);
        pivots.put(prefix + "S3", s3);
    }

    /**
     * Calculate Camarilla pivot levels
     * Camarilla pivots are based on the range (H-L) and are tighter than standard pivots
     */
    private void calculateCamarillaPivots(Map<String, Double> pivots, double high, double low, double close) {
        double range = high - low;

        // Camarilla formula uses specific multipliers
        double h4 = close + (range * 1.1 / 2);   // Strong resistance
        double h3 = close + (range * 1.1 / 4);   // Resistance
        double h2 = close + (range * 1.1 / 6);   // Minor resistance
        double h1 = close + (range * 1.1 / 12);  // Weak resistance

        double l1 = close - (range * 1.1 / 12);  // Weak support
        double l2 = close - (range * 1.1 / 6);   // Minor support
        double l3 = close - (range * 1.1 / 4);   // Support
        double l4 = close - (range * 1.1 / 2);   // Strong support

        pivots.put("camH4", h4);
        pivots.put("camH3", h3);
        pivots.put("camH2", h2);
        pivots.put("camH1", h1);
        pivots.put("camL1", l1);
        pivots.put("camL2", l2);
        pivots.put("camL3", l3);
        pivots.put("camL4", l4);
    }

    /**
     * Store pivots in Redis cache
     */
    private void storePivots(String familyId, Map<String, Double> pivots) {
        String pivotKey = KEY_PREFIX + ":pivots:" + familyId;
        Map<String, String> pivotStrings = new HashMap<>();
        pivots.forEach((k, v) -> pivotStrings.put(k, String.valueOf(v)));
        pivotStrings.put("lastUpdate", LocalDate.now().toString());

        redisTemplate.opsForHash().putAll(pivotKey, pivotStrings);
        redisTemplate.expire(pivotKey, Duration.ofDays(2));
    }

    /**
     * Update session high/low/close as prices come in
     * This accumulates data for pivot calculation
     */
    public void updateSessionData(String familyId, double high, double low, double close) {
        String sessionKey = KEY_PREFIX + ":session:" + familyId;

        // Get existing session data
        Double existingHigh = null;
        Double existingLow = null;
        try {
            Object h = redisTemplate.opsForHash().get(sessionKey, "high");
            Object l = redisTemplate.opsForHash().get(sessionKey, "low");
            if (h != null) existingHigh = Double.parseDouble(h.toString());
            if (l != null) existingLow = Double.parseDouble(l.toString());
        } catch (Exception ignored) {}

        // Update high/low if new extremes
        Map<String, String> updates = new HashMap<>();
        if (existingHigh == null || high > existingHigh) {
            updates.put("high", String.valueOf(high));
        }
        if (existingLow == null || low < existingLow) {
            updates.put("low", String.valueOf(low));
        }
        updates.put("close", String.valueOf(close)); // Always update close

        if (!updates.isEmpty()) {
            redisTemplate.opsForHash().putAll(sessionKey, updates);
            redisTemplate.expire(sessionKey, Duration.ofDays(1));
        }
    }

    /**
     * Calculate and store pivot levels from daily OHLC
     * Called by scheduled job once per day
     *
     * @param familyId Family ID
     * @param high Previous day high
     * @param low Previous day low
     * @param close Previous day close
     */
    public void calculateDailyPivots(String familyId, double high, double low, double close) {
        String pivotKey = KEY_PREFIX + ":pivots:" + familyId;

        // Standard pivot calculation
        double pp = (high + low + close) / 3;
        double r1 = 2 * pp - low;
        double r2 = pp + (high - low);
        double r3 = high + 2 * (pp - low);
        double s1 = 2 * pp - high;
        double s2 = pp - (high - low);
        double s3 = low - 2 * (high - pp);

        Map<String, String> pivots = new HashMap<>();
        pivots.put("dailyPP", String.valueOf(pp));
        pivots.put("dailyR1", String.valueOf(r1));
        pivots.put("dailyR2", String.valueOf(r2));
        pivots.put("dailyR3", String.valueOf(r3));
        pivots.put("dailyS1", String.valueOf(s1));
        pivots.put("dailyS2", String.valueOf(s2));
        pivots.put("dailyS3", String.valueOf(s3));
        pivots.put("lastUpdate", LocalDate.now().toString());

        redisTemplate.opsForHash().putAll(pivotKey, pivots);
        redisTemplate.expire(pivotKey, Duration.ofDays(2));

        log.info("[TECH] Calculated daily pivots for {}: PP={}, R1={}, S1={}",
                familyId, pp, r1, s1);
    }

    // ======================== HELPER METHODS ========================

    private double getHighPrice(FamilyCandle family) {
        if (family.getEquity() != null) {
            return family.getEquity().getHigh();
        }
        if (family.getFuture() != null) {
            return family.getFuture().getHigh();
        }
        return family.getPrimaryPrice();
    }

    private double getLowPrice(FamilyCandle family) {
        if (family.getEquity() != null) {
            return family.getEquity().getLow();
        }
        if (family.getFuture() != null) {
            return family.getFuture().getLow();
        }
        return family.getPrimaryPrice();
    }

    private double getOpenPrice(FamilyCandle family) {
        if (family.getEquity() != null) {
            return family.getEquity().getOpen();
        }
        if (family.getFuture() != null) {
            return family.getFuture().getOpen();
        }
        return family.getPrimaryPrice();
    }

    private long getTimestamp(FamilyCandle family) {
        return family.getWindowEndMillis();
    }

    /**
     * Store full OHLC candle history for SMC analysis
     * Stores: open,high,low,close,timestamp
     */
    private void storeFullCandleHistory(String familyId, String timeframe, double open, double high, double low, double close, long timestamp) {
        String key = getHistoryKey(familyId, timeframe, "ohlc");
        String entry = String.format("%.4f,%.4f,%.4f,%.4f,%d", open, high, low, close, timestamp);
        redisTemplate.opsForList().rightPush(key, entry);
        redisTemplate.opsForList().trim(key, -100, -1);
        redisTemplate.expire(key, HISTORY_TTL);
    }

    /**
     * Get full OHLC candle history for SMC analysis
     * Returns: SmcCandleHistory with opens[], highs[], lows[], closes[], timestamps[]
     */
    public SmcCandleHistory getSmcCandleHistory(String familyId, String timeframe, int count) {
        String key = getHistoryKey(familyId, timeframe, "ohlc");
        List<String> entries = redisTemplate.opsForList().range(key, -count, -1);

        if (entries == null || entries.isEmpty()) {
            return SmcCandleHistory.empty();
        }

        double[] opens = new double[entries.size()];
        double[] highs = new double[entries.size()];
        double[] lows = new double[entries.size()];
        double[] closes = new double[entries.size()];
        long[] timestamps = new long[entries.size()];

        int idx = 0;
        for (String entry : entries) {
            try {
                String[] parts = entry.split(",");
                if (parts.length >= 5) {
                    opens[idx] = Double.parseDouble(parts[0]);
                    highs[idx] = Double.parseDouble(parts[1]);
                    lows[idx] = Double.parseDouble(parts[2]);
                    closes[idx] = Double.parseDouble(parts[3]);
                    timestamps[idx] = Long.parseLong(parts[4]);
                    idx++;
                }
            } catch (NumberFormatException ignored) {}
        }

        // If some entries failed parsing, trim arrays
        if (idx < entries.size()) {
            opens = java.util.Arrays.copyOf(opens, idx);
            highs = java.util.Arrays.copyOf(highs, idx);
            lows = java.util.Arrays.copyOf(lows, idx);
            closes = java.util.Arrays.copyOf(closes, idx);
            timestamps = java.util.Arrays.copyOf(timestamps, idx);
        }

        return new SmcCandleHistory(opens, highs, lows, closes, timestamps, timeframe);
    }

    /**
     * Candle history for SMC analysis
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SmcCandleHistory {
        private double[] opens;
        private double[] highs;
        private double[] lows;
        private double[] closes;
        private long[] timestamps;
        private String timeframe;

        public static SmcCandleHistory empty() {
            return new SmcCandleHistory(new double[0], new double[0], new double[0], new double[0], new long[0], "");
        }

        public int size() {
            return closes != null ? closes.length : 0;
        }

        public boolean hasData() {
            return size() > 0;
        }
    }

    /**
     * Store price history with timestamp for proper deduplication.
     * Format: "timestamp,high,low,close"
     */
    private void storePriceHistory(String familyId, String timeframe, double high, double low, double close) {
        storePriceHistoryWithTimestamp(familyId, timeframe, high, low, close, System.currentTimeMillis());
    }

    /**
     * Store price history with explicit timestamp for deduplication.
     */
    private void storePriceHistoryWithTimestamp(String familyId, String timeframe,
                                                  double high, double low, double close, long timestamp) {
        String key = getHistoryKey(familyId, timeframe, "prices_v2");  // New key with timestamp
        // Format: timestamp,high,low,close (timestamp first for sorting/dedup)
        String entry = String.format("%d,%.4f,%.4f,%.4f", timestamp, high, low, close);

        // Use sorted set for automatic deduplication by timestamp
        redisTemplate.opsForZSet().add(key, entry, timestamp);

        // Trim to keep last 200 entries (more than enough for any indicator)
        Long size = redisTemplate.opsForZSet().zCard(key);
        if (size != null && size > 200) {
            redisTemplate.opsForZSet().removeRange(key, 0, size - 201);
        }
        redisTemplate.expire(key, HISTORY_TTL);
    }

    /**
     * Get price history with automatic bootstrap from historical data store.
     *
     * FIX: This now:
     * 1. Checks real-time streaming data first (prices_v2 sorted set)
     * 2. If insufficient, bootstraps from historical candle store (candles:timeframe:scripCode)
     * 3. Merges both sources with timestamp-based deduplication
     * 4. Returns aligned, deduplicated history sorted chronologically
     *
     * @param familyId Family ID (usually scripCode for equities)
     * @param timeframe Timeframe (1m, 5m, 15m, 30m, 1h)
     * @param count Number of candles needed
     * @return List of [high, low, close] arrays, oldest first
     */
    private List<double[]> getPriceHistory(String familyId, String timeframe, int count) {
        // Use TreeMap for automatic timestamp-based sorting and deduplication
        // Key = timestamp, Value = [high, low, close]
        TreeMap<Long, double[]> mergedHistory = new TreeMap<>();

        // 1. Load from real-time streaming store (new v2 format with timestamps)
        String streamingKey = getHistoryKey(familyId, timeframe, "prices_v2");
        Set<String> streamingEntries = redisTemplate.opsForZSet().reverseRange(streamingKey, 0, count - 1);

        if (streamingEntries != null) {
            for (String entry : streamingEntries) {
                try {
                    String[] parts = entry.split(",");
                    if (parts.length >= 4) {
                        long ts = Long.parseLong(parts[0]);
                        double high = Double.parseDouble(parts[1]);
                        double low = Double.parseDouble(parts[2]);
                        double close = Double.parseDouble(parts[3]);
                        mergedHistory.put(ts, new double[]{high, low, close});
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 2. Also check legacy format (without timestamps) for backward compatibility
        String legacyKey = getHistoryKey(familyId, timeframe, "prices");
        List<String> legacyEntries = redisTemplate.opsForList().range(legacyKey, -count, -1);
        if (legacyEntries != null && !legacyEntries.isEmpty() && mergedHistory.size() < count) {
            // Legacy entries don't have timestamps, use sequential fake timestamps
            // These will be overwritten if real data exists
            long fakeTs = System.currentTimeMillis() - (legacyEntries.size() * 60000);
            for (String entry : legacyEntries) {
                try {
                    String[] parts = entry.split(",");
                    if (parts.length >= 3) {
                        double high = Double.parseDouble(parts[0]);
                        double low = Double.parseDouble(parts[1]);
                        double close = Double.parseDouble(parts[2]);
                        // Only add if not already present
                        mergedHistory.putIfAbsent(fakeTs, new double[]{high, low, close});
                        fakeTs += 60000;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 3. If still insufficient, bootstrap from historical candle store
        String bootstrapKey = familyId + ":" + timeframe;
        int beforeBootstrap = mergedHistory.size();
        if (mergedHistory.size() < count && !bootstrappedKeys.contains(bootstrapKey)) {
            log.info("[TECH-HISTORY] {} [{}] Triggering bootstrap: have {} need {} | streamingData={} legacyData={}",
                    familyId, timeframe, mergedHistory.size(), count,
                    streamingEntries != null ? streamingEntries.size() : 0,
                    legacyEntries != null ? legacyEntries.size() : 0);

            boolean bootstrapSuccess = bootstrapFromHistoricalStore(familyId, timeframe, count, mergedHistory);

            // FIX: Only mark as bootstrapped if we actually loaded sufficient data
            // This allows retry on next candle if bootstrap failed or loaded nothing
            if (bootstrapSuccess && mergedHistory.size() >= count) {
                bootstrappedKeys.add(bootstrapKey);
                log.info("[TECH-HISTORY] {} [{}] Bootstrap SUCCESS: {} -> {} candles",
                        familyId, timeframe, beforeBootstrap, mergedHistory.size());
            } else {
                log.warn("[TECH-HISTORY] {} [{}] Bootstrap INCOMPLETE: {} -> {} candles (need {}), will retry",
                        familyId, timeframe, beforeBootstrap, mergedHistory.size(), count);
            }
        }

        // 4. Convert to list (TreeMap is already sorted by timestamp ascending)
        List<double[]> history = new ArrayList<>();
        int skip = Math.max(0, mergedHistory.size() - count);
        int idx = 0;
        for (double[] hlc : mergedHistory.values()) {
            if (idx >= skip) {
                history.add(hlc);
            }
            idx++;
        }

        if (history.size() < count) {
            log.debug("[TECH] Insufficient history for {} [{}]: have {} need {}",
                    familyId, timeframe, history.size(), count);
        }

        return history;
    }

    /**
     * Bootstrap price history from RedisCandleHistoryService's historical data store.
     *
     * The historical store uses Redis sorted sets with key pattern: candles:{timeframe}:{scripCode}
     * Each entry is a JSON-serialized UnifiedCandle with score = windowStartMillis.
     *
     * @return true if at least some candles were loaded, false otherwise
     */
    private boolean bootstrapFromHistoricalStore(String familyId, String timeframe, int count,
                                               TreeMap<Long, double[]> mergedHistory) {
        String historicalKey = HISTORICAL_KEY_PREFIX + timeframe + ":" + familyId;

        try {
            // FIX: Fetch more candles than needed (3x) to account for deduplication with streaming data
            // The streaming data and historical data often overlap in timestamps
            int fetchCount = Math.max(count * 3, 100);
            Set<String> historicalEntries = redisTemplate.opsForZSet().reverseRange(historicalKey, 0, fetchCount - 1);

            if (historicalEntries == null || historicalEntries.isEmpty()) {
                log.warn("[TECH-BOOTSTRAP] No historical data found for {} [{}] key={} - check if RedisCandleHistoryService is storing data",
                        familyId, timeframe, historicalKey);
                return false;
            }

            log.debug("[TECH-BOOTSTRAP] Found {} entries in historical store for {} [{}]",
                    historicalEntries.size(), familyId, timeframe);

            int bootstrappedCount = 0;
            for (String json : historicalEntries) {
                try {
                    UnifiedCandle candle = objectMapper.readValue(json, UnifiedCandle.class);
                    if (candle != null && candle.getWindowStartMillis() > 0) {
                        long ts = candle.getWindowStartMillis();
                        // Only add if not already present (real-time data takes precedence)
                        if (!mergedHistory.containsKey(ts)) {
                            mergedHistory.put(ts, new double[]{
                                    candle.getHigh(),
                                    candle.getLow(),
                                    candle.getClose()
                            });
                            bootstrappedCount++;
                        }
                    }
                } catch (Exception e) {
                    log.trace("[TECH-BOOTSTRAP] Failed to parse candle: {}", e.getMessage());
                }
            }

            if (bootstrappedCount > 0) {
                log.info("[TECH-BOOTSTRAP] Loaded {} historical candles for {} [{}], total now: {}",
                        bootstrappedCount, familyId, timeframe, mergedHistory.size());

                // FIX: Persist bootstrapped data to streaming store so it survives across calls
                persistToStreamingStore(familyId, timeframe, mergedHistory);

                // Also warm up SuperTrend state from historical data if needed
                warmupSuperTrendState(familyId, timeframe, mergedHistory);
                return true;
            } else {
                log.warn("[TECH-BOOTSTRAP] Found {} entries but parsed 0 candles for {} [{}]",
                        historicalEntries.size(), familyId, timeframe);
                return false;
            }
        } catch (Exception e) {
            log.warn("[TECH-BOOTSTRAP] Failed to bootstrap from historical store for {} [{}]: {}",
                    familyId, timeframe, e.getMessage());
            return false;
        }
    }

    /**
     * Warm up SuperTrend state by processing historical candles in sequence.
     * This ensures the first real-time candle gets correct SuperTrend values.
     */
    private void warmupSuperTrendState(String familyId, String timeframe, TreeMap<Long, double[]> history) {
        if (history.size() < MIN_CANDLES_FOR_SUPERTREND) {
            return;
        }

        String stateKey = getStateKey(familyId, timeframe, "supertrend");
        SuperTrendState existingState = getSuperTrendState(stateKey);

        // Only warmup if no existing state
        if (existingState.superTrend > 0) {
            log.debug("[TECH-WARMUP] SuperTrend state already exists for {} [{}], skipping warmup",
                    familyId, timeframe);
            return;
        }

        // Convert to list for sequential processing
        List<double[]> historyList = new ArrayList<>(history.values());

        // Process candles sequentially to build up SuperTrend state
        // We need to simulate the calculation to build correct state
        double atr = calculateATR(historyList, SUPERTREND_PERIOD);
        if (atr <= 0) {
            return;
        }

        // Get last candle for initial state
        double[] lastCandle = historyList.get(historyList.size() - 1);
        double high = lastCandle[0];
        double low = lastCandle[1];
        double close = lastCandle[2];
        double hl2 = (high + low) / 2;

        double upperBand = hl2 + (SUPERTREND_MULTIPLIER * atr);
        double lowerBand = hl2 - (SUPERTREND_MULTIPLIER * atr);
        boolean bullish = close > hl2;
        double superTrend = bullish ? lowerBand : upperBand;

        SuperTrendState warmupState = new SuperTrendState(
                superTrend, upperBand, lowerBand, bullish, 0, close);
        storeSuperTrendState(stateKey, warmupState);

        log.info("[TECH-WARMUP] Initialized SuperTrend state for {} [{}]: ST={} dir={} from {} candles",
                familyId, timeframe, String.format("%.2f", superTrend), bullish ? "BULL" : "BEAR", historyList.size());
    }

    private String getHistoryKey(String familyId, String timeframe, String metric) {
        return String.format("%s:history:%s:%s:%s", KEY_PREFIX, familyId, timeframe, metric);
    }

    /**
     * Persist bootstrapped historical data to the streaming store (prices_v2).
     * This ensures the data survives across method calls and doesn't require re-bootstrapping.
     */
    private void persistToStreamingStore(String familyId, String timeframe, TreeMap<Long, double[]> history) {
        String streamingKey = getHistoryKey(familyId, timeframe, "prices_v2");

        try {
            // Store each historical point with timestamp as score
            for (Map.Entry<Long, double[]> entry : history.entrySet()) {
                long ts = entry.getKey();
                double[] hlc = entry.getValue();
                // Format: timestamp,high,low,close
                String value = String.format("%d,%.6f,%.6f,%.6f", ts, hlc[0], hlc[1], hlc[2]);
                redisTemplate.opsForZSet().add(streamingKey, value, ts);
            }

            // Trim to keep only last 200 entries
            long size = redisTemplate.opsForZSet().zCard(streamingKey);
            if (size > 200) {
                redisTemplate.opsForZSet().removeRange(streamingKey, 0, size - 201);
            }

            log.debug("[TECH-BOOTSTRAP] Persisted {} candles to streaming store for {} [{}]",
                    history.size(), familyId, timeframe);
        } catch (Exception e) {
            log.warn("[TECH-BOOTSTRAP] Failed to persist to streaming store for {} [{}]: {}",
                    familyId, timeframe, e.getMessage());
        }
    }

    private String getStateKey(String familyId, String timeframe, String indicator) {
        return String.format("%s:state:%s:%s:%s", KEY_PREFIX, familyId, timeframe, indicator);
    }

    private SuperTrendState getSuperTrendState(String key) {
        String state = redisTemplate.opsForValue().get(key);
        if (state == null) return new SuperTrendState(0, 0, 0, true, 0, 0);

        try {
            String[] parts = state.split(",");
            // Handle both old format (5 fields) and new format (6 fields with previousClose)
            return new SuperTrendState(
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Boolean.parseBoolean(parts[3]),
                    Integer.parseInt(parts[4]),
                    parts.length > 5 ? Double.parseDouble(parts[5]) : 0
            );
        } catch (Exception e) {
            return new SuperTrendState(0, 0, 0, true, 0, 0);
        }
    }

    private void storeSuperTrendState(String key, SuperTrendState state) {
        String value = String.format("%f,%f,%f,%b,%d,%f",
                state.superTrend, state.finalUpperBand, state.finalLowerBand,
                state.bullish, state.candlesSinceFlip, state.previousClose);
        redisTemplate.opsForValue().set(key, value, HISTORY_TTL);
    }

    private SessionData getSessionData(String familyId, FamilyCandle family) {
        // In production, this would aggregate from daily data
        // For now, return basic session data
        return new SessionData(
                null, // VWAP - needs tick data
                getHighPrice(family),
                getLowPrice(family),
                family.getPrimaryPrice()
        );
    }

    private Double findNearestSupport(double price, Map<String, Double> pivots, double bbLower) {
        List<Double> supports = new ArrayList<>();

        // Add standard pivot supports
        if (pivots.get("dailyS1") != null) supports.add(pivots.get("dailyS1"));
        if (pivots.get("dailyS2") != null) supports.add(pivots.get("dailyS2"));
        if (pivots.get("dailyS3") != null) supports.add(pivots.get("dailyS3"));
        if (pivots.get("weeklyS1") != null) supports.add(pivots.get("weeklyS1"));
        if (pivots.get("monthlyS1") != null) supports.add(pivots.get("monthlyS1"));

        // Add Camarilla supports (L3 and L4 are key levels)
        if (pivots.get("camL3") != null) supports.add(pivots.get("camL3"));
        if (pivots.get("camL4") != null) supports.add(pivots.get("camL4"));

        // Add BB lower as support
        supports.add(bbLower);

        // Find nearest below price
        return supports.stream()
                .filter(s -> s < price)
                .max(Double::compare)
                .orElse(null);
    }

    private Double findNearestResistance(double price, Map<String, Double> pivots, double bbUpper) {
        List<Double> resistances = new ArrayList<>();

        // Add standard pivot resistances
        if (pivots.get("dailyR1") != null) resistances.add(pivots.get("dailyR1"));
        if (pivots.get("dailyR2") != null) resistances.add(pivots.get("dailyR2"));
        if (pivots.get("dailyR3") != null) resistances.add(pivots.get("dailyR3"));
        if (pivots.get("weeklyR1") != null) resistances.add(pivots.get("weeklyR1"));
        if (pivots.get("monthlyR1") != null) resistances.add(pivots.get("monthlyR1"));

        // Add Camarilla resistances (H3 and H4 are key levels)
        if (pivots.get("camH3") != null) resistances.add(pivots.get("camH3"));
        if (pivots.get("camH4") != null) resistances.add(pivots.get("camH4"));

        // Add BB upper as resistance
        resistances.add(bbUpper);

        // Find nearest above price
        return resistances.stream()
                .filter(r -> r > price)
                .min(Double::compare)
                .orElse(null);
    }

    // ======================== INNER CLASSES ========================

    private record SuperTrendResult(double value, boolean bullish, boolean flipped, int candlesSinceFlip) {}

    private record BollingerBandResult(double upper, double middle, double lower,
                                        double width, double widthPct, double percentB) {}

    private record SuperTrendState(double superTrend, double finalUpperBand, double finalLowerBand,
                                    boolean bullish, int candlesSinceFlip, double previousClose) {}

    private record SessionData(Double vwap, Double high, Double low, Double open) {}
}
