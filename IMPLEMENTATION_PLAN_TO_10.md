# Implementation Plan: Road to 10/10 in All Parameters

**Target**: Code Quality 10/10, Quant Accuracy 10/10, Trader Utility 10/10
**Current**: 8.5/10, 7/10, 7.5/10
**Effort**: ~2-3 days of focused development
**Files to Modify**: 8 core files + 2 new files

---

## PHASE 1: CRITICAL BUGS (MUST FIX) ✅ IN PROGRESS

### ✅ 1.1 Fix OFI Bid Band Formula [DONE]
- **File**: `OrderbookAggregate.java:469-479`
- **Change**: `bidBand = Math.max(prevBestBid, currBestBid);`
- **Impact**: Correct OFI calculation per CKS 2014 paper
- **Status**: ✅ COMPLETED

### ✅ 1.2 Fix OI Change Fallback [DONE]
- **File**: `UnifiedInstrumentCandleProcessor.java:950-961`
- **Change**: Set `oiChange = null` when no previous OI (instead of within-window change)
- **Impact**: Honest reporting - traders won't see misleading "0" change
- **Status**: ✅ COMPLETED

### 1.3 Fix VWAP Calculation
- **File**: `TickAggregate.java`
- **Problem**: VWAP resets after TotalQty reset → diverges from exchange VWAP
- **Solution**: Add separate cumulative VWAP counters (don't reset on TotalQty reset)
- **Changes**:
  ```java
  // Add new fields:
  private long cumulativeVolume = 0;       // Never reset
  private double cumulativeTotalValue = 0.0;  // Never reset

  // In update():
  cumulativeVolume += deltaVol;
  cumulativeTotalValue += deltaVol * price;
  vwap = cumulativeVolume > 0 ? cumulativeTotalValue / cumulativeVolume : close;
  ```
- **Impact**: VWAP matches exchange VWAP exactly
- **Effort**: 30 min

### 1.4 Verify Orderbook Key Matching
- **File**: `OrderBookSnapshot.java` (model)
- **Check**: Ensure method names are `getExchange()` / `getExchangeType()` (not `getExch()` / `getExchType()`)
- **Impact**: Prevent orderbook fallback mismatches
- **Effort**: 5 min

---

## PHASE 2: HIGH-VALUE TRADER FEATURES (CRITICAL FOR 10/10 UTILITY)

### 2.1 Implement Volume Profile (POC, VAH, VAL) ⭐ HIGHEST PRIORITY
- **Files**:
  - `TickAggregate.java` (track volume-at-price histogram)
  - `InstrumentCandle.java` (already has fields, just populate them)
  - `UnifiedInstrumentCandleProcessor.java` (calculate POC/VAH/VAL from histogram)

- **Implementation**:
  ```java
  // TickAggregate.java: Add volume profile tracking
  private Map<Double, Long> volumeAtPrice = new HashMap<>();

  // In update():
  double priceLevel = Math.round(tick.getLastRate() / tickSize) * tickSize;  // Round to tick size
  volumeAtPrice.merge(priceLevel, deltaVol, Long::sum);

  // After window:
  public Map<Double, Long> getVolumeAtPrice() { return volumeAtPrice; }
  ```

  ```java
  // UnifiedInstrumentCandleProcessor.java: Calculate POC/VAH/VAL
  private VolumeProfileResult calculateVolumeProfile(Map<Double, Long> volAtPrice) {
      // 1. Find POC (price with max volume)
      double poc = volAtPrice.entrySet().stream()
          .max(Comparator.comparingLong(Map.Entry::getValue))
          .map(Map.Entry::getKey)
          .orElse(0.0);

      // 2. Calculate total volume
      long totalVol = volAtPrice.values().stream().mapToLong(Long::longValue).sum();
      long valueAreaVol = (long) (totalVol * 0.70);  // 70% value area

      // 3. Find VAH/VAL (expand from POC until 70% volume covered)
      // Sort prices, start from POC, expand up/down alternately
      List<Map.Entry<Double, Long>> sorted = volAtPrice.entrySet().stream()
          .sorted(Comparator.comparingDouble(Map.Entry::getKey))
          .collect(Collectors.toList());

      // Binary search to find VAH/VAL
      // ...

      return new VolumeProfileResult(poc, vah, val, volAtPrice);
  }

  // In buildInstrumentCandle():
  VolumeProfileResult profile = calculateVolumeProfile(tick.getVolumeAtPrice());
  builder.poc(profile.poc);
  builder.vah(profile.vah);
  builder.val(profile.val);
  builder.volumeAtPrice(profile.volumeAtPrice);
  ```

- **Trader Impact**: 🔥🔥🔥 CRITICAL - Volume Profile is #1 institutional trading tool
- **Effort**: 3 hours

### 2.2 Add Tick-Level Spread Metrics
- **File**: `TickAggregate.java`
- **Add**:
  ```java
  private double sumSpread = 0.0;
  private int spreadCount = 0;
  private double minSpread = Double.MAX_VALUE;
  private double maxSpread = 0.0;
  private List<Double> spreadHistory = new ArrayList<>();

  // In update():
  if (tick.getBestBid() > 0 && tick.getBestAsk() > 0) {
      double spread = tick.getBestAsk() - tick.getBestBid();
      sumSpread += spread;
      spreadCount++;
      minSpread = Math.min(minSpread, spread);
      maxSpread = Math.max(maxSpread, spread);
      spreadHistory.add(spread);
  }

  public double getAverageTickSpread() { return spreadCount > 0 ? sumSpread / spreadCount : 0.0; }
  public double getSpreadVolatility() {
      double mean = getAverageTickSpread();
      double variance = spreadHistory.stream()
          .mapToDouble(s -> Math.pow(s - mean, 2))
          .average()
          .orElse(0.0);
      return Math.sqrt(variance);
  }
  public double getTightSpreadPercent() {
      // % of time spread <= 1 tick
      long tightCount = spreadHistory.stream()
          .filter(s -> s <= tickSize)
          .count();
      return spreadCount > 0 ? (double) tightCount / spreadCount * 100.0 : 0.0;
  }
  ```

- **File**: `InstrumentCandle.java` - Add fields:
  ```java
  private Double averageTickSpread;
  private Double spreadVolatilityTick;  // Different from orderbook spread volatility
  private Double tightSpreadPercent;
  ```

- **Trader Impact**: 🔥🔥 HIGH - Spread = execution cost
- **Effort**: 1 hour

### 2.3 Implement VWAP Bands
- **File**: `TickAggregate.java`
- **Add**:
  ```java
  private List<Double> priceHistory = new ArrayList<>();  // For std dev calculation

  // In update():
  priceHistory.add(tick.getLastRate());

  // After window:
  public double getVWAPStdDev() {
      if (priceHistory.isEmpty()) return 0.0;
      double mean = vwap;
      double variance = priceHistory.stream()
          .mapToDouble(p -> Math.pow(p - mean, 2))
          .average()
          .orElse(0.0);
      return Math.sqrt(variance);
  }

  public double getVWAPUpperBand() { return vwap + 2 * getVWAPStdDev(); }
  public double getVWAPLowerBand() { return vwap - 2 * getVWAPStdDev(); }
  ```

- **File**: `InstrumentCandle.java` - Add fields:
  ```java
  private Double vwapUpperBand;
  private Double vwapLowerBand;
  private Double vwapStdDev;
  private String vwapSignal;  // "OVERBOUGHT" / "OVERSOLD" / "NEUTRAL"
  ```

- **Trader Impact**: 🔥🔥 HIGH - Entry/exit signals
- **Effort**: 1 hour

### 2.4 Add Market Depth Slope
- **File**: `OrderbookAggregate.java`
- **Add**:
  ```java
  // Calculate depth slope (liquidity curve)
  private double calculateDepthSlope(List<OrderBookSnapshot.OrderBookLevel> levels, boolean isBid) {
      if (levels == null || levels.size() < 3) return 0.0;

      // Linear regression: cum_qty = slope * (price - best_price) + intercept
      double bestPrice = levels.get(0).getPrice();
      long cumQty = 0;
      double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
      int n = Math.min(10, levels.size());

      for (int i = 0; i < n; i++) {
          double priceDiff = Math.abs(levels.get(i).getPrice() - bestPrice);
          cumQty += levels.get(i).getQuantity();

          sumX += priceDiff;
          sumY += cumQty;
          sumXY += priceDiff * cumQty;
          sumX2 += priceDiff * priceDiff;
      }

      // slope = (n*sumXY - sumX*sumY) / (n*sumX2 - sumX*sumX)
      double numerator = n * sumXY - sumX * sumY;
      double denominator = n * sumX2 - sumX * sumX;

      return denominator != 0 ? numerator / denominator : 0.0;
  }

  // In updateWithSnapshot():
  bidDepthSlope = calculateDepthSlope(orderbook.getAllBids(), true);
  askDepthSlope = calculateDepthSlope(orderbook.getAllAsks(), false);
  ```

- **File**: `InstrumentCandle.java` - Add fields:
  ```java
  private Double bidDepthSlope;   // qty/tick (higher = more liquid)
  private Double askDepthSlope;
  private String liquiditySignal;  // "LIQUID" / "ILLIQUID" based on slope
  ```

- **Trader Impact**: 🔥 MEDIUM - Liquidity assessment
- **Effort**: 1.5 hours

---

## PHASE 3: OPTION-SPECIFIC FEATURES (FOR OPTIONS TRADERS)

### 3.1 Add Days to Expiry
- **File**: `InstrumentCandle.java`
- **Add**:
  ```java
  private Integer daysToExpiry;
  private Double hoursToExpiry;
  private Boolean isNearExpiry;  // true if < 3 days
  ```

- **File**: `UnifiedInstrumentCandleProcessor.java`
- **Implementation**:
  ```java
  // In buildInstrumentCandle():
  if (tick.getExpiry() != null && !tick.getExpiry().isEmpty()) {
      try {
          SimpleDateFormat sdf = new SimpleDateFormat("dd-MMM-yyyy");
          Date expiryDate = sdf.parse(tick.getExpiry());
          long now = System.currentTimeMillis();
          long expiryMillis = expiryDate.getTime();
          long diffMillis = expiryMillis - now;

          builder.daysToExpiry((int) (diffMillis / (24 * 60 * 60 * 1000)));
          builder.hoursToExpiry(diffMillis / (60.0 * 60 * 1000));
          builder.isNearExpiry(diffMillis < 3 * 24 * 60 * 60 * 1000);  // < 3 days
      } catch (Exception e) {
          log.warn("Failed to parse expiry date: {}", tick.getExpiry());
      }
  }
  ```

- **Trader Impact**: 🔥 MEDIUM - Time decay awareness
- **Effort**: 30 min

### 3.2 Add Option Greeks (Black-Scholes)
- **New File**: `src/main/java/com/kotsin/consumer/util/BlackScholesCalculator.java`
- **Implementation**:
  ```java
  public class BlackScholesCalculator {

      public static Greeks calculateGreeks(
          double underlyingPrice,
          double strikePrice,
          double timeToExpiry,  // in years
          double volatility,      // implied volatility (annualized)
          double riskFreeRate,   // e.g., 0.05 for 5%
          boolean isCall
      ) {
          double d1 = (Math.log(underlyingPrice / strikePrice) +
                      (riskFreeRate + 0.5 * volatility * volatility) * timeToExpiry) /
                      (volatility * Math.sqrt(timeToExpiry));
          double d2 = d1 - volatility * Math.sqrt(timeToExpiry);

          // Delta
          double delta = isCall ? cumulativeNormal(d1) : cumulativeNormal(d1) - 1;

          // Gamma (same for call/put)
          double gamma = normalDensity(d1) / (underlyingPrice * volatility * Math.sqrt(timeToExpiry));

          // Vega (same for call/put)
          double vega = underlyingPrice * normalDensity(d1) * Math.sqrt(timeToExpiry) / 100;  // per 1% IV change

          // Theta (time decay)
          double theta = isCall ?
              (-underlyingPrice * normalDensity(d1) * volatility / (2 * Math.sqrt(timeToExpiry)) -
               riskFreeRate * strikePrice * Math.exp(-riskFreeRate * timeToExpiry) * cumulativeNormal(d2)) / 365 :
              (-underlyingPrice * normalDensity(d1) * volatility / (2 * Math.sqrt(timeToExpiry)) +
               riskFreeRate * strikePrice * Math.exp(-riskFreeRate * timeToExpiry) * cumulativeNormal(-d2)) / 365;

          return new Greeks(delta, gamma, vega, theta);
      }

      private static double cumulativeNormal(double x) {
          // Approximation of cumulative normal distribution
          return 0.5 * (1 + erf(x / Math.sqrt(2)));
      }

      private static double normalDensity(double x) {
          return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
      }

      private static double erf(double z) {
          // Error function approximation (Abramowitz and Stegun)
          double t = 1.0 / (1.0 + 0.5 * Math.abs(z));
          double ans = 1 - t * Math.exp(-z*z - 1.26551223 +
                          t * (1.00002368 +
                          t * (0.37409196 +
                          t * (0.09678418 +
                          t * (-0.18628806 +
                          t * (0.27886807 +
                          t * (-1.13520398 +
                          t * (1.48851587 +
                          t * (-0.82215223 +
                          t * (0.17087277))))))))));
          return z >= 0 ? ans : -ans;
      }

      public static class Greeks {
          public final double delta;
          public final double gamma;
          public final double vega;
          public final double theta;

          public Greeks(double delta, double gamma, double vega, double theta) {
              this.delta = delta;
              this.gamma = gamma;
              this.vega = vega;
              this.theta = theta;
          }
      }
  }
  ```

- **File**: `InstrumentCandle.java` - Add fields:
  ```java
  private Double delta;    // Price sensitivity
  private Double gamma;    // Delta sensitivity
  private Double vega;     // IV sensitivity
  private Double theta;    // Time decay
  private Double impliedVolatility;  // From market price (if available)
  ```

- **File**: `UnifiedInstrumentCandleProcessor.java`
- **Challenge**: Need underlying price + IV
  - **Solution**: Maintain cache of underlying prices (from family candles)
  - **IV**: Use historical volatility as proxy (or fetch from IV feed if available)

- **Trader Impact**: 🔥🔥🔥 CRITICAL for options traders
- **Effort**: 4 hours (including IV estimation)

---

## PHASE 4: CODE QUALITY & ROBUSTNESS

### 4.1 Fix Thread Safety in Timestamp Extractors
- **Files**:
  - `OrderBookSnapshotTimestampExtractor.java`
  - `OpenInterestTimestampExtractor.java`

- **Change**:
  ```java
  // Replace:
  private long lastObservedTimestamp = -1L;

  // With:
  private volatile long lastObservedTimestamp = -1L;
  // OR
  private final AtomicLong lastObservedTimestamp = new AtomicLong(-1L);
  ```

- **Impact**: Defensive thread safety
- **Effort**: 10 min

### 4.2 Fix Kyle's Lambda Observation Count
- **File**: `OrderbookAggregate.java`
- **Change**: Remove `lambdaObsCount` field, always use `priceImpactHistory.size()`
  ```java
  // Remove field:
  // private long lambdaObsCount = 0;

  // In calculateKyleLambda():
  long n = priceImpactHistory.size();  // Use as source of truth
  ```

- **Impact**: Prevent desync bugs
- **Effort**: 15 min

### 4.3 Optimize Performance
- **File**: `OrderbookAggregate.java`
- **Changes**:
  ```java
  // Reuse depth maps instead of creating new ones
  @JsonIgnore private transient Map<Double, Integer> tempBidMap = new HashMap<>();
  @JsonIgnore private transient Map<Double, Integer> tempAskMap = new HashMap<>();

  private Map<Double, Integer> buildDepthMap(List<OrderBookSnapshot.OrderBookLevel> levels, Map<Double, Integer> reusableMap) {
      reusableMap.clear();  // Reuse instead of new HashMap()
      if (levels != null) {
          for (OrderBookSnapshot.OrderBookLevel level : levels) {
              if (level.getPrice() > 0 && level.getQuantity() > 0) {
                  double qPrice = quantize(level.getPrice(), getEffectiveTickSize());
                  reusableMap.merge(qPrice, level.getQuantity(), Integer::sum);
              }
          }
      }
      return reusableMap;
  }
  ```

- **Impact**: Reduce GC pressure (100-1000 orderbook updates/sec)
- **Effort**: 30 min

---

## PHASE 5: EDGE CASES & FINAL POLISH

### 5.1 Add Market Halt Detection
- **File**: `InstrumentCandle.java` - Add field:
  ```java
  private Boolean isMarketHalt;  // true if no ticks in window
  ```

- **File**: `UnifiedInstrumentCandleProcessor.java`:
  ```java
  // In buildInstrumentCandle():
  builder.isMarketHalt(tick.getTickCount() == 0);
  ```

### 5.2 Add Staleness Threshold for Orderbook Fallback
- **File**: `UnifiedInstrumentCandleProcessor.java` (fallback transformer):
  ```java
  // In orderbook fallback logic (after line 566):
  if (orderbook != null && isOrderbookFallback) {
      long staleness = windowedKey.window().end() - orderbook.getLastUpdateTimestamp();
      if (staleness > 300000) {  // 5 minutes
          log.warn("[OB-FALLBACK-STALE] {} | Using orderbook from {}ms ago (stale!)",
              tickOb.tick.getScripCode(), staleness);
          // Optional: Set orderbook = null if too stale
      }
  }
  ```

### 5.3 Add Quote-to-Trade Ratio
- **File**: `TickAggregate.java`:
  ```java
  private int quoteUpdates = 0;   // Ticks with no volume
  private int tradeUpdates = 0;   // Ticks with volume > 0

  // In update():
  if (deltaVol > 0) {
      tradeUpdates++;
  } else {
      quoteUpdates++;
  }

  public double getQuoteToTradeRatio() {
      return tradeUpdates > 0 ? (double) quoteUpdates / tradeUpdates : 0.0;
  }
  ```

- **File**: `InstrumentCandle.java` - Add field:
  ```java
  private Double quoteToTradeRatio;  // High = liquid market (market makers active)
  ```

---

## IMPLEMENTATION TIMELINE

### Day 1: Critical Fixes + Volume Profile
- [x] Fix OFI bid band (10 min) ✅
- [x] Fix OI change fallback (15 min) ✅
- [ ] Fix VWAP calculation (30 min)
- [ ] Verify orderbook keys (5 min)
- [ ] Implement Volume Profile (3 hours)
- [ ] Add tick-level spread metrics (1 hour)
**Total**: ~5 hours

### Day 2: VWAP Bands, Depth Slope, Greeks
- [ ] Implement VWAP bands (1 hour)
- [ ] Add market depth slope (1.5 hours)
- [ ] Add days to expiry (30 min)
- [ ] Implement Black-Scholes greeks (4 hours)
**Total**: ~7 hours

### Day 3: Polish, Performance, Testing
- [ ] Fix thread safety (10 min)
- [ ] Fix Kyle's Lambda obs count (15 min)
- [ ] Optimize performance (30 min)
- [ ] Add edge case handling (1 hour)
- [ ] Add quote-to-trade ratio (30 min)
- [ ] Comprehensive testing (3 hours)
- [ ] Documentation updates (1 hour)
**Total**: ~6 hours

**GRAND TOTAL**: ~18 hours of focused development

---

## EXPECTED SCORES AFTER IMPLEMENTATION

| Parameter | Current | Target | How We Get There |
|-----------|---------|--------|------------------|
| **Code Quality** | 8.5/10 | 10/10 | Thread safety fixes, performance optimization, Kyle's Lambda fix |
| **Quant Accuracy** | 7/10 | 10/10 | OFI fix, VWAP fix, Greeks (Black-Scholes) |
| **Trader Utility** | 7.5/10 | 10/10 | Volume Profile, VWAP Bands, Spread metrics, Depth Slope, Greeks |

---

## RISK MITIGATION

1. **Testing Strategy**: Test each phase before moving to next
2. **Rollback Plan**: Git commits after each major feature
3. **Backward Compatibility**: All new fields are nullable (won't break existing consumers)
4. **Performance**: Monitor state store sizes (Volume Profile adds histogram data)

---

**Next Step**: Shall I proceed with Day 1 implementation (Critical Fixes + Volume Profile)?
