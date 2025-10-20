package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.Candlestick;
import com.kotsin.consumer.model.CandleData;
import lombok.Data;

@Data
public class CandleAccumulator {
    private Long windowStart;
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Long volume = 0L;
    private Long windowEnd;
    private int windowMinutes = 1;
    private boolean complete = false;
    private int tickCount = 0;

    // Extra metrics for advanced candle features
    private Double priceVolumeSum = 0.0;  // For VWAP (sum of price * volume)
    private Double previousClose = null;  // For log returns

    // Buy/Sell volume separation
    private Long buyVolume = 0L;      // Volume from buy-side trades
    private Long sellVolume = 0L;     // Volume from sell-side trades
    private Double lastPrice = null;  // For tick rule classification

    public CandleAccumulator() { this.windowStart = null; }

    public CandleAccumulator(Long windowStart, int minutes) {
        this.windowStart = windowStart;
        this.windowMinutes = Math.max(1, minutes);
        this.windowEnd = windowStart + (windowMinutes * 60L * 1000L);
    }

    public void addTick(com.kotsin.consumer.model.TickData tick) {
        if (windowStart == null) {
            windowStart = alignToMinute(tick.getTimestamp());
            windowEnd = windowStart + (windowMinutes * 60L * 1000L);
        }

        tickCount++;

        if (open == null) {
            open = tick.getLastRate();
        }

        close = tick.getLastRate();

        if (high == null || tick.getLastRate() > high) {
            high = tick.getLastRate();
        }

        if (low == null || tick.getLastRate() < low) {
            low = tick.getLastRate();
        }

        Integer dv = tick.getDeltaVolume();
        if (dv != null && dv > 0) {
            volume += dv.longValue();
            priceVolumeSum += tick.getLastRate() * dv;

            boolean isBuy = classifyTrade(tick);
            if (isBuy) {
                buyVolume += dv.longValue();
            } else {
                sellVolume += dv.longValue();
            }
        }

        lastPrice = tick.getLastRate();
    }

    /**
     * Classify trade as buy or sell using quote-rule, then tick-rule, with tick-size aware threshold.
     */
    private boolean classifyTrade(com.kotsin.consumer.model.TickData tick) {
        double currentPrice = tick.getLastRate();

        double bidPrice = tick.getBidRate();
        double askPrice = tick.getOfferRate();

        // Threshold: 1bp of price or 0.01 minimum
        double threshold = Math.max(0.01, Math.abs(currentPrice) * 0.0001);

        if (bidPrice > 0 && askPrice > 0) {
            if (Math.abs(currentPrice - askPrice) <= threshold) {
                return true;
            } else if (Math.abs(currentPrice - bidPrice) <= threshold) {
                return false;
            }
        }

        // Tick rule
        if (lastPrice != null && Math.abs(currentPrice - lastPrice) > threshold) {
            return currentPrice > lastPrice;
        }

        // If we cannot determine (no book and no movement), do not bias: default to false (sell) to avoid buy inflation
        return false;
    }

    public void markComplete() { complete = true; }
    public boolean isComplete() { return complete; }

    private long alignToMinute(long timestamp) { return (timestamp / 60_000) * 60_000; }

    public CandleData toCandleData(String exchange, String exchangeType) {
        double volumeDelta = (double) (buyVolume - sellVolume);
        double volumeDeltaPercent = volume > 0 ? (volumeDelta / volume) * 100.0 : 0.0;

        double vwap = volume > 0 ? priceVolumeSum / volume : 0.0;

        return CandleData.builder()
            .open(open)
            .high(high)
            .low(low)
            .close(close)
            .volume(volume)
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .isComplete(complete)
            .exchange(exchange)
            .exchangeType(exchangeType)
            .buyVolume(buyVolume)
            .sellVolume(sellVolume)
            .volumeDelta(volumeDelta)
            .volumeDeltaPercent(volumeDeltaPercent)
            .vwap(vwap)
            .tickCount(tickCount)
            .build();
    }

    public Candlestick toFinalizedCandlestick(String scripCode, String companyName,
                                               String exchange, String exchangeType,
                                               boolean includeExtras) {
        Candlestick candle = new Candlestick();
        candle.setScripCode(scripCode);
        candle.setCompanyName(companyName);
        candle.setExchange(exchange);
        candle.setExchangeType(exchangeType);
        candle.setWindowStartMillis(windowStart != null ? windowStart : 0);
        candle.setWindowEndMillis(windowEnd != null ? windowEnd : 0);
        candle.setIsComplete(complete);
        candle.setOpen(open != null ? open : 0.0);
        candle.setHigh(high != null ? high : 0.0);
        candle.setLow(low != null ? low : 0.0);
        candle.setClose(close != null ? close : 0.0);
        candle.setVolume(volume != null ? volume.intValue() : 0);
        if (includeExtras) {
            if (volume > 0) candle.setVwap(priceVolumeSum / volume);
            if (high != null && low != null && close != null) candle.setHlc3((high + low + close) / 3.0);
            if (previousClose != null && close != null && previousClose > 0) candle.setLogReturnFromPrevBar(Math.log(close / previousClose));
            candle.setTicksInWindow(tickCount);
            candle.setWindowLatencyMs(System.currentTimeMillis() - windowEnd);
        }
        return candle;
    }

    public void setPreviousClose(Double prevClose) { this.previousClose = prevClose; }
    public Long getWindowStart() { return windowStart; }
}


