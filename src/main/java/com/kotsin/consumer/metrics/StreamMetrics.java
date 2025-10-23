package com.kotsin.consumer.metrics;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class StreamMetrics {
    private final Map<String, AtomicLong> candleEmitsByTf = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> candleDropsByTf = new ConcurrentHashMap<>();
    private final AtomicLong familyEmits = new AtomicLong(0);
    private final AtomicLong oiJoinMisses = new AtomicLong(0);
    private final AtomicLong orderbookJoinMisses = new AtomicLong(0);
    // ✅ P0-2 FIX: Track OI records skipped due to future timestamps
    private final AtomicLong oiFutureSkipped = new AtomicLong(0);

    public void incCandleEmit(String timeframe) { candleEmitsByTf.computeIfAbsent(timeframe, k -> new AtomicLong()).incrementAndGet(); }
    public void incCandleDrop(String timeframe) { candleDropsByTf.computeIfAbsent(timeframe, k -> new AtomicLong()).incrementAndGet(); }
    public void incFamilyEmit() { familyEmits.incrementAndGet(); }
    public void incOiJoinMiss() { oiJoinMisses.incrementAndGet(); }
    public void incOrderbookJoinMiss() { orderbookJoinMisses.incrementAndGet(); }
    // ✅ P0-2 FIX: Increment counter for OI records skipped due to future timestamps
    public void incOiFutureSkipped() { oiFutureSkipped.incrementAndGet(); }

    public Map<String, Long> getCandleEmitsByTf() { return toLongMap(candleEmitsByTf); }
    public Map<String, Long> getCandleDropsByTf() { return toLongMap(candleDropsByTf); }
    public long getFamilyEmits() { return familyEmits.get(); }
    public long getOiJoinMisses() { return oiJoinMisses.get(); }
    public long getOrderbookJoinMisses() { return orderbookJoinMisses.get(); }
    // ✅ P0-2 FIX: Getter for OI future-skipped count
    public long getOiFutureSkipped() { return oiFutureSkipped.get(); }

    private Map<String, Long> toLongMap(Map<String, AtomicLong> src) {
        Map<String, Long> out = new ConcurrentHashMap<>();
        src.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}
