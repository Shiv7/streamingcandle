# Family Candle Migration - Nuclear Option B

## Migration Status

### ✅ COMPLETED
1. **IPUProcessor** - Migrated from `unified-candle-{5m,15m,30m}` to `family-candle-{5m,15m,30m}`
2. **VCPProcessor** - Migrated from `unified-candle-{5m,15m,30m}` to `family-candle-{5m,15m,30m}`

### 🔄 IN PROGRESS
3. **RegimeProcessor** - Migrate from `unified-candle-30m` to `family-candle-30m`
4. **FMAProcessor** - Migrate from `KafkaTopics.UNIFIED_5M` to `KafkaTopics.FAMILY_CANDLE_5M`
5. **UnifiedSignalProcessor** - Migrate from `KafkaTopics.UNIFIED_5M` to `KafkaTopics.FAMILY_CANDLE_5M`
6. **CuratedSignalProcessor** - Migrate from `unified-candle-{1m,2m,3m}` to `family-candle-{1m,2m,3m}`

### ⏳ PENDING - Legacy Code Deletion
7. Delete `EnrichedCandlestick.java`
8. Delete `CandlestickProcessor.java`
9. Delete `OrderbookProcessor.java`
10. Delete `OIProcessor.java`
11. Delete `UnifiedCandleProcessor.java`
12. Delete `OrderbookAggregate.java`
13. Delete `OIAggregate.java`
14. Delete `UnifiedCandle.java`
15. Remove legacy topic configs from `application.properties`

## Migration Pattern

For each processor:
1. Add imports: `FamilyCandle`, `InstrumentCandle`
2. Update JavaDoc comments
3. Change default topic prefix from `unified-candle-` to `family-candle-`
4. Change stream consumption from `UnifiedCandle.serde()` to `FamilyCandle.serde()`
5. Update processor signature from `Processor<String, UnifiedCandle, ...>` to `Processor<String, FamilyCandle, ...>`
6. Update `process()` method to extract equity and convert to UnifiedCandle
7. Add `convertToUnifiedCandle()` helper method
8. Update comments referencing UnifiedCandle to FamilyCandle

## Data Compatibility

**100% Compatible** - All fields from UnifiedCandle are present in InstrumentCandle:
- OHLCV: ✅
- Volume Profile: ✅
- Imbalance Metrics: ✅
- Orderbook Metrics: ✅ (optional, may be null)
- OI Metrics: ✅ (optional, may be null)

## Zero Breakage Strategy

- Keep existing calculator logic unchanged
- Convert `InstrumentCandle` → `UnifiedCandle` at processor boundary
- All downstream modules continue to work with `UnifiedCandle` objects
- State stores continue to use `UnifiedCandle` serialization
- Output topics remain unchanged (ipu-signals-*, vcp-combined, etc.)
