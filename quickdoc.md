• StreamingCandle: End‑to‑End Documentation

Overview

- Purpose: Real‑time per‑instrument candle aggregation and enrichment (ticks, orderbook, OI) across multiple timeframes with Kafka
  Streams.
- Timeframes: 1m, 2m, 3m, 5m, 15m, 30m.
- Outputs: Unified window messages with OHLCV, orderbook/microstructure signals, imbalance bars, and OI fields.

Architecture

- Spring Boot app entry: src/main/java/com/kotsin/consumer/ConsumerApplication.java
- Topology builder: src/main/java/com/kotsin/consumer/processor/TopologyConfiguration.java
- Per‑instrument state: src/main/java/com/kotsin/consumer/service/InstrumentStateManager.java
- Accumulators:
    - Candles (OHLCV): src/main/java/com/kotsin/consumer/processor/CandleAccumulator.java
    - Orderbook depth: src/main/java/com/kotsin/consumer/processor/OrderbookDepthAccumulator.java
    - Microstructure: src/main/java/com/kotsin/consumer/processor/MicrostructureAccumulator.java
    - Imbalance bars: src/main/java/com/kotsin/consumer/processor/ImbalanceBarAccumulator.java
    - Open interest window: src/main/java/com/kotsin/consumer/processor/OiAccumulator.java
- Detectors:
    - Spoofing: src/main/java/com/kotsin/consumer/service/SpoofingDetectionService.java
    - Iceberg: src/main/java/com/kotsin/consumer/service/IcebergDetectionService.java
- Models:
    - Tick: src/main/java/com/kotsin/consumer/model/TickData.java
    - Orderbook: src/main/java/com/kotsin/consumer/model/OrderBookSnapshot.java
    - OI: src/main/java/com/kotsin/consumer/model/OpenInterest.java
    - Unified output: src/main/java/com/kotsin/consumer/model/UnifiedWindowMessage.java
    - Candle output: src/main/java/com/kotsin/consumer/model/InstrumentCandle.java
- Transformers:
    - Cum→Delta vol: src/main/java/com/kotsin/consumer/transformers/CumToDeltaTransformer.java
    - OI delta: src/main/java/com/kotsin/consumer/transformers/OiDeltaTransformer.java
- Window rotation/alignment utility: src/main/java/com/kotsin/consumer/processor/WindowRotationService.java
- Trading hours validation: src/main/java/com/kotsin/consumer/service/TradingHoursValidationService.java
- Kafka Streams config: src/main/java/com/kotsin/consumer/config/KafkaConfig.java

Data Flow

- Inputs (configurable):
    - Ticks: forwardtesting-data
    - Orderbook: Orderbook
    - OpenInterest: OpenInterest
- Ticks pipeline:
    - Deserialize TickData; TickData parses TickDt to event-time in ms.
    - CumToDeltaTransformer converts cumulative TotalQty → DeltaQty per instrument and flags the first tick/reset.
    - Filter keeps ticks with non‑null DeltaQty and not resetFlag=true.
    - Re‑keys by scripCode for per‑instrument aggregation.
- Orderbook pipeline:
    - Deserialize OrderBookSnapshot, run parseDetails() to normalize levels.
- Merge ticks + orderbook (by scripCode) per timeframe window:
    - Use 1‑minute Kafka TimeWindows (suppress until window closes), with configurable grace.
    - Aggregate into InstrumentState:
        - CandleAccumulator (OHLCV, vwap, buy/sell vol, delta metrics).
        - MicrostructureAccumulator (OFI, spread, microprice, etc.).
        - ImbalanceBarAccumulator (VIB/DIB/TRB/VRB).
        - OrderbookDepthAccumulator (spread, depth, VWAPs, slopes, weighted imbalance).
- On window close:
    - InstrumentStateManager.forceCompleteWindows(kafkaWindowEnd) marks windows complete if they ended.
    - Extract finalized InstrumentCandle (merges per-window orderbook metrics + ongoing global detectors).
    - Enrich with OI (LeftJoin against OI KTable, with safety for future OI).
    - Map to UnifiedWindowMessage and produce to per‑timeframe output topic.

Outputs

- Output topics (configurable):
    - candle-complete-1m, candle-complete-2m, candle-complete-3m, candle-complete-5m, candle-complete-15m, candle-complete-30m-v2
- Unified message fields:
    - Header: instrument info, timeframe, window start/end, IST strings
    - Candle: open/high/low/close, volume, buy/sell volume, vwap, tickCount, isComplete
    - OrderbookSignals: ofi, depth imbalance, spreadAvg, depth sums, bid/ask VWAP, microprice, iceberg flags, spoofingCount
    - ImbalanceBars: structure as per accumulator
    - OpenInterest: oiClose, oiChange, oiChangePercent

Configuration

- Base props: src/main/resources/application.properties
    - Kafka bootstrap, Streams properties, state-dir, serdes, guarantees, optimizations, exception handler, producer timestamp
      type.
    - Input topics:
        - unified.input.topic.ticks
        - unified.input.topic.orderbook
        - unified.input.topic.oi
    - Grace:
        - unified.streams.window.grace.period.seconds (default 30; increase if small lateness present)
    - Output topics:
        - stream.outputs.candles.*
- Profiles:
    - Default: production reads from non‑replay topics (as configured in application.properties).

Event Time & Alignment

- Tick timestamp source:
    - From TickDt "/Date(ms)/" (preferred); TickData.parseTimestamp() sets timestamp.
- Orderbook timestamp source: from OrderBookSnapshot.receivedTimestamp.
- Market-aligned 9:15 shift:
    - Currently disabled; src/main/java/com/kotsin/consumer/time/MarketAlignedTimestampExtractor.java returns raw event-times.
    - To re-enable market alignment, restore a 15‑minute offset for NSE and use WindowRotationService.rotateCandleIfNeeded(...,
      offsetMinutes=15) for relevant timeframes.

Windowing & Grace

- Per timeframe, Kafka Streams uses 1‑minute windows and suppress() until window closes; app then rotates accums and extracts final
  candles.
- Grace period (unified.streams.window.grace.period.seconds) defines how late after window end events can arrive and still be
  included. If orderbook events advance stream-time, ticks for prior periods can appear “late” and get dropped with small grace.
  Increase grace for replay/testing to avoid late drops.

Accumulators & Detectors

- CandleAccumulator: OHLCV, buy/sell split (tick rule + quote rule), vwap, tickCount; toCandleData(String exch, String exchType).
- OrderbookDepthAccumulator: computes spread, depth sums, VWAPs, weighted imbalance, slopes; delegates to Spoofing/Iceberg;
  serializable-state safe.
- MicrostructureAccumulator: OFI, microprice, spread bins; analysis window per timeframe.
- ImbalanceBarAccumulator: volume/dollar/tick-run bars; signals per timeframe.
- SpoofingDetectionService: detects large, fast-disappearing orders; logs demoted to DEBUG to reduce noise (events still captured).
- IcebergDetectionService: flags iceberg patterns based on hidden size behavior.

Instrument State & Extraction

- InstrumentStateManager holds per‑timeframe accumulators in EnumMap<Timeframe, ...>.
- On tick:
    - Rotates candle accumulator per timeframe (clock-aligned at present), adds tick to candle/micro/imbalance accumulators.
- On orderbook:
    - Updates global orderbook accumulator (never reset) and per-window accumulator (reset each window).
- On extraction:
    - Combines candle data + microstructure + imbalance + merged orderbook (window metrics + global detection) into
      InstrumentCandle.

Running

- Prerequisites: Kafka broker, topics created, Java 17.
- Start app:
    - mvn spring-boot:run
    - Watch logs in ./logs/streamingcandle.log
- Send sample data:
    - Script: scripts/push_sample_data.py
    - python3 scripts/push_sample_data.py --bootstrap localhost:9092
    - It publishes 6 ticks and multiple orderbooks around current IST minute to trigger 1m candle output.

Troubleshooting

- No candles emitted; “Window closed … hasComplete=false; scrip=null”
    - Causes:
        - App consuming from topics different from your producer. Align input topics.
        - Ticks dropped as late due to orderbook pushing stream-time forward. Increase unified.streams.window.grace.period.seconds
          (e.g., 60s) for tests.
        - First tick filtered as reset; provide at least two ticks in the first minute.
- Out-of-order KTable update for OI:
    - Harmless warning that OI table saw a newer then older timestamp; not a blocker for candles.
- Spoofing WARN spam:
    - Already demoted to DEBUG; adjust logging.level.com.kotsin.consumer.service=INFO (default) to suppress.
- Serializer error on repartition:
    - We anchor value serde with .repartition(Repartitioned.with(Serdes.String(), InstrumentCandle.serde())) to avoid default
      String serde; ensure that code path remains.

Production Notes

- Exactly‑once vs at‑least‑once: default is at‑least‑once; switch to exactly_once_v2 for end‑to‑end consistency (higher overhead).
- Grace selection: balance lateness vs latency. Larger grace increases completeness but defers window close.
- Monitoring & metrics: StreamMetrics (used in topology) increments emission counters; integrate with your monitoring stack if
  needed.

Extensibility

- Re‑enable market-aligned windows:
    - Add NSE 9:15 shift in MarketAlignedTimestampExtractor and pass offset to WindowRotationService for 30m (or other desired
      TFs).
- Add OpenInterest replay or live table semantics:
    - OI KTable is joined post-extraction to avoid advancing stream-time. Ensure event-time validation if strict control is needed.
- Add new detectors/signals:
    - Hook into OrderbookDepthAccumulator (serializable state) and extend UnifiedWindowMessage.OrderbookSignals.

Repository Layout (key files)

- App entry: src/main/java/com/kotsin/consumer/ConsumerApplication.java
- Topology: src/main/java/com/kotsin/consumer/processor/TopologyConfiguration.java
- State manager: src/main/java/com/kotsin/consumer/service/InstrumentStateManager.java
- Models: src/main/java/com/kotsin/consumer/model/*
- Accumulators: src/main/java/com/kotsin/consumer/processor/*Accumulator.java
- Transformers: src/main/java/com/kotsin/consumer/transformers/*
- Services: src/main/java/com/kotsin/consumer/service/*
- Config: src/main/java/com/kotsin/consumer/config/*
- Script: scripts/push_sample_data.py
- Config props: src/main/resources/application.properties
- Logging: src/main/resources/logback.xml

Key Tuning Properties

- unified.input.topic.*: Point to correct input topics.
- unified.streams.window.grace.period.seconds: Grace per timeframe branch (shared).
- spring.kafka.streams.*: Threads, state‑dir, optimization, retries, exception handler.
- stream.outputs.candles.*: Output topics for each timeframe.

This doc should get you from zero to emitting candles, explain how the topology is built and why, and outline the main failure
modes with direct fixes. If you want me to add a topology diagram (Mermaid/ASCII) or a quick Makefile/diagnostics script aligned
for your cluster, I can append those next.
