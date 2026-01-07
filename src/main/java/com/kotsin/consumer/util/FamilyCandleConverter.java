package com.kotsin.consumer.util;

import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.model.EnrichedCandlestick;
import com.kotsin.consumer.model.UnifiedCandle;

/**
 * FamilyCandleConverter - Utility for converting between family-candle domain models
 *
 * Centralizes conversion logic to eliminate code duplication across processors.
 *
 * Migration Strategy:
 * - FamilyCandle → InstrumentCandle → UnifiedCandle (for backwards compatibility)
 * - InstrumentCandle → EnrichedCandlestick (for legacy calculators)
 */
public final class FamilyCandleConverter {

    private FamilyCandleConverter() {
        // Prevent instantiation
    }

    /**
     * Convert InstrumentCandle to UnifiedCandle for backwards compatibility
     *
     * Used by processors that still rely on UnifiedCandle internally while
     * consuming from family-candle-* topics.
     *
     * @param instrument The InstrumentCandle from FamilyCandle.equity
     * @return UnifiedCandle with all fields mapped
     */
    public static UnifiedCandle toUnifiedCandle(InstrumentCandle instrument) {
        if (instrument == null) {
            return null;
        }

        return UnifiedCandle.builder()
                .scripCode(instrument.getScripCode())
                .companyName(instrument.getCompanyName())
                .exchange(instrument.getExchange())
                .exchangeType(instrument.getExchangeType())
                .timeframe(instrument.getTimeframe())
                .windowStartMillis(instrument.getWindowStartMillis())
                .windowEndMillis(instrument.getWindowEndMillis())
                .humanReadableStartTime(instrument.getHumanReadableTime())
                .humanReadableEndTime(instrument.getHumanReadableTime())
                // OHLCV
                .open(instrument.getOpen())
                .high(instrument.getHigh())
                .low(instrument.getLow())
                .close(instrument.getClose())
                .volume(instrument.getVolume())
                .buyVolume(instrument.getBuyVolume())
                .sellVolume(instrument.getSellVolume())
                .vwap(instrument.getVwap())
                .tickCount(instrument.getTickCount())
                // Volume Profile
                .volumeAtPrice(instrument.getVolumeAtPrice())
                .poc(instrument.getPoc())
                .valueAreaHigh(instrument.getVah())
                .valueAreaLow(instrument.getVal())
                // Imbalance
                .volumeImbalance(instrument.getVolumeImbalance())
                .dollarImbalance(instrument.getDollarImbalance())
                .vpin(instrument.getVpin())
                // Orderbook (may be null)
                .ofi(instrument.getOfi() != null ? instrument.getOfi() : 0.0)
                .depthImbalance(instrument.getDepthImbalance() != null ? instrument.getDepthImbalance() : 0.0)
                .kyleLambda(instrument.getKyleLambda() != null ? instrument.getKyleLambda() : 0.0)
                .microprice(instrument.getMicroprice() != null ? instrument.getMicroprice() : 0.0)
                .bidAskSpread(instrument.getBidAskSpread() != null ? instrument.getBidAskSpread() : 0.0)
                .weightedDepthImbalance(instrument.getWeightedDepthImbalance() != null ? instrument.getWeightedDepthImbalance() : 0.0)
                .totalBidDepth(instrument.getAverageBidDepth() != null ? instrument.getAverageBidDepth() : 0.0)
                .totalAskDepth(instrument.getAverageAskDepth() != null ? instrument.getAverageAskDepth() : 0.0)
                // OI (may be null)
                .oiOpen(instrument.getOiOpen())
                .oiHigh(instrument.getOiHigh())
                .oiLow(instrument.getOiLow())
                .oiClose(instrument.getOiClose())
                .oiChange(instrument.getOiChange())
                .oiChangePercent(instrument.getOiChangePercent())
                // Derived fields
                .volumeDeltaPercent(instrument.getVolumeDeltaPercent())
                .range(instrument.getRange())
                .isBullish(instrument.isBullish())
                .build();
    }

    /**
     * Convert InstrumentCandle directly to EnrichedCandlestick (bypass UnifiedCandle)
     *
     * Used for legacy calculators that expect EnrichedCandlestick.
     * This reduces the 3-level conversion chain to 2 levels.
     *
     * @param instrument The InstrumentCandle from FamilyCandle.equity
     * @return EnrichedCandlestick with essential fields mapped
     */
    public static EnrichedCandlestick toEnrichedCandlestick(InstrumentCandle instrument) {
        if (instrument == null) {
            return null;
        }

        EnrichedCandlestick ec = new EnrichedCandlestick();
        ec.setOpen(instrument.getOpen());
        ec.setHigh(instrument.getHigh());
        ec.setLow(instrument.getLow());
        ec.setClose(instrument.getClose());
        ec.setVolume(instrument.getVolume());
        ec.setBuyVolume(instrument.getBuyVolume());
        ec.setSellVolume(instrument.getSellVolume());
        ec.setVwap(instrument.getVwap());
        ec.setScripCode(instrument.getScripCode());
        ec.setCompanyName(instrument.getCompanyName());
        return ec;
    }

    /**
     * Convert UnifiedCandle to EnrichedCandlestick (legacy compatibility)
     *
     * @param candle The UnifiedCandle to convert
     * @return EnrichedCandlestick with essential fields mapped
     */
    public static EnrichedCandlestick toEnrichedCandlestick(UnifiedCandle candle) {
        if (candle == null) {
            return null;
        }

        EnrichedCandlestick ec = new EnrichedCandlestick();
        ec.setOpen(candle.getOpen());
        ec.setHigh(candle.getHigh());
        ec.setLow(candle.getLow());
        ec.setClose(candle.getClose());
        ec.setVolume(candle.getVolume());
        ec.setBuyVolume(candle.getBuyVolume());
        ec.setSellVolume(candle.getSellVolume());
        ec.setVwap(candle.getVwap());
        ec.setScripCode(candle.getScripCode());
        ec.setCompanyName(candle.getCompanyName());
        return ec;
    }
}
