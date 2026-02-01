package com.kotsin.consumer.signal.trigger;

import com.kotsin.consumer.indicator.calculator.BBSuperTrendCalculator;
import com.kotsin.consumer.indicator.model.BBSuperTrend;
import com.kotsin.consumer.indicator.model.BBSuperTrend.*;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.service.CandleService;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FudkiiSignalTrigger - Handles Strategy 1: SuperTrend + Bollinger Band trigger.
 *
 * TRIGGER CONDITIONS:
 * - Timeframe: 30 minutes
 * - BULLISH: SuperTrend flips from DOWN to UP (red→green) AND close > BB_UPPER
 * - BEARISH: SuperTrend flips from UP to DOWN (green→red) AND close < BB_LOWER
 *
 * TRIGGER TIMES (NSE):
 * 9:15, 9:45, 10:15, 10:45, 11:15, 11:45, 12:15, 12:45, 1:15, 1:45, 2:15, 2:45, 3:15
 *
 * Microstructure, SMC, Pivot data → Used for TARGET and STOP LOSS only, not trigger
 */
@Component
@Slf4j
public class FudkiiSignalTrigger {

    private static final String LOG_PREFIX = "[FUDKII-TRIGGER]";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // 30-minute candle close times for NSE (in IST)
    private static final int[] CANDLE_CLOSE_MINUTES = {15, 45};
    private static final int MARKET_OPEN_HOUR = 9;
    private static final int MARKET_CLOSE_HOUR = 15;
    private static final int MARKET_CLOSE_MINUTE = 30;

    @Autowired
    private BBSuperTrendCalculator bbstCalculator;

    @Autowired
    private CandleService candleService;

    @Value("${fudkii.trigger.enabled:true}")
    private boolean enabled;

    @Value("${fudkii.trigger.timeframe:30m}")
    private String triggerTimeframe;

    @Value("${fudkii.trigger.bb.period:20}")
    private int bbPeriod;

    @Value("${fudkii.trigger.st.period:14}")
    private int stPeriod;

    // Cache for last BBST state per symbol
    private final Map<String, BBSuperTrend> lastBbstState = new ConcurrentHashMap<>();

    // Cache for last trigger time to avoid duplicate triggers
    private final Map<String, Instant> lastTriggerTime = new ConcurrentHashMap<>();

    /**
     * Check if FUDKII signal should trigger for given symbol.
     *
     * @param scripCode Symbol scripCode
     * @return FudkiiTriggerResult with trigger status and details
     */
    public FudkiiTriggerResult checkTrigger(String scripCode) {
        if (!enabled) {
            return FudkiiTriggerResult.noTrigger("FUDKII trigger disabled");
        }

        String symbol = scripCode; // Using scripCode as identifier

        try {
            // Step 1: Check if it's a valid 30m candle close time
            if (!isValidTriggerTime()) {
                log.debug("{} {} Not a valid 30m candle close time", LOG_PREFIX, symbol);
                return FudkiiTriggerResult.noTrigger("Not 30m candle close time");
            }

            // Step 2: Get 30m aggregated candles (need at least 21 for BB20)
            List<UnifiedCandle> candles30m = candleService.getCandleHistory(scripCode, Timeframe.M30, 50);
            if (candles30m == null || candles30m.size() < 21) {
                log.debug("{} {} Insufficient 30m candles: {}", LOG_PREFIX, symbol,
                    candles30m == null ? 0 : candles30m.size());
                return FudkiiTriggerResult.noTrigger("Insufficient 30m candles");
            }

            log.info("{} {} Evaluating 30m candles: count={}, latest close={}",
                LOG_PREFIX, symbol, candles30m.size(), candles30m.get(0).getClose());

            // Step 3: Prepare OHLC arrays (oldest to newest for calculation)
            int n = candles30m.size();
            double[] closes = new double[n];
            double[] highs = new double[n];
            double[] lows = new double[n];

            for (int i = 0; i < n; i++) {
                UnifiedCandle c = candles30m.get(n - 1 - i); // Reverse: oldest first
                closes[i] = c.getClose();
                highs[i] = c.getHigh();
                lows[i] = c.getLow();
            }

            // Step 4: Calculate BB-SuperTrend
            BBSuperTrend bbst = bbstCalculator.calculate(symbol, "30m", closes, highs, lows);

            log.info("{} {} BBST calculated: trend={}, trendChanged={}, pricePosition={}, " +
                    "BB=[{}, {}, {}], ST={}, close={}",
                LOG_PREFIX, symbol,
                bbst.getTrend(), bbst.isTrendChanged(), bbst.getPricePosition(),
                String.format("%.2f", bbst.getBbLower()),
                String.format("%.2f", bbst.getBbMiddle()),
                String.format("%.2f", bbst.getBbUpper()),
                String.format("%.2f", bbst.getSuperTrend()),
                String.format("%.2f", closes[n - 1]));

            // Step 5: Check trigger conditions
            FudkiiTriggerResult result = evaluateTrigger(symbol, bbst, closes[n - 1], candles30m.get(0));

            // Step 6: Cache current state
            lastBbstState.put(symbol, bbst);

            if (result.isTriggered()) {
                lastTriggerTime.put(symbol, Instant.now());
                log.info("{} {} *** SIGNAL TRIGGERED *** direction={}, reason={}",
                    LOG_PREFIX, symbol, result.getDirection(), result.getReason());
            }

            return result;

        } catch (Exception e) {
            log.error("{} {} Error checking trigger: {}", LOG_PREFIX, symbol, e.getMessage(), e);
            return FudkiiTriggerResult.noTrigger("Error: " + e.getMessage());
        }
    }

    /**
     * Evaluate trigger conditions.
     */
    private FudkiiTriggerResult evaluateTrigger(String symbol, BBSuperTrend bbst,
                                                 double currentClose, UnifiedCandle latestCandle) {

        // Get previous state for comparison
        BBSuperTrend prevBbst = lastBbstState.get(symbol);

        // Check if SuperTrend just flipped
        boolean superTrendFlipped = bbst.isTrendChanged();

        // If no flip, check if we have previous state to compare
        if (!superTrendFlipped && prevBbst != null) {
            superTrendFlipped = prevBbst.getTrend() != bbst.getTrend();
        }

        // Price position relative to BB
        PricePosition pricePos = bbst.getPricePosition();
        boolean aboveUpperBB = pricePos == PricePosition.ABOVE_UPPER;
        boolean belowLowerBB = pricePos == PricePosition.BELOW_LOWER;

        // Detailed logging
        log.info("{} {} Trigger evaluation: ST_flipped={}, trend={}, pricePos={}, " +
                "aboveUpper={}, belowLower={}, BB_upper={}, BB_lower={}, close={}",
            LOG_PREFIX, symbol,
            superTrendFlipped, bbst.getTrend(), pricePos,
            aboveUpperBB, belowLowerBB,
            String.format("%.2f", bbst.getBbUpper()),
            String.format("%.2f", bbst.getBbLower()),
            String.format("%.2f", currentClose));

        // BULLISH TRIGGER: ST flips UP + close above BB upper
        if (superTrendFlipped && bbst.getTrend() == TrendDirection.UP && aboveUpperBB) {
            return FudkiiTriggerResult.builder()
                .triggered(true)
                .direction(TriggerDirection.BULLISH)
                .reason(String.format("ST flipped UP + close (%.2f) > BB_upper (%.2f)",
                    currentClose, bbst.getBbUpper()))
                .bbst(bbst)
                .triggerPrice(currentClose)
                .triggerTime(Instant.now())
                .latestCandle(latestCandle)
                .build();
        }

        // BEARISH TRIGGER: ST flips DOWN + close below BB lower
        if (superTrendFlipped && bbst.getTrend() == TrendDirection.DOWN && belowLowerBB) {
            return FudkiiTriggerResult.builder()
                .triggered(true)
                .direction(TriggerDirection.BEARISH)
                .reason(String.format("ST flipped DOWN + close (%.2f) < BB_lower (%.2f)",
                    currentClose, bbst.getBbLower()))
                .bbst(bbst)
                .triggerPrice(currentClose)
                .triggerTime(Instant.now())
                .latestCandle(latestCandle)
                .build();
        }

        // No trigger - log why
        String noTriggerReason;
        if (!superTrendFlipped) {
            noTriggerReason = "SuperTrend did not flip";
        } else if (bbst.getTrend() == TrendDirection.UP && !aboveUpperBB) {
            noTriggerReason = String.format("ST UP but close (%.2f) not above BB_upper (%.2f)",
                currentClose, bbst.getBbUpper());
        } else if (bbst.getTrend() == TrendDirection.DOWN && !belowLowerBB) {
            noTriggerReason = String.format("ST DOWN but close (%.2f) not below BB_lower (%.2f)",
                currentClose, bbst.getBbLower());
        } else {
            noTriggerReason = "Conditions not met";
        }

        log.debug("{} {} No trigger: {}", LOG_PREFIX, symbol, noTriggerReason);

        return FudkiiTriggerResult.builder()
            .triggered(false)
            .direction(TriggerDirection.NONE)
            .reason(noTriggerReason)
            .bbst(bbst)
            .triggerPrice(currentClose)
            .triggerTime(Instant.now())
            .latestCandle(latestCandle)
            .build();
    }

    /**
     * Check if current time is a valid 30m candle close time.
     */
    private boolean isValidTriggerTime() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        int hour = now.getHour();
        int minute = now.getMinute();

        // Market hours check
        if (hour < MARKET_OPEN_HOUR || hour > MARKET_CLOSE_HOUR) {
            return false;
        }
        if (hour == MARKET_CLOSE_HOUR && minute > MARKET_CLOSE_MINUTE) {
            return false;
        }

        // Check if minute is within 2 minutes of candle close (15 or 45)
        for (int closeMinute : CANDLE_CLOSE_MINUTES) {
            if (Math.abs(minute - closeMinute) <= 2) {
                return true;
            }
        }

        return false;
    }

    /**
     * Force trigger check (bypasses time check - for testing).
     */
    public FudkiiTriggerResult forceCheckTrigger(String scripCode) {
        log.info("{} {} Force trigger check requested", LOG_PREFIX, scripCode);

        String symbol = scripCode;

        try {
            List<UnifiedCandle> candles30m = candleService.getCandleHistory(scripCode, Timeframe.M30, 50);
            log.debug("{} {} 30m candles fetched: count={}", LOG_PREFIX, scripCode,
                candles30m == null ? 0 : candles30m.size());
            if (candles30m == null || candles30m.size() < 21) {
                return FudkiiTriggerResult.noTrigger(String.format("Insufficient 30m candles: got %d, need 21",
                    candles30m == null ? 0 : candles30m.size()));
            }

            int n = candles30m.size();
            double[] closes = new double[n];
            double[] highs = new double[n];
            double[] lows = new double[n];

            for (int i = 0; i < n; i++) {
                UnifiedCandle c = candles30m.get(n - 1 - i);
                closes[i] = c.getClose();
                highs[i] = c.getHigh();
                lows[i] = c.getLow();
            }

            BBSuperTrend bbst = bbstCalculator.calculate(symbol, "30m", closes, highs, lows);
            lastBbstState.put(symbol, bbst);

            return evaluateTrigger(symbol, bbst, closes[n - 1], candles30m.get(0));

        } catch (Exception e) {
            log.error("{} {} Force check error: {}", LOG_PREFIX, symbol, e.getMessage(), e);
            return FudkiiTriggerResult.noTrigger("Error: " + e.getMessage());
        }
    }

    /**
     * Get current BBST state for symbol.
     */
    public Optional<BBSuperTrend> getBbstState(String symbol) {
        return Optional.ofNullable(lastBbstState.get(symbol));
    }

    // ==================== RESULT CLASS ====================

    @Data
    @Builder
    public static class FudkiiTriggerResult {
        private boolean triggered;
        private TriggerDirection direction;
        private String reason;
        private BBSuperTrend bbst;
        private double triggerPrice;
        private Instant triggerTime;
        private UnifiedCandle latestCandle;

        public static FudkiiTriggerResult noTrigger(String reason) {
            return FudkiiTriggerResult.builder()
                .triggered(false)
                .direction(TriggerDirection.NONE)
                .reason(reason)
                .build();
        }

        public boolean isBullish() {
            return direction == TriggerDirection.BULLISH;
        }

        public boolean isBearish() {
            return direction == TriggerDirection.BEARISH;
        }
    }

    public enum TriggerDirection {
        BULLISH, BEARISH, NONE
    }
}
