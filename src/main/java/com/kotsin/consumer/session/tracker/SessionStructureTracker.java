package com.kotsin.consumer.session.tracker;

import com.kotsin.consumer.session.model.SessionStructure;
import com.kotsin.consumer.session.model.SessionStructure.GapType;
import com.kotsin.consumer.session.model.SessionStructure.PricePosition;
import com.kotsin.consumer.session.model.SessionStructure.SessionSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SessionStructureTracker - Tracks intraday session structure for symbols.
 *
 * Features:
 * - Opening Range calculation (15/30/60 minutes)
 * - Session High/Low tracking
 * - VWAP calculation with standard deviation bands
 * - Previous day reference level management
 * - Gap analysis
 * - Time segment tracking
 */
@Component
@Slf4j
public class SessionStructureTracker {

    // Session data storage per symbol
    private final Map<String, SessionStructure> sessionMap = new ConcurrentHashMap<>();
    private final Map<String, PreviousDayData> previousDayMap = new ConcurrentHashMap<>();

    // VWAP calculation helpers
    private final Map<String, VwapAccumulator> vwapAccumulators = new ConcurrentHashMap<>();

    // Indian market times
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final LocalTime OR_15_END = LocalTime.of(9, 30);
    private static final LocalTime OR_30_END = LocalTime.of(9, 45);
    private static final LocalTime OR_60_END = LocalTime.of(10, 15);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Update session structure with new candle data.
     *
     * @param symbol     Symbol identifier
     * @param timestamp  Candle timestamp
     * @param open       Open price
     * @param high       High price
     * @param low        Low price
     * @param close      Close price
     * @param volume     Volume
     * @return Updated SessionStructure
     */
    public SessionStructure update(String symbol, Instant timestamp,
                                    double open, double high, double low,
                                    double close, long volume) {

        LocalDateTime ldt = LocalDateTime.ofInstant(timestamp, IST);
        LocalDate today = ldt.toLocalDate();
        LocalTime time = ldt.toLocalTime();

        SessionStructure session = sessionMap.computeIfAbsent(symbol,
            s -> initializeSession(s, today));

        // Check if new day
        if (!today.equals(session.getSessionDate())) {
            savePreviousDayData(symbol, session);
            session = initializeSession(symbol, today);
            sessionMap.put(symbol, session);
            vwapAccumulators.remove(symbol);
        }

        // Update session segment
        session.setCurrentSegment(SessionStructure.getSegmentForTime(time));
        session.setLastUpdate(timestamp);

        // Pre-market handling
        if (time.isBefore(MARKET_OPEN)) {
            updatePreMarket(session, high, low);
            return session;
        }

        // Update session OHLC
        if (session.getSessionOpen() == 0) {
            session.setSessionOpen(open);
            initializeGapAnalysis(symbol, session);
        }

        session.setSessionHigh(Math.max(session.getSessionHigh(), high));
        session.setSessionLow(session.getSessionLow() == 0 ? low : Math.min(session.getSessionLow(), low));
        session.setSessionClose(close);
        session.setSessionVolume(session.getSessionVolume() + volume);
        session.setSessionValue(session.getSessionValue() + (close * volume));

        // Update Opening Range
        updateOpeningRange(session, time, high, low);

        // Update VWAP
        updateVwap(symbol, session, close, volume);

        // Update derived metrics
        updateDerivedMetrics(session);

        // Update price positions
        updatePricePositions(session, close);

        return session;
    }

    /**
     * Initialize a new session structure.
     */
    private SessionStructure initializeSession(String symbol, LocalDate date) {
        SessionStructure session = SessionStructure.builder()
            .symbol(symbol)
            .sessionDate(date)
            .lastUpdate(Instant.now())
            .currentSegment(SessionSegment.PRE_MARKET)
            .openingRangeComplete(false)
            .gapFilled(false)
            .build();

        // Load previous day data if available
        PreviousDayData prevDay = previousDayMap.get(symbol);
        if (prevDay != null) {
            session.setPrevDayHigh(prevDay.high);
            session.setPrevDayLow(prevDay.low);
            session.setPrevDayClose(prevDay.close);
            session.setPrevDayVwap(prevDay.vwap);
        }

        return session;
    }

    /**
     * Update pre-market high/low.
     */
    private void updatePreMarket(SessionStructure session, double high, double low) {
        session.setPreMarketHigh(Math.max(session.getPreMarketHigh(), high));
        session.setPreMarketLow(session.getPreMarketLow() == 0 ? low :
            Math.min(session.getPreMarketLow(), low));
    }

    /**
     * Initialize gap analysis on market open.
     */
    private void initializeGapAnalysis(String symbol, SessionStructure session) {
        if (session.getPrevDayClose() > 0) {
            double open = session.getSessionOpen();
            double prevClose = session.getPrevDayClose();
            double prevHigh = session.getPrevDayHigh();
            double prevLow = session.getPrevDayLow();

            session.setGapSize(open - prevClose);
            session.setGapPercent((open - prevClose) / prevClose * 100);

            if (open > prevHigh) {
                session.setGapType(GapType.GAP_UP);
            } else if (open < prevLow) {
                session.setGapType(GapType.GAP_DOWN);
            } else {
                session.setGapType(GapType.NO_GAP);
            }
        }
    }

    /**
     * Update Opening Range levels.
     */
    private void updateOpeningRange(SessionStructure session, LocalTime time,
                                     double high, double low) {
        if (session.isOpeningRangeComplete()) {
            return;
        }

        // 15-minute range
        if (!time.isAfter(OR_15_END)) {
            session.setOpeningRangeHigh15(Math.max(session.getOpeningRangeHigh15(), high));
            session.setOpeningRangeLow15(session.getOpeningRangeLow15() == 0 ? low :
                Math.min(session.getOpeningRangeLow15(), low));
        }

        // 30-minute range
        if (!time.isAfter(OR_30_END)) {
            session.setOpeningRangeHigh30(Math.max(session.getOpeningRangeHigh30(), high));
            session.setOpeningRangeLow30(session.getOpeningRangeLow30() == 0 ? low :
                Math.min(session.getOpeningRangeLow30(), low));
        }

        // 60-minute range
        if (!time.isAfter(OR_60_END)) {
            session.setOpeningRangeHigh60(Math.max(session.getOpeningRangeHigh60(), high));
            session.setOpeningRangeLow60(session.getOpeningRangeLow60() == 0 ? low :
                Math.min(session.getOpeningRangeLow60(), low));
        } else {
            session.setOpeningRangeComplete(true);
        }
    }

    /**
     * Update VWAP and deviation bands.
     */
    private void updateVwap(String symbol, SessionStructure session, double price, long volume) {
        VwapAccumulator acc = vwapAccumulators.computeIfAbsent(symbol,
            s -> new VwapAccumulator());

        acc.update(price, volume);

        double vwap = acc.getVwap();
        double stdDev = acc.getStdDev();

        session.setVwap(vwap);
        session.setVwapUpperBand1(vwap + stdDev);
        session.setVwapUpperBand2(vwap + 2 * stdDev);
        session.setVwapLowerBand1(vwap - stdDev);
        session.setVwapLowerBand2(vwap - 2 * stdDev);

        // VWAP slope (simple difference based)
        double prevVwap = acc.getPrevVwap();
        if (prevVwap > 0) {
            session.setVwapSlope((vwap - prevVwap) / prevVwap * 100);
        }
    }

    /**
     * Update derived metrics.
     */
    private void updateDerivedMetrics(SessionStructure session) {
        double high = session.getSessionHigh();
        double low = session.getSessionLow();
        double close = session.getSessionClose();

        // Midpoint and range
        session.setMidpoint((high + low) / 2);
        session.setRange(high - low);
        session.setRangePercent(close > 0 ? (high - low) / close * 100 : 0);

        // Inside/Outside day detection
        double prevHigh = session.getPrevDayHigh();
        double prevLow = session.getPrevDayLow();

        if (prevHigh > 0 && prevLow > 0) {
            session.setInsideDay(high < prevHigh && low > prevLow);
            session.setOutsideDay(high > prevHigh && low < prevLow);
        }

        // Gap fill detection
        if (!session.isGapFilled() && session.getGapType() != null) {
            if (session.getGapType() == GapType.GAP_UP && low <= session.getPrevDayClose()) {
                session.setGapFilled(true);
            } else if (session.getGapType() == GapType.GAP_DOWN && high >= session.getPrevDayClose()) {
                session.setGapFilled(true);
            }
        }
    }

    /**
     * Update price position indicators.
     */
    private void updatePricePositions(SessionStructure session, double close) {
        // vs VWAP
        double vwap = session.getVwap();
        if (vwap > 0) {
            double vwapDiff = (close - vwap) / vwap;
            if (vwapDiff > 0.001) {
                session.setPriceVsVwap(PricePosition.ABOVE);
            } else if (vwapDiff < -0.001) {
                session.setPriceVsVwap(PricePosition.BELOW);
            } else {
                session.setPriceVsVwap(PricePosition.AT);
            }
        }

        // vs Opening Range
        if (session.isOpeningRangeComplete()) {
            if (close > session.getOpeningRangeHigh30()) {
                session.setPriceVsOpeningRange(PricePosition.ABOVE);
            } else if (close < session.getOpeningRangeLow30()) {
                session.setPriceVsOpeningRange(PricePosition.BELOW);
            } else {
                session.setPriceVsOpeningRange(PricePosition.AT);
            }
        }

        // vs Previous Day
        double prevClose = session.getPrevDayClose();
        if (prevClose > 0) {
            double prevDiff = (close - prevClose) / prevClose;
            if (prevDiff > 0.001) {
                session.setPriceVsPrevDay(PricePosition.ABOVE);
            } else if (prevDiff < -0.001) {
                session.setPriceVsPrevDay(PricePosition.BELOW);
            } else {
                session.setPriceVsPrevDay(PricePosition.AT);
            }
        }
    }

    /**
     * Save end-of-day data for next session.
     */
    private void savePreviousDayData(String symbol, SessionStructure session) {
        if (session.getSessionHigh() > 0) {
            PreviousDayData prevDay = new PreviousDayData();
            prevDay.high = session.getSessionHigh();
            prevDay.low = session.getSessionLow();
            prevDay.close = session.getSessionClose();
            prevDay.vwap = session.getVwap();
            previousDayMap.put(symbol, prevDay);
        }
    }

    /**
     * Get current session structure for a symbol.
     */
    public SessionStructure getSession(String symbol) {
        return sessionMap.get(symbol);
    }

    /**
     * Check if price is near VWAP.
     */
    public boolean isNearVwap(String symbol, double price, double tolerancePercent) {
        SessionStructure session = sessionMap.get(symbol);
        if (session == null || session.getVwap() == 0) return false;

        double diff = Math.abs(price - session.getVwap()) / session.getVwap() * 100;
        return diff <= tolerancePercent;
    }

    /**
     * Check if price is near Opening Range boundary.
     */
    public boolean isNearOpeningRangeBoundary(String symbol, double price, double tolerancePercent) {
        SessionStructure session = sessionMap.get(symbol);
        if (session == null || !session.isOpeningRangeComplete()) return false;

        double diffHigh = Math.abs(price - session.getOpeningRangeHigh30()) / price * 100;
        double diffLow = Math.abs(price - session.getOpeningRangeLow30()) / price * 100;

        return diffHigh <= tolerancePercent || diffLow <= tolerancePercent;
    }

    /**
     * Get expected intraday range based on previous day.
     */
    public double[] getExpectedRange(String symbol) {
        SessionStructure session = sessionMap.get(symbol);
        PreviousDayData prevDay = previousDayMap.get(symbol);

        if (session == null || prevDay == null) return null;

        double prevRange = prevDay.high - prevDay.low;
        double currentMid = session.getMidpoint();

        return new double[]{
            currentMid - prevRange / 2,
            currentMid + prevRange / 2
        };
    }

    /**
     * Clear all session data (for testing or EOD cleanup).
     */
    public void clearAll() {
        sessionMap.clear();
        vwapAccumulators.clear();
    }

    // ==================== HELPER CLASSES ====================

    private static class PreviousDayData {
        double high;
        double low;
        double close;
        double vwap;
    }

    private static class VwapAccumulator {
        private double cumulativeVP = 0;      // Cumulative (Volume * Price)
        private double cumulativeVolume = 0;   // Cumulative Volume
        private double cumulativeVP2 = 0;      // For variance calculation
        private double prevVwap = 0;

        void update(double price, long volume) {
            if (volume <= 0) return;

            prevVwap = getVwap();
            cumulativeVP += price * volume;
            cumulativeVolume += volume;
            cumulativeVP2 += price * price * volume;
        }

        double getVwap() {
            return cumulativeVolume > 0 ? cumulativeVP / cumulativeVolume : 0;
        }

        double getStdDev() {
            if (cumulativeVolume <= 0) return 0;
            double vwap = getVwap();
            double variance = (cumulativeVP2 / cumulativeVolume) - (vwap * vwap);
            return variance > 0 ? Math.sqrt(variance) : 0;
        }

        double getPrevVwap() {
            return prevVwap;
        }
    }
}
