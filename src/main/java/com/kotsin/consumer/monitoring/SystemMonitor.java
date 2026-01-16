package com.kotsin.consumer.monitoring;

import com.kotsin.consumer.metrics.StreamMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.Map;

/**
 * System monitoring and alerting service
 * 
 * OBSERVABILITY: Track system health and performance metrics
 * ALERTING: Detect and alert on critical conditions
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SystemMonitor {

    private final StreamMetrics streamMetrics;
    
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    
    private final long startTime = System.currentTimeMillis();
    private long lastAlertTime = 0;
    private static final long ALERT_COOLDOWN_MS = 60000; // 1 minute cooldown

    /**
     * Periodic health check and metrics reporting
     */
    @Scheduled(fixedRate = 60000) // Every 60 seconds
    public void reportMetrics() {
        log.info("📊 === SYSTEM METRICS REPORT ===");
        
        // Stream metrics
        log.info("📈 Stream Metrics: {}", streamMetrics.getMetrics());
        
        // Memory metrics
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        double heapUsedPercent = (double) heapUsage.getUsed() / heapUsage.getMax() * 100;
        log.info("Memory: Used={}MB, Max={}MB, Usage={}%",
            heapUsage.getUsed() / 1024 / 1024,
            heapUsage.getMax() / 1024 / 1024,
            String.format("%.2f", heapUsedPercent));
        
        // System health
        boolean isHealthy = isSystemHealthy();
        log.info("🏥 System Health: {}", isHealthy ? "HEALTHY" : "UNHEALTHY");
        
        // Check for alert conditions
        checkAlertConditions(heapUsedPercent);
        
        log.info("============================");
    }

    /**
     * Check if system is healthy
     */
    public boolean isSystemHealthy() {
        // Check memory usage
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        double heapUsedPercent = (double) heapUsage.getUsed() / heapUsage.getMax() * 100;
        if (heapUsedPercent > 90) {
            return false;
        }
        
        // Check stream metrics
        if (!streamMetrics.isHealthy()) {
            return false;
        }
        
        return true;
    }

    /**
     * Check for alert conditions
     */
    private void checkAlertConditions(double heapUsedPercent) {
        long now = System.currentTimeMillis();
        
        // Cooldown to avoid alert spam
        if (now - lastAlertTime < ALERT_COOLDOWN_MS) {
            return;
        }

        // High memory usage alert
        if (heapUsedPercent > 90) {
            sendAlert(AlertLevel.CRITICAL, "High memory usage", 
                String.format("Heap memory usage: %.2f%%", heapUsedPercent));
            lastAlertTime = now;
        } else if (heapUsedPercent > 80) {
            sendAlert(AlertLevel.WARNING, "Elevated memory usage", 
                String.format("Heap memory usage: %.2f%%", heapUsedPercent));
            lastAlertTime = now;
        }

        // Stream health alert
        if (!streamMetrics.isHealthy()) {
            sendAlert(AlertLevel.CRITICAL, "Stream processing issues", 
                streamMetrics.getMetrics());
            lastAlertTime = now;
        }
    }

    /**
     * Send alert (can be extended to send to external alerting systems)
     */
    private void sendAlert(AlertLevel level, String title, String message) {
        String emoji = level == AlertLevel.CRITICAL ? "🚨" : "⚠️";
        log.error("{} ALERT [{}]: {} - {}", emoji, level, title, message);
        
        // TODO: Integrate with external alerting systems
        // - PagerDuty
        // - Slack
        // - Email
        // - SMS
    }

    /**
     * Get system metrics as map
     */
    public Map<String, Object> getSystemMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        // Memory metrics
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        metrics.put("memory.heap.used", heapUsage.getUsed());
        metrics.put("memory.heap.max", heapUsage.getMax());
        metrics.put("memory.heap.usage.percent", 
            (double) heapUsage.getUsed() / heapUsage.getMax() * 100);
        
        // Stream metrics
        metrics.put("stream.metrics", streamMetrics.getMetrics());
        
        // Health status
        metrics.put("system.healthy", isSystemHealthy());
        
        // Uptime
        long uptime = System.currentTimeMillis() - startTime;
        metrics.put("system.uptime.seconds", uptime / 1000);
        
        return metrics;
    }

    /**
     * Alert level enum
     */
    public enum AlertLevel {
        INFO,
        WARNING,
        CRITICAL
    }
}
