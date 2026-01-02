# Complete Pipeline Flow - Example Output

## RELIANCE: Complete Journey from Tick to Watchlist

This shows **EXACTLY** what you'll see when integrated logging is enabled:

```log
# ════════════════════════════════════════════════════════════════
# STAGE 1: INPUT DATA RECEIVED
# ════════════════════════════════════════════════════════════════
09:15:01.234 INFO [PipelineTrace] ┌─[INPUT-TICK]  09:15:01 | RELIANCE | Reliance Industries Ltd | price=2856.50 vol=1250
09:15:01.245 INFO [PipelineTrace] ┌─[INPUT-OB]    09:15:01 | RELIANCE | Reliance Industries Ltd | bid=2856.40 ask=2856.60 spread=0.20
09:15:01.256 INFO [PipelineTrace] ┌─[INPUT-OI]    09:15:01 | RELIANCE | Reliance Industries Ltd | OI=8450000

# ════════════════════════════════════════════════════════════════
# STAGE 2: 1-MINUTE CANDLE AGGREGATED
# ════════════════════════════════════════════════════════════════
09:16:00.123 INFO [PipelineTrace] ├─[CANDLE]      09:15:00 | RELIANCE | Reliance Industries Ltd | OHLC=2855.00/2858.00/2854.50/2856.50 vol=45600 OB=✓ OI=✓

# ════════════════════════════════════════════════════════════════
# STAGE 3: FAMILY CANDLE CREATED (Equity + Future + Options)
# ════════════════════════════════════════════════════════════════
09:16:00.234 INFO [PipelineTrace] ├─[FAMILY]      09:15:00 | RELIANCE | RELIANCE | EQ=✓ FUT=✓ OPT=4 | close=2856.50 vol=45600 | OI=LONG_BUILDUP bias=BULLISH

# ════════════════════════════════════════════════════════════════
# STAGE 4: REGIME CALCULATION (Index, Security, ACL)
# ════════════════════════════════════════════════════════════════
09:16:01.345 INFO [PipelineTrace] ├─[REGIME-IDX]  09:16:00 | 999920000 (NIFTY50) | label=STRONG_BULLISH strength=0.88 | flow=1 state=EXPANDING
09:16:01.456 INFO [PipelineTrace] ├─[REGIME-SEC]  09:16:00 | RELIANCE | label=STRONG_BULLISH score=0.82 | EMA=BULLISH_ALIGNED ATR=EXPANDING | IndexAlign=✓ mult=1.10
09:16:01.567 INFO [PipelineTrace] ├─[REGIME-ACL]  09:16:00 | RELIANCE | action=ALLOW mult=1.05 | Aligned with index, trending

# ════════════════════════════════════════════════════════════════
# STAGE 5: SIGNALS GENERATED (CSS, SOM, VTD)
# ════════════════════════════════════════════════════════════════
09:16:01.678 INFO [PipelineTrace] ├─[SIGNAL-CSS]  09:16:00 | RELIANCE | score=0.75 state=ACTIVE | cps=0.68
09:16:01.789 INFO [PipelineTrace] ├─[SIGNAL-SOM]  09:16:00 | RELIANCE | score=0.65 state=STABLE | penalty=0.00
09:16:01.890 INFO [PipelineTrace] ├─[SIGNAL-VTD]  09:16:00 | RELIANCE | score=0.90 state=NORMAL | penalty=0.00

# ════════════════════════════════════════════════════════════════
# STAGE 6: TRADE CLASSIFICATION
# ════════════════════════════════════════════════════════════════
09:16:02.001 INFO [PipelineTrace] ├─[TRADE-CLASS] 09:16:00 | RELIANCE | type=MOMENTUM dir=LONG conf=0.85 | reason=Strong volume, aligned regime

# ════════════════════════════════════════════════════════════════
# STAGE 7: MTIS CALCULATION (Multi-Timeframe Intelligence Score)
# ════════════════════════════════════════════════════════════════
09:16:02.112 INFO [PipelineTrace] ├─[MTIS]        09:16:00 | RELIANCE | score=68.5 TF=5m | price=10.2 fo=18.5 ipu=12.8 regime=14.2

# ════════════════════════════════════════════════════════════════
# STAGE 8: FINAL MAGNITUDE (The Ranking Score!)
# ════════════════════════════════════════════════════════════════
09:16:02.223 INFO [PipelineTrace] ├─[FINAL-MAG]   09:16:00 | RELIANCE | MAGNITUDE=0.825 rank=2 dir=BULLISH | base=0.72 acl=1.05 css=0.75 som=0.00 vtd=0.00

# ════════════════════════════════════════════════════════════════
# STAGE 9: FINAL OUTPUT TO WATCHLIST
# ════════════════════════════════════════════════════════════════
09:16:02.334 INFO [PipelineTrace] └─[FINAL-OUT-WATCHLIST] 09:16:00 | RELIANCE | → watchlist-ranked | magnitude=0.825 dir=BULLISH rank=2
```

---

## What You Can See:

### ✅ Input Stage
- Tick received at **09:15:01** with price **2856.50**
- Orderbook 11ms later, spread **0.20**
- OI 11ms after that, OI=**8,450,000**

### ✅ Candle Stage
- 1-minute candle closed at **09:16:00** (1 minute later)
- OHLC: **2855.00/2858.00/2854.50/2856.50**
- Volume: **45,600** (accumulated from all ticks in 1 min)
- Has **both OB and OI** data (✓✓)

### ✅ Family Stage
- Equity + Future + **4 options** found
- OI Signal: **LONG_BUILDUP** (price up + OI up = bullish)
- Directional Bias: **BULLISH**

### ✅ Regime Stage (Triple Check!)
1. **Index Regime** (NIFTY50): STRONG_BULLISH (0.88 strength)
2. **Security Regime** (RELIANCE): STRONG_BULLISH (0.82 score)
   - Aligned with index ✓ → 10% boost (mult=1.10)
   - EMA: BULLISH_ALIGNED
   - ATR: EXPANDING
3. **ACL**: ALLOW with 5% boost (mult=1.05)

### ✅ Signal Stage (Triple Filter!)
1. **CSS** (Composite Structure): 0.75 (good structure + VCP)
2. **SOM** (Sentiment Oscillation): 0.65 stable (no choppiness penalty)
3. **VTD** (Volatility Trap): 0.90 normal (no trap, no penalty)

### ✅ Trade Classification
- Type: **MOMENTUM** (not reversal, not breakout)
- Direction: **LONG**
- Confidence: **85%**
- Reason: Strong volume + aligned regime

### ✅ MTIS Score
- Overall: **68.5 points** (out of ~100)
- Breakdown:
  - Price Score: **10.2** (price action quality)
  - F&O Score: **18.5** (futures/options alignment)
  - IPU Score: **12.8** (institutional participation)
  - Regime Score: **14.2** (regime strength)

### ✅ Final Magnitude (THE RANKING!)
- **MAGNITUDE = 0.825** (very high, rank #2 in watchlist)
- Direction: **BULLISH**
- Formula: base × acl × css × (1-som) × (1-vtd)
  - Base Signal: **0.72** (IPU + VCP weighted average)
  - ACL Multiplier: **1.05** (5% boost for alignment)
  - CSS Score: **0.75** (structure quality)
  - SOM Penalty: **0.00** (no choppiness)
  - VTD Penalty: **0.00** (no trap)
  - Final: 0.72 × 1.05 × 0.75 × 1.0 × 1.0 = **0.825**

### ✅ Final Output
- Emitted to **watchlist-ranked** topic
- Rank: **#2** (second best opportunity)
- Direction: **BULLISH**

---

## Compare: Stock with Issues (TCS - Volatility Trap)

```log
09:15:01.345 INFO [PipelineTrace] ┌─[INPUT-TICK]  09:15:01 | TCS | Tata Consultancy Services | price=4125.80 vol=850
09:15:01.356 INFO [PipelineTrace] ┌─[INPUT-OB]    09:15:01 | TCS | Tata Consultancy Services | bid=4125.60 ask=4126.00 spread=0.40
09:16:00.234 INFO [PipelineTrace] ├─[CANDLE]      09:15:00 | TCS | Tata Consultancy Services | OHLC=4124.50/4126.80/4124.00/4125.80 vol=12450 OB=✓ OI=✗
09:16:00.345 INFO [PipelineTrace] ├─[FAMILY]      09:15:00 | TCS | TCS | EQ=✓ FUT=✓ OPT=8 | close=4125.80 vol=12450 | OI=SHORT_COVERING bias=BULLISH
09:16:01.456 INFO [PipelineTrace] ├─[REGIME-SEC]  09:16:00 | TCS | label=WEAK_BULLISH score=0.55 | EMA=MIXED_BULLISH ATR=COMPRESSED | IndexAlign=✗ mult=0.75
09:16:01.567 INFO [PipelineTrace] ├─[REGIME-ACL]  09:16:00 | TCS | action=WARN mult=0.90 | Diverging from index
09:16:01.678 INFO [PipelineTrace] ├─[SIGNAL-CSS]  09:16:00 | TCS | score=0.62 state=DEGRADED | cps=0.48
09:16:01.789 INFO [PipelineTrace] ├─[SIGNAL-SOM]  09:16:00 | TCS | score=0.45 state=CHOPPY | penalty=0.15
09:16:01.890 INFO [PipelineTrace] ├─[SIGNAL-VTD]  09:16:00 | TCS | score=0.25 state=COMPRESSION_TRAP | penalty=0.25
09:16:01.901 WARN [PipelineTrace] 🪤 [VTD] TCS | COMPRESSION_TRAP detected | IV=85.3% | ATR compressed | Avoid entries!
09:16:02.112 INFO [PipelineTrace] ├─[MTIS]        09:16:00 | TCS | score=42.3 TF=5m | price=6.5 fo=12.2 ipu=8.5 regime=7.8
09:16:02.223 INFO [PipelineTrace] ├─[FINAL-MAG]   09:16:00 | TCS | MAGNITUDE=0.285 rank=48 dir=BULLISH | base=0.52 acl=0.90 css=0.62 som=0.15 vtd=0.25
09:16:02.334 INFO [PipelineTrace] └─[FINAL-OUT-WATCHLIST] 09:16:00 | TCS | → watchlist-ranked | magnitude=0.285 dir=BULLISH rank=48
```

**Why TCS ranked so low (#48 vs RELIANCE #2)?**
- ❌ Missing OI data (OI=✗)
- ❌ Weak regime (0.55 vs 0.82)
- ❌ Not aligned with index (25% penalty: mult=0.75)
- ❌ ACL warning (10% penalty: mult=0.90)
- ❌ Choppy sentiment (15% SOM penalty)
- ❌ **Volatility trap detected!** (25% VTD penalty)
- ❌ Low MTIS (42.3 vs 68.5)
- ❌ Final magnitude: **0.285** vs RELIANCE **0.825**

**You immediately see why TCS is avoid!** 🚨

---

## Usage: Filter by Stock

```bash
# See complete RELIANCE flow
tail -f logs/streamingcandle.log | grep -E "PipelineTrace.*RELIANCE"

# See only final magnitudes (top stocks)
tail -f logs/streamingcandle.log | grep "FINAL-MAG"

# See all warnings (traps, choppiness, etc)
tail -f logs/streamingcandle.log | grep -E "(WARN|ERROR).*PipelineTrace"

# See stocks missing OB/OI
tail -f logs/streamingcandle.log | grep "OB=✗\|OI=✗"

# See regime calculations
tail -f logs/streamingcandle.log | grep "REGIME-"

# See complete flow for any stock
tail -f logs/streamingcandle.log | grep "PipelineTrace" | grep "TCS"
```

---

## Summary

### Before (Current):
```
DEBUG 📊 EQUITY MUTHOOTFIN OHLC=3876.9/3877.4/3874.8/3877.4 vol=2694 hasOB=false hasOI=false
```
❌ Can't see: where data came from, what happened next, why magnitude is low

### After (With Integrated Logging):
```
├─[INPUT-TICK]  ... | RELIANCE | price=2856.50 vol=1250
├─[CANDLE]      ... | RELIANCE | OHLC=... vol=45600 OB=✓ OI=✓
├─[FAMILY]      ... | RELIANCE | EQ=✓ FUT=✓ OPT=4 | OI=LONG_BUILDUP
├─[REGIME-IDX]  ... | NIFTY50 | STRONG_BULLISH strength=0.88
├─[REGIME-SEC]  ... | RELIANCE | STRONG_BULLISH score=0.82 IndexAlign=✓
├─[SIGNAL-CSS]  ... | RELIANCE | score=0.75
├─[FINAL-MAG]   ... | RELIANCE | MAGNITUDE=0.825 rank=2
└─[FINAL-OUT]   ... | RELIANCE | → watchlist-ranked
```
✅ Complete visibility: every stage, every score, every decision!

**You now have X-ray vision into your pipeline!** 🔍
