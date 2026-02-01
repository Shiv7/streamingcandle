# Domain & Quant Glossary

> Financial and quantitative terms used in StreamingCandle

---

## Market Microstructure

### OFI (Order Flow Imbalance)
The net difference between bid-side and ask-side order flow.

```
OFI = Δ(Bid Qty) - Δ(Ask Qty)
```

- **Positive OFI** → More buying pressure (bullish)
- **Negative OFI** → More selling pressure (bearish)

### Kyle's Lambda (λ)
Measures **price impact per unit of order flow**. Higher λ = less liquid market.

```
λ = ΔPrice / ΔVolume
```

- **Low λ** → Deep market, can trade size without moving price
- **High λ** → Thin market, orders move price significantly

### VPIN (Volume-Synchronized Probability of Informed Trading)
Measures the probability that trading is **informed** (toxic flow).

```
VPIN = |BuyVolume - SellVolume| / TotalVolume (over N buckets)
```

- **High VPIN (>0.7)** → Informed traders active, potential volatility
- **Low VPIN (<0.3)** → Normal market activity

### Bid-Ask Spread
Difference between best offer and best bid price.

```
Spread = Ask - Bid
SpreadPercent = (Ask - Bid) / MidPrice × 100
```

### Microprice
Volume-weighted mid-price, better estimate of fair value.

```
Microprice = (Bid × AskQty + Ask × BidQty) / (BidQty + AskQty)
```

---

## Volume Analysis

### POC (Point of Control)
Price level with the **highest traded volume** in a period.

- Acts as a strong support/resistance level
- Price tends to gravitate toward POC

### Value Area (VAH/VAL)
Range containing **70% of total volume**.

- **VAH** (Value Area High) - Upper boundary
- **VAL** (Value Area Low) - Lower boundary
- Trading inside VA = fair value zone
- Trading outside VA = directional move

### VWAP (Volume Weighted Average Price)
Average price weighted by volume.

```
VWAP = Σ(Price × Volume) / Σ(Volume)
```

- **Above VWAP** → Bullish (buying at higher prices)
- **Below VWAP** → Bearish (selling at lower prices)

### Volume Delta
Net difference between buy and sell volume.

```
VolumeDelta = BuyVolume - SellVolume
```

---

## Trade Classification

### Buy Volume (Lifted Offers)
Volume traded **at or above the ask price** - aggressive buying.

### Sell Volume (Hit Bids)
Volume traded **at or below the bid price** - aggressive selling.

### Buy Pressure / Sell Pressure
Percentage of total volume that was aggressive buy/sell.

```
BuyPressure = BuyVolume / TotalVolume
SellPressure = SellVolume / TotalVolume
```

---

## Imbalance Bars

### VIB (Volume Imbalance Bar)
Triggered when cumulative volume imbalance exceeds dynamic threshold.

```
VIB triggers when: |Σ BuyVol - Σ SellVol| > threshold
```

### DIB (Dollar Imbalance Bar)
Same as VIB but using dollar volume (volume × price).

### TRB (Tick Run Bar)
Triggered when consecutive ticks in same direction exceed threshold.

### VRB (Volume Run Bar)
Triggered when cumulative same-direction volume exceeds threshold.

---

## Open Interest (OI)

### Open Interest
Total number of outstanding derivative contracts.

### OI Interpretation

| OI Change | Price Change | Interpretation | Signal |
|-----------|--------------|----------------|--------|
| ↑ OI | ↑ Price | LONG_BUILDUP | Bullish |
| ↓ OI | ↑ Price | SHORT_COVERING | Bullish (weak) |
| ↑ OI | ↓ Price | SHORT_BUILDUP | Bearish |
| ↓ OI | ↓ Price | LONG_UNWINDING | Bearish (weak) |

### Put-Call Ratio (PCR)
Ratio of put OI to call OI.

```
PCR = Put OI / Call OI
```

- **PCR > 1** → Bearish sentiment
- **PCR < 1** → Bullish sentiment

---

## Technical Indicators

### RSI (Relative Strength Index)
Momentum oscillator (0-100).

```
RSI = 100 - (100 / (1 + RS))
RS = Average Gain / Average Loss
```

- **RSI > 70** → Overbought
- **RSI < 30** → Oversold

### MACD (Moving Average Convergence Divergence)
Trend-following momentum indicator.

```
MACD Line = EMA(12) - EMA(26)
Signal Line = EMA(9) of MACD Line
Histogram = MACD Line - Signal Line
```

### Bollinger Bands
Volatility bands around moving average.

```
Middle = SMA(20)
Upper = Middle + (2 × StdDev)
Lower = Middle - (2 × StdDev)
```

### SuperTrend
Trend indicator using ATR.

```
Upper Band = (High + Low)/2 + (Multiplier × ATR)
Lower Band = (High + Low)/2 - (Multiplier × ATR)
```

- **Price above SuperTrend** → Bullish
- **Price below SuperTrend** → Bearish

### ATR (Average True Range)
Volatility measure.

```
True Range = max(High - Low, |High - PrevClose|, |Low - PrevClose|)
ATR = EMA(True Range, 14)
```

### ADX (Average Directional Index)
Trend strength indicator (0-100).

- **ADX > 25** → Trending market
- **ADX < 20** → Ranging market

---

## FUDKII Components

### Flow Score
Normalized OFI momentum relative to recent history.

### Urgency Score
Based on IPU (Institutional Participation & Urgency) with exhaustion detection.

### Direction Score
Based on VCP runway (distance to support/resistance).

### Kyle Score
Normalized Kyle's Lambda relative to average.

### Imbalance Score
Based on VIB/DIB trigger states.

### Intensity Score
Based on tick activity and large trade count.

---

## Signal States

### IDLE
No setup detected.

### WATCH
Setup identified, waiting for confirmation:
- FUDKII score > 40
- Direction alignment
- No exhaustion

### ACTIVE
Entry triggered:
- FUDKII score > 60
- Imbalance trigger (VIB/DIB)
- Gate chain passed

### EXIT
Position closed:
- Target hit
- Stop loss hit
- Signal expired
- Reversal detected

---

## Market Sessions (IST)

| Session | Time | Characteristics |
|---------|------|-----------------|
| Pre-Open | 9:00 - 9:15 | No trading |
| Opening | 9:15 - 9:30 | High volatility |
| Morning | 9:30 - 11:30 | Active trading |
| Midday | 11:30 - 13:30 | Consolidation |
| Afternoon | 13:30 - 15:00 | Trending |
| Closing | 15:00 - 15:30 | High volatility |

---

## Exchanges

| Code | Exchange | Type |
|------|----------|------|
| N | NSE | National Stock Exchange |
| B | BSE | Bombay Stock Exchange |
| M | MCX | Multi Commodity Exchange |

### Exchange Types

| Code | Type |
|------|------|
| C | Cash (Equity) |
| D | Derivative (F&O) |

---

## Instrument Types

| Type | Description |
|------|-------------|
| INDEX | Index (NIFTY, BANKNIFTY) |
| EQUITY | Stock (RELIANCE, TCS) |
| FUTURE | Futures contract |
| OPTION_CE | Call option |
| OPTION_PE | Put option |
