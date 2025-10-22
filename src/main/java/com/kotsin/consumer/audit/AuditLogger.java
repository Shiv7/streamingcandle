package com.kotsin.consumer.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Audit logging service for tracking data processing events
 * 
 * COMPLIANCE: Track all critical data processing events
 * OBSERVABILITY: Structured audit trail for debugging and compliance
 */
@Component
@Slf4j
public class AuditLogger {

    @Value("${features.audit-logging.enabled:true}")
    private boolean auditLoggingEnabled;

    private static final DateTimeFormatter AUDIT_DATE_FORMAT = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    /**
     * Log data processing event
     */
    public void logDataProcessing(String event, String scripCode, Map<String, Object> details) {
        if (!auditLoggingEnabled) {
            return;
        }

        Map<String, Object> auditRecord = createAuditRecord(event, scripCode, details);
        log.info("AUDIT: {}", formatAuditRecord(auditRecord));
    }

    /**
     * Log configuration change
     */
    public void logConfigurationChange(String component, String parameter, Object oldValue, Object newValue) {
        if (!auditLoggingEnabled) {
            return;
        }

        Map<String, Object> details = new HashMap<>();
        details.put("component", component);
        details.put("parameter", parameter);
        details.put("oldValue", oldValue);
        details.put("newValue", newValue);
        
        logDataProcessing("CONFIG_CHANGE", "SYSTEM", details);
    }

    /**
     * Log stream lifecycle event
     */
    public void logStreamLifecycle(String streamName, String action, String status) {
        if (!auditLoggingEnabled) {
            return;
        }

        Map<String, Object> details = new HashMap<>();
        details.put("streamName", streamName);
        details.put("action", action);
        details.put("status", status);
        
        logDataProcessing("STREAM_LIFECYCLE", streamName, details);
    }

    /**
     * Log data quality issue
     */
    public void logDataQualityIssue(String issue, String scripCode, String description) {
        if (!auditLoggingEnabled) {
            return;
        }

        Map<String, Object> details = new HashMap<>();
        details.put("issue", issue);
        details.put("description", description);
        details.put("severity", "WARNING");
        
        logDataProcessing("DATA_QUALITY_ISSUE", scripCode, details);
    }

    /**
     * Log processing error
     */
    public void logProcessingError(String operation, String scripCode, String errorMessage, String errorType) {
        if (!auditLoggingEnabled) {
            return;
        }

        Map<String, Object> details = new HashMap<>();
        details.put("operation", operation);
        details.put("errorMessage", errorMessage);
        details.put("errorType", errorType);
        details.put("severity", "ERROR");
        
        logDataProcessing("PROCESSING_ERROR", scripCode, details);
    }

    /**
     * Log backpressure event
     */
    public void logBackpressureEvent(String action, long lag, long processedRecords) {
        if (!auditLoggingEnabled) {
            return;
        }

        Map<String, Object> details = new HashMap<>();
        details.put("action", action);
        details.put("lag", lag);
        details.put("processedRecords", processedRecords);
        
        logDataProcessing("BACKPRESSURE_EVENT", "SYSTEM", details);
    }

    /**
     * Log candle emission
     */
    public void logCandleEmission(String scripCode, String timeframe, long timestamp, int tickCount) {
        if (!auditLoggingEnabled) {
            return;
        }

        Map<String, Object> details = new HashMap<>();
        details.put("timeframe", timeframe);
        details.put("timestamp", timestamp);
        details.put("tickCount", tickCount);
        
        logDataProcessing("CANDLE_EMISSION", scripCode, details);
    }

    /**
     * Create audit record
     */
    private Map<String, Object> createAuditRecord(String event, String scripCode, Map<String, Object> details) {
        Map<String, Object> auditRecord = new HashMap<>();
        auditRecord.put("timestamp", ZonedDateTime.now().format(AUDIT_DATE_FORMAT));
        auditRecord.put("event", event);
        auditRecord.put("scripCode", scripCode);
        auditRecord.put("details", details);
        return auditRecord;
    }

    /**
     * Format audit record for logging
     */
    private String formatAuditRecord(Map<String, Object> auditRecord) {
        StringBuilder sb = new StringBuilder();
        sb.append("event=").append(auditRecord.get("event"));
        sb.append(", timestamp=").append(auditRecord.get("timestamp"));
        sb.append(", scripCode=").append(auditRecord.get("scripCode"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) auditRecord.get("details");
        if (details != null && !details.isEmpty()) {
            sb.append(", details={");
            details.forEach((key, value) -> 
                sb.append(key).append("=").append(value).append(", ")
            );
            sb.delete(sb.length() - 2, sb.length()); // Remove trailing ", "
            sb.append("}");
        }
        
        return sb.toString();
    }
}
