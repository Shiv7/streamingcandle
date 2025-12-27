package com.kotsin.consumer.domain.model;

import java.util.Objects;

/**
 * Immutable value object representing an instrument's identity.
 *
 * Following SOLID principles:
 * - SRP: Only holds instrument identification data
 * - Immutable: Thread-safe, can be used as Kafka key
 * - Value Object: Equality based on content, not reference
 */
public final class Instrument {
    private final String token;
    private final String scripCode;
    private final String companyName;
    private final String exchange;        // N = NSE, B = BSE
    private final String exchangeType;    // C, D, F, O
    private final InstrumentType type;
    private final String symbolRoot;      // RELIANCE (for all related instruments)
    private final Integer strikePrice;    // For options (e.g., 2600)
    private final String expiry;          // For derivatives (e.g., "2025-12-30")
    private final double tickSize;        // Minimum price movement
    private final int lotSize;            // Contract size

    // Private constructor - use Builder
    private Instrument(Builder builder) {
        this.token = Objects.requireNonNull(builder.token, "Token cannot be null");
        this.scripCode = builder.scripCode != null ? builder.scripCode : builder.token;
        this.companyName = builder.companyName;
        this.exchange = builder.exchange;
        this.exchangeType = builder.exchangeType;
        this.symbolRoot = builder.symbolRoot;
        this.strikePrice = builder.strikePrice;
        this.expiry = builder.expiry;
        this.tickSize = builder.tickSize > 0 ? builder.tickSize : 0.05;
        this.lotSize = builder.lotSize > 0 ? builder.lotSize : 1;

        // Determine type
        this.type = InstrumentType.fromExchange(builder.exchangeType, builder.companyName);
    }

    // Getters
    public String getToken() { return token; }
    public String getScripCode() { return scripCode; }
    public String getCompanyName() { return companyName; }
    public String getExchange() { return exchange; }
    public String getExchangeType() { return exchangeType; }
    public InstrumentType getType() { return type; }
    public String getSymbolRoot() { return symbolRoot; }
    public Integer getStrikePrice() { return strikePrice; }
    public String getExpiry() { return expiry; }
    public double getTickSize() { return tickSize; }
    public int getLotSize() { return lotSize; }

    /**
     * Check if this is an equity instrument.
     */
    public boolean isEquity() {
        return type == InstrumentType.EQUITY;
    }

    /**
     * Check if this is a derivative (future or option).
     */
    public boolean isDerivative() {
        return type.isDerivative();
    }

    /**
     * Check if this is an option.
     */
    public boolean isOption() {
        return type.isOption();
    }

    /**
     * Get a unique identifier for Kafka keys.
     */
    public String getKey() {
        return token;
    }

    // Equals and HashCode (value object)
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Instrument that = (Instrument) o;
        return token.equals(that.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token);
    }

    @Override
    public String toString() {
        return "Instrument{" +
                "token='" + token + '\'' +
                ", type=" + type +
                ", symbol='" + symbolRoot + '\'' +
                (strikePrice != null ? ", strike=" + strikePrice : "") +
                '}';
    }

    /**
     * Builder for creating Instrument instances.
     */
    public static class Builder {
        private String token;
        private String scripCode;
        private String companyName;
        private String exchange;
        private String exchangeType;
        private String symbolRoot;
        private Integer strikePrice;
        private String expiry;
        private double tickSize;
        private int lotSize;

        public Builder token(String token) {
            this.token = token;
            return this;
        }

        public Builder scripCode(String scripCode) {
            this.scripCode = scripCode;
            return this;
        }

        public Builder companyName(String companyName) {
            this.companyName = companyName;
            return this;
        }

        public Builder exchange(String exchange) {
            this.exchange = exchange;
            return this;
        }

        public Builder exchangeType(String exchangeType) {
            this.exchangeType = exchangeType;
            return this;
        }

        public Builder symbolRoot(String symbolRoot) {
            this.symbolRoot = symbolRoot;
            return this;
        }

        public Builder strikePrice(Integer strikePrice) {
            this.strikePrice = strikePrice;
            return this;
        }

        public Builder expiry(String expiry) {
            this.expiry = expiry;
            return this;
        }

        public Builder tickSize(double tickSize) {
            this.tickSize = tickSize;
            return this;
        }

        public Builder lotSize(int lotSize) {
            this.lotSize = lotSize;
            return this;
        }

        public Instrument build() {
            return new Instrument(this);
        }
    }

    /**
     * Create a builder from existing instrument.
     */
    public Builder toBuilder() {
        return new Builder()
            .token(token)
            .scripCode(scripCode)
            .companyName(companyName)
            .exchange(exchange)
            .exchangeType(exchangeType)
            .symbolRoot(symbolRoot)
            .strikePrice(strikePrice)
            .expiry(expiry)
            .tickSize(tickSize)
            .lotSize(lotSize);
    }
}
