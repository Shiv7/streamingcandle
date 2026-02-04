package com.kotsin.consumer.signal.processor;

import com.kotsin.consumer.config.VCPConfig;
import com.kotsin.consumer.logging.TraceContext;
import com.kotsin.consumer.model.StrategyState.VcpState;
import com.kotsin.consumer.model.StrategyState.VolumeCluster;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * VcpProcessor - Volume Cluster Profile calculation.
 *
 * Identifies significant price levels where volume concentration occurred.
 * These levels act as support/resistance with volume-weighted strength.
 *
 * Key Outputs:
 * - Support clusters (below current price)
 * - Resistance clusters (above current price)
 * - POC (Point of Control) - highest volume price
 * - Value Area High/Low (70% of volume range)
 * - Runway scores (bullish/bearish bias)
 */
@Component
@Slf4j
public class VcpProcessor {

    @Autowired
    private VCPConfig vcpConfig;

    // Default lookback if config not available
    private static final int DEFAULT_LOOKBACK = 100;
    private static final int MAX_CLUSTERS = 10;
    private static final double CLUSTER_MERGE_THRESHOLD = 0.002; // 0.2% price proximity

    private static final String LOG_PREFIX = "[VCP]";

    /**
     * Calculate VCP state from candle history.
     *
     * @param candles List of candles (most recent first)
     * @param currentPrice Current market price
     * @return Updated VcpState
     */
    public VcpState calculate(List<UnifiedCandle> candles, double currentPrice) {
        TraceContext.addStage("VCP");

        if (candles == null || candles.isEmpty()) {
            log.debug("{} {} No candles provided, returning empty state",
                LOG_PREFIX, TraceContext.getShortPrefix());
            return VcpState.builder().calculatedAt(Instant.now()).build();
        }

        int lookback = vcpConfig != null ? vcpConfig.getLookback5m() : DEFAULT_LOOKBACK;
        List<UnifiedCandle> relevantCandles = candles.subList(0, Math.min(lookback, candles.size()));

        log.debug("{} {} Calculating VCP with {} candles, price={}",
            LOG_PREFIX, TraceContext.getShortPrefix(), relevantCandles.size(), currentPrice);

        // Build volume profile
        Map<Double, VolumeNode> volumeProfile = buildVolumeProfile(relevantCandles);

        // Find POC and Value Area
        double poc = findPOC(volumeProfile);
        double[] valueArea = calculateValueArea(volumeProfile, 0.70);

        // Identify clusters
        List<VolumeCluster> allClusters = identifyClusters(volumeProfile, relevantCandles);

        // Enrich clusters with OFI and OB data
        enrichClusters(allClusters, relevantCandles, currentPrice);

        // Split into support and resistance
        List<VolumeCluster> supportClusters = allClusters.stream()
            .filter(c -> c.getPrice() < currentPrice)
            .sorted((a, b) -> Double.compare(b.getPrice(), a.getPrice())) // Closest first
            .limit(MAX_CLUSTERS)
            .collect(Collectors.toList());

        List<VolumeCluster> resistanceClusters = allClusters.stream()
            .filter(c -> c.getPrice() > currentPrice)
            .sorted(Comparator.comparingDouble(VolumeCluster::getPrice)) // Closest first
            .limit(MAX_CLUSTERS)
            .collect(Collectors.toList());

        // Calculate runway scores
        double bullishRunway = calculateBullishRunway(supportClusters, currentPrice);
        double bearishRunway = calculateBearishRunway(resistanceClusters, currentPrice);

        log.debug("{} {} VCP complete: POC={}, VAH={}, VAL={}, supports={}, resistances={}, bullishRunway={}, bearishRunway={}",
            LOG_PREFIX, TraceContext.getShortPrefix(),
            String.format("%.2f", poc),
            String.format("%.2f", valueArea[1]),
            String.format("%.2f", valueArea[0]),
            supportClusters.size(),
            resistanceClusters.size(),
            String.format("%.3f", bullishRunway),
            String.format("%.3f", bearishRunway));

        return VcpState.builder()
            .supportClusters(supportClusters)
            .resistanceClusters(resistanceClusters)
            .pocPrice(poc)
            .valueAreaHigh(valueArea[1])
            .valueAreaLow(valueArea[0])
            .bullishRunway(bullishRunway)
            .bearishRunway(bearishRunway)
            .lookbackCandles(relevantCandles.size())
            .calculatedAt(Instant.now())
            .build();
    }

    /**
     * Build volume profile from candles.
     */
    private Map<Double, VolumeNode> buildVolumeProfile(List<UnifiedCandle> candles) {
        Map<Double, VolumeNode> profile = new TreeMap<>();

        for (UnifiedCandle candle : candles) {
            if (candle == null) continue;

            // Distribute volume across OHLC range
            // Note: Volume at price data is not available in UnifiedCandle
            double[] prices = {candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose()};
            long volumePerLevel = candle.getVolume() / 4;
            for (double price : prices) {
                double roundedPrice = roundPrice(price);
                profile.computeIfAbsent(roundedPrice, p -> new VolumeNode(p))
                       .addVolume(volumePerLevel, candle);
            }
        }

        return profile;
    }

    /**
     * Find Point of Control (highest volume price).
     */
    private double findPOC(Map<Double, VolumeNode> profile) {
        return profile.values().stream()
            .max(Comparator.comparingLong(VolumeNode::getTotalVolume))
            .map(VolumeNode::getPrice)
            .orElse(0.0);
    }

    /**
     * Calculate Value Area (price range containing X% of volume).
     */
    private double[] calculateValueArea(Map<Double, VolumeNode> profile, double percentile) {
        if (profile.isEmpty()) {
            return new double[]{0, 0};
        }

        long totalVolume = profile.values().stream()
            .mapToLong(VolumeNode::getTotalVolume).sum();
        long targetVolume = (long) (totalVolume * percentile);

        // Start from POC and expand outward
        double poc = findPOC(profile);
        List<Double> sortedPrices = new ArrayList<>(profile.keySet());
        Collections.sort(sortedPrices);

        int pocIndex = sortedPrices.indexOf(poc);
        if (pocIndex < 0) {
            return new double[]{sortedPrices.get(0), sortedPrices.get(sortedPrices.size() - 1)};
        }

        int lowIndex = pocIndex;
        int highIndex = pocIndex;
        long accumulatedVolume = profile.get(poc).getTotalVolume();

        while (accumulatedVolume < targetVolume && (lowIndex > 0 || highIndex < sortedPrices.size() - 1)) {
            long lowVol = lowIndex > 0 ?
                profile.get(sortedPrices.get(lowIndex - 1)).getTotalVolume() : 0;
            long highVol = highIndex < sortedPrices.size() - 1 ?
                profile.get(sortedPrices.get(highIndex + 1)).getTotalVolume() : 0;

            if (lowVol >= highVol && lowIndex > 0) {
                lowIndex--;
                accumulatedVolume += lowVol;
            } else if (highIndex < sortedPrices.size() - 1) {
                highIndex++;
                accumulatedVolume += highVol;
            } else if (lowIndex > 0) {
                lowIndex--;
                accumulatedVolume += lowVol;
            }
        }

        return new double[]{sortedPrices.get(lowIndex), sortedPrices.get(highIndex)};
    }

    /**
     * Identify volume clusters (significant price levels).
     */
    private List<VolumeCluster> identifyClusters(Map<Double, VolumeNode> profile,
                                                   List<UnifiedCandle> candles) {
        if (profile.isEmpty()) {
            return new ArrayList<>();
        }

        // Calculate volume statistics
        long totalVolume = profile.values().stream()
            .mapToLong(VolumeNode::getTotalVolume).sum();
        double avgVolume = (double) totalVolume / profile.size();
        double stdDev = Math.sqrt(profile.values().stream()
            .mapToDouble(n -> Math.pow(n.getTotalVolume() - avgVolume, 2))
            .average().orElse(0));

        // Threshold for significant volume (mean + 1 std dev)
        double threshold = avgVolume + stdDev;

        // Find significant levels
        List<VolumeCluster> clusters = new ArrayList<>();
        for (VolumeNode node : profile.values()) {
            if (node.getTotalVolume() >= threshold) {
                VolumeCluster cluster = VolumeCluster.builder()
                    .price(node.getPrice())
                    .totalVolume(node.getTotalVolume())
                    .strength(calculateStrength(node.getTotalVolume(), totalVolume))
                    .contributingCandles(node.getCandleCount())
                    .formedAt(Instant.now())
                    .build();
                clusters.add(cluster);
            }
        }

        // Merge nearby clusters
        return mergeClusters(clusters);
    }

    /**
     * Merge clusters that are within threshold distance.
     */
    private List<VolumeCluster> mergeClusters(List<VolumeCluster> clusters) {
        if (clusters.size() < 2) return clusters;

        List<VolumeCluster> merged = new ArrayList<>();
        clusters.sort(Comparator.comparingDouble(VolumeCluster::getPrice));

        VolumeCluster current = clusters.get(0);
        for (int i = 1; i < clusters.size(); i++) {
            VolumeCluster next = clusters.get(i);
            double distance = (next.getPrice() - current.getPrice()) / current.getPrice();

            if (distance <= CLUSTER_MERGE_THRESHOLD) {
                // Merge: volume-weighted average price
                long totalVol = current.getTotalVolume() + next.getTotalVolume();
                double weightedPrice = (current.getPrice() * current.getTotalVolume() +
                                        next.getPrice() * next.getTotalVolume()) / totalVol;

                current = VolumeCluster.builder()
                    .price(weightedPrice)
                    .totalVolume(totalVol)
                    .strength(Math.max(current.getStrength(), next.getStrength()))
                    .contributingCandles(current.getContributingCandles() + next.getContributingCandles())
                    .formedAt(current.getFormedAt())
                    .build();
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    /**
     * Enrich clusters with additional metrics.
     */
    private void enrichClusters(List<VolumeCluster> clusters,
                                 List<UnifiedCandle> candles,
                                 double currentPrice) {
        for (VolumeCluster cluster : clusters) {
            // Calculate OFI bias at this level
            double ofiBias = calculateOfiBias(cluster.getPrice(), candles);
            cluster.setOfiBias(ofiBias);

            // Calculate proximity to current price
            double proximity = Math.abs(cluster.getPrice() - currentPrice) / currentPrice;
            cluster.setProximity(proximity);

            // Calculate breakout difficulty (volume * proximity)
            double breakoutDifficulty = cluster.getStrength() * (1 - proximity);
            cluster.setBreakoutDifficulty(breakoutDifficulty);

            // Composite score
            double composite = cluster.getStrength() * 0.4 +
                              Math.abs(ofiBias) * 0.2 +
                              breakoutDifficulty * 0.4;
            cluster.setCompositeScore(composite);
        }
    }

    /**
     * Calculate OFI bias at a price level.
     */
    private double calculateOfiBias(double price, List<UnifiedCandle> candles) {
        double ofiBiasSum = 0;
        int count = 0;

        for (UnifiedCandle candle : candles) {
            if (candle == null) continue;

            // Check if price is within candle range
            if (price >= candle.getLow() && price <= candle.getHigh()) {
                if (candle.isHasOrderbook() && candle.getOfi() != null) {
                    double ofi = candle.getOfi();
                    ofiBiasSum += ofi;
                    count++;
                }
            }
        }

        if (count == 0) return 0;

        // Normalize to -1 to +1
        double avgOfi = ofiBiasSum / count;
        return Math.max(-1, Math.min(1, avgOfi / 1000)); // Adjust scale as needed
    }

    /**
     * Calculate bullish runway score.
     * High score = strong support below, good for longs.
     */
    private double calculateBullishRunway(List<VolumeCluster> supportClusters, double currentPrice) {
        if (supportClusters.isEmpty()) return 0;

        double runwayScore = 0;
        double totalWeight = 0;

        for (int i = 0; i < supportClusters.size(); i++) {
            VolumeCluster cluster = supportClusters.get(i);
            // Weight by proximity (closer clusters matter more)
            double weight = 1.0 / (i + 1);
            runwayScore += cluster.getStrength() * weight;
            totalWeight += weight;
        }

        return totalWeight > 0 ? runwayScore / totalWeight : 0;
    }

    /**
     * Calculate bearish runway score.
     * High score = strong resistance above, good for shorts.
     */
    private double calculateBearishRunway(List<VolumeCluster> resistanceClusters, double currentPrice) {
        if (resistanceClusters.isEmpty()) return 0;

        double runwayScore = 0;
        double totalWeight = 0;

        for (int i = 0; i < resistanceClusters.size(); i++) {
            VolumeCluster cluster = resistanceClusters.get(i);
            double weight = 1.0 / (i + 1);
            runwayScore += cluster.getStrength() * weight;
            totalWeight += weight;
        }

        return totalWeight > 0 ? runwayScore / totalWeight : 0;
    }

    private double calculateStrength(long volume, long totalVolume) {
        return totalVolume > 0 ? (double) volume / totalVolume : 0;
    }

    private double roundPrice(double price) {
        // Round to 2 decimal places
        return Math.round(price * 100) / 100.0;
    }

    /**
     * Helper class for volume profile building.
     */
    private static class VolumeNode {
        private final double price;
        private long totalVolume;
        private int candleCount;

        VolumeNode(double price) {
            this.price = price;
        }

        void addVolume(long volume, UnifiedCandle candle) {
            this.totalVolume += volume;
            this.candleCount++;
        }

        double getPrice() { return price; }
        long getTotalVolume() { return totalVolume; }
        int getCandleCount() { return candleCount; }
    }
}
