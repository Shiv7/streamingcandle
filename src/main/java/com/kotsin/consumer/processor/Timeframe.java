package com.kotsin.consumer.processor;

/**
 * Strongly-typed timeframes used across aggregation.
 */
public enum Timeframe {
    ONE_MIN("1m", 1),
    TWO_MIN("2m", 2),
    THREE_MIN("3m", 3),
    FIVE_MIN("5m", 5),
    FIFTEEN_MIN("15m", 15),
    THIRTY_MIN("30m", 30);

    private final String label;
    private final int minutes;

    Timeframe(String label, int minutes) {
        this.label = label;
        this.minutes = minutes;
    }

    public String getLabel() {
        return label;
    }

    public int getMinutes() {
        return minutes;
    }

    public static Timeframe fromLabel(String label) {
        for (Timeframe tf : values()) {
            if (tf.label.equals(label)) return tf;
        }
        throw new IllegalArgumentException("Unknown timeframe label: " + label);
    }
}


