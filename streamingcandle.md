# StreamingCandle Project Documentation

## Overview
StreamingCandle is a real-time market data processing system built on Kafka Streams that transforms raw market tick data into OHLC (Open, High, Low, Close) candlestick data at various time intervals. It's designed to handle high-frequency financial market data with reliability and low latency.

## Architecture
The project is built on Java Spring Boot with Kafka Streams as the core processing engine. It follows a microservices architecture with several related modules:

- **streamingcandle**: Main Kafka Streams application for candlestick aggregation
- **indiactorCalculator**: Module for calculating technical indicators (appears to be in early stages)
- **indicatorAgg**: Module for aggregating indicators (appears to be in early stages)
- **scripFinder**: Module for finding financial instruments (appears to be in early stages)

## Core Components

### Data Models

#### TickData
Represents raw market tick data with properties including:
- Price information (lastRate, high, low, openRate, etc.)
- Volume metrics (lastQuantity, totalQuantity)
- Exchange metadata (exchange, exchangeType, token)
- Timing information (time, timestamp)
- Bid/ask data (bidRate, bidQuantity, offerRate, offerQuantity)

The class includes logic for timestamp parsing and Kafka serialization/deserialization.

#### Candlestick
Represents aggregated OHLC candlestick data with:
- Price points (open, high, low, close)
- Volume information
- Exchange metadata
- Window timing information (start/end timestamps)
- Human-readable time representations

The class contains methods for:
- Building candlesticks from individual ticks
- Merging smaller timeframe candles into larger ones
- Timestamp formatting and window calculations
- Kafka serialization/deserialization

### Processors

#### CandlestickProcessor
The core processing component with functionality to:
- Process raw tick data into 1-minute candlesticks
- Aggregate 1-minute candlesticks into larger timeframes (2, 3, 5, 15, 30 minutes)
- Handle exchange-specific trading hours (NSE: 9:15-3:30, MCX: 9:00-23:30)
- Buffer ticks to handle delayed or out-of-order data
- Ensure proper time window alignment based on market opening times
- Track data quality metrics

#### TimestampExtractors
Custom timestamp extractors to handle market-specific timing requirements:
- `ExchangeTimestampExtractor`: General timestamp extraction with market awareness
- `NseTimestampExtractor`: NSE-specific timestamp handling
- `DailyTimestampProcessor`: Processing for daily time boundaries

### Configuration

#### KafkaConfig
Contains Kafka-related configuration including:
- Bootstrap server settings
- Stream properties configuration
- Producer/consumer settings
- State store configuration
- Processing guarantees (exactly-once semantics)
- Error handling policies

## Data Flow

1. **Raw Tick Ingestion**:
   - Tick data from market is published to `forwardtesting-data` topic
   - Each tick includes price, volume, and metadata

2. **1-minute Candlestick Creation**:
   - Raw ticks are buffered briefly to handle out-of-order data
   - Ticks are windowed into 1-minute intervals
   - For each window, ticks are aggregated into OHLC candles
   - 1-minute candles are published to `1-min-candle` topic

3. **Multi-minute Candlestick Creation**:
   - 1-minute candles from `1-min-candle` topic are consumed
   - Candles are grouped and windowed into larger timeframes (2, 3, 5, 15, 30 minutes)
   - Aggregated candles are published to respective topics (e.g., `5-min-candle`)

4. **Trading Hour Awareness**:
   - The system handles exchange-specific trading hours
   - NSE: 9:15 AM - 3:30 PM Indian Standard Time
   - MCX: 9:00 AM - 11:30 PM Indian Standard Time
   - Candles are properly aligned to these trading hours

## Technical Details

### Key Technologies
- Java 17
- Spring Boot 3.2.2
- Kafka Streams 4.0.0
- Jackson for JSON serialization
- Lombok for boilerplate reduction

### Performance Considerations
- Tick buffering to handle late-arriving data (500ms delay)
- Metrics tracking for data quality monitoring
- Window alignment to ensure accurate time boundaries
- Trading hour awareness to filter out-of-hours data
- Exactly-once processing guarantees

### Error Handling
- Continues processing on deserialization errors
- Graceful shutdown hooks
- Timestamp validation and correction

## Deployment
The system appears to be configured to connect to Kafka at `172.31.12.118:9092` with state storage in `/tmp/kafka-streams-state`.

## Future Integration Points
The additional modules (indiactorCalculator, indicatorAgg, scripFinder) suggest plans for:
- Technical indicator calculation
- Indicator aggregation and analysis
- Financial instrument discovery and management 