package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.TickData;
import lombok.Data;

@Data
public class OiAccumulator {
    private Long windowStart;
    private Long windowEnd;
    private Long oiStart;
    private Long oiEnd;
    private Long oiChange;
    private Double oiChangePercent;
    private Double oiMomentum;
    private Double oiConcentration;
    private boolean complete = false;
    private int windowMinutes = 1;

    // Put/Call OI tracking (NEW)
    private Long putOi = 0L;
    private Long callOi = 0L;
    private Long putOiStart = null;
    private Long callOiStart = null;

    // Volume tracking for OI-Volume correlation (NEW)
    private Long totalVolume = 0L;

    public OiAccumulator() { this.windowStart = null; }

    public OiAccumulator(Long windowStart, int minutes) {
        this.windowStart = windowStart;
        this.windowMinutes = Math.max(1, minutes);
        this.windowEnd = windowStart + (windowMinutes * 60L * 1000L);
    }


    public void markComplete() {
        complete = true;
        calculateMetrics();
    }

    private void calculateMetrics() {
        if (oiStart != null && oiEnd != null) {
            oiChange = oiEnd - oiStart;
            oiChangePercent = oiStart != 0 ? (double) oiChange / oiStart * 100.0 : 0.0;
            if (windowStart != null && windowEnd != null) {
                long windowMinutes = (windowEnd - windowStart) / (60 * 1000);
                oiMomentum = windowMinutes > 0 ? (double) oiChange / windowMinutes : 0.0;
            }
        }
    }

    public Long getWindowStart() { return windowStart; }
}


