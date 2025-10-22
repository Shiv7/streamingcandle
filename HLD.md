# High-Level Design (HLD)
## Market Data Processing System

**Version:** 1.0 (DEPRECATED - See HLD_V2.md)  
**Date:** October 22, 2025  
**Author:** System Architecture Team

> ⚠️ **IMPORTANT**: This is the original design document.  
> 📄 **For the current architecture, see: [HLD_V2.md](HLD_V2.md)**  
> 🎯 **V2.0 includes**: 19 new services, 112 new tests, all issues fixed, A+ grade

---

## 1. Executive Summary

The Market Data Processing System is a real-time streaming analytics platform built on Apache Kafka Streams that processes high-frequency market data from Indian equity and derivatives markets. The system aggregates tick-level data into multi-timeframe candles, computes advanced microstructure features, and provides family-level aggregations for trading strategy consumption.

### Key Capabilities
- **Real-time Processing**: Sub-second latency for tick-to-candle aggregation
- **Multi-Timeframe Support**: Simultaneous 1m, 2m, 3m, 5m, 15m, 30m candle generation
- **Advanced Analytics**: Microstructure metrics (OFI, VPIN, Kyle's Lambda), orderbook depth analysis, iceberg/spoofing detection
- **Family Aggregation**: Unified view of equity + derivatives (futures + options) per underlying
- **Scalability**: Distributed processing with Kafka Streams state stores and horizontal scaling

---

## 2. System Architecture Overview

### 2.1 High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        DATA SOURCES (KAFKA)                          │
├─────────────────────────────────────────────────────────────────────┤
│  TickData Topic  │  OpenInterest Topic  │  Orderbook Topic          │
└────────┬─────────┴───────────┬──────────┴───────────┬───────────────┘
         │                     │                       │
         │                     │                       │
         ▼                     ▼                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│              UNIFIED MARKET DATA PROCESSOR (Kafka Streams)           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │         STREAM 1: Per-Instrument Candle Generation           │   │
│  │  ┌───────────┐  ┌──────────────┐  ┌──────────────────┐     │   │
│  │  │ Delta Vol │→ │ Tick Window  │→ │ State Aggregator │     │   │
│  │  │Transformer│  │ (1m tumbling)│  │ (per instrument) │     │   │
│  │  └───────────┘  └──────────────┘  └──────────────────┘     │   │
│  │                                            │                  │   │
│  │                    ┌───────────────────────┴─────────────┐   │   │
│  │                    │   Enrichment Layer                   │   │   │
│  │                    │  • OI Data Join                      │   │   │
│  │                    │  • Orderbook Data Join               │   │   │
│  │                    │  • Microstructure Calculation        │   │   │
│  │                    │  • Imbalance Bar Generation          │   │   │
│  │                    │  • Orderbook Depth Analytics         │   │   │
│  │                    └───────────────────────┬─────────────┘   │   │
│  │                                            │                  │   │
│  │                                            ▼                  │   │
│  │                    ┌──────────────────────────────────────┐  │   │
│  │                    │   Multi-Timeframe Emission Service   │  │   │
│  │                    │  (1m, 2m, 3m, 5m, 15m, 30m)         │  │   │
│  │                    └──────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │      STREAM 2-6: Family-Structured Aggregation Streams      │   │
│  │  (One stream per timeframe: 1m, 2m, 5m, 15m, 30m)          │   │
│  │  ┌────────────────┐  ┌─────────────────────────────────┐   │   │
│  │  │ Candle Topics  │→ │  Family Key Grouping & Assembly │   │   │
│  │  │ (per timeframe)│  │  • Group by underlying equity    │   │   │
│  │  └────────────────┘  │  • Combine equity + derivatives  │   │   │
│  │                      │  • Compute family metrics        │   │   │
│  │                      └─────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │          STREAM 7: Family-Structured ALL Stream              │   │
│  │  (Combines all timeframes into single enriched output)       │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
└───────────────────────────────┬───────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        OUTPUT TOPICS (KAFKA)                         │
├─────────────────────────────────────────────────────────────────────┤
│  • candle-complete-{1m,2m,3m,5m,15m,30m}                            │
│  • family-structured-{1m,2m,5m,15m,30m}                             │
│  • family-structured-all                                             │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          DOWNSTREAM CONSUMERS                         │
├─────────────────────────────────────────────────────────────────────┤
│  • Trading Strategy Engines                                          │
│  • Risk Management Systems                                           │
│  • Analytics & Visualization Dashboards                              │
│  • Historical Data Archival (MongoDB)                                │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 Component Overview

| Component | Technology | Responsibility |
|-----------|-----------|----------------|
| **Data Ingestion** | Kafka Topics | Receives tick data, OI updates, orderbook snapshots |
| **Stream Processing** | Kafka Streams | Stateful aggregation, windowing, enrichment |
| **State Management** | RocksDB (via Kafka Streams) | Persistent state stores for accumulators |
| **Configuration** | Spring Boot | Application configuration, dependency injection |
| **Metadata Storage** | MongoDB | Instrument families, scrip groups, reference data |
| **Monitoring** | Logback, StreamMetrics | Performance metrics, error tracking |

---

## 3. Functional Requirements

### 3.1 Core Features

#### FR-1: Tick Data Processing
- **Input**: Raw tick data with price, volume, timestamp
- **Processing**:
    - Delta volume calculation (cumulative to delta conversion)
    - Trading hours validation
    - Event-time windowing
- **Output**: Delta-enriched ticks for downstream processing

#### FR-2: Multi-Timeframe Candle Generation
- **Timeframes**: 1m, 2m, 3m, 5m, 15m, 30m
- **OHLCV Calculation**: Open, High, Low, Close, Volume
- **Window Semantics**: Tumbling windows with event-time processing
- **Grace Period**: 10-second grace period for late-arriving data

#### FR-3: Open Interest Aggregation
- **Input**: OI updates from derivatives
- **Processing**: Time-aligned aggregation with tick data
- **Output**: OI and OI-change metrics per instrument

#### FR-4: Orderbook Depth Analytics
- **Input**: Full orderbook snapshots (bids/asks)
- **Calculations**:
    - Bid-ask spread, mid-price
    - Depth imbalance (bid vs ask liquidity)
    - VWAP for bid/ask sides
    - Depth slope analysis
    - **Iceberg Detection**: Persistent hidden liquidity patterns
    - **Spoofing Detection**: Rapid order placement/cancellation

#### FR-5: Microstructure Features
- **Order Flow Imbalance (OFI)**: Buy vs sell pressure
- **Volume-Synchronized Probability of Informed Trading (VPIN)**: Toxic flow detection
- **Kyle's Lambda**: Price impact of trades
- **Implementation**: EWMA-based online computation

#### FR-6: Imbalance Bar Generation
- **Types**:
    - Volume Imbalance Bars (VIB)
    - Dollar Imbalance Bars (DIB)
    - Tick Runs Bars (TRB)
    - Volume Runs Bars (VRB)
- **Purpose**: Alternative to time-based bars for non-uniform market activity

#### FR-7: Family-Level Aggregation
- **Grouping**: By underlying equity/index
- **Components**: Equity + Futures + Options
- **Metrics**:
    - Put-Call Ratio (PCR)
    - Put-Call Volume Ratio
    - Futures Basis
    - Total family volume/OI
    - Aggregated microstructure features

---

## 4. Non-Functional Requirements

### 4.1 Performance
- **Latency**: P99 < 500ms for tick-to-candle processing
- **Throughput**: Handle 10,000+ ticks/second per instrument
- **State Store Size**: Optimize for instruments (< 5GB per instance)

### 4.2 Reliability
- **Fault Tolerance**: Kafka Streams exactly-once or at-least-once semantics
- **State Recovery**: Automatic recovery from RocksDB state stores
- **Error Handling**: LogAndContinue for deserialization errors

### 4.3 Scalability
- **Horizontal Scaling**: Multiple Kafka Streams instances with partition assignment
- **State Partitioning**: Key-based partitioning by scripCode/familyKey
- **Resource Optimization**: Configurable cache sizes, thread counts

### 4.4 Maintainability
- **Modular Design**: Separation of concerns (accumulators, services, processors)
- **Configuration**: Externalized configuration via Spring properties
- **Logging**: Structured logging with correlation IDs

---

## 5. Data Flow Architecture

### 5.1 Per-Instrument Processing Flow

```
Tick Data → Delta Volume Transformer → Trading Hours Filter → 
Window Aggregation → State Accumulation → 
OI Enrichment → Orderbook Enrichment → 
Feature Calculation → Multi-Timeframe Emission → Output Topics
```

### 5.2 Family Aggregation Flow

```
Candle Topics (per timeframe) → Family Key Extraction → 
Group By Family → Aggregate Assembly → 
Compute Family Metrics → Family Structured Output
```

---

## 6. Key Design Decisions

### 6.1 Event-Time Processing
- **Rationale**: Handle out-of-order data and replay scenarios
- **Implementation**: Custom timestamp extractors per data type
- **Trade-off**: Complexity vs correctness

### 6.2 State Store Strategy
- **Instrument State**: Per-scrip aggregators (candle, OI, micro, orderbook)
- **Global Orderbook State**: Never reset for iceberg/spoofing detection
- **Timeframe State**: Reset on window rotation for clean metrics

### 6.3 Enrichment Pattern
- **Left Join**: Ticks left-join OI and Orderbook
- **Rationale**: Not all instruments have OI/orderbook data
- **Fallback**: Graceful degradation with null checks

### 6.4 Family Assembly
- **Windowed Join**: Time-aligned joining of equity, futures, options
- **Selection Logic**:
    - Futures: Near-month contract
    - Options: Top 4 by volume/OI

---

## 7. Integration Points

### 7.1 Input Interfaces

| Interface | Format | Source | Frequency |
|-----------|--------|--------|-----------|
| Tick Data | JSON (Kafka) | Market Data Feed | Real-time (milliseconds) |
| Open Interest | JSON (Kafka) | Derivatives Exchange | Real-time (seconds) |
| Orderbook Snapshots | JSON (Kafka) | Order Book Feed | Real-time (100ms) |
| Scrip Groups | MongoDB | Reference Data Service | Daily batch |

### 7.2 Output Interfaces

| Interface | Format | Consumer | Frequency |
|-----------|--------|----------|-----------|
| Instrument Candles | JSON (Kafka) | Strategy Engines | Per window close |
| Family Structured | JSON (Kafka) | Portfolio Analytics | Per window close |
| Metrics | Logs/JMX | Monitoring Systems | Continuous |

---

## 8. Technology Stack

### 8.1 Core Technologies
- **Language**: Java 17
- **Framework**: Spring Boot 3.2.2
- **Streaming**: Kafka Streams 3.x
- **Build Tool**: Maven
- **State Store**: RocksDB (embedded)

### 8.2 Dependencies
- **Kafka Client**: Spring Kafka
- **Serialization**: Jackson (JSON), Spring JsonSerde
- **Database**: MongoDB (reference data)
- **Utilities**: Lombok, SLF4J

### 8.3 Development Tools
- **Testing**: JUnit 5, Mockito, Jacoco (90% coverage target)
- **Logging**: Logback with JSON formatting
- **Monitoring**: StreamMetrics, Kafka metrics

---

## 9. Deployment Architecture

### 9.1 Environment Configuration

| Environment | Purpose | Kafka Cluster | Instances |
|-------------|---------|---------------|-----------|
| Local | Development | localhost:9092 | 1 |
| Test | Integration Testing | test-cluster:9092 | 2 |
| Production | Live Trading | prod-cluster-1,2,3:9092 | 4+ (auto-scale) |

### 9.2 Deployment Strategy
- **Container**: Docker images with JRE 17
- **Orchestration**: Kubernetes or bare-metal
- **State Management**: Persistent volumes for RocksDB
- **Configuration**: ConfigMaps/environment variables

---

## 10. Monitoring and Observability

### 10.1 Key Metrics
- **Throughput**: Messages processed per second
- **Latency**: P50, P95, P99 processing time
- **State Store Size**: Disk usage per instance
- **Error Rate**: Deserialization failures, null pointer exceptions

### 10.2 Alerting
- **High Latency**: P99 > 1 second
- **Low Throughput**: < 1000 msgs/sec (expected 5000+)
- **State Store Full**: > 80% disk usage
- **Stream Failure**: Application crash or rebalancing loops

---

## 11. Security Considerations

### 11.1 Data Security
- **Encryption**: Kafka SSL/TLS for data in transit
- **Authentication**: SASL for Kafka client authentication
- **Authorization**: Kafka ACLs for topic access control

### 11.2 Application Security
- **Sensitive Data**: No PII/credentials in logs
- **Dependency Management**: Regular CVE scanning and updates
- **Configuration**: Secrets via environment variables (not hardcoded)

---

## 12. Disaster Recovery

### 12.1 Backup Strategy
- **State Stores**: Kafka changelog topics (automatic backup)
- **Configuration**: Version-controlled in Git
- **Reference Data**: MongoDB daily snapshots

### 12.2 Recovery Procedures
1. **Application Crash**: Automatic restart with state restoration from changelog
2. **Data Corruption**: Reset state stores and replay from Kafka (earliest offset)
3. **Kafka Cluster Failure**: Failover to secondary cluster with catchup processing

---

## 13. Future Enhancements

### 13.1 Roadmap
- **Phase 2**: Machine learning feature engineering pipeline
- **Phase 3**: Real-time alerting on anomalous patterns
- **Phase 4**: Multi-exchange support (NSE, BSE, MCX)
- **Phase 5**: Low-latency optimization (<100ms P99)

### 13.2 Scalability Improvements
- **Tiered Storage**: Move cold data to object storage
- **Compression**: Enable Kafka compression for larger payloads
- **Partitioning**: Dynamic partition assignment based on load

---

## 14. Appendix

### 14.1 Glossary
- **OI**: Open Interest - number of outstanding derivative contracts
- **OFI**: Order Flow Imbalance - net aggressive buy/sell pressure
- **VPIN**: Volume-Synchronized Probability of Informed Trading
- **PCR**: Put-Call Ratio - options market sentiment indicator
- **Basis**: Price difference between futures and spot

### 14.2 References
- Kafka Streams Documentation: https://kafka.apache.org/documentation/streams/
- Spring Boot Documentation: https://spring.io/projects/spring-boot
- Market Microstructure: Advances in Financial Machine Learning (Marcos López de Prado)

---

**Document Control**
- **Version**: 1.0
- **Last Updated**: October 22, 2025
- **Review Cycle**: Quarterly
- **Approvers**: Architecture Team, Product Owner, Tech Lead