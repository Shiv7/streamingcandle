## Option Trading Pipeline - Refactor Plan (StreamingCandle + IndicatorCalculator)

### Goals
- Preserve equity-family concept: one enriched stream per equity; derivatives grouped under the equity.
- Provide finalized time-based candles (1/2/3/5/15/30) for indicator computation without schema breaks.
- Compute option analytics (PCR, IV, Greeks) downstream in IndicatorCalculator using option-level inputs.
- StrategyModule consumes indicators and family context, writes all timeframes to Redis, and runs multi-timeframe family-aware strategies.

---

### Responsibilities by Module

1) streamingcandle (Kafka Streams)
- Keep enriched stream one-message-per-equity (partials only). No OI aggregation here.
- Emit finalized candles on window close:
  - Topics: `1-min-candle`, `2-min-candle`, `3-min-candle`, `5-min-candle`, `15-min-candle`, `30-min-candle`
  - Key: `equityScripCode` (underlying equity; family preserved)
  - Payload (IC-compatible Candlestick + optional extras):
    - Required: `scripCode`, `exchange`, `exchangeType`, `windowStart`, `windowEnd`, `isComplete=true`, `open`, `high`, `low`, `close`, `volume`
    - Optional extras (behind flag): `vwap`, `hlc3`, `logReturnFromPrevBar`, `ticksInWindow`, `windowLatencyMs`
- Feature flags (application.properties):
  - `stream.outputs.candles.enabled=true`
  - `stream.outputs.enriched.enabled=true`
- Derivative mapping: all ticks keyed to underlying equity via `MongoInstrumentFamilyService.resolveFamily`.

2) indicatorCalculator (Spring Cloud Stream)
- Consume the classic candle topics above (bindings already exist).
- Compute technical indicators per timeframe from finalized candles.
- Compute option analytics (moved/kept here):
  - PCR: per equity + expiry, from option-level OI stream (and/or existing upstream OI topics if present).
  - IV/Greeks (delta, gamma, vega, theta):
    - Inputs: option mid (from orderbook), last price fallback; underlying spot (equity candle close or mid); expiry; r,q from config.
    - Sanity bounds and fallbacks; publish per-option metrics per timeframe.
- Publish indicator outputs to IC’s existing indicator topics (unchanged), or new compact `option-analytics-{tf}` if needed.

3) StrategyModule
- Consume indicator outputs and enriched equity-family context.
- Write Redis keys:
  - `equity:{scripCode}:{tf}` → candle indicators + meta
  - `option:{token}:{tf}` → IV/Greeks + OI snapshot (if available from IC)
  - `equity:{scripCode}:expiry:{yyyy-MM-dd}:{tf}` → PCR, ATM IV, skew/smile
- Run multi-timeframe family-aware strategies (equity with its futures/options).

---

### Data Contracts (Non-Breaking)

Enriched (unchanged, partials only)
- One message per equity key.
- Derivatives grouped under `instrumentFamily`.
- Optional future additions gated by flags and `messageVersion=2.1` when present.

Finalized Candlestick (classic topics)
- Key: `equityScripCode`
- Required fields: `scripCode`, `exchange`, `exchangeType`, `windowStart`, `windowEnd`, `isComplete=true`, `open`, `high`, `low`, `close`, `volume`
- Optional fields (flagged): `vwap`, `hlc3`, `logReturnFromPrevBar`, `ticksInWindow`, `windowLatencyMs`

Option Analytics (in IndicatorCalculator)
- PCR per equity+expiry per timeframe: `{expiry, ceOi, peOi, pcrOi, ceVol, peVol, pcrVol}`
- Per-option IV/Greeks per timeframe: `{token, strike, optionType, expiry, iv, delta, gamma, vega, theta, mid, last, volume, daysToExpiry}`

---

### Operational Notes
- Kafka Streams app-id: `unified-market-processor1` (fresh group) and `auto.offset.reset=earliest`.
- Derivative ticks are mapped to equity key for candles and enriched; option analytics stay option-scoped in IC.
- No deletions or renames of existing fields; only additive fields behind flags.

---

### Rollout Plan
1) streamingcandle
   - Implement window-close emission to classic candle topics (with required fields only).
   - Keep enriched unchanged; ensure derivative→equity keying is in place.
   - Add feature flags, default ON for candles.

2) indicatorCalculator
   - Verify existing bindings consume the finalized candle topics (no code changes needed initially).
   - Add PCR + IV/Greeks computation (separate processors), publish to indicator outputs.
   - Optional optimization: read enriched for family context instead of recomputing joins.

3) StrategyModule
   - Consume indicators and enriched; write Redis for all timeframes and families.
   - Validate multi-timeframe strategies across equity/futures/options.

---

### Acceptance Criteria
- Candle topics carry finalized bars only, keyed by equity.
- IndicatorCalculator computes per-timeframe indicators from those bars without schema changes.
- Option analytics (PCR, IV/Greeks) produced by IC per timeframe; StrategyModule uses them along with enriched.
- Enriched remains one-message-per-equity; family grouping intact.


