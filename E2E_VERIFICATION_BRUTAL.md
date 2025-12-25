# BRUTAL END-TO-END VERIFICATION CHECKLIST
## StreamingCandle - Complete Pipeline Testing Protocol

**VERSION**: 1.0
**DATE**: 2024-12-26
**PURPOSE**: Zero-tolerance comprehensive testing from raw input to final signals

---

## 📋 VERIFICATION OVERVIEW

This document provides a **BRUTAL** step-by-step verification protocol for the entire StreamingCandle pipeline covering:
- **42 Kafka Topics**
- **10 Kafka Streams Processors**
- **16 Calculation Modules**
- **8 Processing Stages**
- **End-to-End Integration**

**CRITICAL**: Do NOT skip any section. Every stage depends on previous stages being 100% correct.

---

# 🔴 STAGE 0: PRE-FLIGHT CHECKS

## 0.1 Infrastructure Readiness

### Kafka Cluster
```bash
# Verify broker connectivity
kafka-broker-api-versions.sh --bootstrap-server localhost:9092

# Expected: List of brokers with API versions
# FAIL IF: Connection refused, timeout, or error
```

### Topic Existence Check
```bash
# List all topics
kafka-topics.sh --bootstrap-server localhost:9092 --list | sort > /tmp/topics.txt

# Verify minimum required topics exist:
grep -E "forwardtesting-data|Orderbook|OpenInterest" /tmp/topics.txt

# FAIL IF: Any of the 3 input topics are missing
```

### Application Startup
```bash
# Start application
mvn spring-boot:run

# Watch startup sequence
tail -f logs/app.log | grep -E "Started|state:|ERROR"

# Expected sequence (within 30 seconds):
# ✅ Started CandlestickProcessor
# ✅ Started OrderbookProcessor
# ✅ Started OIProcessor
# ✅ Started UnifiedCandleProcessor
# ✅ Started IPUProcessor
# ✅ Started VCPProcessor
# ✅ Started RegimeProcessor
# ✅ Started TradingSignalProcessor
# ✅ Started FMAProcessor
# ✅ Started UnifiedSignalProcessor

# FAIL IF: Any processor shows ERROR state or fails to start
```

### State Store Validation
```bash
# Check state stores are created
ls -lh /tmp/kafka-streams/streamingcandle/

# Expected directories:
# - vcp-history-5m/
# - vcp-history-15m/
# - vcp-history-30m/
# - ipu-history-5m/
# - ipu-history-15m/
# - ipu-history-30m/
# - unified-candle-table-*/
# - fma-candle-history/
# - etc.

# FAIL IF: State stores not created or permission denied
```

---

# 🟠 STAGE 1: RAW INPUT VALIDATION

## 1.1 Tick Data (forwardtesting-data)

### Volume Check
```bash
# Check if data is flowing
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic forwardtesting-data --max-messages 10 --timeout-ms 5000

# FAIL IF: Zero messages in 5 seconds (market hours)
# PASS IF: At least 5 messages/second during market hours (9:15-15:30 IST)
```

### Schema Validation
```bash
# Consume and parse 10 messages
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic forwardtesting-data --max-messages 10 | jq '.' > /tmp/ticks.json

# Verify required fields exist:
cat /tmp/ticks.json | jq -r '
  if (.scripCode and .ltp and .volume and .timestamp)
  then "PASS"
  else "FAIL: Missing required fields in tick: \(.)"
  end'

# CRITICAL FIELDS REQUIRED:
# - scripCode (string, not null)
# - ltp (double, > 0)
# - volume (long, >= 0)
# - timestamp (long, valid epoch millis)
# - buyQty, sellQty (long, >= 0)
# - bidPrice, askPrice (double, > 0)
# - exchange (string: "NSE" or "NFO")

# FAIL IF: Any message missing critical fields
# FAIL IF: ltp <= 0
# FAIL IF: timestamp not within last 5 minutes (during market hours)
```

### Data Quality Checks
```bash
# Check for duplicate timestamps (same scripCode + exact same timestamp)
cat /tmp/ticks.json | jq -r '[.scripCode, .timestamp] | @csv' | sort | uniq -d

# FAIL IF: Duplicates exist (indicates upstream bug)

# Check price sanity (no stocks > ₹1,00,000)
cat /tmp/ticks.json | jq -r 'select(.ltp > 100000) | .scripCode, .ltp'

# FAIL IF: Any price > 100000 (data error)

# Check for negative values
cat /tmp/ticks.json | jq -r 'select(.ltp < 0 or .volume < 0) | .'

# FAIL IF: Any negative prices/volumes
```

### Latency Check
```bash
# Check tick freshness
current_time=$(date +%s)000  # millis
cat /tmp/ticks.json | jq -r --arg now "$current_time" '
  ($now | tonumber) - .timestamp |
  if . > 60000 then "WARN: Tick older than 60s: \(.)" else "PASS" end'

# FAIL IF: Ticks older than 5 minutes during market hours
```

---

## 1.2 Orderbook Data (Orderbook)

### Volume Check
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic Orderbook --max-messages 10 --timeout-ms 5000 | jq '.' > /tmp/orderbook.json

# FAIL IF: Zero messages in 5 seconds
# PASS IF: At least 1 message every 2 seconds per symbol
```

### Schema Validation
```bash
cat /tmp/orderbook.json | jq -r '
  if (.scripCode and .bids and .asks and .timestamp)
  then "PASS"
  else "FAIL: Missing required fields"
  end'

# REQUIRED FIELDS:
# - scripCode (string)
# - bids (array of {price, quantity, orders})
# - asks (array of {price, quantity, orders})
# - timestamp (long)

# Verify bid/ask structure:
cat /tmp/orderbook.json | jq -r '
  .bids[0] |
  if (.price and .quantity and .orders)
  then "PASS"
  else "FAIL: Invalid bid structure"
  end'

# FAIL IF: bids or asks array empty
# FAIL IF: bid[0].price >= ask[0].price (crossed book)
```

### Depth Validation
```bash
# Check for minimum depth (at least 5 levels)
cat /tmp/orderbook.json | jq -r '
  if (.bids | length >= 5) and (.asks | length >= 5)
  then "PASS"
  else "FAIL: Insufficient depth: bids=\(.bids|length), asks=\(.asks|length)"
  end'

# Check depth is sorted (bids descending, asks ascending)
cat /tmp/orderbook.json | jq -r '
  .bids as $b |
  if ([$b[].price] | . == (. | sort | reverse))
  then "PASS: Bids sorted"
  else "FAIL: Bids not sorted descending"
  end'

# FAIL IF: Order book not properly sorted
```

### Spread Sanity
```bash
# Check bid-ask spread is reasonable (< 2% for liquid stocks)
cat /tmp/orderbook.json | jq -r '
  (.asks[0].price - .bids[0].price) as $spread |
  (.bids[0].price + .asks[0].price) / 2 as $mid |
  ($spread / $mid * 100) as $spread_pct |
  if $spread_pct > 2.0
  then "WARN: Wide spread: \($spread_pct)%"
  else "PASS: Spread \($spread_pct)%"
  end'

# FAIL IF: Spread > 5% (illiquid or bad data)
```

---

## 1.3 Open Interest Data (OpenInterest)

### Volume Check
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic OpenInterest --max-messages 5 --timeout-ms 5000 | jq '.' > /tmp/oi.json

# PASS IF: Messages exist (OI may be less frequent than ticks)
# WARN IF: Zero messages (expected for cash securities, required for F&O)
```

### Schema Validation
```bash
cat /tmp/oi.json | jq -r '
  if (.scripCode and .openInterest and .timestamp)
  then "PASS"
  else "FAIL: Missing OI fields"
  end'

# REQUIRED:
# - scripCode (string)
# - openInterest (long, >= 0)
# - timestamp (long)

# OPTIONAL BUT IMPORTANT:
# - putOI, callOI (for options)
# - futureOI (for futures)

# FAIL IF: openInterest < 0
```

### Data Consistency
```bash
# Check OI doesn't decrease drastically (no > 50% drops in 1 minute)
cat /tmp/oi.json | jq -s 'sort_by(.timestamp) |
  .[] |
  select(.openInterest) |
  {scripCode, oi: .openInterest, ts: .timestamp}'

# Manual check: Ensure OI changes are gradual
# FAIL IF: OI drops > 50% in single update (likely data error)
```

---

# 🟡 STAGE 2: PROCESSED CANDLES VALIDATION

## 2.1 EnrichedCandlestick (candle-ohlcv-1m)

### Data Flow Check
```bash
# Wait 90 seconds after first tick for 1-min candle
sleep 90

# Check candle topic
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic candle-ohlcv-1m --max-messages 5 --property print.key=true \
  | jq '.' > /tmp/candles_1m.json

# FAIL IF: Zero candles after 2 minutes of tick data
```

### OHLCV Integrity
```bash
cat /tmp/candles_1m.json | jq -r '
  if (.open and .high and .low and .close and .volume)
  then
    if (.high >= .low) and
       (.high >= .open) and (.high >= .close) and
       (.low <= .open) and (.low <= .close)
    then "PASS: OHLC valid"
    else "FAIL: OHLC constraints violated: \(.)"
    end
  else "FAIL: Missing OHLC fields"
  end'

# CRITICAL CONSTRAINTS:
# high >= max(open, close, low)
# low <= min(open, close, high)
# volume >= 0
# vwap between low and high

# FAIL IF: Any constraint violated
```

### Volume Profile Validation
```bash
# Check volumeAtPrice exists and sums to total volume
cat /tmp/candles_1m.json | jq -r '
  .volumeAtPrice as $vap |
  .volume as $total |
  if $vap
  then
    ([$vap | to_entries[] | .value] | add) as $sum |
    if ($sum == $total)
    then "PASS: Volume profile sums correctly"
    else "FAIL: volumeAtPrice sum=\($sum) != volume=\($total)"
    end
  else "WARN: volumeAtPrice missing (expected for tick aggregation)"
  end'

# FAIL IF: volumeAtPrice sum != volume
```

### Buy/Sell Volume Check
```bash
cat /tmp/candles_1m.json | jq -r '
  if (.buyVolume + .sellVolume <= .volume)
  then "PASS: Buy+Sell <= Total"
  else "FAIL: buyVolume=\(.buyVolume) + sellVolume=\(.sellVolume) > volume=\(.volume)"
  end'

# FAIL IF: buyVolume + sellVolume > volume
```

### VPIN Validation
```bash
# Check VPIN is in [0, 1]
cat /tmp/candles_1m.json | jq -r '
  .vpin as $v |
  if $v >= 0 and $v <= 1
  then "PASS: VPIN=\($v)"
  else "FAIL: VPIN out of bounds: \($v)"
  end'

# FAIL IF: VPIN < 0 or VPIN > 1
```

### POC (Point of Control) Validation
```bash
# Check POC is within [low, high]
cat /tmp/candles_1m.json | jq -r '
  .poc as $p | .low as $l | .high as $h |
  if $p >= $l and $p <= $h
  then "PASS: POC=\($p) in range [\($l), \($h)]"
  else "FAIL: POC=\($p) outside range [\($l), \($h)]"
  end'

# FAIL IF: POC outside candle range
```

### Timestamp Validation
```bash
# Check window timestamps are aligned to 1-minute boundaries
cat /tmp/candles_1m.json | jq -r '
  .windowStartMillis as $start |
  .windowEndMillis as $end |
  ($end - $start) as $duration |
  if $duration == 60000
  then "PASS: 1-min window"
  else "FAIL: Window duration=\($duration)ms, expected 60000ms"
  end'

# FAIL IF: Window not exactly 60 seconds
```

---

## 2.2 Multi-Timeframe Candles (2m, 3m, 5m, 15m, 30m)

### Aggregation Correctness (5m test)
```bash
# Consume 5 consecutive 1m candles and 1 5m candle for same symbol
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic candle-ohlcv-1m --max-messages 5 > /tmp/candles_1m_seq.json

sleep 300  # Wait for 5m candle

kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic candle-ohlcv-5m --max-messages 1 > /tmp/candle_5m.json

# Verify aggregation:
# 5m.open == first 1m.open
# 5m.close == last 1m.close
# 5m.high == max(all 1m.high)
# 5m.low == min(all 1m.low)
# 5m.volume == sum(all 1m.volume)

jq -s '
  .[0:5] as $ones |
  .[5] as $five |
  {
    open_match: ($ones[0].open == $five.open),
    close_match: ($ones[4].close == $five.close),
    high_match: ([$ones[].high] | max) == $five.high,
    low_match: ([$ones[].low] | min) == $five.low,
    volume_match: ([$ones[].volume] | add) == $five.volume
  }
' /tmp/candles_1m_seq.json /tmp/candle_5m.json

# Expected: All fields should be true
# FAIL IF: Any aggregation mismatch
```

### Window Alignment (15m test)
```bash
# Check 15m candles align to 15-minute boundaries
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic candle-ohlcv-15m --max-messages 3 | jq -r '
  .windowStartMillis as $start |
  ($start / 1000 / 60 % 15) as $offset |
  if $offset == 0
  then "PASS: 15m aligned"
  else "FAIL: 15m candle not aligned, offset=\($offset) minutes"
  end'

# FAIL IF: Start time not aligned to 00, 15, 30, 45 minutes
```

---

## 2.3 OrderbookAggregate (orderbook-signals-*)

### OFI Calculation Check
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic orderbook-signals-5m --max-messages 3 | jq '.' > /tmp/ob_5m.json

# Verify OFI exists and is reasonable
cat /tmp/ob_5m.json | jq -r '
  .ofi as $ofi |
  if $ofi
  then "PASS: OFI=\($ofi)"
  else "FAIL: OFI missing"
  end'

# Check OFI is not NaN or Infinity
cat /tmp/ob_5m.json | jq -r '
  .ofi |
  if (. | isnan) or (. | isinfinite)
  then "FAIL: OFI is NaN or Infinite"
  else "PASS: OFI is finite"
  end'

# FAIL IF: OFI is NaN, Infinity, or missing
```

### Kyle's Lambda Validation
```bash
# Check lambda exists and is positive
cat /tmp/ob_5m.json | jq -r '
  .kyleLambda as $l |
  if $l and $l > 0
  then "PASS: kyleLambda=\($l)"
  else "FAIL: Invalid kyleLambda=\($l)"
  end'

# Check lambda is not absurdly high (> 1.0 is very rare)
cat /tmp/ob_5m.json | jq -r '
  .kyleLambda as $l |
  if $l > 1.0
  then "WARN: Very high kyleLambda=\($l), check calculation"
  else "PASS"
  end'

# FAIL IF: kyleLambda <= 0 or missing
```

### Microprice Bounds
```bash
# Microprice should be between bid and ask
cat /tmp/ob_5m.json | jq -r '
  .microprice as $m |
  .currentBestBid as $b |
  .currentBestAsk as $a |
  if $m >= $b and $m <= $a
  then "PASS: Microprice in spread"
  else "FAIL: Microprice=\($m) outside [\($b), \($a)]"
  end'

# FAIL IF: Microprice outside bid-ask spread
```

### Spoofing Detection
```bash
# Check spoofing flag is boolean
cat /tmp/ob_5m.json | jq -r '
  .spoofingDetected |
  if (. == true or . == false)
  then "PASS"
  else "FAIL: spoofingDetected not boolean"
  end'

# Log spoofing occurrences
cat /tmp/ob_5m.json | jq -r 'select(.spoofingDetected == true) |
  "ALERT: Spoofing detected for \(.scripCode) at \(.timestamp)"'
```

---

## 2.4 OIAggregate (oi-metrics-*)

### OI Change Calculation
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic oi-metrics-5m --max-messages 3 | jq '.' > /tmp/oi_5m.json

# Verify OI change % is calculated correctly
cat /tmp/oi_5m.json | jq -r '
  .oiClose as $close |
  .oiOpen as $open |
  .oiChangePercent as $pct |
  (($close - $open) / $open * 100) as $expected |
  if ($pct - $expected | fabs) < 0.01
  then "PASS: OI change % correct"
  else "FAIL: OI change %=\($pct), expected=\($expected)"
  end'

# FAIL IF: OI change % calculation incorrect
```

### Put/Call OI (Options only)
```bash
# For options, verify putOI + callOI exists
cat /tmp/oi_5m.json | jq -r '
  if (.putOI and .callOI)
  then
    (.putOI + .callOI) as $total |
    .openInterest as $oi |
    if $total == $oi
    then "PASS: Put+Call OI sums correctly"
    else "WARN: Put+Call=\($total) != OI=\($oi)"
    end
  else "INFO: No put/call split (cash security)"
  end'
```

---

# 🟢 STAGE 3: UNIFIED CANDLE VALIDATION

## 3.1 UnifiedCandle Join Success

### Topic Flow Check
```bash
# Wait for joins to complete (window + grace = ~15 seconds)
sleep 20

kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic unified-candle-5m --max-messages 5 | jq '.' > /tmp/unified_5m.json

# FAIL IF: Zero messages after 30 seconds
```

### Field Completeness
```bash
# Verify all 3 sources are joined
cat /tmp/unified_5m.json | jq -r '
  if (.open and .ofi and .oiClose)
  then "PASS: All 3 sources joined"
  else "FAIL: Missing fields - candle=\(.open?), ob=\(.ofi?), oi=\(.oiClose?)"
  end'

# CRITICAL CHECK: Ensure LEFT JOIN doesn't drop candles
# Even if OB/OI missing, candle should exist with null values
cat /tmp/unified_5m.json | jq -r '
  if .open
  then
    if (.ofi == null)
    then "WARN: Orderbook missing for \(.scripCode)"
    else "PASS"
    end
  else "FAIL: Candle data missing (join failed)"
  end'

# FAIL IF: Candle (primary) data missing
# WARN IF: OB or OI consistently missing (check upstream)
```

### Consistency Checks
```bash
# Verify price consistency (close should match across sources)
cat /tmp/unified_5m.json | jq -r '
  .close as $c |
  if $c > 0
  then "PASS: Price data present"
  else "FAIL: close <= 0"
  end'

# Verify timestamps align (within 1 second tolerance)
cat /tmp/unified_5m.json | jq -r '
  .windowEndMillis as $w |
  .timestamp as $t |
  (($w - $t) | fabs) as $diff |
  if $diff < 1000
  then "PASS: Timestamps aligned"
  else "WARN: Timestamp drift=\($diff)ms"
  end'
```

### Data Enrichment Validation
```bash
# Check derived fields exist
cat /tmp/unified_5m.json | jq -r '
  if (.vwap and .poc and .microprice)
  then "PASS: Derived fields present"
  else "FAIL: Missing derived fields"
  end'
```

---

# 🔵 STAGE 4: FEATURE EXTRACTION VALIDATION

## 4.1 IPU (Institutional Participation & Urgency)

### Score Bounds Check
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic ipu-signals-5m --max-messages 5 | jq '.' > /tmp/ipu_5m.json

# All scores must be [0, 1]
cat /tmp/ipu_5m.json | jq -r '
  [.finalIpuScore, .volExpansionScore, .priceEfficiency,
   .ofQuality, .instProxy, .urgencyScore, .certainty] as $scores |
  $scores |
  if all(. >= 0 and . <= 1)
  then "PASS: All scores in [0,1]"
  else "FAIL: Score out of bounds: \($scores)"
  end'

# FAIL IF: Any score < 0 or > 1
```

### Momentum State Validation
```bash
# Check momentum state is valid enum
cat /tmp/ipu_5m.json | jq -r '
  .momentumState as $m |
  if ($m | IN("ACCELERATING", "DECELERATING", "TRENDING", "DRIFTING", "FLAT"))
  then "PASS: Valid momentum state: \($m)"
  else "FAIL: Invalid momentum state: \($m)"
  end'

# Check urgency level is valid enum
cat /tmp/ipu_5m.json | jq -r '
  .urgencyLevel as $u |
  if ($u | IN("AGGRESSIVE", "ELEVATED", "PATIENT", "PASSIVE"))
  then "PASS: Valid urgency level: \($u)"
  else "FAIL: Invalid urgency level: \($u)"
  end'

# FAIL IF: Invalid enum values
```

### Direction Consistency
```bash
# Check direction matches sign of directional conviction
cat /tmp/ipu_5m.json | jq -r '
  .direction as $d |
  .directionalConviction as $c |
  if ($d == "NEUTRAL" and $c < 0.3) or
     ($d != "NEUTRAL" and $c >= 0.3)
  then "PASS: Direction consistent with conviction"
  else "WARN: Direction=\($d) but conviction=\($c)"
  end'
```

### X-Factor Correlation
```bash
# X-factor flag should be true when xfactorScore >= 0.65
cat /tmp/ipu_5m.json | jq -r '
  .xfactorScore as $s |
  .xfactorFlag as $f |
  if ($s >= 0.65 and $f == true) or ($s < 0.65 and $f == false)
  then "PASS: X-factor flag consistent"
  else "FAIL: xfactorScore=\($s) but flag=\($f)"
  end'

# FAIL IF: Flag doesn't match score threshold
```

### Component Validation
```bash
# Verify all components contribute to final score
cat /tmp/ipu_5m.json | jq -r '
  if (.finalIpuScore > 0.5) and (.instProxy < 0.1)
  then "WARN: High IPU but low instProxy (check formula)"
  else "PASS"
  end'
```

---

## 4.2 VCP (Volume Cluster Pivot)

### Score Bounds Check
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic vcp-combined --max-messages 5 | jq '.' > /tmp/vcp_combined.json

# All scores [0, 1], bias [-1, +1]
cat /tmp/vcp_combined.json | jq -r '
  [.vcpCombinedScore, .vcp5m, .vcp15m, .vcp30m,
   .supportScore, .resistanceScore, .runwayScore] as $scores |
  .structuralBias as $bias |
  if ($scores | all(. >= 0 and . <= 1)) and
     ($bias >= -1 and $bias <= 1)
  then "PASS: VCP scores valid"
  else "FAIL: VCP scores out of bounds"
  end'

# FAIL IF: Any score out of range
```

### Multi-Timeframe Fusion
```bash
# Verify combined score is weighted average
cat /tmp/vcp_combined.json | jq -r '
  .vcp5m as $v5 |
  .vcp15m as $v15 |
  .vcp30m as $v30 |
  .vcpCombinedScore as $combined |
  (0.50 * $v5 + 0.30 * $v15 + 0.20 * $v30) as $expected |
  if ($combined - $expected | fabs) < 0.001
  then "PASS: Combined score = 0.5*5m + 0.3*15m + 0.2*30m"
  else "FAIL: Combined=\($combined), expected=\($expected)"
  end'

# FAIL IF: Fusion formula incorrect
```

### Cluster Validation
```bash
# Check clusters exist and are sorted by composite score
cat /tmp/vcp_combined.json | jq -r '
  .clusters as $c |
  if ($c | length > 0)
  then
    ([$c[].compositeScore] | . == (. | sort | reverse)) as $sorted |
    if $sorted
    then "PASS: Clusters sorted by score"
    else "FAIL: Clusters not sorted"
    end
  else "WARN: No clusters detected (low volume?)"
  end'

# Verify cluster price in reasonable range
cat /tmp/vcp_combined.json | jq -r '
  .currentPrice as $p |
  .clusters[] |
  (.price - $p) / $p as $dist |
  if ($dist | fabs) > 0.1
  then "WARN: Cluster at \(.price) is \($dist*100)% from current"
  else "PASS: Cluster within 10%"
  end'

# FAIL IF: Cluster prices are unrealistic (> 50% away from current)
```

### Structural Bias Calculation
```bash
# Verify bias = (support - resistance) / (support + resistance + epsilon)
cat /tmp/vcp_combined.json | jq -r '
  .supportScore as $s |
  .resistanceScore as $r |
  .structuralBias as $bias |
  (($s - $r) / ($s + $r + 0.001)) as $expected |
  if ($bias - $expected | fabs) < 0.01
  then "PASS: Structural bias correct"
  else "FAIL: Bias=\($bias), expected=\($expected)"
  end'

# FAIL IF: Formula incorrect
```

### **CRITICAL BUG CHECK: currentPrice = 0**
```bash
# KNOWN BUG: currentPrice and microprice are often 0
cat /tmp/vcp_combined.json | jq -r '
  .currentPrice as $p |
  if $p == 0
  then "🔴 CRITICAL BUG CONFIRMED: currentPrice = 0 (VCPProcessor.java:189)"
  else "PASS: currentPrice = \($p)"
  end'

# FAIL IF: currentPrice = 0 (this is the bug reported in audit)
# ROOT CAUSE: VCPProcessor passes null to buildCombinedOutput()
```

---

# 🟣 STAGE 5: REGIME ANALYSIS VALIDATION

## 5.1 Index Regime (regime-index-output)

### Index Coverage Check
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic regime-index-output --max-messages 10 | jq '.' > /tmp/regime_index.json

# Verify all major indices are covered
cat /tmp/regime_index.json | jq -r '.indexName' | sort -u

# Expected indices:
# - NIFTY50
# - BANKNIFTY
# - FINNIFTY
# - MIDCPNIFTY

# FAIL IF: Missing any major index
```

### Regime Label Validation
```bash
cat /tmp/regime_index.json | jq -r '
  .label as $l |
  if ($l | IN("STRONG_UPTREND", "UPTREND", "SIDEWAYS",
              "DOWNTREND", "STRONG_DOWNTREND"))
  then "PASS: Valid regime label: \($l)"
  else "FAIL: Invalid label: \($l)"
  end'

# FAIL IF: Invalid regime enum
```

### Regime Strength Bounds
```bash
cat /tmp/regime_index.json | jq -r '
  .regimeStrength as $s |
  if $s >= 0 and $s <= 1
  then "PASS: Regime strength=\($s)"
  else "FAIL: Regime strength out of bounds: \($s)"
  end'

# FAIL IF: Strength not in [0, 1]
```

---

## 5.2 Security Regime (regime-security-output)

### Per-Stock Regime
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic regime-security-output --max-messages 10 | jq '.' > /tmp/regime_security.json

# Check regime score valid
cat /tmp/regime_security.json | jq -r '
  .finalRegimeScore as $s |
  if $s >= -1 and $s <= 1
  then "PASS: Regime score in [-1, 1]"
  else "FAIL: Regime score=\($s)"
  end'

# FAIL IF: Score out of bounds
```

### Index Alignment Check
```bash
cat /tmp/regime_security.json | jq -r '
  .isAlignedWithIndex as $aligned |
  if ($aligned == true or $aligned == false)
  then "PASS: Alignment flag boolean"
  else "FAIL: Invalid alignment flag: \($aligned)"
  end'

# Check alignment makes sense
cat /tmp/regime_security.json | jq -r '
  .finalRegimeScore as $s |
  .isAlignedWithIndex as $a |
  if ($s > 0.5 and $a == false)
  then "WARN: Strong bullish score but not aligned with index?"
  else "PASS"
  end'
```

---

## 5.3 Anti-Cycle Limiter (regime-acl-output)

### Cycle Age Validation
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic regime-acl-output --max-messages 5 | jq '.' > /tmp/acl.json

cat /tmp/acl.json | jq -r '
  .cycleAge as $age |
  if $age >= 0
  then "PASS: Cycle age=\($age) bars"
  else "FAIL: Negative cycle age: \($age)"
  end'

# FAIL IF: Cycle age < 0
```

### Multiplier Bounds
```bash
cat /tmp/acl.json | jq -r '
  .aclMultiplier as $m |
  if $m >= 0 and $m <= 2
  then "PASS: ACL multiplier=\($m)"
  else "WARN: ACL multiplier out of expected range: \($m)"
  end'

# Expected range: [0.5, 1.5] typically
# WARN IF: Multiplier outside [0, 2]
```

---

# 🟤 STAGE 6: SIGNAL DETECTION VALIDATION

## 6.1 CSS (Composite Structure Score)

### CSS Output Check
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic css-output --max-messages 5 | jq '.' > /tmp/css.json

cat /tmp/css.json | jq -r '
  .cssScore as $s |
  if $s >= 0 and $s <= 1
  then "PASS: CSS score=\($s)"
  else "FAIL: CSS score out of bounds: \($s)"
  end'

# FAIL IF: Score not in [0, 1]
```

---

## 6.2 SOM (Sentiment Oscillation Module)

### Choppiness Validation
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic som-output --max-messages 5 | jq '.' > /tmp/som.json

cat /tmp/som.json | jq -r '
  .choppiness as $c |
  if $c >= 0 and $c <= 1
  then "PASS: Choppiness=\($c)"
  else "FAIL: Choppiness out of bounds: \($c)"
  end'

# High choppiness (> 0.7) should reduce signal confidence
# FAIL IF: Choppiness not in [0, 1]
```

---

## 6.3 VTD (Volatility Trap Detector)

### Trap Detection
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic vtd-output --max-messages 5 | jq '.' > /tmp/vtd.json

cat /tmp/vtd.json | jq -r '
  .isTrapDetected as $trap |
  .vtdScore as $score |
  if ($trap == true and $score > 0.6) or
     ($trap == false and $score < 0.4)
  then "PASS: VTD consistent"
  else "WARN: Trap=\($trap) but score=\($score)"
  end'

# WARN IF: Trap flag doesn't match score
```

---

## 6.4 OHM (Option Health Module)

### PCR Validation
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic ohm-output --max-messages 5 | jq '.' > /tmp/ohm.json

cat /tmp/ohm.json | jq -r '
  .putCallRatio as $pcr |
  if $pcr > 0
  then "PASS: PCR=\($pcr)"
  else "FAIL: Invalid PCR=\($pcr)"
  end'

# PCR typically in range [0.5, 2.0]
# WARN IF: PCR > 3.0 (extreme bearishness) or < 0.3 (extreme bullishness)
```

---

# ⚫ STAGE 7: MAGNITUDE ASSEMBLY VALIDATION

## 7.1 FUDKII (Ignition Detection)

### Ignition Flag Check
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic fudkii-output --max-messages 10 | jq '.' > /tmp/fudkii.json

# Count ignition signals
cat /tmp/fudkii.json | jq -r 'select(.isIgnitionFlag == true) |
  "IGNITION: \(.scripCode) score=\(.fudkiiScore)"' | wc -l

# Expected: 0-5 ignitions per minute (rare events)
# WARN IF: > 20 ignitions/minute (too many false positives)
```

### Score Validation
```bash
cat /tmp/fudkii.json | jq -r '
  .fudkiiScore as $s |
  if $s >= 0 and $s <= 1
  then "PASS: FUDKII score=\($s)"
  else "FAIL: Score out of bounds: \($s)"
  end'

# FAIL IF: Score not in [0, 1]
```

---

## 7.2 Final Magnitude (magnitude-final)

### THE CRITICAL OUTPUT - Magnitude Validation
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic magnitude-final --max-messages 10 | jq '.' > /tmp/magnitude.json

# Score bounds
cat /tmp/magnitude.json | jq -r '
  .finalMagnitude as $m |
  if $m >= 0 and $m <= 1
  then "PASS: Magnitude=\($m)"
  else "FAIL: Magnitude out of bounds: \($m)"
  end'

# FAIL IF: Magnitude not in [0, 1]
```

### Component Breakdown Validation
```bash
# Verify all components present
cat /tmp/magnitude.json | jq -r '
  .components as $c |
  if ($c.ipuScore and $c.vcpScore and $c.regimeStrength)
  then "PASS: All components present"
  else "FAIL: Missing components in magnitude"
  end'

# Check no component is NaN
cat /tmp/magnitude.json | jq -r '
  .components |
  to_entries[] |
  select(.value | isnan or isinfinite) |
  "FAIL: Component \(.key) is NaN/Infinite"'

# FAIL IF: Any component is NaN or Infinite
```

### Multipliers Validation
```bash
# All multipliers should be > 0 (they modify magnitude, not zero it out)
cat /tmp/magnitude.json | jq -r '
  .multipliers as $m |
  [$m.aclMultiplier, $m.volumeMultiplier, $m.cssMultiplier, $m.regimeMultiplier] as $all |
  if ($all | all(. > 0))
  then "PASS: All multipliers positive"
  else "FAIL: Zero or negative multiplier: \($m)"
  end'

# FAIL IF: Any multiplier <= 0
```

### Trade Parameters Validation
```bash
# **CRITICAL**: Check if stop-loss and targets are provided
cat /tmp/magnitude.json | jq -r '
  .tradeParams as $tp |
  if ($tp.entryPrice and $tp.stopLoss and $tp.target1)
  then
    # Validate stop-loss is below entry (for long)
    if (.direction == "BULLISH")
    then
      if ($tp.stopLoss < $tp.entryPrice and $tp.target1 > $tp.entryPrice)
      then "PASS: Long trade params valid"
      else "FAIL: Long SL/Target inverted"
      end
    elif (.direction == "BEARISH")
    then
      if ($tp.stopLoss > $tp.entryPrice and $tp.target1 < $tp.entryPrice)
      then "PASS: Short trade params valid"
      else "FAIL: Short SL/Target inverted"
      end
    else "WARN: Neutral direction, no trade params needed"
    end
  else "🔴 CRITICAL: Missing trade params (entryPrice, stopLoss, target1)"
  end'

# FAIL IF: Trade params missing for actionable signal
```

### Direction Consistency
```bash
# Direction should match sign of finalMagnitude bias
cat /tmp/magnitude.json | jq -r '
  .direction as $d |
  .directionConfidence as $c |
  if ($d == "NEUTRAL" and $c < 0.5) or ($d != "NEUTRAL" and $c >= 0.5)
  then "PASS: Direction consistent"
  else "WARN: Direction=\($d) but confidence=\($c)"
  end'
```

---

## 7.3 Watchlist (watchlist-ranked)

### Ranking Validation
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic watchlist-ranked --max-messages 20 | jq '.' > /tmp/watchlist.json

# Verify sorted by magnitude descending
cat /tmp/watchlist.json | jq -r '.finalMagnitude' | \
  awk '{print} NR>1{if($1>prev) print "FAIL: Not sorted"; exit} {prev=$1}'

# FAIL IF: Watchlist not sorted by magnitude
```

### Top Stocks Quality
```bash
# Top 10 should have magnitude > 0.5
cat /tmp/watchlist.json | head -10 | jq -r '
  if .finalMagnitude > 0.5
  then "PASS: \(.scripCode) mag=\(.finalMagnitude)"
  else "WARN: Top stock \(.scripCode) has low magnitude: \(.finalMagnitude)"
  end'

# WARN IF: Top stocks have magnitude < 0.5
```

---

# ⚪ STAGE 8: FINAL TRADING SIGNALS VALIDATION

## 8.1 TradingSignal (trading-signals)

### Signal Type Validation
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic trading-signals --max-messages 10 | jq '.' > /tmp/signals.json

# Verify signal type is valid enum
cat /tmp/signals.json | jq -r '
  .signal as $s |
  if ($s | IN("STRONG_BUY", "BUY", "BREAKOUT_LONG", "BREAKOUT_SHORT",
              "STRONG_SELL", "SELL", "RUNWAY_LONG", "RUNWAY_SHORT",
              "DIVERGENCE_WARNING", "NO_SIGNAL"))
  then "PASS: Valid signal: \($s)"
  else "FAIL: Invalid signal type: \($s)"
  end'

# FAIL IF: Invalid signal enum
```

### Confidence Validation
```bash
# **BUG CHECK**: Confidence must be <= 1.0
cat /tmp/signals.json | jq -r '
  .confidence as $c |
  if $c >= 0 and $c <= 1
  then "PASS: Confidence=\($c)"
  else "🔴 BUG CONFIRMED: Confidence=\($c) exceeds 1.0 (reported in audit)"
  end'

# FAIL IF: Confidence > 1.0 (this is bug #5 from audit)
```

### Signal Direction Consistency
```bash
# BUY signals should have BULLISH IPU
cat /tmp/signals.json | jq -r '
  select(.signal | test("BUY|LONG")) |
  if .ipuDirection == "BULLISH"
  then "PASS: \(.signal) consistent with IPU direction"
  else "WARN: \(.signal) but IPU is \(.ipuDirection)"
  end'

# WARN IF: Buy signal with bearish IPU (low confidence trade)
```

### Rationale Validation
```bash
# Every signal must have a rationale
cat /tmp/signals.json | jq -r '
  .rationale as $r |
  if $r and ($r | length > 10)
  then "PASS: Rationale provided"
  else "FAIL: Missing or empty rationale for \(.scripCode)"
  end'

# FAIL IF: No rationale provided
```

### **CRITICAL: Duplicate Signal Check**
```bash
# Check for duplicate DIVERGENCE_WARNING signals
cat /tmp/signals.json | jq -r '
  select(.signal == "DIVERGENCE_WARNING") |
  "DIVERGENCE_WARNING: \(.scripCode) - \(.rationale)"'

# Expected: Rationale should distinguish BULLISH vs BEARISH divergence
# BUG: Currently both use same enum value
# FAIL IF: Can't distinguish bullish vs bearish divergence from signal alone
```

---

# 🔶 END-TO-END INTEGRATION TESTS

## E2E-1: Full Pipeline Latency Test

### Measure Tick-to-Signal Latency
```bash
# Capture tick timestamp
tick_ts=$(kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic forwardtesting-data --max-messages 1 | jq -r '.timestamp')

# Wait for processing
sleep 10

# Capture signal timestamp
signal_ts=$(kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic trading-signals --max-messages 1 | jq -r '.timestamp')

# Calculate latency
latency=$(($signal_ts - $tick_ts))
echo "Tick-to-Signal Latency: ${latency}ms"

# PASS IF: Latency < 30 seconds (5m window + processing)
# WARN IF: Latency > 60 seconds
# FAIL IF: Latency > 120 seconds
```

---

## E2E-2: Data Lineage Validation

### Trace Single Symbol End-to-End
```bash
SYMBOL="RELIANCE"  # Choose a liquid stock

# Step 1: Find tick
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic forwardtesting-data --max-messages 100 | \
  jq -r --arg sym "$SYMBOL" 'select(.scripCode == $sym) | .ltp' | head -1

# Step 2: Find 1m candle
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic candle-ohlcv-1m --max-messages 10 --property print.key=true | \
  jq -r --arg sym "$SYMBOL" 'select(.scripCode == $sym) | .close'

# Step 3: Find 5m candle
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic candle-ohlcv-5m --max-messages 5 --property print.key=true | \
  jq -r --arg sym "$SYMBOL" 'select(.scripCode == $sym) | .close'

# Step 4: Find unified candle
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic unified-candle-5m --max-messages 5 --property print.key=true | \
  jq -r --arg sym "$SYMBOL" 'select(.scripCode == $sym) | {close, ofi, oiClose}'

# Step 5: Find IPU output
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic ipu-signals-5m --max-messages 5 --property print.key=true | \
  jq -r --arg sym "$SYMBOL" 'select(.scripCode == $sym) | .finalIpuScore'

# Step 6: Find VCP output
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic vcp-combined --max-messages 5 --property print.key=true | \
  jq -r --arg sym "$SYMBOL" 'select(.scripCode == $sym) | .vcpCombinedScore'

# Step 7: Find magnitude
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic magnitude-final --max-messages 10 --property print.key=true | \
  jq -r --arg sym "$SYMBOL" 'select(.scripCode == $sym) | .finalMagnitude'

# Step 8: Find trading signal
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic trading-signals --max-messages 10 --property print.key=true | \
  jq -r --arg sym "$SYMBOL" 'select(.scripCode == $sym) | {signal, confidence}'

# PASS IF: Symbol appears in all stages within 5 minutes
# FAIL IF: Symbol missing from any stage
```

---

## E2E-3: Multi-Symbol Consistency Test

### Verify Multiple Symbols Flow Correctly
```bash
# Capture 10 symbols from tick data
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic forwardtesting-data --max-messages 100 | \
  jq -r '.scripCode' | sort -u | head -10 > /tmp/symbols.txt

# Wait for full pipeline (5 min window + 30s processing)
sleep 330

# Check each symbol appears in magnitude-final
for symbol in $(cat /tmp/symbols.txt); do
  found=$(kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic magnitude-final --max-messages 100 --property print.key=true | \
    jq -r --arg sym "$symbol" 'select(.scripCode == $sym)' | wc -l)

  if [ $found -gt 0 ]; then
    echo "PASS: $symbol found in magnitude-final"
  else
    echo "FAIL: $symbol missing from magnitude-final"
  fi
done

# PASS IF: At least 80% of symbols make it through
# FAIL IF: < 50% of symbols in final output
```

---

## E2E-4: State Store Persistence Test

### Verify State Stores Survive Restart
```bash
# Record state store size before restart
du -sh /tmp/kafka-streams/streamingcandle/ > /tmp/state_before.txt

# Stop application
pkill -f "streamingcandle"

# Wait 5 seconds
sleep 5

# Restart application
mvn spring-boot:run &

# Wait for full startup (30s)
sleep 30

# Check state stores restored
du -sh /tmp/kafka-streams/streamingcandle/ > /tmp/state_after.txt

# Compare sizes (should be same or larger)
diff /tmp/state_before.txt /tmp/state_after.txt

# PASS IF: State stores exist and have data
# FAIL IF: State stores empty (lost history)
```

---

## E2E-5: Cache Eviction Test (Memory Leak Check)

### Monitor Cache Growth Over Time
```bash
# Monitor VCP cache size
for i in {1..10}; do
  cache_size=$(grep "VCP.*cache.*size" logs/app.log | tail -1)
  echo "Iteration $i: $cache_size"
  sleep 60  # Wait 1 minute
done

# Expected: Cache size should stabilize (TTL eviction working)
# FAIL IF: Cache grows continuously (memory leak confirmed)
# This tests bug #3 and #4 from audit
```

---

# 🚨 CRITICAL BUG VERIFICATION (From Audit Report)

## BUG-1: currentPrice = 0 in MTVCPOutput

```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic vcp-combined --max-messages 20 | \
  jq -r 'select(.currentPrice == 0) |
  "🔴 BUG CONFIRMED: \(.scripCode) currentPrice=0, microprice=\(.microprice)"'

# Expected: Should see this bug frequently
# Root cause: VCPProcessor.java:189 passes null to buildCombinedOutput()
# Impact: Position sizing impossible
```

---

## BUG-2: Topic Naming Mismatch

```bash
# Check if application.properties overrides are applied
grep "unified.input.orderbook.prefix" logs/app.log

# Expected: orderbook-signals-
# If default used: orderbook-ohlv- (WRONG)

# Test join success
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic unified-candle-5m --max-messages 5 | \
  jq -r 'select(.ofi == null) |
  "WARN: OFI missing for \(.scripCode) - join may have failed"'

# FAIL IF: Consistent OFI nulls (topic mismatch)
```

---

## BUG-3 & BUG-4: Unbounded Cache Growth

```bash
# Monitor cache metrics
watch -n 10 'grep "cache.*size" logs/app.log | tail -5'

# Run for 1 hour
# Expected: Cache size should cap at ~1000 entries (with TTL)
# Bug: Cache grows to 10000+ entries (no eviction)

# FAIL IF: Cache size grows beyond 5000 entries
```

---

## BUG-5: Confidence > 1.0

```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic trading-signals --max-messages 50 | \
  jq -r 'select(.confidence > 1.0) |
  "🔴 BUG CONFIRMED: \(.scripCode) confidence=\(.confidence) exceeds 1.0"'

# Expected: May see this occasionally
# Root cause: No Math.min(confidence, 1.0) in TradingSignal.java
```

---

## BUG-6: Duplicate DIVERGENCE_WARNING Enum

```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic trading-signals --max-messages 50 | \
  jq -r 'select(.signal == "DIVERGENCE_WARNING") |
  {signal, rationale, ipuDirection}' | \
  jq -s 'group_by(.rationale) |
  if (length > 1)
  then "🔴 BUG: Same enum for bullish/bearish divergence"
  else "PASS: Divergence signals distinguishable"
  end'

# Root cause: TradingSignal.java uses same enum for both directions
```

---

# 📊 PERFORMANCE BENCHMARKS

## PERF-1: Throughput Test

```bash
# Measure messages processed per second
for topic in candle-ohlcv-5m unified-candle-5m ipu-signals-5m magnitude-final; do
  count=$(kafka-run-class.sh kafka.tools.GetOffsetShell \
    --broker-list localhost:9092 --topic $topic --time -1 | \
    awk -F: '{sum += $3} END {print sum}')
  echo "$topic: $count total messages"
done

# Expected throughput (market hours):
# - candle-ohlcv-5m: ~200 msg/min (1000 symbols * 1 candle/5min)
# - unified-candle-5m: ~200 msg/min
# - ipu-signals-5m: ~200 msg/min
# - magnitude-final: ~200 msg/min

# FAIL IF: Throughput < 50% of expected
```

---

## PERF-2: CPU Usage

```bash
# Monitor CPU usage
top -b -n 10 -d 5 | grep java > /tmp/cpu.log

# Calculate average CPU %
avg_cpu=$(awk '{sum+=$9; count++} END {print sum/count}' /tmp/cpu.log)
echo "Average CPU: $avg_cpu%"

# Expected: < 50% CPU during normal operation
# WARN IF: > 70% CPU
# FAIL IF: > 90% CPU (performance issue)
```

---

## PERF-3: Memory Usage

```bash
# Check JVM heap usage
jstat -gc <PID> 1000 10 > /tmp/heap.log

# Monitor for memory leaks (OldGen should not grow continuously)
# Expected: GC should prevent unbounded growth

# FAIL IF: OldGen grows > 80% and stays there
```

---

## PERF-4: State Store Size

```bash
# Check state store disk usage
du -sh /tmp/kafka-streams/streamingcandle/*/ | sort -h

# Expected total: < 5 GB for 1000 symbols with 50-bar history
# WARN IF: > 10 GB
# FAIL IF: > 20 GB (check for data retention issues)
```

---

# 🧪 EDGE CASE & FAILURE SCENARIOS

## EDGE-1: Zero Volume Candle

```bash
# Inject zero-volume tick (if possible in test environment)
# Or find naturally occurring zero-volume candle

kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic candle-ohlcv-5m --max-messages 100 | \
  jq -r 'select(.volume == 0) |
  "Zero-volume candle: \(.scripCode) at \(.windowStartMillis)"'

# Verify downstream handles gracefully:
# - VPIN should handle division by zero
# - VCP should skip or return empty
# - IPU should return low score

# FAIL IF: NaN or Infinity appears in any output
```

---

## EDGE-2: Missing Orderbook Data

```bash
# Simulate orderbook outage
# Stop orderbook producer temporarily

# Check UnifiedCandle still emits (LEFT JOIN should preserve candles)
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic unified-candle-5m --max-messages 10 | \
  jq -r 'select(.ofi == null) |
  "UnifiedCandle with null OFI: \(.scripCode)"'

# PASS IF: Candles still emit with null OFI/orderbook fields
# FAIL IF: No candles emitted (join broken)
```

---

## EDGE-3: Extreme Price Movement

```bash
# Find candles with > 5% move
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic candle-ohlcv-5m --max-messages 100 | \
  jq -r 'select(((.close - .open) / .open | fabs) > 0.05) |
  {scripCode, open, close, move: ((.close - .open) / .open * 100)}'

# Verify IPU/VCP handle extreme moves:
# - IPU exhaustion should trigger
# - VCP should detect breakout clusters

# Check if these generate signals
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic trading-signals --max-messages 100 | \
  jq -r 'select(.signal == "BREAKOUT_LONG" or .signal == "BREAKOUT_SHORT")'

# PASS IF: Extreme moves generate appropriate signals
```

---

## EDGE-4: Market Open/Close

```bash
# Test during market open (9:15 AM IST)
# Expect high volume, wide spreads initially

# Check if processors handle gracefully
grep "ERROR" logs/app.log | grep "9:1[0-5]"

# FAIL IF: Errors during market open

# Test during market close (3:30 PM IST)
# Volume should taper

# PASS IF: No crashes during open/close transitions
```

---

## EDGE-5: Kafka Broker Restart

```bash
# Restart Kafka broker
sudo systemctl restart kafka

# Monitor application logs
tail -f logs/app.log | grep -E "ERROR|WARN|Rebalance"

# Expected: Kafka Streams should auto-recover within 30 seconds
# PASS IF: All processors return to RUNNING state
# FAIL IF: Processors stuck in ERROR or REBALANCING > 2 minutes
```

---

# ✅ FINAL VALIDATION CHECKLIST

## Pre-Production Go/No-Go

### Data Quality
- [ ] All 3 input topics flowing consistently
- [ ] No NaN/Infinity in any output
- [ ] All OHLCV constraints validated
- [ ] Timestamps align across all topics

### Pipeline Integrity
- [ ] All 10 processors in RUNNING state
- [ ] End-to-end latency < 60 seconds
- [ ] 80%+ symbols make it to final output
- [ ] State stores persist across restarts

### Critical Bugs Fixed
- [ ] currentPrice != 0 in MTVCPOutput (**BLOCKER**)
- [ ] confidence <= 1.0 in all signals
- [ ] Unbounded caches replaced with TTLCache
- [ ] Topic naming mismatches resolved

### Trade Execution Readiness
- [ ] Trade params (stopLoss, target) present in magnitude-final
- [ ] Position sizing fields non-zero
- [ ] Signal rationales meaningful and unique
- [ ] Risk-reward ratio > 1.5 for all signals

### Performance
- [ ] CPU usage < 70% during peak hours
- [ ] Memory stable (no leaks)
- [ ] State store size < 10 GB
- [ ] Throughput meets requirements

### Monitoring
- [ ] Metrics exposed (Prometheus/Micrometer)
- [ ] Dead letter queue configured
- [ ] Alerting setup for ERROR states
- [ ] Dashboard shows real-time pipeline health

---

## GO-LIVE DECISION

**PASS CRITERIA**: All critical bugs fixed + 90% of checks passing
**NO-GO CRITERIA**: Any blocker bug present OR < 70% checks passing

**SIGN-OFF REQUIRED FROM**:
- [ ] Quant Lead (algorithm correctness)
- [ ] DevOps (infrastructure readiness)
- [ ] Risk Manager (trade params validation)
- [ ] Tech Lead (code review + bug fixes)

---

**END OF BRUTAL VERIFICATION PROTOCOL**

*Last Updated: 2024-12-26*
*Document Version: 1.0*
*Total Checks: 150+*
