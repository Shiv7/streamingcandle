package com.kotsin.consumer.event;

import com.kotsin.consumer.model.TickCandle;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.TimeframeBoundary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CandleBoundaryPublisher - Publishes events when timeframe boundaries are crossed.
 *
 * Called from TickAggregator when a 1m candle closes.
 * Checks if any higher timeframe boundary is crossed and publishes events.
 *
 * This is the bridge between the tick aggregation layer and the signal processing layer.
 */
@Component
@Slf4j
public class CandleBoundaryPublisher {

    private static final String LOG_PREFIX = "[BOUNDARY-PUB]";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    // Track last published boundary per scripCode:timeframe to avoid duplicates
    private final Map<String, Instant> lastPublishedBoundary = new ConcurrentHashMap<>();

    // Pending 1m candles for aggregation (key: scripCode:timeframe:windowStart)
    private final Map<String, java.util.List<TickCandle>> pendingCandles = new ConcurrentHashMap<>();

    /**
     * Called when a 1m candle closes. Checks for boundary crossings and publishes events.
     *
     * @param candle1m The just-closed 1m candle
     */
    public void onCandleClose(TickCandle candle1m) {
        if (candle1m == null || candle1m.getWindowEnd() == null) {
            log.debug("{} onCandleClose called with null candle or windowEnd", LOG_PREFIX);
            return;
        }

        String scripCode = candle1m.getScripCode();
        Instant closeTime = candle1m.getWindowEnd();

        // Determine exchange - default to NSE if not specified
        String exchange = candle1m.getExchange();
        if (exchange == null || exchange.isEmpty()) {
            exchange = "N";
        }

        // TRACE: Log every call to verify method is being invoked
        log.trace("{} {} [{}] onCandleClose: windowEnd={} IST",
            LOG_PREFIX, scripCode, exchange, closeTime.atZone(IST).format(TIME_FMT));

        // Skip if outside market hours (exchange-aware)
        if (!TimeframeBoundary.isMarketHours(closeTime, exchange)) {
            log.trace("{} {} [{}] Skipped - outside market hours: {}",
                LOG_PREFIX, scripCode, exchange, closeTime.atZone(IST).format(TIME_FMT));
            return;
        }

        // Track this 1m candle for potential aggregation
        trackCandleForAggregation(candle1m);

        // Check which boundaries are crossed (exchange-aware)
        List<Timeframe> crossedBoundaries = TimeframeBoundary.getCrossedBoundaries(closeTime, exchange);

        if (crossedBoundaries.isEmpty()) {
            return;
        }

        log.debug("{} {} [{}] Boundaries crossed at {}: {}",
            LOG_PREFIX, scripCode, exchange,
            closeTime.atZone(IST).format(TIME_FMT),
            crossedBoundaries);

        // Publish event for each crossed boundary
        for (Timeframe tf : crossedBoundaries) {
            publishBoundaryEvent(scripCode, candle1m, tf, closeTime);
        }
    }

    /**
     * Track 1m candles for aggregation into higher timeframes.
     */
    private void trackCandleForAggregation(TickCandle candle1m) {
        String scripCode = candle1m.getScripCode();
        Instant closeTime = candle1m.getWindowEnd();

        // Track for each popular timeframe
        for (Timeframe tf : new Timeframe[]{Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1, Timeframe.H4}) {
            Instant windowStart = tf.alignToWindowStart(closeTime);
            String key = scripCode + ":" + tf.getLabel() + ":" + windowStart.toEpochMilli();

            pendingCandles.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(candle1m);
        }
    }

    /**
     * Publish a boundary event for a specific timeframe.
     */
    private void publishBoundaryEvent(String scripCode, TickCandle candle1m, Timeframe timeframe, Instant closeTime) {
        // Calculate window boundaries
        Instant windowStart = timeframe.alignToWindowStart(closeTime);
        Instant windowEnd = timeframe.getWindowEnd(windowStart);

        // Check for duplicate publish
        String dedupKey = scripCode + ":" + timeframe.getLabel();
        Instant lastPublished = lastPublishedBoundary.get(dedupKey);
        if (lastPublished != null && lastPublished.equals(windowEnd)) {
            log.debug("{} {} Already published {} boundary ending at {}",
                LOG_PREFIX, scripCode, timeframe.getLabel(), closeTime.atZone(IST).format(TIME_FMT));
            return;
        }

        // Get constituent 1m candles for this window
        String pendingKey = scripCode + ":" + timeframe.getLabel() + ":" + windowStart.toEpochMilli();
        List<TickCandle> constituent1mCandles = pendingCandles.remove(pendingKey);

        // Aggregate OHLCV from constituent candles
        double open = candle1m.getOpen();
        double high = candle1m.getHigh();
        double low = candle1m.getLow();
        double close = candle1m.getClose();
        long volume = candle1m.getVolume();

        if (constituent1mCandles != null && !constituent1mCandles.isEmpty()) {
            // Sort by time to get correct OHLC
            constituent1mCandles.sort((a, b) -> a.getWindowStart().compareTo(b.getWindowStart()));
            open = constituent1mCandles.get(0).getOpen();
            high = constituent1mCandles.stream().mapToDouble(TickCandle::getHigh).max().orElse(high);
            low = constituent1mCandles.stream().mapToDouble(TickCandle::getLow).min().orElse(low);
            close = constituent1mCandles.get(constituent1mCandles.size() - 1).getClose();
            volume = constituent1mCandles.stream().mapToLong(TickCandle::getVolume).sum();
        }

        // Build event data
        CandleBoundaryEvent.CandleBoundaryData data = CandleBoundaryEvent.CandleBoundaryData.builder()
            .scripCode(scripCode)
            .symbol(candle1m.getSymbol())
            .exchange(candle1m.getExchange())
            .exchangeType(candle1m.getExchangeType())
            .timeframe(timeframe)
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .open(open)
            .high(high)
            .low(low)
            .close(close)
            .volume(volume)
            .constituent1mCandles(constituent1mCandles)
            .eventTime(Instant.now())
            .build();

        // Publish event
        CandleBoundaryEvent event = new CandleBoundaryEvent(this, data);
        eventPublisher.publishEvent(event);

        // Track published boundary
        lastPublishedBoundary.put(dedupKey, windowEnd);

        log.info("{} {} Published {} boundary event: {} IST, O={} H={} L={} C={} V={}",
            LOG_PREFIX, scripCode, timeframe.getLabel(),
            windowEnd.atZone(IST).format(TIME_FMT),
            String.format("%.2f", open),
            String.format("%.2f", high),
            String.format("%.2f", low),
            String.format("%.2f", close),
            volume);
    }

    /**
     * Cleanup old pending candles (call periodically).
     */
    public void cleanupOldPendingCandles() {
        Instant cutoff = Instant.now().minusSeconds(3600); // 1 hour

        pendingCandles.entrySet().removeIf(entry -> {
            List<TickCandle> candles = entry.getValue();
            if (candles == null || candles.isEmpty()) {
                return true;
            }
            // Remove if oldest candle is too old
            Instant oldest = candles.stream()
                .map(TickCandle::getWindowStart)
                .min(Instant::compareTo)
                .orElse(Instant.EPOCH);
            return oldest.isBefore(cutoff);
        });
    }
}
