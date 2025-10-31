# OHLCV Aggregation: Known Issues, Fixes, and Ops Guide

This document captures the problems we saw while matching broker candles, the root causes, and the precise code-level fixes. It also covers configuration knobs, operational procedures, and validation tips.

## Data Flow Overview

1. Ticks → 1-minute enriched candles
   - Topology: `CandlestickProcessor.processTickData`
   - Timestamp: tick event time with per-exchange offset (NSE +15m)
   - Aggregation: 1m `TimeWindows` with suppression until window closes

2. 1-minute → multi-minute rollups (2m/3m/5m/15m/30m)
   - Topology: `CandlestickProcessor.processMultiMinuteCandlestick`
   - Timestamp: 1m candle START time + per-exchange offset

---

## Fixes Applied (Symptoms → Root Cause → Solution)

### 1) 1m candles missing volume or late ticks

- Symptom: Under-counted volume; missing ticks around minute boundaries.
- Root Cause: Grace period set to 1s; late ticks often arrive >1s late.
- Solution: Make grace configurable and set 1m grace to 5s (cap finalization delay to 5s).
  - Code: `CandlestickProcessor` uses `candles.window.grace.seconds.1m` and `candles.window.grace.seconds.multi`.

### 2) 1m O=H=L=C zeroed or flat for index/ETF

- Symptom: All OHLC zero or flat when feed has price but no per-tick volume.
- Root Cause: OHLC updates were gated on trade volume; index/ETF often have price prints without LastQty/TotalQty.
- Solution: Always update OHLC from price; update volume/VWAP only when delta volume > 0.
  - Code: `EnrichedCandlestick.updateWithDelta` now always updates OHLC from price.

### 3) First bar huge/phantom volume after restart

- Symptom: First bar shows massive volume spike on cold start.
- Root Cause: Cum→delta transformer used current cumulative when `prevMax == null`.
- Solution: Treat first observation as reset with zero delta; set `resetFlag` for metrics only.
  - Code: `CumToDeltaTransformer` when `prevMax == null` sets `add = 0` and `isReset = true`.

### 4) Delta fallback may double-count for indices

- Symptom: Possible over-count when both LastQty and cumulative appear intermittently.
- Root Cause: Fallback to `lastQuantity` when cumulative doesn’t change can double-count if cumulative later jumps and includes that trade.
- Solution: Keep fallback but conservatively apply; consider restricting to known index instruments if needed.
  - Code: `CumToDeltaTransformer` retains a conservative fallback; can be tightened later by whitelist.

### 5) Event-time timestamp parsing unstable

- Symptom: Mis-windowing due to fallback to record time.
- Root Cause: Parsing via `ZonedDateTime` + strict pattern often fails; `TickData.time` was unused.
- Solution: Harden extractor with ordered fallbacks: `tick.timestamp` → `TickData.time` (ms or sec) → `/Date(…)/` → `yyyy-MM-dd HH:mm:ss[.SSS]` as IST → record/partition time. Never use system time.
  - Code: `TickTimestampExtractor`, `TickTimestampExtractorWithOffset`.

### 6) Cross-instrument mixing (state bleed)

- Symptom: Volume/price from one symbol bleeding into another.
- Root Cause: Keying by `ScripCode` or bare token can collide across exchanges/segments.
- Solution: Composite key `Exch:ExchType:Token` across the pipeline and delta store.
  - Code: `CandlestickProcessor` selects composite key; delta store uses the stream key.

### 7) Multi-minute rollups misaligned (e.g., 2m/3m off by a minute)

- Symptom: 2m or 3m bars start at 14:11 instead of broker’s 14:12; 2m bars split wrong pairs.
- Root Cause: Using 1m candle END time as event time plus exchange offset splits adjacent 1m bars across rollup windows.
- Solution: Use 1m candle START time for rollups, then shift using per-exchange offset; subtract the same offset when publishing for correct display.
  - Code: `MultiMinuteOffsetTimestampExtractor` prefers START over END.

### 8) Trading hours end-bound inclusivity

- Symptom: Last candle mismatch at exact session close.
- Root Cause: Inclusive end-bound for NSE (15:30) may differ from broker logic.
- Solution: Optional: make end bound exclusive if required by broker (not enabled by default).
  - Code: `CandlestickProcessor.withinTradingHours` can be adjusted if needed.

### 9) O=H=L=C even with price movement

- Symptom: Final OHLC all equal at last price.
- Root Cause: OHLC state fields (`openInitialized`, `highInitialized`, etc.) were transient and not persisted, resetting each tick.
- Solution: Persist OHLC state fields (remove transient) so aggregation is consistent across the window.
  - Code: `EnrichedCandlestick` persistence of OHLC flags and source timestamps.

---

## Configuration Summary

Properties in `src/main/resources/application.properties`:

- Kafka Streams
  - `spring.kafka.streams.properties.auto.offset.reset=earliest`
  - `spring.kafka.streams.state-dir=/tmp/kafka-streams/${spring.application.name}`

- Windowing
  - `candles.window.grace.seconds.1m=5`  ← 1m finalization delay
  - `candles.window.grace.seconds.multi=10`  ← multi-minute grace

- Testing filters
  - `candles.filter.enabled=true`
  - `candles.filter.token=999920000`

Notes:
- With suppression, the final 1m bar emits at window close + grace. Set grace to balance completeness vs. latency.

---

## Operational Playbook

1. Start fresh replay
   - Delete consumer group offsets for app IDs (or bump app ID suffix).
   - Clear the state directory for those app IDs if window boundaries changed.

2. Verify alignment
   - Compare 1m vs broker at open/close and around bursts of activity.
   - Validate 2m/3m starts match broker boundaries (e.g., 14:12, 14:15 …).

3. Late data behavior
   - If you see missing last ticks, increase `candles.window.grace.seconds.1m` to 6–8.

4. Index/ETF specifics
   - OHLC is price-driven; volume stays 0 unless trades present. This matches many index feeds.

---

## Tuning Knobs

- `candles.window.grace.seconds.1m` (default 5)
  - Lower for faster emits; raise for fewer missing late ticks.

- `candles.window.grace.seconds.multi` (default 10)
  - Multi-minute tolerance; can be larger than 1m without UX impact.

- Trading hours end-bound
  - If broker excludes trades exactly at end, make end bound exclusive in `withinTradingHours`.

---

## Validation Checklist

- 1m
  - OHLC changes within a minute track price prints even without volume.
  - Volume only increases when trades occur.
  - Final bar delay equals 1m grace.

- Multi-minute
  - 2m groups (t, t+1) correctly; 3m ticks align to broker starts (e.g., :12, :15).
  - Start/end times of published candles match expectations post offset removal.

- No cross-symbol mixing
  - Keys are `Exch:ExchType:Token`; verify no bleed across segments.

---

## Key Code References

- 1m aggregation: `src/main/java/com/kotsin/consumer/processor/CandlestickProcessor.java`
- Cum→delta: `src/main/java/com/kotsin/consumer/transformers/CumToDeltaTransformer.java`
- 1m timestamp: `src/main/java/com/kotsin/consumer/timeExtractor/TickTimestampExtractorWithOffset.java`
- Multi-minute timestamp: `src/main/java/com/kotsin/consumer/timeExtractor/MultiMinuteOffsetTimestampExtractor.java`
- Candle model/aggregation: `src/main/java/com/kotsin/consumer/model/EnrichedCandlestick.java`

---

## Example Settings (production testing)

```properties
spring.kafka.streams.properties.auto.offset.reset=earliest

# Grace
candles.window.grace.seconds.1m=5
candles.window.grace.seconds.multi=10

# Single instrument test
candles.filter.enabled=true
candles.filter.token=999920000
```

