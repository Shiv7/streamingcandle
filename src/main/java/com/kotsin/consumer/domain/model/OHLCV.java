package com.kotsin.consumer.domain.model;

import java.util.Objects;

/**
 * Immutable value object for OHLCV (Open, High, Low, Close, Volume) data.
 *
 * SRP: Only holds price and volume data
 * Immutable: Thread-safe
 * Value Object: Equality based on content
 */
public final class OHLCV {
    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final long volume;
    private final long buyVolume;
    private final long sellVolume;
    private final double vwap;           // Volume-Weighted Average Price
    private final int tickCount;         // Number of trades

    private OHLCV(Builder builder) {
        this.open = builder.open;
        this.high = builder.high;
        this.low = builder.low;
        this.close = builder.close;
        this.volume = builder.volume;
        this.buyVolume = builder.buyVolume;
        this.sellVolume = builder.sellVolume;
        this.vwap = builder.vwap;
        this.tickCount = builder.tickCount;

        // Validate invariants
        if (high < low) {
            throw new IllegalArgumentException("High (" + high + ") cannot be less than low (" + low + ")");
        }
        if (buyVolume + sellVolume > volume) {
            throw new IllegalArgumentException(
                "Buy + Sell volume (" + (buyVolume + sellVolume) + ") exceeds total volume (" + volume + ")"
            );
        }
    }

    // Getters
    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public long getVolume() { return volume; }
    public long getBuyVolume() { return buyVolume; }
    public long getSellVolume() { return sellVolume; }
    public double getVwap() { return vwap; }
    public int getTickCount() { return tickCount; }

    /**
     * Calculate volume delta (buy - sell).
     */
    public long getVolumeDelta() {
        return buyVolume - sellVolume;
    }

    /**
     * Calculate volume delta as percentage of total volume.
     */
    public double getVolumeDeltaPercent() {
        return volume > 0 ? ((double) (buyVolume - sellVolume) / volume) * 100.0 : 0.0;
    }

    /**
     * Check if this is a bullish candle (close > open).
     */
    public boolean isBullish() {
        return close > open;
    }

    /**
     * Check if this is a bearish candle (close < open).
     */
    public boolean isBearish() {
        return close < open;
    }

    /**
     * Get the range (high - low).
     */
    public double getRange() {
        return high - low;
    }

    /**
     * Get the body size (|close - open|).
     */
    public double getBodySize() {
        return Math.abs(close - open);
    }

    /**
     * Check if price has data (not all zeros).
     */
    public boolean hasData() {
        return high > 0 || low > 0 || open > 0 || close > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OHLCV ohlcv = (OHLCV) o;
        return Double.compare(ohlcv.open, open) == 0 &&
               Double.compare(ohlcv.high, high) == 0 &&
               Double.compare(ohlcv.low, low) == 0 &&
               Double.compare(ohlcv.close, close) == 0 &&
               volume == ohlcv.volume;
    }

    @Override
    public int hashCode() {
        return Objects.hash(open, high, low, close, volume);
    }

    @Override
    public String toString() {
        return String.format("OHLCV{O=%.2f H=%.2f L=%.2f C=%.2f V=%d}", open, high, low, close, volume);
    }

    /**
     * Builder for creating OHLCV instances.
     */
    public static class Builder {
        private double open;
        private double high;
        private double low;
        private double close;
        private long volume;
        private long buyVolume;
        private long sellVolume;
        private double vwap;
        private int tickCount;

        public Builder open(double open) {
            this.open = open;
            return this;
        }

        public Builder high(double high) {
            this.high = high;
            return this;
        }

        public Builder low(double low) {
            this.low = low;
            return this;
        }

        public Builder close(double close) {
            this.close = close;
            return this;
        }

        public Builder volume(long volume) {
            this.volume = volume;
            return this;
        }

        public Builder buyVolume(long buyVolume) {
            this.buyVolume = buyVolume;
            return this;
        }

        public Builder sellVolume(long sellVolume) {
            this.sellVolume = sellVolume;
            return this;
        }

        public Builder vwap(double vwap) {
            this.vwap = vwap;
            return this;
        }

        public Builder tickCount(int tickCount) {
            this.tickCount = tickCount;
            return this;
        }

        public OHLCV build() {
            return new OHLCV(this);
        }
    }

    /**
     * Create empty OHLCV (all zeros).
     */
    public static OHLCV empty() {
        return new Builder().build();
    }
}
