package com.kotsin.consumer.service;

import com.kotsin.consumer.model.OrderBookSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Service for calculating microstructure metrics
 * Extracted from MicrostructureFeatureState to follow Single Responsibility Principle
 */
@Slf4j
public class MicrostructureMetricsCalculator {

    /**
     * Calculate Order Flow Imbalance (OFI)
     *
     * Formula: OFI = Σ[ΔQ_bid * I{P_bid >= P_bid_prev}] - Σ[ΔQ_ask * I{P_ask <= P_ask_prev}]
     */
    public double calculateOFI(OrderBookSnapshot prev, OrderBookSnapshot curr, int levels) {
        double ofi = 0.0;

        try {
            // Bid side OFI
            for (int i = 0; i < Math.min(levels, curr.getAllBids().size()); i++) {
                if (i >= prev.getAllBids().size()) break;

                OrderBookSnapshot.OrderBookLevel bidPrev = prev.getAllBids().get(i);
                OrderBookSnapshot.OrderBookLevel bidCurr = curr.getAllBids().get(i);

                double deltaQty;
                if (bidCurr.getPrice() == bidPrev.getPrice()) {
                    // Price unchanged: use quantity difference
                    deltaQty = bidCurr.getQuantity() - bidPrev.getQuantity();
                } else if (bidCurr.getPrice() > bidPrev.getPrice()) {
                    // Price increased: aggressive buy
                    deltaQty = bidCurr.getQuantity();
                } else {
                    // Price decreased: demand removed
                    deltaQty = -bidPrev.getQuantity();
                }

                ofi += deltaQty * bidCurr.getPrice();
            }

            // Ask side OFI
            for (int i = 0; i < Math.min(levels, curr.getAllAsks().size()); i++) {
                if (i >= prev.getAllAsks().size()) break;

                OrderBookSnapshot.OrderBookLevel askPrev = prev.getAllAsks().get(i);
                OrderBookSnapshot.OrderBookLevel askCurr = curr.getAllAsks().get(i);

                double deltaQty;
                if (askCurr.getPrice() == askPrev.getPrice()) {
                    // Price unchanged: use quantity difference
                    deltaQty = askCurr.getQuantity() - askPrev.getQuantity();
                } else if (askCurr.getPrice() < askPrev.getPrice()) {
                    // Price decreased: aggressive sell
                    deltaQty = askCurr.getQuantity();
                } else {
                    // Price increased: supply removed
                    deltaQty = -askPrev.getQuantity();
                }

                ofi -= deltaQty * askCurr.getPrice();
            }

        } catch (Exception e) {
            log.error("Error calculating OFI: {}", e.getMessage());
        }

        return ofi;
    }

    /**
     * Calculate Depth Imbalance
     *
     * Formula: DI = (bid_qty - ask_qty) / (bid_qty + ask_qty)
     * Returns value between -1 (all asks) and +1 (all bids)
     */
    public double calculateDepthImbalance(OrderBookSnapshot snapshot, int levels) {
        try {
            int bidQty = getTotalVolume(snapshot.getTopBids(levels));
            int askQty = getTotalVolume(snapshot.getTopAsks(levels));
            int totalQty = bidQty + askQty;

            if (totalQty == 0) {
                return 0.0;
            }

            return (double) (bidQty - askQty) / totalQty;

        } catch (Exception e) {
            log.error("Error calculating depth imbalance: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Calculate VPIN (Volume-Synchronized Probability of Informed Trading)
     *
     * Formula: VPIN = |V_buy - V_sell| / V_total
     * Measures toxicity of order flow
     */
    public double calculateVPIN(List<Double> signedVolumeHistory, int minObservations) {
        if (signedVolumeHistory.size() < minObservations) {
            return 0.0;
        }

        try {
            double buyVolume = 0.0;
            double sellVolume = 0.0;

            for (Double signedVol : signedVolumeHistory) {
                if (signedVol > 0) {
                    buyVolume += signedVol;
                } else {
                    sellVolume += Math.abs(signedVol);
                }
            }

            double totalVolume = buyVolume + sellVolume;
            if (totalVolume == 0) {
                return 0.0;
            }

            return Math.abs(buyVolume - sellVolume) / totalVolume;

        } catch (Exception e) {
            log.error("Error calculating VPIN: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Calculate Kyle's Lambda (price impact per unit volume)
     *
     * Formula: λ = Cov(Δp, V_signed) / Var(V_signed)
     * Measures market liquidity
     */
    public double calculateKyleLambda(List<Double> priceHistory, List<Double> signedVolumeHistory, int minObservations) {
        if (priceHistory.size() < minObservations || signedVolumeHistory.size() < minObservations) {
            return 0.0;
        }

        try {
            // Calculate price changes
            Double[] prices = priceHistory.toArray(new Double[0]);
            double[] priceChanges = new double[prices.length - 1];
            for (int i = 1; i < prices.length; i++) {
                priceChanges[i - 1] = prices[i] - prices[i - 1];
            }

            // Get signed volumes
            Double[] signedVols = signedVolumeHistory.toArray(new Double[0]);
            int minLen = Math.min(priceChanges.length, signedVols.length);

            if (minLen < minObservations) {
                return 0.0;
            }

            // Calculate means
            double meanPriceChange = 0.0;
            double meanSignedVol = 0.0;
            for (int i = 0; i < minLen; i++) {
                meanPriceChange += priceChanges[i];
                meanSignedVol += signedVols[i];
            }
            meanPriceChange /= minLen;
            meanSignedVol /= minLen;

            // Calculate covariance and variance
            double covariance = 0.0;
            double variance = 0.0;
            for (int i = 0; i < minLen; i++) {
                double priceDev = priceChanges[i] - meanPriceChange;
                double volDev = signedVols[i] - meanSignedVol;
                covariance += priceDev * volDev;
                variance += volDev * volDev;
            }
            covariance /= minLen;
            variance /= minLen;

            if (variance == 0 || Double.isNaN(variance)) {
                return 0.0;
            }

            double kyleLambda = covariance / variance;

            // Sanity check
            if (kyleLambda < 0 || kyleLambda > 0.1) {
                log.debug("Unusual Kyle's Lambda: {}", kyleLambda);
            }

            return kyleLambda;

        } catch (Exception e) {
            log.error("Error calculating Kyle's Lambda: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Get total volume from list of levels
     */
    public int getTotalVolume(List<OrderBookSnapshot.OrderBookLevel> levels) {
        return levels.stream()
            .mapToInt(OrderBookSnapshot.OrderBookLevel::getQuantity)
            .sum();
    }
}
