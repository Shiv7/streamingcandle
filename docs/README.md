# StreamingCandle Documentation

Comprehensive documentation for the StreamingCandle trading signal system.

## Quick Start

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | System overview & architecture |
| [GLOSSARY.md](./GLOSSARY.md) | Domain & quant terminology |

## Module Documentation

| Module | Package | Description |
|--------|---------|-------------|
| [AGGREGATORS](./modules/AGGREGATORS.md) | `aggregator` | Kafka consumers, data ingestion |
| [MODELS](./modules/MODELS.md) | `model` | Data structures reference |
| [SIGNALS](./modules/SIGNALS.md) | `signal` | Signal generation, FUDKII |
| [GATES](./modules/GATES.md) | `gate` | Quality filtering |
| [SERVICES](./modules/SERVICES.md) | `service` | Business logic, caching |

## Architecture Summary

```
Kafka → Aggregators → MongoDB/Redis → CandleService → SignalEngine → Signals
```

### Key Components

1. **3 Independent Aggregators** - Consume tick, orderbook, OI data
2. **CandleService** - Query-time merge into UnifiedCandle
3. **SignalEngine** - Orchestrates signal generation every 5s
4. **FudkiiCalculator** - Composite scoring (Flow, Urgency, Direction, Kyle, Imbalance, Intensity)
5. **GateChain** - Quality filtering (Volume, Trend, R:R, Momentum, VWAP, Time, FUDKII)

### File Counts

| Directory | Files | Purpose |
|-----------|-------|---------|
| `aggregator/` | 3 | Data ingestion |
| `model/` | 9 | Data structures |
| `service/` | 5 | Business logic |
| `signal/` | 9 | Signal generation |
| `gate/` | 10 | Quality gates |
| `config/` | 10 | Configuration |
| `controller/` | 11 | REST APIs |
| **Total** | **63** | Java files |

---

*Documentation generated: 2026-02-01*
