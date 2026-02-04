# StreamingCandle Architecture

> **Last Updated**: 2026-02-01 | **Version**: V2 (Refactored)

## Overview

StreamingCandle is a **real-time trading signal generation system** for Indian markets (NSE, BSE, MCX). It processes live market data and generates institutional-grade trading signals using microstructure analysis.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         STREAMINGCANDLE SYSTEM                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐                │
│  │ Tick Topic   │   │ Orderbook    │   │ OpenInterest │                │
│  │ (10 partns)  │   │ (20 partns)  │   │ (6 partns)   │                │
│  └──────┬───────┘   └──────┬───────┘   └──────┬───────┘                │
│         │                  │                  │                         │
│         ▼                  ▼                  ▼                         │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   LAYER 1     │
│  │ TickAggre-   │   │ Orderbook    │   │ OI           │   DATA        │
│  │ gator        │   │ Aggregator   │   │ Aggregator   │   INGESTION   │
│  └──────┬───────┘   └──────┬───────┘   └──────┬───────┘                │
│         │                  │                  │                         │
│         ▼                  ▼                  ▼                         │
│  ┌──────────────────────────────────────────────────────┐              │
│  │              MongoDB + Redis Cache                    │   LAYER 2   │
│  │  TickCandle │ OrderbookMetrics │ OIMetrics           │   STORAGE   │
│  └──────────────────────┬───────────────────────────────┘              │
│                         │                                               │
│                         ▼                                               │
│  ┌──────────────────────────────────────────────────────┐              │
│  │                   CandleService                       │   LAYER 3   │
│  │            (Query-time merge → UnifiedCandle)         │   QUERY     │
│  └──────────────────────┬───────────────────────────────┘              │
│                         │                                               │
│         ┌───────────────┼───────────────┐                              │
│         ▼               ▼               ▼                              │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐                        │
│  │ VCP        │  │ IPU        │  │ Pivot      │       LAYER 4         │
│  │ Processor  │  │ Processor  │  │ Processor  │       STRATEGY        │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘                        │
│        └───────────────┼───────────────┘                               │
│                        ▼                                                │
│  ┌──────────────────────────────────────────────────────┐              │
│  │                 FudkiiCalculator                      │   LAYER 5   │
│  │   Flow + Urgency + Direction + Kyle + Imbalance      │   SCORING   │
│  └──────────────────────┬───────────────────────────────┘              │
│                         │                                               │
│                         ▼                                               │
│  ┌──────────────────────────────────────────────────────┐              │
│  │                    GateChain                          │   LAYER 6   │
│  │  Volume │ Trend │ R:R │ Momentum │ VWAP │ Time      │   FILTERING  │
│  └──────────────────────┬───────────────────────────────┘              │
│                         │                                               │
│                         ▼                                               │
│  ┌──────────────────────────────────────────────────────┐              │
│  │                 Trading Signals                       │   OUTPUT    │
│  └──────────────────────────────────────────────────────┘              │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Design Principles

### V2 Architecture (Current)

| Principle | Implementation |
|-----------|----------------|
| **Independence** | Each aggregator runs in its own thread, no dependencies |
| **Query-time Merge** | Data joined when queried, not during ingestion |
| **Explicit Availability** | `hasOrderbook`, `hasOI` flags indicate data presence |
| **Cache-First** | Redis for hot data, MongoDB for persistence |
| **Simple Debugging** | Clear data lineage, no Kafka Streams state stores |

### Why V2 Over V1?

| V1 (Old) | V2 (Current) |
|----------|--------------|
| Kafka Streams joins | Independent consumers |
| Join failures → fallback logic | No joins needed |
| Complex state stores | Simple MongoDB + Redis |
| Hard to debug | Clear data flow |
| Single topology | Isolated consumers |

---

## Layer Details

### Layer 1: Data Ingestion

Three independent Kafka consumers run in parallel, each with its own consumer group:

| Aggregator | Input Topic | Output | Consumer Group |
|------------|-------------|--------|----------------|
| `TickAggregator` | `forwardtesting-data` | `TickCandle` | `v2.tick.consumer.group` |
| `OrderbookAggregator` | `Orderbook` | `OrderbookMetrics` | `v2.orderbook.consumer.group` |
| `OIAggregator` | `OpenInterest` | `OIMetrics` | `v2.oi.consumer.group` |

Each aggregator:
- Uses **wall-clock based** windowing (not stream-time)
- Emits 1-minute aggregated records
- Writes to both MongoDB and Redis

### Layer 2: Storage

| Storage | Purpose | TTL |
|---------|---------|-----|
| MongoDB | Persistent storage, historical queries | 90 days |
| Redis | Hot cache, real-time queries | 48 hours |

Collections:
- `tick_candles_1m` - Indexed by `symbol + timestamp`
- `orderbook_metrics_1m`
- `oi_metrics_1m`

### Layer 3: Query Layer

`CandleService` is the **main interface** for strategies:

```java
// Get latest unified candle (merges tick + orderbook + OI)
UnifiedCandle candle = candleService.getLatestCandle("NIFTY");

// Get specific timeframe (on-demand aggregation)
UnifiedCandle candle5m = candleService.getLatestCandle("NIFTY", Timeframe.M5);

// Get history
List<UnifiedCandle> history = candleService.getCandleHistory("NIFTY", Timeframe.M5, 50);
```

### Layer 4: Strategy Processors

| Processor | Input | Output | Purpose |
|-----------|-------|--------|---------|
| `VcpProcessor` | Candle + history | `VcpState` | Volume Cluster Profile |
| `IpuProcessor` | Candle + orderbook | `IpuState` | Institutional participation |
| `PivotProcessor` | Candle history | `PivotState` | Swing highs/lows |

### Layer 5: FUDKII Scoring

Composite score from 6 components:

| Component | Weight | Source |
|-----------|--------|--------|
| Flow | 15% | OFI momentum |
| Urgency | 20% | IPU exhaustion |
| Direction | 25% | VCP runway |
| Kyle | 10% | Kyle's Lambda |
| Imbalance | 15% | VIB/DIB triggers |
| Intensity | 15% | Tick activity |

### Layer 6: Quality Gates

Signals pass through configurable gates:

| Gate | Check | Required |
|------|-------|----------|
| VolumeGate | Volume > 1.2x average | No |
| TrendGate | EMA/SuperTrend alignment | Yes |
| RiskRewardGate | R:R > 1.5 | Yes |
| MomentumGate | RSI extremes | No |
| VWAPGate | Price vs VWAP | No |
| TimeOfDayGate | Not opening/closing | No |
| FUDKIIGate | Score > threshold | No |

---

## Threading Model

```
Main Thread
├── TickAggregator (4 threads)
├── OrderbookAggregator (2 threads)
├── OIAggregator (2 threads)
├── SignalEngine (@Scheduled every 5s)
└── REST Controllers (Tomcat pool)
```

---

## Configuration

Key files:
- `application.properties` - Main config (644 lines)
- `application-prod.properties` - Production overrides

Key sections:
```properties
# Kafka
spring.kafka.bootstrap-servers=3.111.242.49:9094

# Aggregators
v2.tick.aggregator.enabled=true
v2.orderbook.aggregator.enabled=true
v2.oi.aggregator.enabled=true

# Signal Engine
signal.engine.enabled=true
signal.engine.schedule.interval.ms=5000
```

---

## See Also

- [GLOSSARY.md](./GLOSSARY.md) - Domain & quant terminology
- [modules/AGGREGATORS.md](./modules/AGGREGATORS.md) - Data ingestion layer
- [modules/MODELS.md](./modules/MODELS.md) - Data models reference
- [modules/SIGNALS.md](./modules/SIGNALS.md) - Signal generation
- [modules/GATES.md](./modules/GATES.md) - Quality filtering
- [modules/SERVICES.md](./modules/SERVICES.md) - Business logic
