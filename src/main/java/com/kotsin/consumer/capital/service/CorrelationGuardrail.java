package com.kotsin.consumer.capital.service;

import com.kotsin.consumer.capital.model.CorrelationGuardrailOutput;
import com.kotsin.consumer.capital.model.CorrelationGuardrailOutput.*;
import com.kotsin.consumer.capital.model.FinalMagnitude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CorrelationGuardrail - Module 13: CG
 * 
 * Prevents portfolio concentration risk by tracking:
 * - Sector exposure
 * - Direction concentration
 * - Beta exposure
 * - Correlated positions
 * 
 * Applies position caps when heat exceeds thresholds.
 */
@Slf4j
@Service
public class CorrelationGuardrail {

    @Value("${cg.max.positions:10}")
    private int maxPositions;

    @Value("${cg.max.sector.positions:3}")
    private int maxSectorPositions;

    @Value("${cg.max.same.direction:7}")
    private int maxSameDirection;

    @Value("${cg.sector.heat.threshold:40}")
    private double sectorHeatThreshold;

    @Value("${cg.portfolio.heat.threshold:70}")
    private double portfolioHeatThreshold;

    // Track open positions
    private final ConcurrentHashMap<String, PositionInfo> openPositions = new ConcurrentHashMap<>();

    // Sector mapping (would normally come from database)
    private static final Map<String, String> SECTOR_MAP = new HashMap<>();
    static {
        // Common sectors - in production, fetch from ScripFinder
        SECTOR_MAP.put("RELIANCE", "OIL_GAS");
        SECTOR_MAP.put("INFY", "IT");
        SECTOR_MAP.put("TCS", "IT");
        SECTOR_MAP.put("HCLTECH", "IT");
        SECTOR_MAP.put("WIPRO", "IT");
        SECTOR_MAP.put("TECHM", "IT");
        SECTOR_MAP.put("HDFCBANK", "BANKING");
        SECTOR_MAP.put("ICICIBANK", "BANKING");
        SECTOR_MAP.put("KOTAKBANK", "BANKING");
        SECTOR_MAP.put("AXISBANK", "BANKING");
        SECTOR_MAP.put("SBIN", "BANKING");
        SECTOR_MAP.put("BHARTIARTL", "TELECOM");
        SECTOR_MAP.put("TATAMOTORS", "AUTO");
        SECTOR_MAP.put("MARUTI", "AUTO");
        SECTOR_MAP.put("M&M", "AUTO");
        SECTOR_MAP.put("SUNPHARMA", "PHARMA");
        SECTOR_MAP.put("DRREDDY", "PHARMA");
        SECTOR_MAP.put("CIPLA", "PHARMA");
    }

    private static class PositionInfo {
        String scripCode;
        String companyName;
        String sector;
        String direction;  // LONG or SHORT
        double size;
        long entryTime;
    }

    /**
     * Check correlation guardrail for new position
     */
    public CorrelationGuardrailOutput check(
            String scripCode,
            String companyName,
            FinalMagnitude.Direction direction) {

        String sector = getSector(companyName);
        String dirStr = direction == FinalMagnitude.Direction.BULLISH ? "LONG" : "SHORT";

        // Count current exposures
        int totalPositions = openPositions.size();
        int sectorPositions = (int) openPositions.values().stream()
                .filter(p -> p.sector.equals(sector))
                .count();
        int sameDirPositions = (int) openPositions.values().stream()
                .filter(p -> p.direction.equals(dirStr))
                .count();

        // Calculate heat
        double portfolioHeat = (double) totalPositions / maxPositions * 100;
        double sectorHeat = (double) sectorPositions / maxSectorPositions * 100;
        double directionHeat = (double) sameDirPositions / maxSameDirection * 100;

        // Find correlated positions
        List<CorrelatedPosition> topCorrelated = findCorrelatedPositions(scripCode, sector, dirStr);
        double avgCorrelation = topCorrelated.stream()
                .mapToDouble(CorrelatedPosition::getCorrelation)
                .average()
                .orElse(0);

        // Determine action
        GuardrailAction action;
        double multiplier;
        String reason;

        if (totalPositions >= maxPositions) {
            action = GuardrailAction.BLOCK;
            multiplier = 0;
            reason = "Maximum portfolio positions reached (" + maxPositions + ")";
        } else if (sectorPositions >= maxSectorPositions) {
            action = GuardrailAction.BLOCK;
            multiplier = 0;
            reason = "Maximum sector positions reached for " + sector + " (" + maxSectorPositions + ")";
        } else if (sameDirPositions >= maxSameDirection) {
            action = GuardrailAction.REDUCE;
            multiplier = 0.5;
            reason = "High direction concentration (" + sameDirPositions + " " + dirStr + " positions)";
        } else if (portfolioHeat > portfolioHeatThreshold) {
            action = GuardrailAction.SMALL_ONLY;
            multiplier = 0.4;
            reason = "Portfolio heat high (" + String.format("%.1f", portfolioHeat) + "%)";
        } else if (sectorHeat > sectorHeatThreshold) {
            action = GuardrailAction.REDUCE;
            multiplier = 0.7;
            reason = "Sector heat high for " + sector + " (" + String.format("%.1f", sectorHeat) + "%)";
        } else if (avgCorrelation > 0.7) {
            action = GuardrailAction.REDUCE;
            multiplier = 0.7;
            reason = "High correlation with existing positions (" + String.format("%.2f", avgCorrelation) + ")";
        } else {
            action = GuardrailAction.ALLOW;
            multiplier = 1.0;
            reason = "All checks passed";
        }

        return CorrelationGuardrailOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .sector(sector)
                .timestamp(System.currentTimeMillis())
                .portfolioHeat(portfolioHeat)
                .sectorHeat(sectorHeat)
                .directionHeat(directionHeat)
                .openPositions(totalPositions)
                .sectorPositions(sectorPositions)
                .sameDirPositions(sameDirPositions)
                .maxPositions(maxPositions)
                .maxSectorPositions(maxSectorPositions)
                .maxSameDirPositions(maxSameDirection)
                .avgCorrelation(avgCorrelation)
                .topCorrelated(topCorrelated)
                .action(action)
                .positionSizeMultiplier(multiplier)
                .reason(reason)
                .build();
    }

    /**
     * Register a new position
     */
    public void addPosition(String scripCode, String companyName, String direction, double size) {
        PositionInfo pos = new PositionInfo();
        pos.scripCode = scripCode;
        pos.companyName = companyName;
        pos.sector = getSector(companyName);
        pos.direction = direction;
        pos.size = size;
        pos.entryTime = System.currentTimeMillis();
        openPositions.put(scripCode, pos);
        log.info("📊 CG: Added position {} {} {} size={}", scripCode, direction, pos.sector, size);
    }

    /**
     * Remove a closed position
     */
    public void removePosition(String scripCode) {
        PositionInfo removed = openPositions.remove(scripCode);
        if (removed != null) {
            log.info("📊 CG: Removed position {}", scripCode);
        }
    }

    /**
     * Get current portfolio heat
     */
    public double getPortfolioHeat() {
        return (double) openPositions.size() / maxPositions * 100;
    }

    /**
     * Get all open positions
     */
    public Map<String, PositionInfo> getOpenPositions() {
        return new HashMap<>(openPositions);
    }

    /**
     * Clear all positions (for testing)
     */
    public void clearPositions() {
        openPositions.clear();
    }

    /**
     * Find correlated positions
     */
    private List<CorrelatedPosition> findCorrelatedPositions(String scripCode, String sector, String direction) {
        return openPositions.values().stream()
                .filter(p -> !p.scripCode.equals(scripCode))
                .map(p -> {
                    // Simplified correlation: same sector = 0.8, same direction = 0.3
                    double corr = 0;
                    if (p.sector.equals(sector)) corr += 0.5;
                    if (p.direction.equals(direction)) corr += 0.3;
                    
                    return CorrelatedPosition.builder()
                            .scripCode(p.scripCode)
                            .companyName(p.companyName)
                            .correlation(corr)
                            .direction(p.direction)
                            .build();
                })
                .filter(cp -> cp.getCorrelation() > 0.2)
                .sorted((a, b) -> Double.compare(b.getCorrelation(), a.getCorrelation()))
                .limit(5)
                .collect(Collectors.toList());
    }

    /**
     * Get sector for company
     */
    private String getSector(String companyName) {
        if (companyName == null) return "UNKNOWN";
        
        // Check known sectors
        String upperName = companyName.toUpperCase();
        for (Map.Entry<String, String> entry : SECTOR_MAP.entrySet()) {
            if (upperName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        // Heuristics
        if (upperName.contains("BANK") || upperName.contains("FIN")) return "BANKING";
        if (upperName.contains("TECH") || upperName.contains("INFO") || upperName.contains("SOFT")) return "IT";
        if (upperName.contains("PHARMA") || upperName.contains("DRUG")) return "PHARMA";
        if (upperName.contains("MOTOR") || upperName.contains("AUTO")) return "AUTO";
        if (upperName.contains("POWER") || upperName.contains("ENERGY")) return "POWER";
        if (upperName.contains("STEEL") || upperName.contains("METAL")) return "METALS";
        if (upperName.contains("CEMENT") || upperName.contains("INFRA")) return "INFRASTRUCTURE";
        
        return "DIVERSIFIED";
    }
}
