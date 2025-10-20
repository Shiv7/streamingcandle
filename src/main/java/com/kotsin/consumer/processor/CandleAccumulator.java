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
    private Double volumeSum = 0.0;  // For VWAP
    private Double priceVolumeSum = 0.0;  // For VWAP
    private Double previousClose = null;  // For log returns

    // Buy/Sell volume separation (NEW)
    private Long buyVolume = 0L;      // Volume from buy-side trades
    private Long sellVolume = 0L;     // Volume from sell-side trades
    private Double lastPrice = null;  // For tick rule classification

    public CandleAccumulator() {
        this.windowStart = null;
    }

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

        if (tick.getDeltaVolume() != null) {
            volume += tick.getDeltaVolume();

            // Track for VWAP calculation
            volumeSum += tick.getDeltaVolume();
            priceVolumeSum += tick.getLastRate() * tick.getDeltaVolume();

            // Buy/Sell classification using tick rule
            boolean isBuy = classifyTrade(tick);
            if (isBuy) {
                buyVolume += tick.getDeltaVolume();
            } else {
                sellVolume += tick.getDeltaVolume();
            }
        }

        lastPrice = tick.getLastRate();
    }

    /**
     * Classify trade as buy or sell using tick rule
     */
    private boolean classifyTrade(com.kotsin.consumer.model.TickData tick) {
        double currentPrice = tick.getLastRate();

        // Get bid/ask for more accurate classification
        double bidPrice = tick.getBidRate();
        double askPrice = tick.getOfferRate();

        // Quote rule: trade at ask = buy, trade at bid = sell
        if (bidPrice > 0 && askPrice > 0) {
            if (Math.abs(currentPrice - askPrice) < 0.01) {
                return true;  // Trade at ask → buy
            } else if (Math.abs(currentPrice - bidPrice) < 0.01) {
                return false;  // Trade at bid → sell
            }
        }

        // Tick rule: if price > last price → buy, else → sell
        if (lastPrice != null && Math.abs(currentPrice - lastPrice) > 0.01) {
            return currentPrice > lastPrice;
        }

        // Default: use mid price
        if (bidPrice > 0 && askPrice > 0) {
            double midPrice = (bidPrice + askPrice) / 2.0;
            return currentPrice >= midPrice;
        }

        return true;  // Default to buy if cannot determine
    }

    public void markComplete() { complete = true; }
    public boolean isComplete() { return complete; }

    private long alignToMinute(long timestamp) {
        return (timestamp / 60_000) * 60_000;
    }

    public CandleData toCandleData(String exchange, String exchangeType) {
        // Calculate volume delta and percentage
        double volumeDelta = (double) (buyVolume - sellVolume);
        double volumeDeltaPercent = volume > 0 ? (volumeDelta / volume) * 100.0 : 0.0;

        // Calculate VWAP
        double vwap = volumeSum > 0 ? priceVolumeSum / volumeSum : 0.0;

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

        // Required fields
        candle.setScripCode(scripCode);
        candle.setCompanyName(companyName);
        candle.setExchange(exchange);
        candle.setExchangeType(exchangeType);
        candle.setWindowStartMillis(windowStart != null ? windowStart : 0);
        candle.setWindowEndMillis(windowEnd != null ? windowEnd : 0);
        candle.setIsComplete(complete);

        // OHLCV
        candle.setOpen(open != null ? open : 0.0);
        candle.setHigh(high != null ? high : 0.0);
        candle.setLow(low != null ? low : 0.0);
        candle.setClose(close != null ? close : 0.0);
        candle.setVolume(volume != null ? volume.intValue() : 0);

        if (includeExtras) {
            if (volumeSum > 0) {
                candle.setVwap(priceVolumeSum / volumeSum);
            }
            if (high != null && low != null && close != null) {
                candle.setHlc3((high + low + close) / 3.0);
            }
            if (previousClose != null && close != null && previousClose > 0) {
                candle.setLogReturnFromPrevBar(Math.log(close / previousClose));
            }
            candle.setTicksInWindow(tickCount);
            candle.setWindowLatencyMs(System.currentTimeMillis() - windowEnd);
        }

        return candle;
    }

    public void setPreviousClose(Double prevClose) { this.previousClose = prevClose; }

    public Long getWindowStart() { return windowStart; }
}


