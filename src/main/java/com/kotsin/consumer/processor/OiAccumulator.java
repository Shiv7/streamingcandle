package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.OpenInterestTimeframeData;
import com.kotsin.consumer.model.TickData;

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

    public void addOiData(TickData tick) {
        if (windowStart == null) {
            windowStart = alignToMinute(tick.getTimestamp());
            windowEnd = windowStart + (windowMinutes * 60L * 1000L);
        }
        if (oiStart == null) {
            oiStart = tick.getOpenInterest();
        }
        oiEnd = tick.getOpenInterest();

        // Track volume for OI-Volume correlation
        if (tick.getDeltaVolume() != null) {
            totalVolume += tick.getDeltaVolume();
        }
    }

    /**
     * Add Put OI data (called for put options)
     */
    public void addPutOi(Long oi) {
        if (oi == null) return;
        if (putOiStart == null) {
            putOiStart = oi;
        }
        putOi = oi;
    }

    /**
     * Add Call OI data (called for call options)
     */
    public void addCallOi(Long oi) {
        if (oi == null) return;
        if (callOiStart == null) {
            callOiStart = oi;
        }
        callOi = oi;
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

    private long alignToMinute(long timestamp) { return (timestamp / 60_000) * 60_000; }

    public OpenInterestTimeframeData toOiTimeframeData() {
        // Calculate Put/Call ratio
        Double putCallRatio = callOi > 0 ? (double) putOi / callOi : null;

        // Calculate Put/Call changes
        Long putOiChange = (putOiStart != null && putOi != null) ? putOi - putOiStart : null;
        Long callOiChange = (callOiStart != null && callOi != null) ? callOi - callOiStart : null;

        // Calculate Put/Call ratio change
        Double putCallRatioChange = null;
        if (putOiStart != null && callOiStart != null && callOiStart > 0 && callOi > 0) {
            double initialRatio = (double) putOiStart / callOiStart;
            double finalRatio = (double) putOi / callOi;
            putCallRatioChange = finalRatio - initialRatio;
        }

        // Calculate OI-Volume correlation (simplified)
        Double oiVolumeCorr = null;
        if (oiChange != null && totalVolume > 0) {
            // Simple correlation indicator: both positive or both negative = high correlation
            boolean oiPositive = oiChange > 0;
            boolean volumeHigh = totalVolume > 50000;  // Threshold adjustable
            oiVolumeCorr = (oiPositive == volumeHigh) ? 0.8 : -0.2;
        }

        return OpenInterestTimeframeData.builder()
            .oi(oiEnd)
            .oiChange(oiChange)
            .oiChangePercent(oiChangePercent)
            .oiMomentum(oiMomentum)
            .oiConcentration(oiConcentration)
            .isComplete(complete)
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .putOi(putOi > 0 ? putOi : null)
            .callOi(callOi > 0 ? callOi : null)
            .putCallRatio(putCallRatio)
            .putOiChange(putOiChange)
            .callOiChange(callOiChange)
            .putCallRatioChange(putCallRatioChange)
            .volumeInWindow(totalVolume > 0 ? totalVolume : null)
            .oiVolumeCorrelation(oiVolumeCorr)
            .build();
    }

    public Long getWindowStart() { return windowStart; }
}


