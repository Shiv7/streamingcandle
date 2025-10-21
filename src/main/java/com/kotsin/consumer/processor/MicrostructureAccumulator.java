package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.MicrostructureData;
import com.kotsin.consumer.model.TickData;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulator for market microstructure metrics
 * Implements OFI, VPIN, Kyle's Lambda, and other microstructure indicators
 */
@Data
public class MicrostructureAccumulator {
    // Order Flow Imbalance (OFI)
    private Double ofi = 0.0;
    private Double prevBidQty = 0.0;
    private Double prevAskQty = 0.0;
    private Double prevBidPrice = 0.0;
    private Double prevAskPrice = 0.0;

    // VPIN calculation
    private List<Double> volumeBuckets = new ArrayList<>();
    private List<Double> buyVolumeInBuckets = new ArrayList<>();
    private double currentBucketVolume = 0.0;
    private double currentBucketBuyVolume = 0.0;
    private Double vpin = 0.0;  // VPIN value
    private static final double BUCKET_SIZE = 10000.0;  // Volume per bucket
    private static final int VPIN_WINDOW = 50;  // Number of buckets for VPIN

    // Depth imbalance
    private Double depthImbalance = 0.0;
    private Double totalBidQty = 0.0;
    private Double totalAskQty = 0.0;

    // Kyle's Lambda (price impact)
    private List<Double> priceChanges = new ArrayList<>();
    private List<Double> volumes = new ArrayList<>();
    private Double kyleLambda = 0.0;

    // Effective spread
    private Double effectiveSpread = 0.0;
    private Double midPrice = 0.0;

    // Microprice
    private Double microprice = 0.0;

    private boolean complete = false;
    private int updateCount = 0;

    public void addTick(TickData tick) {
        if (tick.getLastRate() <= 0 || tick.getDeltaVolume() == null) {
            return;
        }

        updateCount++;

        // Update bid/ask from tick data
        double bidPrice = tick.getBidRate();
        double askPrice = tick.getOfferRate();
        double bidQty = (double) tick.getBidQuantity();
        double askQty = (double) tick.getOfferQuantity();

        // Calculate mid price
        if (bidPrice > 0 && askPrice > 0) {
            midPrice = (bidPrice + askPrice) / 2.0;

            // Calculate microprice (weighted by depth)
            if (bidQty + askQty > 0) {
                microprice = (bidPrice * askQty + askPrice * bidQty) / (bidQty + askQty);
            }

            // Calculate effective spread
            double tradePrice = tick.getLastRate();
            effectiveSpread = 2.0 * Math.abs(tradePrice - midPrice);
        }

        // Update depth imbalance
        totalBidQty = (double) tick.getTotalBidQuantity();
        totalAskQty = (double) tick.getTotalOfferQuantity();
        if (totalBidQty + totalAskQty > 0) {
            depthImbalance = (totalBidQty - totalAskQty) / (totalBidQty + totalAskQty);
        }

        // Calculate OFI (Order Flow Imbalance)
        // OFI = ΔBid - ΔAsk
        if (prevBidPrice > 0 && prevAskPrice > 0) {
            double deltaBid = 0.0;
            double deltaAsk = 0.0;

            // If bid price increased, add bid quantity
            if (bidPrice >= prevBidPrice) {
                deltaBid = bidQty - (bidPrice == prevBidPrice ? prevBidQty : 0);
            } else {
                deltaBid = -prevBidQty;  // Bid was removed
            }

            // If ask price decreased, add ask quantity
            if (askPrice <= prevAskPrice) {
                deltaAsk = askQty - (askPrice == prevAskPrice ? prevAskQty : 0);
            } else {
                deltaAsk = -prevAskQty;  // Ask was removed
            }

            ofi = deltaBid - deltaAsk;
        }

        // Update previous values for OFI
        prevBidPrice = bidPrice;
        prevAskPrice = askPrice;
        prevBidQty = bidQty;
        prevAskQty = askQty;

        // VPIN calculation (volume bucketing)
        updateVPIN(tick);

        // Kyle's Lambda (price impact estimation)
        updateKyleLambda(tick);

        if (updateCount >= 10) {
            complete = true;
        }
    }

    private void updateVPIN(TickData tick) {
        // Classify trade direction using tick rule
        boolean isBuy = determineIsBuy(tick);
        double volume = tick.getDeltaVolume();

        currentBucketVolume += volume;
        if (isBuy) {
            currentBucketBuyVolume += volume;
        }

        // If bucket is full, close it and start new one
        if (currentBucketVolume >= BUCKET_SIZE) {
            volumeBuckets.add(currentBucketVolume);
            buyVolumeInBuckets.add(currentBucketBuyVolume);

            // Keep only last VPIN_WINDOW buckets
            if (volumeBuckets.size() > VPIN_WINDOW) {
                volumeBuckets.remove(0);
                buyVolumeInBuckets.remove(0);
            }

            // Reset current bucket
            currentBucketVolume = 0.0;
            currentBucketBuyVolume = 0.0;

            // Calculate VPIN
            calculateVPIN();
        }
    }

    private void calculateVPIN() {
        if (volumeBuckets.isEmpty()) {
            return;
        }

        double totalVolume = 0.0;
        double totalAbsImbalance = 0.0;

        for (int i = 0; i < volumeBuckets.size(); i++) {
            double bucketVol = volumeBuckets.get(i);
            double buyVol = buyVolumeInBuckets.get(i);
            double sellVol = bucketVol - buyVol;

            totalVolume += bucketVol;
            totalAbsImbalance += Math.abs(buyVol - sellVol);
        }

        if (totalVolume > 0) {
            this.vpin = totalAbsImbalance / totalVolume;
        }
    }

    private boolean determineIsBuy(TickData tick) {
        // Use tick rule: if trade price >= mid, it's a buy
        if (midPrice > 0) {
            return tick.getLastRate() >= midPrice;
        }
        // Fallback: if price went up, assume buy
        return tick.getLastRate() > (prevBidPrice + prevAskPrice) / 2.0;
    }

    private void updateKyleLambda(TickData tick) {
        // Kyle's Lambda: λ = Cov(ΔP, V) / Var(V)
        // Simplified: track price changes and volumes for regression

        double priceChange = tick.getLastRate() - (prevBidPrice + prevAskPrice) / 2.0;
        double volume = tick.getDeltaVolume();

        priceChanges.add(priceChange);
        volumes.add(volume);

        // Keep only last 100 observations
        if (priceChanges.size() > 100) {
            priceChanges.remove(0);
            volumes.remove(0);
        }

        // Calculate Kyle's Lambda (simple covariance / variance)
        if (priceChanges.size() >= 20) {
            double meanPriceChange = priceChanges.stream().mapToDouble(d -> d).average().orElse(0.0);
            double meanVolume = volumes.stream().mapToDouble(d -> d).average().orElse(0.0);

            double covariance = 0.0;
            double varianceVolume = 0.0;

            for (int i = 0; i < priceChanges.size(); i++) {
                double pDev = priceChanges.get(i) - meanPriceChange;
                double vDev = volumes.get(i) - meanVolume;
                covariance += pDev * vDev;
                varianceVolume += vDev * vDev;
            }

            if (varianceVolume > 0) {
                kyleLambda = covariance / varianceVolume;
            }
        }
    }

    public void markComplete() {
        complete = true;
    }

    public MicrostructureData toMicrostructureData(Long windowStart, Long windowEnd) {
        return MicrostructureData.builder()
            .ofi(ofi)
            .vpin(vpin)
            .depthImbalance(depthImbalance)
            .kyleLambda(kyleLambda)
            .effectiveSpread(effectiveSpread)
            .microprice(microprice)
            .isComplete(complete)
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .build();
    }
}
