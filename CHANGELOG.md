# Changelog

All notable changes to the StreamingCandle module are documented in this file.

## [Unreleased] - 2025-12-28

### 🐛 Bug Fixes (Critical)

#### Bug #1: CuratedSignalProcessor `activeBreakouts` Memory Leak
- **File**: `CuratedSignalProcessor.java`
- **Problem**: Breakouts added to map but never removed if price reversed without retest
- **Impact**: Memory leak + stale breakouts triggering false signals days later
- **Fix**: 
  - Added `breakoutTimestamps` map to track breakout detection time
  - Added `cleanupExpiredBreakouts()` method with 30-minute TTL
  - Breakouts now auto-expire if not retested within window

#### Bug #2: FamilyCandle `getSignalStrength()` OI Double-Counting
- **File**: `FamilyCandle.java`
- **Problem**: OI signal always counted as "confirmed" even if it disagreed with price direction
- **Impact**: False high-confidence signals when OI diverges from price
- **Fix**: OI signal now only confirms if direction matches equity price direction

#### Bug #3: RetestDetector Only Works for LONGs
- **File**: `RetestDetector.java`
- **Problem**: `detectRetest()` only handled bullish scenarios
- **Impact**: Missing 50% of potential trades (shorts)
- **Fix**: 
  - Added `detectBearishRetest()` method
  - Checks: high touches pivot from below, closes below pivot, negative OFI (selling pressure)

#### Bug #4: VCPCalculator `VCPResult.empty()` Returns runwayScore=1.0
- **File**: `VCPCalculator.java`
- **Problem**: No-data scenario returned "runway is clear" (1.0) - dangerous
- **Impact**: No-data looked like "safe to trade"
- **Fix**: 
  - Changed `runwayScore` to `0` in empty result
  - Added `isEmpty()` method to distinguish no-data from calculated state

#### Bug #5: InstrumentCandle `isValid()` Missing NaN/Infinity Checks
- **File**: `InstrumentCandle.java`
- **Problem**: Bad data from upstream could propagate (NaN/Infinity values)
- **Impact**: Silent corruption of calculations
- **Fix**:
  - Added `Double.isNaN()` and `Double.isInfinite()` checks for all price fields
  - Added `getValidationErrors()` method for detailed diagnostics

#### Bug #6: CuratedSignalProcessor Hardcoded NIFTY50
- **File**: `CuratedSignalProcessor.java`
- **Problem**: All securities used NIFTY50 regime, even BANKNIFTY components, midcaps, etc.
- **Impact**: Wrong regime alignment for non-NIFTY50 stocks
- **Fix**: 
  - Added `getRelevantIndex()` method
  - Uses `SecurityRegime.parentIndexCode` when available
  - Supports `SCRIP_TO_INDEX_MAP` for explicit mappings
  - Falls back to NIFTY50 only when no better option

#### Bug #7: IPU Direction Votes Cancellation
- **File**: `IPUCalculator.java`
- **Problem**: Weighted sum of signed votes could cancel out (3 bullish + 2 bearish = weak)
- **Impact**: Strong directional consensus appeared weak
- **Fix**: 
  - Rewrote to count bullish vs bearish factors separately
  - `directionalConviction` now based on `agreementRatio = max(bullish%, bearish%)`
  - `majorityDirection` explicitly tracks winning direction

#### Bug #8: PCR `calculateVolumeBasedPCR()` Never Called
- **File**: `PCRCalculator.java`
- **Problem**: Only OI-based PCR was used, volume PCR ignored
- **Impact**: Intraday analysis used stale overnight OI instead of current volume
- **Fix**:
  - Added `PCRResult` class combining both OI and volume PCR
  - Added `calculateComprehensive(options, isIntraday)` method
  - Intraday: 70% volume PCR, 30% OI PCR
  - Swing: 30% volume PCR, 70% OI PCR

### 🐛 Bug Fixes (Medium)

#### Bug #9: ATR Fallback Uses Fixed 1% of Price
- **File**: `VCPCalculator.java`
- **Problem**: Fixed % doesn't account for instrument volatility
- **Fix**: Now calculates from actual candle range data, logs warning when using fallback

#### Bug #10: VCP Weight Validation Missing
- **File**: `VCPCalculator.java`
- **Problem**: Misconfigured weights could produce scores > 1.0
- **Fix**: Added weight normalization with warning log when sum ≠ 1.0

#### Bug #11: IPU Zero Volume Returns 0 Not NaN
- **File**: `IPUCalculator.java`
- **Problem**: Zero volume returned 0, looking like "balanced flow" instead of "no data"
- **Fix**: Returns empty output when buyVolume == 0 && sellVolume == 0 && volume == 0

#### Bug #12: Gaussian Volume Distribution is Arbitrary
- **File**: `VCPCalculator.java`
- **Problem**: Gaussian distribution centered on VWAP with arbitrary std dev
- **Fix**: 
  - Changed to triangular distribution (more realistic)
  - Added `estimatedDataRatio` tracking
  - Logs warning when > 50% of data is estimated

#### Bug #13: VCP Kyle Lambda Null Handling
- **File**: `VCPCalculator.java`
- **Problem**: Null Kyle Lambda caused issues in penetration difficulty calculation
- **Fix**: Added null/NaN check with debug logging, treats as zero (no liquidity impact data)

### ✅ Previously Fixed (From Earlier Session)

| Bug | File | Fix |
|-----|------|-----|
| IPU Static Cache No TTL | `IPUProcessor.java` | Replaced `ConcurrentHashMap` with `TTLCache` (10-min TTL) |
| VCP Min History = 5 | `VCPProcessor.java` | Increased to `max(20, lookback/3)` |
| OFI Returns 0 for Null | `OFICalculator.java` | Now returns NaN with `OFIResult.hasValidPreviousData()` |
| FuturesBuildupDetector Fixed % | `FuturesBuildupDetector.java` | Added `detectWithATR()` for ATR-relative thresholds |

### 📁 Files Modified

| File | Lines Changed | Summary |
|------|--------------|---------|
| `PCRCalculator.java` | +60 | New `PCRResult` class, `calculateComprehensive()` |
| `IPUProcessor.java` | +25 | TTLCache initialization using project's `TTLCache` |
| `VCPProcessor.java` | +5 | Minimum history check |
| `VCPCalculator.java` | +80 | Triangular distribution, weight validation, ATR fallback |
| `OFICalculator.java` | +50 | `OFIResult` class, NaN handling |
| `FuturesBuildupDetector.java` | +40 | `detectWithATR()` method |
| `RetestDetector.java` | +120 | `detectBearishRetest()` method |
| `IPUCalculator.java` | +35 | Zero volume check, count-based agreement |
| `FamilyCandle.java` | +15 | OI alignment check in `getSignalStrength()` |
| `CuratedSignalProcessor.java` | +60 | Breakout expiry, `getRelevantIndex()` |
| `InstrumentCandle.java` | +30 | NaN/Infinity validation, `getValidationErrors()` |

### 🧪 Tests Added

- `BugFixValidationTest.java` - Comprehensive test suite for all bug fixes
  - PCR volume-based calculation tests
  - TTLCache expiry and LRU eviction tests
  - VCP empty runway score test
  - OFI NaN handling tests
  - FuturesBuildupDetector ATR-relative tests
  - RetestDetector bullish and bearish tests
  - FamilyCandle OI alignment tests
  - InstrumentCandle validation tests
  - IPU direction votes tests

### 🔧 Technical Debt Addressed

1. **Memory Safety**: All unbounded caches now have TTL and size limits
2. **Data Integrity**: NaN/Infinity propagation blocked at InstrumentCandle level
3. **Short Trade Support**: RetestDetector now handles both long and short scenarios
4. **Signal Quality**: OI signals only confirm when aligned with price direction
5. **Dynamic Configuration**: Index regime lookup now respects security's sector

### ⚠️ Breaking Changes

None. All changes are backward compatible. New methods are additions, not replacements.

### 📋 Migration Notes

1. **PCRCalculator**: Use `calculateComprehensive(options, isIntraday)` instead of `calculate(options)` for better intraday analysis
2. **RetestDetector**: Use `detectBearishRetest()` for short setups
3. **InstrumentCandle**: Check `isValid()` before processing candles
4. **VCPResult**: Check `isEmpty()` to distinguish no-data from calculated results

---

## [Previous Versions]

See git history for changes prior to this audit.

