package com.kotsin.consumer.enrichment.enricher;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TechnicalIndicatorEnricher {

    private final RedisTemplate<String, String> redisTemplate;

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
    private static final Duration HISTORY_TTL = Duration.ofHours(48);

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
        double high = getHighPrice(family);
        double low = getLowPrice(family);

        // Store current price in history
        storePriceHistory(familyId, timeframe, high, low, close);

        // Get price history
        List<double[]> history = getPriceHistory(familyId, timeframe, BB_PERIOD + 5);

        if (history.size() < 5) {
            // Not enough data yet
            return TechnicalContext.builder()
                    .familyId(familyId)
                    .timeframe(timeframe)
                    .currentPrice(close)
                    .superTrendBullish(true)
                    .superTrendFlip(false)
                    .bbPercentB(0.5)
                    .build();
        }

        // Calculate ATR first (needed for SuperTrend)
        double atr = calculateATR(history, ATR_PERIOD);
        double atrPct = close > 0 ? atr / close * 100 : 0;

        // Calculate SuperTrend
        SuperTrendResult superTrend = calculateSuperTrend(history, familyId, timeframe, SUPERTREND_PERIOD, SUPERTREND_MULTIPLIER);

        // Calculate Bollinger Bands
        BollingerBandResult bb = calculateBollingerBands(history, BB_PERIOD, BB_STD_DEV);

        // Load pivot levels (from MongoDB or pre-calculated)
        Map<String, Double> pivots = getPivotLevels(familyId);

        // Get session data
        SessionData session = getSessionData(familyId, family);

        // Find nearest support/resistance
        Double nearestSupport = findNearestSupport(close, pivots, bb.lower);
        Double nearestResistance = findNearestResistance(close, pivots, bb.upper);

        double distToSupport = nearestSupport != null ?
                (close - nearestSupport) / close * 100 : 99;
        double distToResistance = nearestResistance != null ?
                (nearestResistance - close) / close * 100 : 99;

        // Calculate ATR percentile (compare to recent ATR values)
        double atrPercentile = calculateATRPercentile(familyId, timeframe, atr);
        boolean volatilityExpanding = atrPercentile > 70;

        // Build context
        return TechnicalContext.builder()
                .familyId(familyId)
                .timeframe(timeframe)
                .currentPrice(close)
                // SuperTrend
                .superTrendValue(superTrend.value)
                .superTrendBullish(superTrend.bullish)
                .superTrendFlip(superTrend.flipped)
                .candlesSinceStFlip(superTrend.candlesSinceFlip)
                .superTrendMultiplier(SUPERTREND_MULTIPLIER)
                .superTrendPeriod(SUPERTREND_PERIOD)
                // Bollinger Bands
                .bbUpper(bb.upper)
                .bbMiddle(bb.middle)
                .bbLower(bb.lower)
                .bbWidth(bb.width)
                .bbWidthPct(bb.widthPct)
                .bbPercentB(bb.percentB)
                .bbSqueezing(bb.widthPct < BB_SQUEEZE_THRESHOLD)
                .bbSqueezeThreshold(BB_SQUEEZE_THRESHOLD)
                // ATR
                .atr(atr)
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
                .build();
    }

    // ======================== SUPERTREND CALCULATION ========================

    private SuperTrendResult calculateSuperTrend(List<double[]> history, String familyId,
                                                  String timeframe, int period, double multiplier) {
        if (history.size() < period + 1) {
            return new SuperTrendResult(0, true, false, 0);
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

        // Final bands (compare with previous)
        double finalUpperBand = prevState.finalUpperBand > 0 ?
                Math.min(upperBand, prevState.finalUpperBand) : upperBand;
        double finalLowerBand = prevState.finalLowerBand > 0 ?
                Math.max(lowerBand, prevState.finalLowerBand) : lowerBand;

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

        // Store new state
        SuperTrendState newState = new SuperTrendState(
                superTrend, finalUpperBand, finalLowerBand, bullish, candlesSinceFlip);
        storeSuperTrendState(stateKey, newState);

        if (flipped) {
            log.info("[TECH] SuperTrend FLIP for {} {} -> {}",
                    familyId, bullish ? "BULLISH" : "BEARISH", superTrend);
        }

        return new SuperTrendResult(superTrend, bullish, flipped, candlesSinceFlip);
    }

    // ======================== BOLLINGER BANDS CALCULATION ========================

    private BollingerBandResult calculateBollingerBands(List<double[]> history, int period, double stdDevMult) {
        if (history.size() < period) {
            double close = history.isEmpty() ? 0 : history.get(history.size() - 1)[2];
            return new BollingerBandResult(close, close, close, 0, 0, 0.5);
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

    private double calculateATR(List<double[]> history, int period) {
        if (history.size() < 2) return 0;

        List<Double> trueRanges = new ArrayList<>();

        for (int i = 1; i < history.size(); i++) {
            double high = history.get(i)[0];
            double low = history.get(i)[1];
            double prevClose = history.get(i - 1)[2];

            double tr = Math.max(high - low,
                    Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            trueRanges.add(tr);
        }

        // Simple moving average of TR
        int start = Math.max(0, trueRanges.size() - period);
        double sum = 0;
        int count = 0;
        for (int i = start; i < trueRanges.size(); i++) {
            sum += trueRanges.get(i);
            count++;
        }

        return count > 0 ? sum / count : 0;
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
        }

        // If no cached pivots, return empty (should be populated by scheduled job)
        return pivots;
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

    private void storePriceHistory(String familyId, String timeframe, double high, double low, double close) {
        String key = getHistoryKey(familyId, timeframe, "prices");
        String entry = String.format("%.4f,%.4f,%.4f", high, low, close);

        // Append to list
        redisTemplate.opsForList().rightPush(key, entry);
        // Trim to keep last 100 entries
        redisTemplate.opsForList().trim(key, -100, -1);
        redisTemplate.expire(key, HISTORY_TTL);
    }

    private List<double[]> getPriceHistory(String familyId, String timeframe, int count) {
        String key = getHistoryKey(familyId, timeframe, "prices");
        List<String> entries = redisTemplate.opsForList().range(key, -count, -1);

        List<double[]> history = new ArrayList<>();
        if (entries != null) {
            for (String entry : entries) {
                try {
                    String[] parts = entry.split(",");
                    if (parts.length >= 3) {
                        history.add(new double[]{
                                Double.parseDouble(parts[0]),
                                Double.parseDouble(parts[1]),
                                Double.parseDouble(parts[2])
                        });
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return history;
    }

    private String getHistoryKey(String familyId, String timeframe, String metric) {
        return String.format("%s:history:%s:%s:%s", KEY_PREFIX, familyId, timeframe, metric);
    }

    private String getStateKey(String familyId, String timeframe, String indicator) {
        return String.format("%s:state:%s:%s:%s", KEY_PREFIX, familyId, timeframe, indicator);
    }

    private SuperTrendState getSuperTrendState(String key) {
        String state = redisTemplate.opsForValue().get(key);
        if (state == null) return new SuperTrendState(0, 0, 0, true, 0);

        try {
            String[] parts = state.split(",");
            return new SuperTrendState(
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Boolean.parseBoolean(parts[3]),
                    Integer.parseInt(parts[4])
            );
        } catch (Exception e) {
            return new SuperTrendState(0, 0, 0, true, 0);
        }
    }

    private void storeSuperTrendState(String key, SuperTrendState state) {
        String value = String.format("%f,%f,%f,%b,%d",
                state.superTrend, state.finalUpperBand, state.finalLowerBand,
                state.bullish, state.candlesSinceFlip);
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

        // Add pivot supports
        if (pivots.get("dailyS1") != null) supports.add(pivots.get("dailyS1"));
        if (pivots.get("dailyS2") != null) supports.add(pivots.get("dailyS2"));
        if (pivots.get("dailyS3") != null) supports.add(pivots.get("dailyS3"));
        if (pivots.get("weeklyS1") != null) supports.add(pivots.get("weeklyS1"));
        if (pivots.get("monthlyS1") != null) supports.add(pivots.get("monthlyS1"));

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

        // Add pivot resistances
        if (pivots.get("dailyR1") != null) resistances.add(pivots.get("dailyR1"));
        if (pivots.get("dailyR2") != null) resistances.add(pivots.get("dailyR2"));
        if (pivots.get("dailyR3") != null) resistances.add(pivots.get("dailyR3"));
        if (pivots.get("weeklyR1") != null) resistances.add(pivots.get("weeklyR1"));
        if (pivots.get("monthlyR1") != null) resistances.add(pivots.get("monthlyR1"));

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
                                    boolean bullish, int candlesSinceFlip) {}

    private record SessionData(Double vwap, Double high, Double low, Double open) {}
}
