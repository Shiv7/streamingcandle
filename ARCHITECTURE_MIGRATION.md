# Architecture Migration Guide

## Overview

This document outlines the migration from **Legacy Architecture** to **New Unified Architecture** in the StreamingCandle system.

---

## 🏗️ ARCHITECTURE COMPARISON

### LEGACY ARCHITECTURE (Pre-2025)

**Data Models**:
- `EnrichedCandlestick` (1040 lines) - God object with too many responsibilities
- Separate `OrderbookAggregate`, `OIAggregate` classes
- `UnifiedCandle` - Manual JOIN of 3 streams

**Processors** (Independent Streams):
1. `CandlestickProcessor` - Ticks → Enriched Candles
2. `OrderbookProcessor` - Orderbook → Microstructure Signals
3. `OIProcessor` - OI → OI Metrics
4. `UnifiedCandleProcessor` - JOINS the above 3

**Issues**:
- ❌ God class violates SRP
- ❌ Manual coordination of 3 streams
- ❌ No cross-instrument analysis
- ❌ Hardcoded VPIN bucket size (10,000)
- ❌ Duplicate caching logic

---

### NEW UNIFIED ARCHITECTURE (2025+)

**Data Models**:
- `InstrumentCandle` (287 lines) - Clean, single-purpose candle model
- `FamilyCandle` - Cross-instrument family grouping
- LEFT JOIN semantics (never discard data)

**Processors** (Unified Stream):
1. `UnifiedInstrumentCandleProcessor` - 3 topics → InstrumentCandle (LEFT JOIN)
2. `FamilyCandleProcessor` - InstrumentCandle → FamilyCandle (grouping)
3. `TimeframeAggregator` - 1m → 12 timeframes

**Improvements**:
- ✅ Single responsibility classes
- ✅ Adaptive VPIN (instrument-specific bucket sizes)
- ✅ Cross-instrument OI signal detection
- ✅ Consolidated caching (FamilyCacheAdapter)
- ✅ Circuit breaker for external APIs
- ✅ Thread-safe concurrent operations

---

## 📊 CURRENT STATE (2025-12-27)

### ✅ COMPLETED MIGRATIONS:

1. **Thread Safety Fixes**
   - ✅ UnifiedInstrumentCandleProcessor: HashMap → ConcurrentHashMap
   - ✅ FamilyCacheAdapter: Removed String.intern() anti-pattern
   - ✅ TTLCache: volatile → AtomicLong
   - ✅ Added @PreDestroy hooks

2. **Fault Tolerance**
   - ✅ ScripFinderClient: Added Circuit Breaker

3. **Code Consolidation**
   - ✅ FamilyCandleProcessor: Now uses FamilyCacheAdapter (removed duplicate code)

### ⏳ IN PROGRESS:

4. **Dead Code Removal**
   - ⏳ Remove unused legacy components
   - ⏳ Complete EnrichedCandlestick → InstrumentCandle migration

### 🔜 PENDING:

5. **Configuration Management**
   - 🔜 Externalize all hardcoded values
   - 🔜 Create @ConfigurationProperties classes

6. **EnrichedCandlestick Refactoring**
   - 🔜 Extract VPINCalculator (reuse AdaptiveVPINCalculator)
   - 🔜 Extract ImbalanceBarCalculator
   - 🔜 Extract VolumeProfileCalculator
   - 🔜 Extract TradeClassifier

7. **SOLID Compliance**
   - 🔜 Extract interfaces (IFamilyDataProvider, etc.)
   - 🔜 Add null safety annotations

8. **Testing & Validation**
   - 🔜 Integration tests for full data flow
   - 🔜 Performance benchmarks

---

## 🎯 MIGRATION STRATEGY

### PHASE 1: Parallel Operation (CURRENT)
- Both legacy and new processors run in parallel
- Topics: legacy (`candle-ohlcv-*`) + new (`instrument-candle-*`, `family-candle-*`)
- Downstream consumers can choose which to consume

### PHASE 2: Gradual Migration
- Update strategy modules to consume from new topics
- Monitor data quality and performance
- Keep legacy as fallback

### PHASE 3: Legacy Deprecation
- Mark legacy processors as `@Deprecated`
- Set sunset date (e.g., Q2 2025)
- Disable legacy processors in production

### PHASE 4: Complete Removal
- Delete legacy code: `EnrichedCandlestick`, old processors
- Clean up unused topics
- Archive historical data

---

## 🔧 REFACTORING ROADMAP

### Component Breakdown

| Component | Status | Lines | Target | Owner |
|-----------|--------|-------|--------|-------|
| EnrichedCandlestick | 🔴 Needs Refactoring | 1040 | 200 | TBD |
| UnifiedInstrumentCandleProcessor | ✅ Complete | 540 | - | Done |
| FamilyCandleProcessor | ✅ Complete | 350 | - | Done |
| FamilyCacheAdapter | ✅ Complete | 258 | - | Done |
| ScripFinderClient | ✅ Complete | 235 | - | Done |

### Extracted Components (Planned)

From `EnrichedCandlestick` → Extract:
1. `AdaptiveVPINCalculator` ✅ (Already exists in domain!)
2. `ImbalanceBarCalculator` 🔜
3. `VolumeProfileCalculator` 🔜
4. `TradeClassifier` 🔜
5. `CandleData` (Pure data model) 🔜

---

## 📈 DATA FLOW (New Architecture)

```
┌─────────────────────────────────────────────────────────────┐
│                    KAFKA INPUT TOPICS                        │
│  1. forwardtesting-data  2. Orderbook  3. OpenInterest      │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│      UnifiedInstrumentCandleProcessor (LEFT JOIN)            │
│  • Tick OHLCV (mandatory)                                    │
│  • Orderbook metrics (optional) → OFI, Kyle's Lambda         │
│  • OI metrics (optional) → OI OHLC                           │
│  • Adaptive VPIN (per-instrument bucket size)                │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼ instrument-candle-1m
┌─────────────────────────────────────────────────────────────┐
│           FamilyCandleProcessor (Grouping)                   │
│  • Group by equity family ID                                 │
│  • 1 Equity + 1 Future + 4 Options                           │
│  • Calculate cross-instrument metrics                        │
│  • OI Signal Detection (6 types)                             │
│  • PCR, Spot-Future Premium, etc.                            │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼ family-candle-1m
┌─────────────────────────────────────────────────────────────┐
│          TimeframeAggregator (Rollup)                        │
│  1m → 2m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 1d, 1wk, 1mo       │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼ family-candle-{tf} (12 timeframes)
┌─────────────────────────────────────────────────────────────┐
│              STRATEGY MODULES (Downstream)                   │
│  • IPU, VCP, Regime, Signals, FMA, Curated, etc.            │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔒 BENEFITS OF NEW ARCHITECTURE

### 1. **Performance**
- Single-pass processing (3 topics → 1 unified candle)
- No repeated JOINs across processors
- Adaptive algorithms tuned per instrument

### 2. **Reliability**
- LEFT JOIN: Never discard data
- Circuit breakers for external APIs
- Thread-safe concurrent operations
- Proper resource cleanup (@PreDestroy)

### 3. **Maintainability**
- Small, focused classes (SRP compliant)
- No code duplication
- Clear separation of concerns
- Comprehensive documentation

### 4. **Functionality**
- Cross-instrument analysis (OI signals)
- Adaptive VPIN (not one-size-fits-all)
- 6 OI signal types for smart money detection
- 12 timeframes from single 1m source

---

## 🚨 BREAKING CHANGES

### For Downstream Consumers:

**OLD Topic Names**:
```
candle-ohlcv-1m
candle-ohlcv-5m
orderbook-ohlv-1m
oi-ohlv-1m
unified-candle-1m
```

**NEW Topic Names**:
```
instrument-candle-1m
family-candle-1m
family-candle-2m
family-candle-5m
... (12 timeframes)
```

**Migration Guide**:
1. Update Kafka consumer subscriptions
2. Change data model from `EnrichedCandlestick` → `InstrumentCandle` or `FamilyCandle`
3. Use `hasOrderbook()` and `hasOI()` flags before accessing optional data
4. Handle new OI signal types (BULLISH_ACCUMULATION, etc.)

---

## 📞 SUPPORT

Questions? Contact:
- Architecture: [Architecture Team]
- Migration Support: [DevOps Team]
- Data Quality: [Data Engineering Team]

---

**Last Updated**: 2025-12-27
**Version**: 2.0.0
**Status**: Migration In Progress
