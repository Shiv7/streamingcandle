# Commodity Benchmark Regime - Leading Indicator Integration

## Overview

MCX commodities currently fall back to **NIFTY50** as their index regime benchmark, which provides no meaningful leading signal for commodity price movements. This document outlines the implementation of **international benchmark tracking** to provide proper leading indicators for commodity regime analysis.

### Current State (Problem)

```java
// MTISProcessor.java:557-564
private IndexRegime getIndexRegime(String familyId) {
    IndexRegime regime = indexRegimeCache.get(familyId);
    if (regime != null) return regime;

    // Fallback to NIFTY for ALL instruments including commodities
    return latestNiftyRegime;  // <-- NOT meaningful for commodities
}
```

**Impact:** Dashboard shows "Security aligned with NIFTY50 index regime" for Gold, Crude, etc. which is irrelevant.

---

## Proposed Commodity Benchmark Mappings

### Tier 1: Primary International Benchmarks (Highest Priority)

| MCX Commodity | Leading Benchmark | Symbol | Exchange | Lead Time |
|---------------|-------------------|--------|----------|-----------|
| Gold, GoldM, GoldGuinea, GoldTen | COMEX Gold Futures | GC | COMEX/CME | 2-5 min |
| Silver, SilverM, SilverMic | COMEX Silver Futures | SI | COMEX/CME | 2-5 min |
| Crude Oil, CrudeOilM | Brent Crude Futures | BZ | ICE | 1-3 min |
| Crude Oil, CrudeOilM | WTI Crude Futures | CL | NYMEX/CME | 1-3 min |
| Natural Gas, NaturalGasM | Henry Hub Natural Gas | NG | NYMEX/CME | 2-5 min |
| Copper | LME Copper / COMEX Copper | HG | LME/COMEX | 5-10 min |
| Aluminum | LME Aluminum | ALI | LME | 5-10 min |
| Zinc | LME Zinc | ZN | LME | 5-10 min |
| Lead | LME Lead | PB | LME | 5-10 min |
| Nickel | LME Nickel | NI | LME | 5-10 min |

### Tier 2: Currency Regime (Required for All Commodities)

| Indicator | Symbol | Impact | Source |
|-----------|--------|--------|--------|
| USD/INR Spot | USDINR=X | Direct - Rupee depreciation = higher MCX prices | RBI/Forex |
| Dollar Index (DXY) | DX-Y.NYB | Inverse - Dollar weakness = commodity strength | ICE |

### Tier 3: Macro Regime Indicators

| Category | Indicator | Symbol | Impact |
|----------|-----------|--------|--------|
| Precious Metals | US 10Y Real Yield | ^TNX - Breakeven | Inverse correlation |
| Precious Metals | VIX | ^VIX | Positive in risk-off |
| Base Metals | China Caixin PMI | - | Demand indicator |
| Base Metals | LME Warehouse Stocks | - | Supply indicator |
| Energy | EIA Crude Inventory | - | Weekly catalyst |
| Energy | Brent-WTI Spread | BZ - CL | Supply dynamics |

---

## Integration Architecture

### High-Level Data Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        EXTERNAL DATA SOURCES                                 │
├─────────────────┬─────────────────┬─────────────────┬───────────────────────┤
│  Yahoo Finance  │   TradingView   │   LME Official  │    RBI/Forex Feed     │
│  (Free/Delayed) │   (Webhooks)    │   (Paid API)    │    (Real-time)        │
└────────┬────────┴────────┬────────┴────────┬────────┴───────────┬───────────┘
         │                 │                 │                    │
         ▼                 ▼                 ▼                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     BENCHMARK DATA INGESTION SERVICE                         │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  BenchmarkDataFetcher.java                                          │    │
│  │  - Polls Yahoo Finance every 1 min for Tier 1 benchmarks            │    │
│  │  - Receives TradingView webhooks for real-time alerts               │    │
│  │  - Subscribes to RBI reference rate feed                            │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          KAFKA TOPICS                                        │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌───────────────────┐  │
│  │ benchmark-prices     │  │ benchmark-regime     │  │ currency-regime   │  │
│  │ (raw OHLCV data)     │  │ (calculated regime)  │  │ (USD/INR, DXY)    │  │
│  └──────────────────────┘  └──────────────────────┘  └───────────────────┘  │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    COMMODITY REGIME CALCULATOR                               │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  CommodityBenchmarkRegimeCalculator.java                            │    │
│  │  - Maps MCX commodity → International benchmark                     │    │
│  │  - Calculates regime from benchmark EMA20/50, momentum              │    │
│  │  - Applies currency adjustment (USD/INR impact)                     │    │
│  │  - Outputs CommodityRegime with leadingIndicatorBias                │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EXISTING PIPELINE INTEGRATION                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  MTISProcessor.java - getIndexRegime() updated                      │    │
│  │  QuantScoreProcessor.java - uses CommodityRegime for commodities    │    │
│  │  PriceNarrative.tsx - shows "Aligned with COMEX Gold regime"        │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Integration Points by Data Source

### 1. Yahoo Finance Integration (FREE - Delayed 15-20 min)

**Use Case:** Tier 1 benchmarks for regime calculation (acceptable delay for regime, not for execution)

**Symbols to Fetch:**
```
GC=F      # COMEX Gold
SI=F      # COMEX Silver
CL=F      # WTI Crude
BZ=F      # Brent Crude
NG=F      # Henry Hub Natural Gas
HG=F      # COMEX Copper
DX-Y.NYB  # Dollar Index
USDINR=X  # USD/INR
^VIX      # VIX
^TNX      # 10Y Treasury Yield
```

**Integration File:** `src/main/java/com/kotsin/consumer/benchmark/fetcher/YahooFinanceFetcher.java`

```java
/**
 * Yahoo Finance Integration
 *
 * Library: yahoo-finance-api or yfinance4j
 * Polling Interval: 60 seconds (respect rate limits)
 * Data Retrieved: OHLCV + 52-week high/low
 *
 * Rate Limits: ~2000 requests/hour (unauthenticated)
 * Delay: 15-20 minutes for free tier
 */
@Service
@Slf4j
public class YahooFinanceFetcher {

    private static final List<String> BENCHMARK_SYMBOLS = List.of(
        "GC=F", "SI=F", "CL=F", "BZ=F", "NG=F", "HG=F",
        "DX-Y.NYB", "USDINR=X", "^VIX", "^TNX"
    );

    @Scheduled(fixedRate = 60000)  // Every 1 minute
    public void fetchBenchmarkPrices() {
        // Implementation: Batch fetch all symbols
        // Publish to Kafka topic: benchmark-prices
    }
}
```

**Dependencies to Add (pom.xml):**
```xml
<!-- Yahoo Finance API -->
<dependency>
    <groupId>com.yahoofinance-api</groupId>
    <artifactId>YahooFinanceAPI</artifactId>
    <version>3.17.0</version>
</dependency>
```

---

### 2. TradingView Webhooks Integration (REAL-TIME Alerts)

**Use Case:** Real-time regime change alerts, breakout notifications

**Setup Required:**
1. Create TradingView alerts on benchmark charts
2. Configure webhook URL pointing to our endpoint
3. Parse incoming JSON payloads

**Integration File:** `src/main/java/com/kotsin/consumer/benchmark/webhook/TradingViewWebhookController.java`

```java
/**
 * TradingView Webhook Integration
 *
 * Endpoint: POST /api/v1/webhook/tradingview
 * Authentication: Secret token in header
 * Payload: Custom JSON from TradingView alert
 *
 * Alert Types:
 * - REGIME_CHANGE: EMA crossover on benchmark
 * - BREAKOUT: Price breaking key level
 * - DIVERGENCE: RSI/MACD divergence detected
 */
@RestController
@RequestMapping("/api/v1/webhook")
public class TradingViewWebhookController {

    @PostMapping("/tradingview")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-TV-Secret") String secret,
            @RequestBody TradingViewAlert alert) {
        // Validate secret
        // Parse alert type and symbol
        // Publish to Kafka topic: benchmark-alerts
    }
}
```

**TradingView Alert Message Format (configure in TV):**
```json
{
    "symbol": "{{ticker}}",
    "exchange": "{{exchange}}",
    "alertType": "REGIME_CHANGE",
    "direction": "BULLISH",
    "price": {{close}},
    "ema20": {{plot_0}},
    "ema50": {{plot_1}},
    "timestamp": "{{timenow}}"
}
```

**Required TradingView Alerts to Create:**

| Chart | Alert Condition | Webhook Payload |
|-------|-----------------|-----------------|
| COMEX:GC1! | EMA(20) crosses above EMA(50) | `alertType: REGIME_CHANGE, direction: BULLISH` |
| COMEX:GC1! | EMA(20) crosses below EMA(50) | `alertType: REGIME_CHANGE, direction: BEARISH` |
| COMEX:SI1! | EMA(20) crosses above EMA(50) | Same pattern |
| NYMEX:CL1! | EMA(20) crosses above EMA(50) | Same pattern |
| ICE:BRN1! | EMA(20) crosses above EMA(50) | Same pattern |
| NYMEX:NG1! | EMA(20) crosses above EMA(50) | Same pattern |
| TVC:DXY | EMA(20) crosses below EMA(50) | `alertType: REGIME_CHANGE, direction: COMMODITY_BULLISH` |

---

### 3. LME Official Data Integration (PAID - Real-time)

**Use Case:** Base metals (Copper, Aluminum, Zinc, Lead, Nickel) - authoritative pricing

**Options:**
1. **LME DataCloud API** (Official) - $500-2000/month
2. **Refinitiv/Reuters** - Enterprise pricing
3. **Quandl LME Dataset** - ~$50/month (delayed)

**Integration File:** `src/main/java/com/kotsin/consumer/benchmark/fetcher/LMEDataFetcher.java`

```java
/**
 * LME Data Integration
 *
 * API: LME DataCloud REST API or Quandl
 * Authentication: API Key
 * Polling Interval: 5 minutes (or real-time websocket if available)
 *
 * Symbols:
 * - COPPER (Cu)
 * - ALUMINIUM (Al)
 * - ZINC (Zn)
 * - LEAD (Pb)
 * - NICKEL (Ni)
 */
@Service
@ConditionalOnProperty(name = "benchmark.lme.enabled", havingValue = "true")
public class LMEDataFetcher {

    @Value("${benchmark.lme.api-key}")
    private String apiKey;

    @Scheduled(fixedRate = 300000)  // Every 5 minutes
    public void fetchLMEPrices() {
        // Fetch official LME settlement prices
        // Publish to Kafka topic: benchmark-prices
    }
}
```

**Alternative - Quandl Integration:**
```xml
<!-- Quandl API -->
<dependency>
    <groupId>com.jimmoores</groupId>
    <artifactId>quandl4j</artifactId>
    <version>2.0.0</version>
</dependency>
```

---

### 4. RBI Reference Rate Integration (Official USD/INR)

**Use Case:** Currency regime for all commodities

**Integration File:** `src/main/java/com/kotsin/consumer/benchmark/fetcher/RBIRateFetcher.java`

```java
/**
 * RBI Reference Rate Integration
 *
 * Source: RBI FBIL Reference Rate
 * URL: https://www.fbil.org.in/api/...
 * Update Frequency: Published at 1:30 PM IST daily
 *
 * For real-time: Use forex broker feed or Yahoo USDINR=X
 */
@Service
public class RBIRateFetcher {

    @Scheduled(cron = "0 35 13 * * MON-FRI", zone = "Asia/Kolkata")
    public void fetchRBIRate() {
        // Fetch official RBI reference rate
        // Publish to Kafka topic: currency-regime
    }
}
```

---

## New Kafka Topics Required

```yaml
# application.properties additions

# Benchmark data topics
kafka.topic.benchmark-prices=benchmark-prices
kafka.topic.benchmark-regime=benchmark-regime
kafka.topic.benchmark-alerts=benchmark-alerts
kafka.topic.currency-regime=currency-regime

# Topic configurations (add to TopicInitializer.java)
# benchmark-prices: partitions=4, retention=7d
# benchmark-regime: partitions=4, retention=1d
# benchmark-alerts: partitions=2, retention=1d
# currency-regime: partitions=2, retention=1d
```

---

## New Configuration Properties

```properties
# application.properties additions

# ==================== BENCHMARK INTEGRATION ====================

# Yahoo Finance (Free tier)
benchmark.yahoo.enabled=true
benchmark.yahoo.polling-interval-ms=60000
benchmark.yahoo.symbols=GC=F,SI=F,CL=F,BZ=F,NG=F,HG=F,DX-Y.NYB,USDINR=X,^VIX,^TNX

# TradingView Webhooks
benchmark.tradingview.enabled=true
benchmark.tradingview.secret=${TV_WEBHOOK_SECRET:changeme}

# LME Data (Paid)
benchmark.lme.enabled=false
benchmark.lme.api-key=${LME_API_KEY:}
benchmark.lme.polling-interval-ms=300000

# Currency
benchmark.currency.source=yahoo  # Options: yahoo, rbi, forex-broker

# ==================== COMMODITY MAPPINGS ====================

# MCX Symbol -> International Benchmark mapping
commodity.benchmark.GOLD=GC=F
commodity.benchmark.GOLDM=GC=F
commodity.benchmark.GOLDGUINEA=GC=F
commodity.benchmark.GOLDTEN=GC=F
commodity.benchmark.SILVER=SI=F
commodity.benchmark.SILVERM=SI=F
commodity.benchmark.SILVERMIC=SI=F
commodity.benchmark.CRUDEOIL=BZ=F
commodity.benchmark.CRUDEOILM=BZ=F
commodity.benchmark.NATURALGAS=NG=F
commodity.benchmark.COPPER=HG=F
commodity.benchmark.ALUMINIUM=ALI
commodity.benchmark.ZINC=ZN
commodity.benchmark.LEAD=PB
commodity.benchmark.NICKEL=NI
```

---

## New Java Classes Required

### 1. Models

```
src/main/java/com/kotsin/consumer/benchmark/model/
├── BenchmarkPrice.java           # OHLCV from international benchmark
├── BenchmarkRegime.java          # Calculated regime for benchmark
├── CommodityBenchmarkMapping.java # MCX -> International mapping
└── CurrencyRegime.java           # USD/INR regime state
```

### 2. Fetchers

```
src/main/java/com/kotsin/consumer/benchmark/fetcher/
├── YahooFinanceFetcher.java      # Yahoo Finance polling
├── LMEDataFetcher.java           # LME official data (optional)
├── RBIRateFetcher.java           # RBI reference rate
└── BenchmarkDataAggregator.java  # Combines all sources
```

### 3. Calculators

```
src/main/java/com/kotsin/consumer/benchmark/calculator/
├── BenchmarkRegimeCalculator.java      # EMA20/50 regime from benchmark
├── CurrencyImpactCalculator.java       # USD/INR impact on MCX prices
└── CommodityLeadingIndicator.java      # Combines benchmark + currency
```

### 4. Processors

```
src/main/java/com/kotsin/consumer/benchmark/processor/
├── BenchmarkRegimeProcessor.java       # Kafka consumer for benchmark data
└── CommodityRegimeEnricher.java        # Enriches FamilyCandle with benchmark regime
```

### 5. Webhook Controller

```
src/main/java/com/kotsin/consumer/benchmark/webhook/
├── TradingViewWebhookController.java   # POST endpoint for TV alerts
└── TradingViewAlert.java               # Alert payload model
```

---

## Integration with Existing Pipeline

### 1. Update MTISProcessor.java

```java
// BEFORE (current)
private IndexRegime getIndexRegime(String familyId) {
    IndexRegime regime = indexRegimeCache.get(familyId);
    if (regime != null) return regime;
    return latestNiftyRegime;  // Fallback to NIFTY
}

// AFTER (proposed)
private IndexRegime getIndexRegime(String familyId, boolean isCommodity) {
    if (isCommodity) {
        // Use commodity benchmark regime instead of NIFTY
        CommodityRegime commodityRegime = commodityRegimeCache.get(familyId);
        if (commodityRegime != null) {
            return commodityRegime.toIndexRegime();  // Convert to IndexRegime interface
        }
        // Fallback to generic commodity regime based on type
        return getDefaultCommodityRegime(familyId);
    }

    // Existing logic for equities
    IndexRegime regime = indexRegimeCache.get(familyId);
    if (regime != null) return regime;
    return latestNiftyRegime;
}
```

### 2. Update PriceNarrative.tsx (Dashboard)

```typescript
// BEFORE
text: `Security aligned with ${score.indexRegimeLabel} index regime`

// AFTER
text: score.isCommodity
    ? `Aligned with ${score.benchmarkLabel} (${score.benchmarkRegimeLabel})`
    : `Security aligned with ${score.indexRegimeLabel} index regime`

// Example output:
// "Aligned with COMEX Gold (BULLISH)"
// "Diverging from Brent Crude (BEARISH) - Currency headwind"
```

### 3. Update FamilyScore.java

```java
// Add new fields
private String benchmarkSymbol;        // "GC=F" for gold
private String benchmarkLabel;         // "COMEX Gold"
private String benchmarkRegimeLabel;   // "BULLISH", "BEARISH"
private double benchmarkRegimeStrength;
private double currencyImpact;         // USD/INR impact factor
private boolean benchmarkAligned;      // Is MCX aligned with benchmark?
```

---

## Regime Calculation Logic

### CommodityBenchmarkRegimeCalculator.java

```java
/**
 * Calculate commodity regime from international benchmark
 *
 * Formula:
 * BenchmarkRegime = f(EMA20, EMA50, Momentum, Volume)
 * CurrencyAdjustment = f(USD/INR trend, DXY trend)
 * FinalCommodityRegime = BenchmarkRegime * CurrencyMultiplier
 */
public CommodityRegime calculate(BenchmarkPrice benchmark, CurrencyRegime currency) {

    // 1. Benchmark trend direction
    int trendDir = benchmark.getEma20() > benchmark.getEma50() ? 1 : -1;

    // 2. Trend strength (EMA separation normalized by ATR)
    double emaSeparation = Math.abs(benchmark.getEma20() - benchmark.getEma50());
    double trendStrength = Math.min(emaSeparation / benchmark.getAtr14(), 1.0);

    // 3. Momentum (ROC 10)
    double momentum = (benchmark.getClose() - benchmark.getCloseLag10()) / benchmark.getCloseLag10();

    // 4. Currency impact
    // If USD/INR rising + benchmark flat = MCX bullish (currency-driven)
    // If USD/INR falling + benchmark rising = MCX super bullish
    // If USD/INR rising + benchmark falling = MCX mixed
    double currencyMultiplier = calculateCurrencyMultiplier(currency, trendDir);

    // 5. Final regime score
    double rawRegimeScore = (0.50 * trendDir * trendStrength) +
                           (0.30 * Math.signum(momentum) * Math.min(Math.abs(momentum) * 10, 1.0)) +
                           (0.20 * currencyMultiplier);

    RegimeLabel label = RegimeLabel.fromStrengthAndDirection(
        Math.abs(rawRegimeScore),
        (int) Math.signum(rawRegimeScore)
    );

    return CommodityRegime.builder()
        .benchmarkSymbol(benchmark.getSymbol())
        .regimeScore(rawRegimeScore)
        .label(label)
        .trendDirection(trendDir)
        .trendStrength(trendStrength)
        .currencyImpact(currencyMultiplier)
        .leadingIndicatorBias(trendDir > 0 ? "BULLISH" : "BEARISH")
        .build();
}

private double calculateCurrencyMultiplier(CurrencyRegime currency, int benchmarkTrend) {
    // USD/INR trending up = positive for MCX commodity prices (in INR)
    // DXY trending down = positive for commodities globally

    double usdInrImpact = currency.getUsdInrTrend() * 0.15;  // 15% weight
    double dxyImpact = -currency.getDxyTrend() * 0.10;       // 10% weight, inverse

    return 1.0 + usdInrImpact + dxyImpact;
}
```

---

## Implementation Phases

### Phase 1: Yahoo Finance Integration (Week 1)
- [ ] Add Yahoo Finance dependency
- [ ] Implement YahooFinanceFetcher
- [ ] Create benchmark-prices Kafka topic
- [ ] Test with Gold/Silver benchmarks

### Phase 2: Regime Calculator (Week 2)
- [ ] Implement BenchmarkRegimeCalculator
- [ ] Create CommodityBenchmarkMapping configuration
- [ ] Integrate with MTISProcessor
- [ ] Test regime alignment detection

### Phase 3: Currency Integration (Week 3)
- [ ] Implement USD/INR tracking
- [ ] Add DXY tracking
- [ ] Implement CurrencyImpactCalculator
- [ ] Test combined regime calculation

### Phase 4: TradingView Webhooks (Week 4)
- [ ] Create webhook endpoint
- [ ] Configure TradingView alerts (manual)
- [ ] Implement real-time regime change notifications
- [ ] Test end-to-end alerting

### Phase 5: Dashboard Integration (Week 5)
- [ ] Update FamilyScore with benchmark fields
- [ ] Update PriceNarrative.tsx
- [ ] Add benchmark regime display
- [ ] Test full dashboard flow

### Phase 6: LME Integration - Optional (Week 6+)
- [ ] Evaluate LME data subscription
- [ ] Implement LMEDataFetcher if subscribed
- [ ] Test base metals regime

---

## Testing Checklist

### Unit Tests
- [ ] YahooFinanceFetcher parses OHLCV correctly
- [ ] BenchmarkRegimeCalculator computes regime accurately
- [ ] CurrencyImpactCalculator handles edge cases
- [ ] Commodity mapping resolves correctly

### Integration Tests
- [ ] Yahoo Finance API responds within timeout
- [ ] TradingView webhook authenticates correctly
- [ ] Kafka topics receive benchmark data
- [ ] MTISProcessor uses correct regime for commodities

### End-to-End Tests
- [ ] Gold on MCX shows "COMEX Gold" regime
- [ ] Crude on MCX shows "Brent Crude" regime
- [ ] Dashboard displays benchmark alignment
- [ ] Currency impact reflected in regime

---

## Cost Analysis

| Integration | Cost | Data Quality | Latency |
|-------------|------|--------------|---------|
| Yahoo Finance | Free | Good (delayed 15-20 min) | Acceptable for regime |
| TradingView Webhooks | Free (with account) | Real-time alerts | Real-time |
| Quandl LME | ~$50/month | Good (EOD) | EOD only |
| LME DataCloud | $500-2000/month | Official, real-time | Real-time |
| Refinitiv | Enterprise | Best | Real-time |

**Recommended Starting Point:** Yahoo Finance (free) + TradingView Webhooks (free)

---

## References

- [Yahoo Finance API Documentation](https://github.com/sstrickx/yahoofinance-api)
- [TradingView Webhooks Guide](https://www.tradingview.com/support/solutions/43000529348-about-webhooks/)
- [LME DataCloud API](https://www.lme.com/en/market-data/lme-datacloud)
- [FBIL Reference Rates](https://www.fbil.org.in/)

---

## Appendix: Symbol Reference

### Yahoo Finance Symbols
```
Precious Metals:
  GC=F   - COMEX Gold Futures
  SI=F   - COMEX Silver Futures

Energy:
  CL=F   - WTI Crude Oil Futures (NYMEX)
  BZ=F   - Brent Crude Oil Futures (ICE)
  NG=F   - Natural Gas Futures (NYMEX)

Base Metals:
  HG=F   - Copper Futures (COMEX)

Currency:
  USDINR=X  - USD/INR Spot
  DX-Y.NYB  - US Dollar Index

Volatility:
  ^VIX   - CBOE Volatility Index

Rates:
  ^TNX   - 10-Year Treasury Yield
```

### TradingView Symbols
```
Precious Metals:
  COMEX:GC1!  - Gold Front Month
  COMEX:SI1!  - Silver Front Month

Energy:
  NYMEX:CL1!  - WTI Crude Front Month
  ICE:BRN1!   - Brent Crude Front Month
  NYMEX:NG1!  - Natural Gas Front Month

Base Metals:
  COMEX:HG1!  - Copper Front Month

Currency:
  TVC:DXY     - Dollar Index
  FX:USDINR   - USD/INR
```
