package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.*;
import com.kotsin.consumer.monitoring.Timeframe;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * State aggregator for Open Interest data only.
 * Produces WindowedOIMetrics after window closes.
 */
@Data
@Slf4j
public class OIWindowState {

    // Basic instrument info
    private String scripCode;

    // Window info
    private Long windowStartMillis;
    private Long windowEndMillis;

    // OI tracking
    private Long currentOI;
    private Long previousOI;

    public void addOI(OpenInterest oi) {
        if (scripCode == null && oi.getToken() != 0) {
            scripCode = String.valueOf(oi.getToken());
        }

        previousOI = currentOI;
        currentOI = oi.getOpenInterest();
    }

    public WindowedOIMetrics extractOIMetrics(Timeframe timeframe, long windowStart, long windowEnd) {
        this.windowStartMillis = windowStart;
        this.windowEndMillis = windowEnd;

        Long oiChange = null;
        Double oiChangePercent = null;

        if (currentOI != null && previousOI != null) {
            oiChange = currentOI - previousOI;
            if (previousOI != 0) {
                oiChangePercent = (oiChange * 100.0) / previousOI;
            }
        }

        return WindowedOIMetrics.builder()
            .scripCode(scripCode)
            .windowStartMillis(windowStart)
            .windowEndMillis(windowEnd)
            .timeframe(timeframe.getLabel())
            .openInterest(currentOI)
            .oiChange(oiChange)
            .oiChangePercent(oiChangePercent)
            .build();
    }
}
