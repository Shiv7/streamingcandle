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

    /**
     * Factory method to create ImbalanceBarData from raw values
     */
    public static ImbalanceBarData create(
        long volumeImb, long dollarImb, int tickRuns, long volumeRuns,
        String direction, double expVolImb, double expDollarImb,
        double expTickRuns, double expVolRuns
    ) {
        VolumeImbalanceData vib = VolumeImbalanceData.builder()
            .cumulative(volumeImb)
            .direction(direction)
            .threshold((long)expVolImb)
            .progress(expVolImb > 0 ? Math.abs(volumeImb) / expVolImb : 0.0)
            .isComplete(expVolImb > 0 && Math.abs(volumeImb) >= expVolImb)
            .build();

        DollarImbalanceData dib = DollarImbalanceData.builder()
            .cumulative(dollarImb)
            .direction(direction)
            .threshold((long)expDollarImb)
            .progress(expDollarImb > 0 ? Math.abs(dollarImb) / expDollarImb : 0.0)
            .isComplete(expDollarImb > 0 && Math.abs(dollarImb) >= expDollarImb)
            .build();

        TickRunsData trb = TickRunsData.builder()
            .currentRun(tickRuns)
            .direction(direction)
            .threshold((int)expTickRuns)
            .progress(expTickRuns > 0 ? Math.abs(tickRuns) / expTickRuns : 0.0)
            .isComplete(expTickRuns > 0 && Math.abs(tickRuns) >= expTickRuns)
            .build();

        VolumeRunsData vrb = VolumeRunsData.builder()
            .currentRun(volumeRuns)
            .direction(direction)
            .threshold((long)expVolRuns)
            .progress(expVolRuns > 0 ? Math.abs(volumeRuns) / expVolRuns : 0.0)
            .isComplete(expVolRuns > 0 && Math.abs(volumeRuns) >= expVolRuns)
            .build();

        return ImbalanceBarData.builder()
            .volumeImbalance(vib)
            .dollarImbalance(dib)
            .tickRuns(trb)
            .volumeRuns(vrb)
            .build();
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
