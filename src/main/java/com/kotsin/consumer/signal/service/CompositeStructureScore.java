package com.kotsin.consumer.signal.service;

import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.signal.model.CSSOutput;
import com.kotsin.consumer.signal.model.CSSOutput.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CompositeStructureScore - Module 6C: CSS Calculator
 * 
 * Combines:
 * - VCP Fast (5m) - 45% weight
 * - VCP Slow (30m) - 35% weight  
 * - CPS (Pivot) Proximity - 20% weight
 * 
 * Also calculates daily pivot levels and CPR.
 */
@Slf4j
@Service
public class CompositeStructureScore {

    @Value("${css.weight.vcp.fast:0.45}")
    private double weightVcpFast;

    @Value("${css.weight.vcp.slow:0.35}")
    private double weightVcpSlow;

    @Value("${css.weight.cps:0.20}")
    private double weightCps;

    // Cache for daily pivots per scripCode
    private final ConcurrentHashMap<String, DailyPivotCache> pivotCache = new ConcurrentHashMap<>();

    private static class DailyPivotCache {
        LocalDate date;
        PivotLevels levels;
    }

    /**
     * Calculate CSS from VCP outputs and candle data
     */
    public CSSOutput calculate(
            String scripCode,
            String companyName,
            MTVCPOutput vcpOutput,
            List<UnifiedCandle> dailyCandles,
            UnifiedCandle currentCandle) {

        // Get VCP scores
        double vcpFast = vcpOutput != null ? vcpOutput.getVcp5m() : 0;
        double vcpSlow = vcpOutput != null ? vcpOutput.getVcp30m() : 0;
        double structuralBias = vcpOutput != null ? vcpOutput.getStructuralBias() : 0;
        double runwayScore = vcpOutput != null ? vcpOutput.getRunwayScore() : 0.5;

        // Calculate pivot levels
        PivotLevels pivotLevels = calculatePivotLevels(scripCode, dailyCandles, currentCandle);

        // Calculate CPS score (proximity to pivot/support/resistance)
        double cpsScore = calculateCPSScore(currentCandle, pivotLevels);

        // Calculate composite score
        double cssScore = weightVcpFast * vcpFast 
                        + weightVcpSlow * vcpSlow 
                        + weightCps * cpsScore;

        // Determine structure state
        StructureState structureState = StructureState.fromScore(cssScore);

        return CSSOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .vcpFast(vcpFast)
                .vcpSlow(vcpSlow)
                .cpsScore(cpsScore)
                .cssScore(cssScore)
                .structureState(structureState)
                .structuralBias(structuralBias)
                .runwayScore(runwayScore)
                .pivotLevels(pivotLevels)
                .build();
    }

    /**
     * Calculate daily pivot levels using previous day's OHLC
     */
    private PivotLevels calculatePivotLevels(String scripCode, List<UnifiedCandle> dailyCandles, UnifiedCandle current) {
        // Check cache
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        DailyPivotCache cached = pivotCache.get(scripCode);
        if (cached != null && cached.date.equals(today) && cached.levels != null) {
            // Update inCPR status with current price
            PivotLevels levels = cached.levels;
            if (current != null) {
                double price = current.getClose();
                boolean inCPR = price >= levels.getCprBottom() && price <= levels.getCprTop();
                return PivotLevels.builder()
                        .pivot(levels.getPivot())
                        .r1(levels.getR1()).r2(levels.getR2()).r3(levels.getR3())
                        .s1(levels.getS1()).s2(levels.getS2()).s3(levels.getS3())
                        .cprTop(levels.getCprTop()).cprBottom(levels.getCprBottom())
                        .inCPR(inCPR)
                        .cprWidth(levels.getCprWidth())
                        .build();
            }
            return levels;
        }

        // Calculate new pivots from previous day's candle
        if (dailyCandles == null || dailyCandles.isEmpty()) {
            return emptyPivotLevels();
        }

        UnifiedCandle prevDay = dailyCandles.get(dailyCandles.size() - 1);
        double high = prevDay.getHigh();
        double low = prevDay.getLow();
        double close = prevDay.getClose();

        // Classic Pivot Point calculation
        double pivot = (high + low + close) / 3.0;
        double range = high - low;

        double r1 = (2 * pivot) - low;
        double r2 = pivot + range;
        double r3 = high + 2 * (pivot - low);

        double s1 = (2 * pivot) - high;
        double s2 = pivot - range;
        double s3 = low - 2 * (high - pivot);

        // CPR (Central Pivot Range)
        double cprTop = (pivot + high + low) / 3.0;
        double cprBottom = (pivot + pivot - cprTop);
        if (cprBottom > cprTop) {
            double temp = cprTop;
            cprTop = cprBottom;
            cprBottom = temp;
        }

        // CPR width classification
        double cprWidthPct = (cprTop - cprBottom) / close * 100;
        CPRWidth cprWidth;
        if (cprWidthPct < 0.3) {
            cprWidth = CPRWidth.NARROW;
        } else if (cprWidthPct > 0.7) {
            cprWidth = CPRWidth.WIDE;
        } else {
            cprWidth = CPRWidth.NORMAL;
        }

        // Check if current price is in CPR
        boolean inCPR = false;
        if (current != null) {
            double price = current.getClose();
            inCPR = price >= cprBottom && price <= cprTop;
        }

        PivotLevels levels = PivotLevels.builder()
                .pivot(pivot)
                .r1(r1).r2(r2).r3(r3)
                .s1(s1).s2(s2).s3(s3)
                .cprTop(cprTop).cprBottom(cprBottom)
                .inCPR(inCPR)
                .cprWidth(cprWidth)
                .build();

        // Cache for today
        DailyPivotCache newCache = new DailyPivotCache();
        newCache.date = today;
        newCache.levels = levels;
        pivotCache.put(scripCode, newCache);

        return levels;
    }

    /**
     * Calculate CPS score based on proximity to pivot levels
     */
    private double calculateCPSScore(UnifiedCandle current, PivotLevels levels) {
        if (current == null || levels == null || levels.getPivot() == 0) {
            return 0.5;  // Neutral
        }

        double price = current.getClose();
        double atr = current.getHigh() - current.getLow();
        // FIX Bug #23: Use 1.5% fallback (more realistic than 1%)
        if (atr <= 0) atr = price * 0.015;

        // Calculate distance to nearest level
        double[] significantLevels = {
            levels.getPivot(),
            levels.getR1(), levels.getR2(),
            levels.getS1(), levels.getS2(),
            levels.getCprTop(), levels.getCprBottom()
        };

        double minDistance = Double.MAX_VALUE;
        for (double level : significantLevels) {
            if (level > 0) {
                double distance = Math.abs(price - level);
                minDistance = Math.min(minDistance, distance);
            }
        }

        // Normalize by ATR
        double distanceInATR = minDistance / atr;
        
        // Score: close to levels = high score (indicates structure)
        // Far from levels = low score (open air)
        double cpsScore = 1 - Math.min(distanceInATR / 2.0, 1.0);
        
        // Boost if in CPR (consolidation zone)
        if (levels.isInCPR()) {
            cpsScore = Math.min(cpsScore * 1.2, 1.0);
        }

        return cpsScore;
    }

    private PivotLevels emptyPivotLevels() {
        return PivotLevels.builder()
                .pivot(0).r1(0).r2(0).r3(0)
                .s1(0).s2(0).s3(0)
                .cprTop(0).cprBottom(0)
                .inCPR(false)
                .cprWidth(CPRWidth.NORMAL)
                .build();
    }

    /**
     * Clear pivot cache (call at market open)
     */
    public void clearPivotCache() {
        pivotCache.clear();
    }
}
