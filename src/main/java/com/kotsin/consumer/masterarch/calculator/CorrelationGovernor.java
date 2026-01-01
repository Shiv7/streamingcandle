package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.infrastructure.api.IndustryClient;
import com.kotsin.consumer.masterarch.model.NormalizedScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CorrelationGovernor - PART 3: CG – Correlation Governor
 * 
 * MASTER ARCHITECTURE Compliant Module (FF1 Spec)
 * 
 * Purpose: Prevent over-concentration in correlated positions
 * 
 * Logic:
 * - Uses IndustryClient to get dynamic industry/cluster from ScripFinder
 * - Same cluster active → ×0.80 multiplier
 * - Opposite beta cluster active → ×0.70 multiplier
 * 
 * Clusters are dynamically derived from ScripFinder's industry mapping:
 * - Excel file: NSE_nifty_symbol_seggregation.xlsx
 * - MongoDB: nifty100_stocks collection
 * 
 * Output Topic: validation-correlation-output
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorrelationGovernor {
    
    // Inject IndustryClient for dynamic cluster lookup
    private final IndustryClient industryClient;
    
    // Active positions tracked per cluster
    private final Map<String, Integer> activePositionsPerCluster = new ConcurrentHashMap<>();
    
    // Multipliers per FF1 spec
    private static final double SAME_CLUSTER_MULTIPLIER = 0.80;
    private static final double OPPOSITE_BETA_MULTIPLIER = 0.70;
    
    // Opposite cluster mappings (risk-on vs risk-off, export vs domestic)
    private static final Map<String, String> OPPOSITE_CLUSTERS = Map.of(
        "BANK", "METAL",
        "METAL", "BANK",
        "IT", "FMCG",
        "FMCG", "IT",
        "PHARMA", "ENERGY",
        "ENERGY", "PHARMA",
        "AUTO", "REALTY",
        "REALTY", "AUTO"
    );
    
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
     * Find cluster for a company name using IndustryClient (dynamic lookup)
     */
    private String findCluster(String companyName) {
        if (companyName == null) return null;
        return industryClient.getCluster(companyName);
    }
    
    /**
     * Find opposite beta cluster (typically negative correlation)
     * Uses OPPOSITE_CLUSTERS map for lookup
     */
    private String findOppositeCluster(String cluster) {
        if (cluster == null) return null;
        return OPPOSITE_CLUSTERS.get(cluster);
    }
}

