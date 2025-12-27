# 🎬 Playback Testing - READY TO GO!

## ✅ What's Been Prepared

### 1. Enhanced Logging ✅
**File**: `CuratedSignalProcessor.java`

**Added detailed logs at every decision point:**
- `🔍 BREAKOUT DETECTED` - Multi-TF breakout with metrics
- `🚫 GATE_X_FAILED` - Specific gate failure reasons
- `✅ ALL_GATES_PASSED` - Gate success with regime details
- `✅ BREAKOUT ACCEPTED` - Accepted breakouts waiting for retest
- `✅ RETEST ENTRY CONFIRMED` - Retest detection with entry details
- `📤 ENHANCED CURATED SIGNAL EMITTED` - Final signal emission
- `⛔ BREAKOUT REJECTED` - Rejection reasons
- `⛔ Signal rejected` - Low score rejections

**Log Format Example:**
```
🔍 BREAKOUT DETECTED | scrip=RELIANCE | TF_confirmations=3/3 | confluence=1.00 | volZ=3.5 | kyle=0.15
✅ ALL_GATES_PASSED | scrip=RELIANCE | indexRegime=STRONG_BULL | secRegime=BULL | aclState=ENTRY_ALLOWED
✅ RETEST ENTRY CONFIRMED: RELIANCE @ 2448.50 | Stop=2425.00 | Target=2490.00 | R:R=2.80
📤 ENHANCED CURATED SIGNAL EMITTED: RELIANCE | Score=87.5 | Entry=2448.50 | Stop=2425.00 | Target=2490.00 | R:R=2.80
```

### 2. Configurable Consumer Groups ✅
**File**: `CuratedKafkaConfig.java`

**Changes:**
- ✅ Consumer group ID is now configurable: `curated.consumer.group-id`
- ✅ Auto-offset-reset is configurable: `curated.consumer.auto-offset-reset`
- ✅ **Default is now `earliest`** (reads from beginning)

### 3. Playback Configuration ✅
**File**: `src/main/resources/application-playback.yml`

**Features:**
- ✅ Both OLD and NEW systems enabled
- ✅ All consumers read from `earliest`
- ✅ Unique consumer groups per run (using UUID)
- ✅ F&O and Levels **disabled** (no historical data)
- ✅ Scoring adjusted for playback (min score: 55 instead of 60)
- ✅ Enhanced logging to `logs/playback-comparison.log`
- ✅ Separate output topics for comparison

### 4. Setup Script ✅
**File**: `scripts/setup_playback.sh`

**Does:**
- ✅ Resets all consumer groups
- ✅ Creates required Kafka topics
- ✅ Clears old logs
- ✅ Provides step-by-step instructions

### 5. Comparison Script ✅
**File**: `scripts/compare_signals.py`

**Analyzes:**
- ✅ OLD system signals (count, confidence, distribution)
- ✅ NEW system signals (count, scores, R:R ratios)
- ✅ Breakout funnel (detected → gates → retest → emitted)
- ✅ Gate failure breakdown
- ✅ Side-by-side comparison
- ✅ Success criteria evaluation

### 6. Testing Guide ✅
**File**: `PLAYBACK_TESTING_GUIDE.md`

**Includes:**
- ✅ Pre-playback checklist
- ✅ Step-by-step execution guide
- ✅ What to monitor during playback
- ✅ Expected results
- ✅ Troubleshooting guide
- ✅ Success criteria

---

## 🚀 Quick Start Guide

### Step 1: Prepare Environment

```bash
# Navigate to project directory
cd /Users/shivendrapratap/Downloads/kotsinfix/streamingcandle

# Run setup script
./scripts/setup_playback.sh
```

This will:
- Reset all consumer groups
- Create required topics
- Clear old logs

### Step 2: Start Kafka

```bash
# If not already running
cd ~/kafka  # Your Kafka directory
bin/kafka-server-start.sh config/server.properties
```

### Step 3: Start Signal Monitors

**Terminal 1 - Monitor OLD signals:**
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic trading-signals \
  --from-beginning \
  --property print.timestamp=true | tee logs/old-signals.log
```

**Terminal 2 - Monitor NEW signals:**
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic trading-signals-curated \
  --from-beginning \
  --property print.timestamp=true | tee logs/new-signals.log
```

### Step 4: Start Application

```bash
# Build (if needed)
mvn clean package -DskipTests

# Run in playback mode
java -jar target/streamingcandle-*.jar --spring.profiles.active=playback
```

### Step 5: Monitor Application Logs

**Terminal 3 - Watch logs:**
```bash
tail -f logs/playback-comparison.log
```

Look for:
- `🔍 BREAKOUT DETECTED` - Breakouts being found
- `🚫 GATE_X_FAILED` - Why signals are rejected
- `✅ RETEST ENTRY CONFIRMED` - Successful retests
- `📤 ENHANCED CURATED SIGNAL EMITTED` - Final signals
- `🎯 TRADING SIGNAL` - OLD system signals

### Step 6: Replay Historical Data

If your forward-testing data is in a topic, it should automatically start processing.

**If you need to manually replay:**
```bash
# Example: Copy data from forward-testing-data to candle topics
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic forward-testing-data \
  --from-beginning | \
kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic candle-unified-1m
```

### Step 7: Run Comparison Analysis

**After playback completes:**
```bash
python3 scripts/compare_signals.py logs/playback-comparison.log
```

**Output will show:**
- Total signals from each system
- Signal quality metrics
- Volume reduction percentage
- Success criteria evaluation
- Gate failure breakdown

---

## 📊 What to Expect

### OLD System (Expected):
```
📊 Total Signals: 150-300 (per day)
🎯 Signal Distribution:
   BUY             120 (80%)
   SELL            30  (20%)
📈 Confidence Scores:
   Average:   0.65
   Min:       0.50
   Max:       0.95
```

### NEW System (Expected):
```
📊 Total Curated Signals: 3-6 (per day)
📈 Curated Scores:
   Average:         72.5
   Min:             60.0
   Max:             88.0
   High (>=80):     2 (40%)
💰 Risk:Reward Ratios:
   Average:   2.5
   Min:       2.0
   Max:       4.2
   Good (>=2.0): 6 (100%)
```

### Comparison (Expected):
```
📊 Signal Volume:
   OLD:       250 signals
   NEW:       5 signals
   Reduction: 98.0%

✅ Success Criteria Evaluation:
   ✅ Average score >= 70 (actual: 72.5)
   ✅ >50% high quality signals (40%)
   ✅ Average R:R >= 2.0 (2.5)
   ✅ Volume reduction 90-98% (98.0%)
```

---

## 🔍 Key Logs to Watch

### Breakout Detection
```
🔍 BREAKOUT DETECTED | scrip=RELIANCE | TF_confirmations=3/3 | confluence=1.00 | volZ=3.5 | kyle=0.15
```
**Meaning**: Multi-timeframe breakout detected with strong volume and liquidity shock.

### Gate Failures
```
🚫 GATE_1_FAILED | scrip=RELIANCE | gate=INDEX_REGIME | reason=Not_tradeable | regime=CHOPPY | strength=0.35
```
**Meaning**: Breakout rejected because index regime is not tradeable.

```
🚫 GATE_3_FAILED | scrip=TATAMOTORS | gate=SECURITY_REGIME | reason=Not_aligned_with_index | secRegime=BEAR | idxRegime=BULL
```
**Meaning**: Stock regime doesn't match index regime (bearish stock in bullish market).

### Successful Signals
```
✅ ALL_GATES_PASSED | scrip=RELIANCE | indexRegime=STRONG_BULL | secRegime=BULL | aclState=ENTRY_ALLOWED
✅ RETEST ENTRY CONFIRMED: RELIANCE @ 2448.50 | Stop=2425.00 | Target=2490.00 | R:R=2.80
📤 ENHANCED CURATED SIGNAL EMITTED: RELIANCE | Score=87.5 | Entry=2448.50 | Stop=2425.00 | Target=2490.00 | R:R=2.80
```
**Meaning**: Complete successful signal from breakout → gates → retest → emission.

---

## 🐛 Troubleshooting

### No signals from NEW system?

**Check breakout detection:**
```bash
grep "BREAKOUT DETECTED" logs/playback-comparison.log | wc -l
```
If 0: Structure tracking or breakout detection issue.

**Check gate failures:**
```bash
grep "GATE.*FAILED" logs/playback-comparison.log | head -20
```
Shows which gates are rejecting signals.

**Check retests:**
```bash
grep "RETEST ENTRY CONFIRMED" logs/playback-comparison.log | wc -l
```
If breakouts detected but no retests: Retest detection issue.

### No signals from OLD system?

**Check if VCP and IPU topics have data:**
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic vcp-combined --max-messages 10
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic ipu-signals-5m --max-messages 10
```

### Too many gate failures?

**Common causes:**
- Index regime not tradeable (check market conditions in historical data)
- Session phase = OPENING/CLOSING (check timing)
- Security regime not aligned (bearish stocks in bullish market)
- ACL not allowing entry (already in position or trend too old)

**Solution:** Check which gate is failing most:
```bash
grep "GATE_._FAILED" logs/playback-comparison.log | cut -d'|' -f3 | sort | uniq -c | sort -rn
```

---

## ✅ Success Checklist

After playback, verify:

- [ ] OLD system emitted signals (check `logs/old-signals.log`)
- [ ] NEW system emitted signals (check `logs/new-signals.log`)
- [ ] Application log has both system outputs (`logs/playback-comparison.log`)
- [ ] Comparison script ran successfully
- [ ] Signal volume reduced by 90-98%
- [ ] Average curated score >= 70
- [ ] Average R:R >= 2.0
- [ ] Can trace signal flow from breakout → emit
- [ ] Understand why signals were rejected

---

## 📝 Files Created/Modified

### New Files:
- ✅ `application-playback.yml` - Playback configuration
- ✅ `scripts/setup_playback.sh` - Setup automation
- ✅ `scripts/compare_signals.py` - Signal comparison tool
- ✅ `PLAYBACK_TESTING_GUIDE.md` - Detailed testing guide
- ✅ `PLAYBACK_READY.md` - This file

### Modified Files:
- ✅ `CuratedKafkaConfig.java` - Configurable consumer groups
- ✅ `CuratedSignalProcessor.java` - Enhanced logging

---

## 🎯 Next Steps

1. **Run playback** using the steps above
2. **Analyze results** using comparison script
3. **Review logs** to understand signal flow
4. **Tune parameters** if needed (gate thresholds, min score)
5. **Re-run playback** with adjustments
6. **Deploy to production** when satisfied

---

**You're all set!** 🚀

Everything is configured for playback testing. Just run:
```bash
./scripts/setup_playback.sh
```

Then start the application with `--spring.profiles.active=playback` and watch the comparison unfold!
