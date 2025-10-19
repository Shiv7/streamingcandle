package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Imbalance bar data (VIB, DIB, TRB, VRB)
 * Real-time progress of information-driven bars
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImbalanceBarData {
    
    private VolumeImbalanceData volumeImbalance;
    private DollarImbalanceData dollarImbalance;
    private TickRunsData tickRuns;
    private VolumeRunsData volumeRuns;
    
    /**
     * Check if any imbalance bar is complete
     */
    public boolean hasAnyCompleteBar() {
        return (volumeImbalance != null && volumeImbalance.getIsComplete()) ||
               (dollarImbalance != null && dollarImbalance.getIsComplete()) ||
               (tickRuns != null && tickRuns.getIsComplete()) ||
               (volumeRuns != null && volumeRuns.getIsComplete());
    }
    
    /**
     * Get total complete bars count
     */
    public int getCompleteBarsCount() {
        int count = 0;
        if (volumeImbalance != null && volumeImbalance.getIsComplete()) count++;
        if (dollarImbalance != null && dollarImbalance.getIsComplete()) count++;
        if (tickRuns != null && tickRuns.getIsComplete()) count++;
        if (volumeRuns != null && volumeRuns.getIsComplete()) count++;
        return count;
    }
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class VolumeImbalanceData {
    private Long cumulative;
    private String direction;
    private Long threshold;
    private Double progress;
    private Boolean isComplete;
    
    public Double getProgressPercent() {
        return progress != null ? progress * 100 : null;
    }
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class DollarImbalanceData {
    private Long cumulative;
    private String direction;
    private Long threshold;
    private Double progress;
    private Boolean isComplete;
    
    public Double getProgressPercent() {
        return progress != null ? progress * 100 : null;
    }
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class TickRunsData {
    private Integer currentRun;
    private String direction;
    private Integer threshold;
    private Double progress;
    private Boolean isComplete;
    
    public Double getProgressPercent() {
        return progress != null ? progress * 100 : null;
    }
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class VolumeRunsData {
    private Long currentRun;
    private String direction;
    private Long threshold;
    private Double progress;
    private Boolean isComplete;
    
    public Double getProgressPercent() {
        return progress != null ? progress * 100 : null;
    }
}
