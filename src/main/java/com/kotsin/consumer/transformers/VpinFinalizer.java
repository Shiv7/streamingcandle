package com.kotsin.consumer.transformers;

import com.kotsin.consumer.model.EnrichedCandlestick;
import com.kotsin.consumer.model.VpinState;
import com.kotsin.consumer.util.MarketTimeAligner;
import org.apache.kafka.streams.kstream.ValueTransformerWithKey;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.kstream.Windowed;

import java.util.ArrayList;
import java.util.List;

public class VpinFinalizer implements ValueTransformerWithKey<Windowed<String>, EnrichedCandlestick, EnrichedCandlestick> {
    private final String storeName;
    private final double initialBucketSize;
    private final double adaptiveAlpha;
    private final int maxBuckets;
    private KeyValueStore<String, VpinState> store;

    public VpinFinalizer(String storeName, double initialBucketSize, double adaptiveAlpha, int maxBuckets) {
        this.store = null;
        this.storeName = storeName;
        this.initialBucketSize = initialBucketSize;
        this.adaptiveAlpha = adaptiveAlpha;
        this.maxBuckets = maxBuckets;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void init(ProcessorContext context) {
        this.store = (KeyValueStore<String, VpinState>) context.getStateStore(storeName);
    }

    @Override
    public EnrichedCandlestick transform(Windowed<String> windowedKey, EnrichedCandlestick candle) {
        if (candle == null) return null;
        String key = windowedKey.key();

        VpinState s = store.get(key);
        if (s == null) s = new VpinState();
        if (s.bucketSize <= 0) s.bucketSize = initialBucketSize;

        // Merge finalized buckets from candle, normalizing to fixed-size buckets
        if (candle.getVpinBucketCount() > 0) {
            List<EnrichedCandlestick.VPINBucket> src = candle.getVpinBuckets();
            if (src != null && !src.isEmpty()) {
                normalizeAndAppendBuckets(src, s);
            }
        }

        // Merge current partials
        s.currentBucketVolume += candle.getVpinCurrentBucketVolume();
        s.currentBucketBuyVolume += candle.getVpinCurrentBucketBuyVolume();

        // Keep bucket size aligned with candle's adaptive size if set
        if (candle.getVpinBucketSize() > 0) s.bucketSize = candle.getVpinBucketSize();

        // Flush full buckets from current partials using observed buy ratio
        flushFromCurrent(s);

        // Calculate VPIN value
        double vpin = calcVpin(s.buckets);

        // Update state
        store.put(key, s);

        // Reflect into candle
        candle.setVpin(vpin);
        candle.setVpinBucketSize(s.bucketSize);
        // Trim to max buckets for payload as well
        if (s.buckets.size() > maxBuckets) {
            int start = s.buckets.size() - maxBuckets;
            candle.setVpinBuckets(new ArrayList<>(s.buckets.subList(start, s.buckets.size())));
        } else {
            candle.setVpinBuckets(new ArrayList<>(s.buckets));
        }
        candle.setVpinCurrentBucketVolume(s.currentBucketVolume);
        candle.setVpinCurrentBucketBuyVolume(s.currentBucketBuyVolume);

        // Remove exchange offset from window times for display
        int offMin = MarketTimeAligner.getWindowOffsetMinutes(candle.getExchange(), 1);
        long offMs = offMin * 60_000L;
        candle.setWindowStartMillis(windowedKey.window().start() - offMs);
        candle.setWindowEndMillis(windowedKey.window().end() - offMs);

        return candle;
    }

    private void flushFromCurrent(VpinState s) {
        if (s.currentBucketVolume <= 0 || s.bucketSize <= 0) return;
        double ratioBuy = s.currentBucketVolume > 0 ? (s.currentBucketBuyVolume / s.currentBucketVolume) : 0.0;
        boolean madeBucket = false;
        while (s.currentBucketVolume >= s.bucketSize) {
            double bucketVol = s.bucketSize;
            double buyVol = ratioBuy * bucketVol;
            double sellVol = bucketVol - buyVol;
            s.buckets.add(new EnrichedCandlestick.VPINBucket(bucketVol, buyVol, sellVol));
            if (s.buckets.size() > maxBuckets) {
                int excess = s.buckets.size() - maxBuckets;
                for (int i = 0; i < excess; i++) s.buckets.remove(0);
            }
            s.currentBucketVolume -= bucketVol;
            s.currentBucketBuyVolume -= buyVol;
            madeBucket = true;
        }
        // Adaptive bucket sizing
        if (madeBucket) {
            EnrichedCandlestick.VPINBucket last = s.buckets.get(s.buckets.size() - 1);
            s.bucketSize = adaptiveAlpha * last.totalVolume + (1 - adaptiveAlpha) * s.bucketSize;
        }
    }

    private void normalizeAndAppendBuckets(List<EnrichedCandlestick.VPINBucket> src, VpinState s) {
        for (EnrichedCandlestick.VPINBucket b : src) {
            double total = b.totalVolume;
            if (total <= 0) continue;
            double buy = b.buyVolume;
            double ratioBuy = buy / total;

            while (total >= s.bucketSize && s.bucketSize > 0) {
                double vol = s.bucketSize;
                double buyVol = ratioBuy * vol;
                double sellVol = vol - buyVol;
                s.buckets.add(new EnrichedCandlestick.VPINBucket(vol, buyVol, sellVol));
                total -= vol;
                buy -= buyVol;
                if (s.buckets.size() > maxBuckets) {
                    int excess = s.buckets.size() - maxBuckets;
                    for (int i = 0; i < excess; i++) s.buckets.remove(0);
                }
            }
            if (total > 0) {
                double buyVol = Math.max(0.0, Math.min(buy, total));
                double sellVol = total - buyVol;
                s.buckets.add(new EnrichedCandlestick.VPINBucket(total, buyVol, sellVol));
                if (s.buckets.size() > maxBuckets) {
                    int excess = s.buckets.size() - maxBuckets;
                    for (int i = 0; i < excess; i++) s.buckets.remove(0);
                }
            }
        }
    }

    private double calcVpin(List<EnrichedCandlestick.VPINBucket> buckets) {
        if (buckets == null || buckets.isEmpty()) return 0.0;
        double sumImb = 0.0, sumVol = 0.0;
        for (EnrichedCandlestick.VPINBucket b : buckets) {
            sumImb += Math.abs(b.buyVolume - b.sellVolume);
            sumVol += b.totalVolume;
        }
        return sumVol > 0 ? sumImb / sumVol : 0.0;
    }

    @Override
    public void close() {}
}
