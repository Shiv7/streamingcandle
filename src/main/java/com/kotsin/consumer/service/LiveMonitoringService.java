package com.kotsin.consumer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LiveMonitoringService - Production-grade monitoring for live data
 *
 * Provides:
 * - Per-topic throughput counters
 * - Periodic heartbeat logs
 * - Data flow health checks
 * - Latency tracking
 *
 * ISSUE #2 FIX: Added replay mode config to bypass market hours check during replay.
 */
@Slf4j
@Service
public class LiveMonitoringService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ISSUE #2 FIX: Config flag to enable monitoring during replay (outside market hours)
    @Value("${monitoring.replay.mode:false}")
    private boolean replayMode;

    // Throughput counters
    private final ConcurrentHashMap<String, AtomicLong> messageCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastPeriodCounters = new ConcurrentHashMap<>();

    // Signal counters
    private final AtomicLong totalSignals = new AtomicLong(0);
    private final AtomicLong longSignals = new AtomicLong(0);
    private final AtomicLong shortSignals = new AtomicLong(0);

    // Error counters
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    // Track last data timestamps
    private final ConcurrentHashMap<String, Long> lastDataTime = new ConcurrentHashMap<>();

    /**
     * Increment message counter for a topic/processor
     */
    public void recordMessage(String source) {
        messageCounters.computeIfAbsent(source, k -> new AtomicLong(0)).incrementAndGet();
        lastDataTime.put(source, System.currentTimeMillis());
    }

    /**
     * Record a trading signal
     */
    public void recordSignal(String type) {
        totalSignals.incrementAndGet();
        if ("LONG".equalsIgnoreCase(type)) {
            longSignals.incrementAndGet();
        } else if ("SHORT".equalsIgnoreCase(type)) {
            shortSignals.incrementAndGet();
        }
    }

    /**
     * Record cache hit/miss
     */
    public void recordCacheHit() { cacheHits.incrementAndGet(); }
    public void recordCacheMiss() { cacheMisses.incrementAndGet(); }

    /**
     * Record error
     */
    public void recordError() { errorCount.incrementAndGet(); }

    /**
     * Heartbeat log - every 1 minute during market hours
     */
    @Scheduled(fixedRate = 60000)
    public void heartbeat() {
        LocalTime now = LocalTime.now();

        // ISSUE #2 FIX: Skip market hours check in replay mode
        if (!replayMode) {
            // Only log during extended market hours (9:00 - 16:00)
            if (now.isBefore(LocalTime.of(9, 0)) || now.isAfter(LocalTime.of(16, 0))) {
                return;
            }
        }

        log.info("💓 HEARTBEAT | time={} | signals={} (L:{} S:{}) | errors={}",
                now.format(TIME_FMT),
                totalSignals.get(),
                longSignals.get(),
                shortSignals.get(),
                errorCount.get());
    }

    /**
     * Throughput log - every 5 minutes during market hours
     */
    @Scheduled(fixedRate = 300000)
    public void throughputReport() {
        LocalTime now = LocalTime.now();

        // ISSUE #2 FIX: Skip market hours check in replay mode
        if (!replayMode) {
            // Only log during extended market hours
            if (now.isBefore(LocalTime.of(9, 0)) || now.isAfter(LocalTime.of(16, 0))) {
                return;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n📊 THROUGHPUT REPORT | ").append(now.format(TIME_FMT)).append("\n");
        sb.append("┌─────────────────────────┬──────────┬──────────┐\n");
        sb.append("│ Source                  │ Total    │ Last 5m  │\n");
        sb.append("├─────────────────────────┼──────────┼──────────┤\n");

        for (String source : messageCounters.keySet()) {
            long total = messageCounters.get(source).get();
            long lastPeriod = lastPeriodCounters.getOrDefault(source, new AtomicLong(0)).get();
            long delta = total - lastPeriod;
            lastPeriodCounters.put(source, new AtomicLong(total));
            
            sb.append(String.format("│ %-23s │ %8d │ %8d │\n", 
                    truncate(source, 23), total, delta));
        }

        sb.append("└─────────────────────────┴──────────┴──────────┘");
        log.info(sb.toString());

        // Log cache efficiency
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        double hitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) * 100 : 0;
        log.info("CACHE EFFICIENCY | hits={} misses={} hitRate={}%", hits, misses, String.format("%.1f", hitRate));
    }

    /**
     * Data staleness check - every 2 minutes
     */
    @Scheduled(fixedRate = 120000)
    public void stalenessCheck() {
        LocalTime now = LocalTime.now();

        // ISSUE #2 FIX: Skip market hours check in replay mode
        if (!replayMode) {
            // Only check during market hours (9:15 - 15:30)
            if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 30))) {
                return;
            }
        }

        long staleThreshold = 120000; // 2 minutes
        long currentTime = System.currentTimeMillis();
        
        for (String source : lastDataTime.keySet()) {
            long lastTime = lastDataTime.get(source);
            long age = currentTime - lastTime;
            
            if (age > staleThreshold) {
                log.warn("⚠️ STALE DATA | source={} | last_data={}s ago",
                        source, age / 1000);
            }
        }
    }

    /**
     * End of day summary - at 15:35
     */
    @Scheduled(cron = "0 35 15 * * MON-FRI")
    public void endOfDaySummary() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("📈 END OF DAY SUMMARY | {}", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        log.info("═══════════════════════════════════════════════════════════");
        log.info("  Total Signals: {}", totalSignals.get());
        log.info("  Long Signals:  {}", longSignals.get());
        log.info("  Short Signals: {}", shortSignals.get());
        log.info("  Total Errors:  {}", errorCount.get());
        
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        double hitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) * 100 : 0;
        log.info("  Cache Hit Rate: {}%", String.format("%.1f", hitRate));
        
        // Message totals
        log.info("  Message Totals:");
        for (String source : messageCounters.keySet()) {
            log.info("    {}: {}", source, messageCounters.get(source).get());
        }
        log.info("═══════════════════════════════════════════════════════════");

        // Reset counters for next day
        resetDailyCounters();
    }

    /**
     * Reset counters at start of day - at 09:00
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI")
    public void resetDailyCounters() {
        totalSignals.set(0);
        longSignals.set(0);
        shortSignals.set(0);
        errorCount.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
        messageCounters.clear();
        lastPeriodCounters.clear();
        log.info("🔄 Daily counters reset for new trading day");
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 2) + "..";
    }
}
