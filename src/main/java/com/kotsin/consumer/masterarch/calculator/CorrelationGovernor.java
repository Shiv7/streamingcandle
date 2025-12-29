package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.masterarch.model.NormalizedScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CorrelationGovernor - PART 3: CG – Correlation Governor
 * 
 * MASTER ARCHITECTURE Compliant Module
 * 
 * Purpose: Prevent over-concentration in correlated positions
 * 
 * Logic:
 * - Track rolling correlation >0.75 for cluster formation
 * - Same cluster active → ×0.80 multiplier
 * - Opposite beta cluster active → ×0.70 multiplier
 * 
 * Clusters (examples):
 * - BANK_NIFTY cluster: HDFC, ICICI, KOTAK, AXIS, SBI, INDUS
 * - IT cluster: INFY, TCS, WIPRO, HCLTECH, TECHM
 * - AUTO cluster: MARUTI, TATAMOTORS, M&M, BAJAJ-AUTO
 * 
 * Output Topic: validation-correlation-output
 */
@Slf4j
@Component
public class CorrelationGovernor {
    
    // Predefined clusters based on sector correlation
    private static final Map<String, Set<String>> CLUSTERS = new HashMap<>();
    
    // Active positions tracked per cluster
    private final Map<String, Integer> activePositionsPerCluster = new ConcurrentHashMap<>();
    
    // Correlation threshold
    private static final double CORRELATION_THRESHOLD = 0.75;
    
    // Multipliers per spec
    private static final double SAME_CLUSTER_MULTIPLIER = 0.80;
    private static final double OPPOSITE_BETA_MULTIPLIER = 0.70;
    
    static {
        // Bank/Financial cluster
        CLUSTERS.put("BANK", Set.of(
            "HDFCBANK", "ICICIBANK", "KOTAKBANK", "AXISBANK", "SBIN", 
            "INDUSINDBK", "BANKBARODA", "PNB", "IDFCFIRSTB", "FEDERALBNK"
        ));
        
        // IT cluster
        CLUSTERS.put("IT", Set.of(
            "INFY", "TCS", "WIPRO", "HCLTECH", "TECHM", 
            "LTIM", "MPHASIS", "COFORGE", "PERSISTENT"
        ));
        
        // Auto cluster
        CLUSTERS.put("AUTO", Set.of(
            "MARUTI", "TATAMOTORS", "M&M", "BAJAJ-AUTO", "HEROMOTOCO",
            "EICHERMOT", "TVSMOTOR", "ASHOKLEY"
        ));
        
        // Metal cluster
        CLUSTERS.put("METAL", Set.of(
            "TATASTEEL", "JSWSTEEL", "HINDALCO", "VEDL", "SAIL",
            "JINDALSTEL", "NATIONALUM", "NMDC"
        ));
        
        // Pharma cluster
        CLUSTERS.put("PHARMA", Set.of(
            "SUNPHARMA", "DRREDDY", "CIPLA", "DIVISLAB", "APOLLOHOSP",
            "LUPIN", "BIOCON", "TORNTPHARM"
        ));
        
        // Energy/Oil cluster
        CLUSTERS.put("ENERGY", Set.of(
            "RELIANCE", "ONGC", "IOC", "BPCL", "GAIL",
            "HINDPETRO", "POWERGRID", "NTPC", "ADANIGREEN"
        ));
        
        // FMCG cluster
        CLUSTERS.put("FMCG", Set.of(
            "HINDUNILVR", "ITC", "NESTLEIND", "BRITANNIA", "DABUR",
            "MARICO", "GODREJCP", "COLPAL"
        ));
        
        // Realty cluster
        CLUSTERS.put("REALTY", Set.of(
            "DLF", "GODREJPROP", "OBEROIRLTY", "PHOENIXLTD", "PRESTIGE",
            "LODHA", "BRIGADE"
        ));
    }
    
    /**
     * Correlation Governor Output
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class CorrelationGovernorOutput {
        private String scripCode;
        private String companyName;
        private long timestamp;
        
        // Cluster identification
        private String cluster;
        private boolean clusterFound;
        
        // Active positions
        private int activeInSameCluster;
        private boolean sameClusterActive;
        
        // Opposite beta (negative correlation)
        private String oppositeCluster;
        private int activeInOppositeCluster;
        private boolean oppositeClusterActive;
        
        // Multipliers
        private double cgMultiplier;
        
        // Final output
        private NormalizedScore cgScore;
        
        // Metadata
        private boolean isValid;
    }
    
    /**
     * Calculate Correlation Governor multiplier
     */
    public CorrelationGovernorOutput calculate(
            String scripCode,
            String companyName,
            double signalStrength,
            double previousScore
    ) {
        long timestamp = System.currentTimeMillis();
        
        // Find cluster for this security
        String cluster = findCluster(companyName);
        boolean clusterFound = cluster != null;
        
        // Check same cluster positions
        int activeInSameCluster = 0;
        boolean sameClusterActive = false;
        if (cluster != null) {
            activeInSameCluster = activePositionsPerCluster.getOrDefault(cluster, 0);
            sameClusterActive = activeInSameCluster > 0;
        }
        
        // Check opposite beta cluster
        String oppositeCluster = findOppositeCluster(cluster);
        int activeInOppositeCluster = 0;
        boolean oppositeClusterActive = false;
        if (oppositeCluster != null) {
            activeInOppositeCluster = activePositionsPerCluster.getOrDefault(oppositeCluster, 0);
            oppositeClusterActive = activeInOppositeCluster > 0;
        }
        
        // Calculate multiplier
        double cgMultiplier = 1.0;
        if (oppositeClusterActive) {
            cgMultiplier = OPPOSITE_BETA_MULTIPLIER;  // 0.70 (worse)
        } else if (sameClusterActive) {
            cgMultiplier = SAME_CLUSTER_MULTIPLIER;   // 0.80
        }
        
        // Apply to signal
        double adjustedSignal = signalStrength * cgMultiplier;
        NormalizedScore cgScore = NormalizedScore.strength(adjustedSignal, previousScore, timestamp);
        
        return CorrelationGovernorOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                // Cluster
                .cluster(cluster != null ? cluster : "UNKNOWN")
                .clusterFound(clusterFound)
                // Same cluster
                .activeInSameCluster(activeInSameCluster)
                .sameClusterActive(sameClusterActive)
                // Opposite
                .oppositeCluster(oppositeCluster)
                .activeInOppositeCluster(activeInOppositeCluster)
                .oppositeClusterActive(oppositeClusterActive)
                // Multiplier
                .cgMultiplier(cgMultiplier)
                // Score
                .cgScore(cgScore)
                // Meta
                .isValid(true)
                .build();
    }
    
    /**
     * Register/update active position in cluster
     */
    public void registerPosition(String companyName, boolean isActive) {
        String cluster = findCluster(companyName);
        if (cluster == null) return;
        
        if (isActive) {
            activePositionsPerCluster.merge(cluster, 1, Integer::sum);
            log.debug("Position added to cluster {}: now {} active", cluster, 
                    activePositionsPerCluster.get(cluster));
        } else {
            activePositionsPerCluster.computeIfPresent(cluster, (k, v) -> v > 1 ? v - 1 : null);
            log.debug("Position removed from cluster {}: now {} active", cluster,
                    activePositionsPerCluster.getOrDefault(cluster, 0));
        }
    }
    
    /**
     * Get current active count for a cluster
     */
    public int getActiveCount(String cluster) {
        return activePositionsPerCluster.getOrDefault(cluster, 0);
    }
    
    /**
     * Find cluster for a company name
     */
    private String findCluster(String companyName) {
        if (companyName == null) return null;
        
        String normalized = companyName.toUpperCase().replaceAll("[^A-Z]", "");
        
        for (Map.Entry<String, Set<String>> entry : CLUSTERS.entrySet()) {
            for (String member : entry.getValue()) {
                if (normalized.contains(member) || member.contains(normalized)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }
    
    /**
     * Find opposite beta cluster (typically negative correlation)
     * Examples:
     * - BANK tends to be opposite to METAL (risk-on vs risk-off)
     * - IT can be opposite to AUTO (export vs domestic)
     */
    private String findOppositeCluster(String cluster) {
        if (cluster == null) return null;
        
        return switch (cluster) {
            case "BANK" -> "METAL";
            case "METAL" -> "BANK";
            case "IT" -> "FMCG";  // Export sensitive vs defensive
            case "FMCG" -> "IT";
            case "PHARMA" -> "ENERGY";  // Defensive vs cyclical
            case "ENERGY" -> "PHARMA";
            case "AUTO" -> "REALTY";
            case "REALTY" -> "AUTO";
            default -> null;
        };
    }
}
