🎯 COMPLETE SYSTEM DESIGN (Plain English)
THE BUSINESS PROBLEM
You're a trader watching the Indian stock market (NSE/MCX). You want to know:

What's happening right now? (Price, volume, orderbook)
Is this normal or special? (Should I pay attention?)
What should I do? (Buy, sell, or wait?)

You get 3 data feeds:

Trades - Every time someone buys/sells
Orderbook - Who wants to buy/sell (pending orders)
Open Interest - How many contracts are open (derivatives only)

You want this data aggregated into timeframes (1min, 2min, 5min, 15min, 30min) with smart signals telling you when something important is happening.

THE DATA FEEDS (What You're Getting)
Feed 1: Trade Ticks
Every time a trade happens, you get:
- Price: ₹10.50
- Quantity: 1400 contracts
- Time: 12:02:35.234
- Bid/Ask at that moment

This comes VERY fast (10-100 times per second for liquid stocks)
```

### Feed 2: Orderbook Updates
```
Every time someone places/cancels an order:
- Bids: [Price ₹10.15 → 1600 qty, Price ₹10.10 → 1400 qty, ...]
- Asks: [Price ₹10.55 → 1400 qty, Price ₹10.60 → 1600 qty, ...]
- 20 levels deep on each side

This also comes very fast (5-50 times per second)
```

### Feed 3: Open Interest Updates
```
Every few seconds (derivatives only):
- OpenInterest: 59,200 contracts

This comes slow (every 5-10 seconds)
```

---

## WINDOW ALIGNMENT (NSE Trading Hours)

### NSE Opens: 9:15 AM IST

This is **CRITICAL** - all windows must align to market open, not clock hours.

### Wrong Way (Clock-Based):
```
9:00 - 9:30 (30min window)  ❌ Market not even open!
9:30 - 10:00 (30min window)  ❌ Crosses pre-market/regular session
```

### Right Way (Market-Based):
```
Session start: 9:15 AM IST

1-minute windows:
9:15:00 - 9:16:00
9:16:00 - 9:17:00
9:17:00 - 9:18:00
...

2-minute windows:
9:15:00 - 9:17:00
9:17:00 - 9:19:00
9:19:00 - 9:21:00
...

5-minute windows:
9:15:00 - 9:20:00
9:20:00 - 9:25:00
9:25:00 - 9:30:00
...

15-minute windows:
9:15:00 - 9:30:00
9:30:00 - 9:45:00
9:45:00 - 10:00:00
...

30-minute windows:
9:15:00 - 9:45:00  ✅ Correct!
9:45:00 - 10:15:00
10:15:00 - 10:45:00
...
```

### How We Do This in Kafka Streams:

**Problem:** Kafka's `TimeWindows` uses UTC/epoch time, doesn't know about "9:15 AM IST market open"

**Solution:** Custom timestamp extractor that shifts timestamps to align with market open
```
Actual timestamp: 9:17:35 (2 min 35 sec after market open)
Shifted timestamp: Align to "2 minutes elapsed since 9:15"

For 1-min window: Put in window [2-3 minutes]
For 5-min window: Put in window [0-5 minutes]
For 30-min window: Put in window [0-30 minutes]
```

---

## THE ARCHITECTURE (How It Works)

### Step 1: Merge All 3 Feeds Into One Stream
```
Input Topic 1: "forwardtesting-data" (trades)
Input Topic 2: "Orderbook" (depth)
Input Topic 3: "OpenInterest" (OI)

→ Rekey all by token (96955)
→ Merge into single stream
→ Tag each event with type (TRADE, ORDERBOOK, OI)

Result: One stream with all events for each stock
```

---

### Step 2: Window by Timeframe
```
Take merged stream
→ Group by token
→ Window by 1 minute (market-aligned)
→ Accumulate state for 1 minute
→ At window close, emit enriched candle
```

We do this **separately for each timeframe**:
- Topology 1: 1-minute candles
- Topology 2: 2-minute candles
- Topology 3: 5-minute candles
- Topology 4: 15-minute candles
- Topology 5: 30-minute candles

---

### Step 3: Accumulate State During Window

For each window (e.g., 9:17:00 to 9:18:00), we keep a "state object" in memory that tracks:
```
CandleState {
// Basic OHLC
open, high, low, close

// Volume breakdown
totalVolume, buyVolume, sellVolume

// Latest orderbook (20 levels each side)
bidLevels[20], askLevels[20]

// Orderbook history (last 60 snapshots for calculations)
orderbookHistory[60]

// Imbalance bar accumulators
volumeImbalanceCumulative
dollarImbalanceCumulative
tickRunsCount
volumeRunsCumulative

// Microstructure accumulators
ofiSum, vpinBars, kyleLambdaPoints

// Volume profile
volumeAtPrice[priceLevel → volume]

// Tick counters
tickCount, upticks, downticks

// OI tracking
oiOpen, oiHigh, oiLow, oiClose
}
```

Every time an event arrives, we update this state:

---

#### When Trade Arrives:
```
1. Update OHLC:
    - If first tick: open = price
    - high = max(high, price)
    - low = min(low, price)
    - close = price (always latest)

2. Classify direction:
    - If price > previousPrice → UPTICK (buy)
    - If price < previousPrice → DOWNTICK (sell)
    - If price == previousPrice → Look at bid/ask proximity

3. Update volumes:
    - totalVolume += quantity
    - If uptick: buyVolume += quantity
    - If downtick: sellVolume += quantity

4. Update VWAP:
    - vwapNumerator += (price × quantity)
    - vwapDenominator += quantity

5. Update volume profile:
    - volumeAtPrice[price] += quantity

6. Update imbalance bars:
    - volumeImbalanceCumulative += (quantity × direction)
    - dollarImbalanceCumulative += (quantity × price × direction)
    - If same direction as last tick: tickRunsCount++
    - If same direction: volumeRunsCumulative += quantity

7. Update microstructure:
    - Add to VPIN calculation
    - Add point to Kyle's Lambda regression

8. Increment counters:
    - tickCount++
    - If uptick: upticks++
    - If downtick: downticks++
```

---

#### When Orderbook Update Arrives:
```
1. Store full 20-level snapshot:
    - bidLevels = [all 20 bid levels]
    - askLevels = [all 20 ask levels]

2. Extract top of book:
    - bestBid = bidLevels[0].price
    - bestAsk = askLevels[0].price
    - midPrice = (bestBid + bestAsk) / 2

3. Calculate totals:
    - totalBidQty = sum of all 20 bid quantities
    - totalAskQty = sum of all 20 ask quantities

4. Add to history (ring buffer):
    - If orderbookHistory.size >= 60: remove oldest
    - orderbookHistory.add(snapshot)

5. Calculate OFI (Order Flow Imbalance):
    - Compare current bid/ask to previous
    - If bid moved up or stayed: ofi += currentBidQty
    - If bid moved down: ofi -= previousBidQty
    - If ask moved down or stayed: ofi -= currentAskQty
    - If ask moved up: ofi += previousAskQty

6. Detect icebergs:
    - For each level, check if quantity stays same despite trades
    - If yes, mark as iceberg

7. Detect spoofing:
    - Check if large orders placed then quickly cancelled
```

---

#### When Open Interest Update Arrives:
```
1. Update OI OHLC:
    - If first update: oiOpen = openInterest
    - oiHigh = max(oiHigh, openInterest)
    - oiLow = min(oiLow, openInterest)
    - oiClose = openInterest (always latest)

2. Count updates:
    - oiUpdateCount++
```

---

### Step 4: When Window Closes (Calculate Final Metrics)

After 1 minute (e.g., at 9:18:00), the window closes. Now we:
```
1. Calculate basic metrics:
    - VWAP = vwapNumerator / vwapDenominator
    - volumeDelta = buyVolume - sellVolume
    - spread = bestAsk - bestBid
    - oiChange = oiClose - oiOpen

2. Calculate orderbook aggregates:
    - depthRatio = totalBidQty / totalAskQty
    - depthImbalance = (totalBidQty - totalAskQty) / (totalBidQty + totalAskQty)
    - bidSlope = how fast bid depth thins out (regression)
    - askSlope = how fast ask depth thins out (regression)
    - Detect icebergs (final pass)
    - Detect spoofing events

3. Calculate imbalance bar metrics:
    - For each bar type (volume, dollar, tick, volumeRuns):
        * Calculate progress = cumulative / threshold
        * Calculate acceleration = current_rate / historical_avg_rate
        * Calculate exhaustion = activity_in_last_quarter / activity_in_first_quarter
        * Calculate percentile = rank vs last 100 windows
        * Detect momentum shifts (did direction flip mid-window?)

    - Check for divergence:
        * Do volume bars and tick bars disagree? (institutional vs retail)

4. Calculate microstructure:
    - ofiZScore = (current ofi - mean) / stdDev
    - VPIN = measure of informed trading
    - Kyle's Lambda = price impact per unit volume

5. Calculate volume profile:
    - POC = price with most volume
    - Value Area = range containing 70% of volume

6. Calculate importance score:
    - Component 1 (40%): Average progress of all 4 imbalance bars
    - Component 2 (30%): Alignment (all bars same direction?)
    - Component 3 (20%): OFI extremeness (Z-score)
    - Component 4 (10%): Depth imbalance extremeness
    - Final score = 0.0 to 1.0

7. Determine trading signal:
    - If importanceScore > 0.7 AND alignment > 0.75: Strong signal
    - Determine direction from dominant imbalance
    - Set confidence level

8. Build output JSON with all metrics

9. Emit to output topic
```

---

### Step 5: Reset for Next Window
```
1. Reset accumulators:
    - volumeImbalanceCumulative = 0
    - dollarImbalanceCumulative = 0
    - tickRunsCount = 0
    - volumeRunsCumulative = 0
    - ofiSum = 0
    - volumeAtPrice.clear()

2. Keep persistent state:
    - EWMA thresholds (they adapt across windows)
    - Orderbook history (keep last 60 snapshots)
    - previousPrice (for tick classification)
    - Iceberg trackers (persist across windows)

3. Ready for next window
```

---

## IMBALANCE BAR CALCULATIONS (Detailed)

### 1. Basic Accumulation (Happens on Every Tick)
```
Volume Imbalance:
- If uptick: volumeImbalance += quantity
- If downtick: volumeImbalance -= quantity
- Example: +100, +200, -150, +300 = +450 (net buying)

Dollar Imbalance:
- If uptick: dollarImbalance += (quantity × price)
- If downtick: dollarImbalance -= (quantity × price)
- Example: +(100×10.5), +(200×10.6), -(150×10.4) = net dollar buying

Tick Runs:
- Count consecutive ticks in same direction
- If direction flips, reset counter
- Example: UP, UP, UP, DOWN → run of 3, then new run starts

Volume Runs:
- Sum volume while direction stays same
- If direction flips, reset
- Example: 100+200+150=450, then direction flips, reset
```

---

### 2. Threshold Calculation (Adaptive)
```
Initial threshold (first window of the day):
- volumeImbalanceThreshold = 50 contracts × timeframe_minutes
- For 1min: 50 contracts
- For 5min: 250 contracts
- For 30min: 1500 contracts

After first window completes:
- Calculate EWMA (Exponential Weighted Moving Average)
- Alpha = 0.1 (smoothing factor)
- New threshold = (alpha × actual_imbalance) + ((1-alpha) × old_threshold)

This makes threshold ADAPTIVE:
- In volatile periods → threshold increases (needs more imbalance to complete)
- In quiet periods → threshold decreases (less imbalance needed)
```

**Example:**
```
Window 1: actual imbalance = 60, threshold = 50 → BAR COMPLETES
New threshold = 0.1×60 + 0.9×50 = 51

Window 2: actual imbalance = 45, threshold = 51 → bar doesn't complete
(threshold stays 51)

Window 3: actual imbalance = 80, threshold = 51 → BAR COMPLETES
New threshold = 0.1×80 + 0.9×51 = 53.9

See how threshold adapts? Market getting more active, so threshold rises.
```

---

### 3. Progress Calculation
```
progress = |cumulative| / threshold

Example:
- cumulative = 42
- threshold = 50
- progress = 42/50 = 0.84 (84% complete)
```

---

### 4. Acceleration Calculation (NEW - This is the Alpha)
```
Measure: How fast is imbalance building?

Step 1: Calculate current rate
- elapsed_seconds = (current_time - window_start) / 1000
- current_rate = cumulative / elapsed_seconds

Step 2: Compare to historical average
- Keep rolling buffer of last 20 rates
- historical_avg_rate = EWMA(last_20_rates, span=20)

Step 3: Calculate acceleration
- acceleration = current_rate / historical_avg_rate

Interpretation:
- acceleration = 1.0 → Normal pace
- acceleration = 2.0 → 2x faster than usual (institutional flow!)
- acceleration = 0.5 → Half speed (quiet)
```

**Example:**
```
Current window:
- 45 seconds elapsed
- cumulative = 35 contracts
- current_rate = 35/45 = 0.78 contracts/second

Historical average (last 20 windows):
- avg_rate = 0.35 contracts/second

Acceleration = 0.78 / 0.35 = 2.23

This is 2.23x faster than normal!
→ Something unusual happening
→ Probably institutional flow
→ PAY ATTENTION
```

---

### 5. Exhaustion Calculation (NEW - Catches Reversals)
```
Measure: Is the buying/selling pressure fading?

Step 1: Split window into 4 quarters
- Q1: first 25% of time (0-15 seconds for 1min)
- Q2: next 25% (15-30 seconds)
- Q3: next 25% (30-45 seconds)
- Q4: last 25% (45-60 seconds)

Step 2: Track contribution per quarter
- Q1_contribution = imbalance accumulated in Q1
- Q2_contribution = imbalance accumulated in Q2
- Q3_contribution = imbalance accumulated in Q3
- Q4_contribution = imbalance accumulated in Q4

Step 3: Calculate momentum
- momentum = Q4_contribution / Q1_contribution

Step 4: Calculate exhaustion score
- exhaustion = 1 - momentum
- Clamp to [0, 1]

Interpretation:
- momentum > 1.0 → Accelerating (Q4 > Q1)
- momentum = 1.0 → Steady
- momentum < 1.0 → Decelerating (Q4 < Q1)
- momentum < 0.5 → Exhausted! (Q4 less than half of Q1)
```

**Example:**
```
Q1: 12 contracts (first 15 seconds)
Q2: 10 contracts
Q3: 8 contracts
Q4: 4 contracts (last 15 seconds)

momentum = 4 / 12 = 0.33
exhaustion = 1 - 0.33 = 0.67 (67% exhausted)

Interpretation:
- Started strong (12 contracts in Q1)
- Fading badly (only 4 in Q4)
- Buying pressure EXHAUSTED
- Expect reversal or consolidation
```

---

### 6. Percentile Ranking (NEW - Context)
```
Measure: Is this activity level normal or rare?

Step 1: Keep rolling buffer of last 100 windows
- Store cumulative imbalance from each window

Step 2: Rank current window
- Sort buffer
- Find position of current cumulative
- percentile = position / 100

Interpretation:
- percentile = 0.50 → Median (normal)
- percentile = 0.90 → Top 10% (very active)
- percentile = 0.10 → Bottom 10% (very quiet)
```

**Example:**
```
Last 100 windows had these imbalances:
[10, 15, 18, 20, 22, 25, ..., 80, 95, 120]

Current window: 88

Sorting: 88 is the 92nd highest value
percentile = 92 / 100 = 0.92 (92nd percentile)

Interpretation:
- Only 8 out of 100 windows had MORE activity
- This is RARE
- Something unusual happening
- High importance score
```

---

### 7. Divergence Detection (NEW - Institutional vs Retail)
```
Measure: Do different bar types disagree?

Volume bars track LARGE orders (institutions)
Tick bars track NUMBER of orders (retail)

Example divergence:
- volumeImbalance = +500 (large buying)
- tickRuns = -8 (consecutive selling ticks)

Interpretation:
- Institutions: BUYING (large orders)
- Retail: SELLING (many small orders)
- Divergence detected!
- Retail always wrong → Bullish signal
```

**How we detect:**
```
1. Check direction of each bar type:
    - volumeImbalance: BUY or SELL
    - dollarImbalance: BUY or SELL
    - tickRuns: BUY or SELL
    - volumeRuns: BUY or SELL

2. Count agreements:
    - buyCount = how many say BUY
    - sellCount = how many say SELL

3. Determine divergence:
    - If all 4 agree (4-0): NO divergence, STRONG signal
    - If 3-1 split: Minor divergence
    - If 2-2 split: MAJOR divergence
        * If volume+dollar say BUY, tick+volumeRuns say SELL:
          → Institutions buying, retail selling
          → BULLISH divergence

4. Set confidence:
    - All aligned (4-0): confidence = 0.95
    - Minor divergence (3-1): confidence = 0.75
    - Major divergence (2-2): confidence = 0.60
        * But if it's institutional vs retail: confidence = 0.85 (trust institutions)
```

---

### 8. Importance Score Calculation (Final Output)
```
Combines 4 components:

Component 1 (40% weight): Average Progress
- avgProgress = (vol_progress + dollar_progress + tick_progress + volrun_progress) / 4
- If bars near completion (>0.8): High importance

Component 2 (30% weight): Alignment
- How many bars agree on direction?
- alignment = max(buyCount, sellCount) / 4
- If all agree: alignment = 1.0 (very important)

Component 3 (20% weight): OFI Z-Score
- How extreme is order flow imbalance?
- ofiZScore = (current_ofi - mean) / stdDev
- If |zScore| > 2: Very unusual (important)

Component 4 (10% weight): Depth Imbalance
- How lopsided is orderbook?
- depthImbalance = (bidQty - askQty) / (bidQty + askQty)
- If |imbalance| > 0.3: Significant pressure

Final Score:
importanceScore = 0.4×avgProgress + 0.3×alignment + 0.2×(ofiZScore/3) + 0.1×(depthImbalance/0.5)
```

**Example:**
```
avgProgress = 0.85 (all bars ~85% complete)
alignment = 1.0 (all 4 bars say BUY)
ofiZScore = 2.5 (order flow very bullish)
depthImbalance = 0.25 (more bids than asks)

Score = 0.4×0.85 + 0.3×1.0 + 0.2×(2.5/3) + 0.1×(0.25/0.5)
= 0.34 + 0.30 + 0.17 + 0.05
= 0.86

importanceScore = 0.86 (HIGH)
importanceLevel = "HIGH"
signal = "STRONG_BUY"
```

---

## ORDERBOOK CALCULATIONS (Detailed)

### 1. Depth Imbalance
```
Purpose: Who has more firepower - buyers or sellers?

Calculation:
totalBidQty = sum of all 20 bid levels
totalAskQty = sum of all 20 ask levels

depthImbalance = (totalBidQty - totalAskQty) / (totalBidQty + totalAskQty)

Result range: -1.0 to +1.0
- +1.0 = Only bids, no asks (extreme bullish)
- 0.0 = Equal bid/ask (neutral)
- -1.0 = Only asks, no bids (extreme bearish)

Interpretation:
- > +0.3 = Bid heavy (bullish)
- -0.3 to +0.3 = Balanced
- < -0.3 = Ask heavy (bearish)
```

**Example:**
```
Bids:
L1: 1600 @ 10.15
L2: 1400 @ 10.10
L3: 1200 @ 10.05
... (17 more levels)
Total: 7500 contracts

Asks:
L1: 1400 @ 10.55
L2: 1600 @ 10.60
L3: 1200 @ 10.65
... (17 more levels)
Total: 6000 contracts

depthImbalance = (7500 - 6000) / (7500 + 6000)
= 1500 / 13500
= 0.111 (11.1% bid heavy)

Signal: BID_HEAVY (more buyers than sellers)
```

---

### 2. Depth Slope
```
Purpose: How fast does liquidity disappear as you move away from top?

Calculation (linear regression):
For bids:
- X = [level 1, level 2, ..., level 20]
- Y = [qty1, qty2, ..., qty20]
- Fit line: Y = slope × X + intercept
- bidSlope = slope

For asks:
- Same process
- askSlope = slope

Interpretation:
- Steep negative slope = thin book (liquidity disappears fast)
- Gentle slope = deep book (lots of liquidity at all levels)

slopeRatio = bidSlope / askSlope
- > 1.0 = Bids deeper (harder to push down)
- < 1.0 = Asks deeper (easier to push up)
```

**Example:**
```
Bids:
L1: 1600
L2: 1550
L3: 1500
L4: 1450
... gradually decreasing
L20: 800

Regression: slope = -40 (decreases 40 contracts per level)
bidSlope = -40

Asks:
L1: 1400
L2: 1200
L3: 900
L4: 700
... steeply decreasing
L20: 200

Regression: slope = -63
askSlope = -63

slopeRatio = -40 / -63 = 0.63

Interpretation:
- Bids decrease slowly (deep book)
- Asks decrease fast (thin book)
- Easier to push price UP (thin asks)
- Signal: Bullish bias
```

---

### 3. Iceberg Detection
```
Purpose: Detect hidden orders (large orders showing only small portion)

How icebergs work:
- Trader wants to buy 10,000 contracts at ₹10.10
- But doesn't want to show entire order (would scare sellers away)
- Shows only 500 contracts at a time
- As 500 gets filled, automatically replenishes to 500 again

Detection logic:
1. Track each price level over time
2. For each level, record:
    - Timestamp
    - Quantity
    - Number of orders

3. Look for pattern:
    - Quantity stays constant (around 500) despite trades
    - Number of orders stays low (1-2 orders)
    - Volume traded at this level EXCEEDS quantity shown
    - When quantity drops, it "magically" refills to 500 within 1 second

4. Calculate probability:
    - observationCount = how many times we saw this pattern
    - icebergProbability = observationCount / totalObservations
    - If > 0.6: Likely iceberg
```

**Example:**
```
Time    Bid @ 10.10    Orders    Trades Seen
12:02:05   500           1          0
12:02:08   500           1          200 (but still shows 500!) ← Suspicious
12:02:12   500           1          150 (still 500!)
12:02:18   480           1          0
12:02:19   500           1          0 (refilled instantly!) ← Iceberg!

Conclusion: Iceberg detected at ₹10.10
- Someone has large hidden order here
- This is STRONG SUPPORT
- Price likely bounces here
```

---

### 4. Spoofing Detection
```
Purpose: Detect manipulation (fake orders to trick others)

How spoofing works:
- Trader wants to BUY at ₹10.15
- Places HUGE fake SELL order at ₹10.20 (5000 contracts)
- Other traders see large sell pressure, panic
- Price drops to ₹10.15
- Trader cancels fake sell order, buys at ₹10.15

Detection logic:
1. Monitor order placements/cancellations
2. Look for:
    - Very large orders (>3x average)
    - Placed far from top of book (2-3 levels away)
    - Cancelled quickly (<2 seconds)
    - No actual fills (zero execution)

3. Track suspicious events:
    - timestamp
    - side (BID or ASK)
    - price level
    - quantity
    - duration before cancellation

4. Count events per window
```

**Example:**
```
12:02:05: Large SELL order 5000 @ ₹10.70 (3 levels away) appears
12:02:06: Price drops from ₹10.50 to ₹10.45
12:02:07: Order at ₹10.70 CANCELLED (only existed 2 seconds)
12:02:08: Large BUY order 2000 @ ₹10.45 executes

Conclusion: Spoofing detected
- Fake sell order pushed price down
- Real buy order executed at lower price
- Mark as suspicious activity
- Be cautious trading this stock
```

---

## MICROSTRUCTURE CALCULATIONS (Detailed)

### 1. OFI (Order Flow Imbalance)
```
Purpose: Detect institutional buying/selling BEFORE it shows in price

Theory:
- When institutions buy, they lift asks (aggressive buying)
- When institutions sell, they hit bids (aggressive selling)
- OFI measures net aggression

Calculation (on each orderbook update):
currentBidQty = quantity at best bid now
previousBidQty = quantity at best bid before

currentAskQty = quantity at best ask now
previousAskQty = quantity at best ask before

Bid side contribution:
- If bestBid moved UP or stayed same: ofi += currentBidQty
- If bestBid moved DOWN or stayed same: ofi -= previousBidQty

Ask side contribution:
- If bestAsk moved DOWN or stayed same: ofi -= currentAskQty
- If bestAsk moved UP or stayed same: ofi += previousAskQty

Cumulative OFI = sum across all orderbook updates in window
```

**Example:**
```
Update 1:
Previous: Bid 10.15 (1600 qty), Ask 10.55 (1400 qty)
Current:  Bid 10.20 (1800 qty), Ask 10.55 (1200 qty)

OFI calculation:
- Bid moved UP (10.15 → 10.20): ofi += 1800
- Ask stayed same (10.55): ofi -= 1200
- Net OFI = +600 (buying pressure)

Update 2:
Previous: Bid 10.20 (1800), Ask 10.55 (1200)
Current:  Bid 10.20 (2000), Ask 10.50 (1000)

OFI calculation:
- Bid stayed same: ofi += 2000
- Ask moved DOWN (10.55 → 10.50): ofi -= 1000
- Net OFI = +1000 (more buying pressure)

Total cumulative OFI = +600 + +1000 = +1600

Interpretation:
- Positive OFI = Institutional buying
- Price likely to go UP soon
```

---

### 2. VPIN (Volume-Synchronized Probability of Informed Trading)
```
Purpose: Measure "toxicity" - are informed traders active?

Theory:
- Some traders have inside information (earnings, news, etc.)
- These "informed traders" cause price to move against liquidity providers
- VPIN measures % of toxic (informed) flow

Calculation:
1. Bucket trades by volume (not time)
    - Each bucket = 1000 contracts (fixed volume)

2. For each bucket, calculate:
    - buyVolume = volume on upticks
    - sellVolume = volume on downticks
    - imbalance = |buyVolume - sellVolume|

3. Calculate VPIN:
    - VPIN = average(imbalance) / bucketSize
    - Over last N buckets (e.g., 50 buckets)

Result: 0.0 to 1.0
- 0.0 = Balanced flow (no informed trading)
- 0.5 = Moderate imbalance
- 0.8+ = Toxic flow (informed traders active)
```

**Example:**
```
Bucket 1 (1000 contracts):
- Buy: 700
- Sell: 300
- Imbalance: 400

Bucket 2:
- Buy: 600
- Sell: 400
- Imbalance: 200

Bucket 3:
- Buy: 800
- Sell: 200
- Imbalance: 600

Average imbalance = (400 + 200 + 600) / 3 = 400
VPIN = 400 / 1000 = 0.40

Interpretation:
- Moderate imbalance (not extreme)
- Some directional flow but not toxic
- Safe to trade
```

---

### 3. Kyle's Lambda (Price Impact)
```
Purpose: How much does price move per unit of volume?

Theory:
- If you buy 1000 contracts, price will move up a bit
- Lambda measures how much
- High lambda = illiquid (your order moves price a lot)
- Low lambda = liquid (your order barely affects price)

Calculation (linear regression):
- X = cumulative signed volume
- Y = price change
- Fit line: Y = lambda × X
- kyleLambda = slope

Then:
priceImpact = lambda × orderSize
```

**Example:**
```
Trades during window:
Trade 1: Buy 200 @ ₹10.50 (price was ₹10.48, moved +0.02)
Trade 2: Buy 150 @ ₹10.52 (price was ₹10.50, moved +0.02)
Trade 3: Sell 300 @ ₹10.50 (price was ₹10.52, moved -0.02)
Trade 4: Buy 400 @ ₹10.54 (price was ₹10.50, moved +0.04)

Regression:
X = [+200, +350, +50, +450]
Y = [+0.02, +0.04, +0.02, +0.06]

Slope = 0.0001
kyleLambda = 0.0001

If you want to buy 1000 contracts:
priceImpact = 0.0001 × 1000 = 0.10

Interpretation:
- Buying 1000 will push price up by ₹0.10
- Use limit orders or split into smaller chunks
```

---

## VOLUME PROFILE (Detailed)

### Point of Control (POC)
```
Purpose: Find the "fair value" - where most volume traded

Calculation:
1. Track volume at each price level:
   volumeAtPrice[10.40] = 800
   volumeAtPrice[10.45] = 3200  ← Most volume here
   volumeAtPrice[10.50] = 1800
   volumeAtPrice[10.55] = 1200
   ...

2. Find price with maximum volume:
   POC = ₹10.45 (3200 contracts)

3. Current price vs POC:
   If current > POC: Price stretched HIGH (expect pullback down to POC)
   If current < POC: Price stretched LOW (expect bounce up to POC)
   If current == POC: Price at fair value (balanced)
```

---

### Value Area
```
Purpose: Find range where 70% of volume traded

Calculation:
1. Start from POC (highest volume price)
2. Expand up and down, adding volume
3. Stop when accumulated volume = 70% of total

Result:
- Value Area High = top of range
- Value Area Low = bottom of range
```

**Example:**
```
Total volume = 25,200 contracts
Target = 70% = 17,640 contracts

Starting from POC (₹10.45 with 3200 volume):
Accumulated = 3200

Expand to ₹10.40 (+800) and ₹10.50 (+1800):
Accumulated = 5800

Expand to ₹10.35 (+600) and ₹10.55 (+1200):
Accumulated = 7600

Continue expanding until:
Accumulated = 17,640

Final range: ₹10.25 to ₹10.65
- Value Area Low = ₹10.25
- Value Area High = ₹10.65

Interpretation:
- If price > ₹10.65: ABOVE value area (overbought)
- If price < ₹10.25: BELOW value area (oversold)
- If price inside: Normal (range-bound)
```

---

## TRADING SIGNALS (What To Do)

### Signal 1: High Importance Score
```
If importanceScore > 0.70:
→ This minute is IMPORTANT
→ Pay attention

If importanceScore > 0.85:
→ CRITICAL moment
→ High probability trading opportunity
→ Act now
```

---

### Signal 2: Imbalance Bar Completion
```
If all 4 bars near completion (>80%) in SAME direction:
→ Strong coordinated pressure
→ If BUY: Enter long
→ If SELL: Enter short or exit longs
```

---

### Signal 3: Acceleration Spike
```
If acceleration > 2.0:
→ Activity 2x normal
→ Institutions likely involved
→ If direction = BUY: Go long
→ If direction = SELL: Go short
```

---

### Signal 4: Exhaustion
```
If progress > 0.80 BUT exhaustionScore > 0.70:
→ Move is DYING
→ Don't chase
→ Take profit if in trade
→ Wait for reversal
```

---

### Signal 5: Divergence (Institutional vs Retail)
```
If volumeImbalance says BUY but tickRuns says SELL:
→ Institutions buying, retail selling
→ Retail always wrong
→ STRONG BUY signal
```

---

### Signal 6: POC Distance
```
If |currentPrice - POC| > 1.5%:
→ Price stretched too far
→ Mean reversion likely
→ If above POC: Take profit or short
→ If below POC: Buy the dip
```

---

### Signal 7: Iceberg Support/Resistance
```
If iceberg detected at ₹10.10:
→ Strong support (big hidden buyer)
→ Price likely bounces here
→ Buy near ₹10.10
→ Stop below iceberg
```

---

### Signal 8: OFI Extreme
```
If ofiZScore > +2.0:
→ Institutional buying (extreme)
→ Price will rise soon
→ Enter long

If ofiZScore < -2.0:
→ Institutional selling (extreme)
→ Price will fall soon
→ Enter short or exit
```

---

## IMPLEMENTATION STEPS (How To Build This)

### Week 1: Basic 1-Minute Candles
```
1. Create CandleState class
2. Build Kafka Streams topology for 1-minute
3. Implement market-aligned timestamp extractor (9:15 AM base)
4. Process trades: Update OHLC, volume, ticks
5. Process orderbook: Store latest snapshot
6. Process OI: Track OHLC
7. Emit basic candle (OHLC + volume + OI)
8. Test with 1 stock
```

### Week 2: Add Imbalance Bars
```
1. Add imbalance accumulators to CandleState
2. Implement basic accumulation (volume, dollar, tick, volumeRuns)
3. Implement adaptive thresholds (EWMA)
4. Calculate progress
5. Emit imbalance bar data in output
6. Test: Verify bars complete at reasonable frequency
```

### Week 3: Add Advanced Imbalance Features
```
1. Implement acceleration calculation
2. Implement exhaustion detection
3. Implement percentile ranking (keep rolling buffer of 100 windows)
4. Implement divergence detection
5. Implement importance score calculation
6. Test: Verify high-importance scores correlate with price moves
```

### Week 4: Add Orderbook Analysis
```
1. Implement depth aggregation (imbalance, slope)
2. Implement iceberg detection
3. Implement spoofing detection
4. Add orderbook evolution tracking (ring buffer of 60 snapshots)
5. Test: Verify icebergs detected correctly
```

### Week 5: Add Microstructure
```
1. Implement OFI calculation
2. Implement VPIN calculation
3. Implement Kyle's Lambda calculation
4. Test: Verify OFI predicts price moves
```

### Week 6: Add Volume Profile
```
1. Track volumeAtPrice during window
2. Calculate POC
3. Calculate Value Area
4. Test: Verify POC acts as magnet
```

### Week 7: Scale to All Timeframes
```
1. Add 2-minute topology (aggregate 2 x 1min candles)
2. Add 5-minute topology
3. Add 15-minute topology
4. Add 30-minute topology (9:15-9:45, 9:45-10:15, etc.)
5. Test all timeframes with multiple stocks
```

### Week 8: Production Deployment
```
1. Load test: 5000 stocks × 100 ticks/sec
2. Verify latency < 50ms
3. Set up monitoring
4. Deploy to production
5. Start paper trading

FINAL OUTPUT SIZE
Typical 1-minute candle:

JSON size: ~2-3 KB
Per stock: 375 candles/day (6.5 hours × 60 minutes)
Per stock per day: ~1 MB

For 5000 stocks:

Per day: ~5 GB
Manageable with Kafka retention


sample data of input topic
Topic :->forwardtesting-data
key:->96955 (which is token as well as this is only scripCode)
{
"companyName": "INDUSINDBK 28 OCT 2025 PE 760.00",
"Exch": "N",
"ExchType": "D",
"Token": 96955,
"LastRate": 10.2,
"LastQty": 1400,
"TotalQty": 25200,
"High": 11.6,
"Low": 8.5,
"OpenRate": 10.5,
"PClose": 11.35,
"AvgRate": 9.69,
"Time": 28037,
"BidQty": 1400,
"BidRate": 10.15,
"OffQty": 1400,
"OffRate": 10.55,
"TBidQ": 181300,
"TOffQ": 174300,
"TickDt": "/Date(1761034637000)/",
"ChgPcnt": -10.1321583
}

second topic :->
topic name :->>>Orderbook
topic key :->	N|92960 (exchange | token)
again we can extract token from key
{
"companyName": "INFY 28 OCT 2025 PE 1460.00",
"receivedTimestamp": 1761034637914,
"bids": [
{
"bid": true,
"ask": false,
"Quantity": 1600,
"Price": 20.2,
"NumberOfOrders": 4,
"BbBuySellFlag": 66
},
{
"bid": true,
"ask": false,
"Quantity": 1600,
"Price": 20.15,
"NumberOfOrders": 4,
"BbBuySellFlag": 66
},
{
"bid": true,
"ask": false,
"Quantity": 1200,
"Price": 20.1,
"NumberOfOrders": 3,
"BbBuySellFlag": 66
},
{
"bid": true,
"ask": false,
"Quantity": 800,
"Price": 20.05,
"NumberOfOrders": 2,
"BbBuySellFlag": 66
},
{
"bid": true,
"ask": false,
"Quantity": 1200,
"Price": 20.0,
"NumberOfOrders": 3,
"BbBuySellFlag": 66
}
],
"asks": [
{
"bid": false,
"ask": true,
"Quantity": 400,
"Price": 20.4,
"NumberOfOrders": 1,
"BbBuySellFlag": 83
},
{
"bid": false,
"ask": true,
"Quantity": 1600,
"Price": 20.5,
"NumberOfOrders": 2,
"BbBuySellFlag": 83
},
{
"bid": false,
"ask": true,
"Quantity": 800,
"Price": 20.55,
"NumberOfOrders": 2,
"BbBuySellFlag": 83
},
{
"bid": false,
"ask": true,
"Quantity": 800,
"Price": 20.6,
"NumberOfOrders": 2,
"BbBuySellFlag": 83
},
{
"bid": false,
"ask": true,
"Quantity": 2000,
"Price": 20.65,
"NumberOfOrders": 3,
"BbBuySellFlag": 83
}
],
"Exch": "N",
"ExchType": "D",
"Token": 92960,
"TBidQ": 426400,
"TOffQ": 109600,
"Details": [
{
"bid": true,
"ask": false,
"Quantity": 1600,
"Price": 20.2,
"NumberOfOrders": 4,
"BbBuySellFlag": 66
},
{
"bid": true,
"ask": false,
"Quantity": 1600,
"Price": 20.15,
"NumberOfOrders": 4,
"BbBuySellFlag": 66
},
{
"bid": true,
"ask": false,
"Quantity": 1200,
"Price": 20.1,
"NumberOfOrders": 3,
"BbBuySellFlag": 66
},
{
"bid": true,
"ask": false,
"Quantity": 800,
"Price": 20.05,
"NumberOfOrders": 2,
"BbBuySellFlag": 66
},
{
"bid": true,
"ask": false,
"Quantity": 1200,
"Price": 20.0,
"NumberOfOrders": 3,
"BbBuySellFlag": 66
},
{
"bid": false,
"ask": true,
"Quantity": 400,
"Price": 20.4,
"NumberOfOrders": 1,
"BbBuySellFlag": 83
},
{
"bid": false,
"ask": true,
"Quantity": 1600,
"Price": 20.5,
"NumberOfOrders": 2,
"BbBuySellFlag": 83
},
{
"bid": false,
"ask": true,
"Quantity": 800,
"Price": 20.55,
"NumberOfOrders": 2,
"BbBuySellFlag": 83
},
{
"bid": false,
"ask": true,
"Quantity": 800,
"Price": 20.6,
"NumberOfOrders": 2,
"BbBuySellFlag": 83
},
{
"bid": false,
"ask": true,
"Quantity": 2000,
"Price": 20.65,
"NumberOfOrders": 3,
"BbBuySellFlag": 83
}
]
}
third topic is OpenInterest
same way key :->(Exchange|126978 (token or scripCode))

here we get
{
"companyName": "ITC 25 NOV 2025 CE 412.50",
"receivedTimestamp": 1761034638963,
"Exch": "N",
"ExchType": "D",
"Token": 126978,
"OpenInterest": 59200
}


