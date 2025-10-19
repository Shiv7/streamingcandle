package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Message metadata and quality information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageMetadata {
    
    private String messageVersion;
    private Long producedAt;
    private String dataQuality;
    private Set<String> completeWindows;
    private Integer processingLatency;
    private String source;
    private Long sequenceNumber;
    
    /**
     * Check if message is high quality
     */
    public boolean isHighQuality() {
        return "HIGH".equals(dataQuality);
    }
    
    /**
     * Check if message is medium quality
     */
    public boolean isMediumQuality() {
        return "MEDIUM".equals(dataQuality);
    }
    
    /**
     * Check if message is low quality
     */
    public boolean isLowQuality() {
        return "LOW".equals(dataQuality);
    }
    
    /**
     * Get complete windows count
     */
    public int getCompleteWindowsCount() {
        return completeWindows != null ? completeWindows.size() : 0;
    }
    
    /**
     * Check if specific timeframe is complete
     */
    public boolean isTimeframeComplete(String timeframe) {
        return completeWindows != null && completeWindows.contains(timeframe);
    }
    
    /**
     * Get processing latency level
     */
    public String getLatencyLevel() {
        if (processingLatency == null) return "UNKNOWN";
        if (processingLatency < 10) return "VERY_FAST";
        if (processingLatency < 50) return "FAST";
        if (processingLatency < 100) return "NORMAL";
        if (processingLatency < 500) return "SLOW";
        return "VERY_SLOW";
    }
    
    /**
     * Get display string for logging
     */
    public String getDisplayString() {
        return String.format("Meta[v%s,%s,%dms,%d complete]",
            messageVersion, dataQuality, processingLatency, getCompleteWindowsCount());
    }
}
