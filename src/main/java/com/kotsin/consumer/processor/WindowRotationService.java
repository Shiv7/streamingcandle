package com.kotsin.consumer.processor;

/**
 * Centralizes window rotation logic for accumulators.
 * Keeps behavior identical while improving readability and testability.
 */
public final class WindowRotationService {

    private WindowRotationService() {}

    public static CandleAccumulator rotateCandleIfNeeded(CandleAccumulator acc, long tickTime, int minutes) {
        long windowStart = align(tickTime, minutes);
        if (acc.getWindowStart() == null) {
            return new CandleAccumulator(windowStart, minutes);
        }
        if (!acc.getWindowStart().equals(windowStart)) {
            acc.markComplete();
            return new CandleAccumulator(windowStart, minutes);
        }
        return acc;
    }

    public static CandleAccumulator rotateCandleIfNeeded(CandleAccumulator acc, long tickTime, int minutes, int offsetMinutes) {
        long windowStart = alignWithOffset(tickTime, minutes, offsetMinutes);
        if (acc.getWindowStart() == null) {
            return new CandleAccumulator(windowStart, minutes);
        }
        if (!acc.getWindowStart().equals(windowStart)) {
            acc.markComplete();
            return new CandleAccumulator(windowStart, minutes);
        }
        return acc;
    }

    public static OiAccumulator rotateOiIfNeeded(OiAccumulator acc, long tickTime, int minutes) {
        long windowStart = align(tickTime, minutes);
        if (acc.getWindowStart() == null) {
            return new OiAccumulator(windowStart, minutes);
        }
        if (!acc.getWindowStart().equals(windowStart)) {
            acc.markComplete();
            return new OiAccumulator(windowStart, minutes);
        }
        return acc;
    }

    public static OiAccumulator rotateOiIfNeeded(OiAccumulator acc, long tickTime, int minutes, int offsetMinutes) {
        long windowStart = alignWithOffset(tickTime, minutes, offsetMinutes);
        if (acc.getWindowStart() == null) {
            return new OiAccumulator(windowStart, minutes);
        }
        if (!acc.getWindowStart().equals(windowStart)) {
            acc.markComplete();
            return new OiAccumulator(windowStart, minutes);
        }
        return acc;
    }

    private static long align(long ts, int minutes) {
        long sizeMs = minutes * 60_000L;
        return (ts / sizeMs) * sizeMs;
    }

    private static long alignWithOffset(long ts, int minutes, int offsetMinutes) {
        long sizeMs = minutes * 60_000L;
        long offsetMs = offsetMinutes * 60_000L;
        return ((ts - offsetMs) / sizeMs) * sizeMs + offsetMs;
    }
}


