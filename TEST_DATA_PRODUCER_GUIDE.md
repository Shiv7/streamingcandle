# Test Data Producer for StreamingCandle

## Overview

This script sends realistic dummy market data to Kafka for testing the streamingcandle module.

## Quick Start

### 1. Install Dependencies

```bash
pip install kafka-python
```

### 2. Run the Producer

```bash
cd /Users/shivendrapratap/Documents/kotsin/streamingcandle
python test_data_producer.py
```

### 3. Stop the Producer

Press `Ctrl+C` to stop

---

## What It Does

### Data Generation

The script generates realistic market data for 5 instruments:

| Token | Symbol | Name | Base Price |
|-------|--------|------|------------|
| 26000 | NIFTY | NIFTY 50 | ₹19,500 |
| 26009 | BANKNIFTY | BANK NIFTY | ₹44,500 |
| 2885 | RELIANCE | RELIANCE IND | ₹2,450 |
| 1330 | HDFC | HDFC BANK | ₹1,650 |
| 3045 | SBIN | SBI | ₹580 |

### Topics & Data

#### 1. `forwardtesting-data` (TickData) - Every 2 seconds
```json
{
  "Exch": "N",
  "ExchType": "C",
  "Token": 26000,
  "ScripCode": "NIFTY",
  "LastRate": 19505.50,
  "LastQty": 2500,
  "TotalQty": 2500000,
  "High": 19600.00,
  "Low": 19450.00,
  "OpenRate": 19500.00,
  "PClose": 19461.00,
  "AvgRate": 19505.01,
  "Time": 1729378800000,
  "BidQty": 1500,
  "BidRate": 19505.45,
  "OffQty": 1200,
  "OffRate": 19505.55,
  "TBidQ": 25000,
  "TOffQ": 22000,
  "TickDt": "/Date(1729378800000)/",
  "ChgPcnt": 0.03,
  "companyName": "NIFTY 50"
}
```

#### 2. `Orderbook` (OrderBookSnapshot) - Every 4 seconds
```json
{
  "exchange": "N",
  "exchangeType": "C",
  "token": "26000",
  "bidRate": [19505.45, 19505.40, 19505.35, 19505.30, 19505.25],
  "bidQty": [1500, 1200, 800, 600, 400],
  "offRate": [19505.50, 19505.55, 19505.60, 19505.65, 19505.70],
  "offQty": [1200, 900, 700, 500, 300],
  "totalBidQty": 4500,
  "totalOffQty": 3600,
  "companyName": "NIFTY 50",
  "receivedTimestamp": 1729378800000
}
```

#### 3. `OpenInterest` - Every 10 seconds (NIFTY & BANKNIFTY only)
```json
{
  "exchange": "N",
  "exchangeType": "C",
  "token": "26000",
  "openInterest": 15000000,
  "oiChange": 5000,
  "oiChangePercent": 0.03,
  "lastRate": 19505.50,
  "volume": 250000,
  "companyName": "NIFTY 50",
  "receivedTimestamp": 1729378800000
}
```

---

## Data Characteristics

### Realistic Simulation

1. **Price Movement**:
   - ±0.5% random walk
   - Maintains continuity across iterations
   - Realistic bid-ask spread (0.05 points)

2. **Volume**:
   - Cumulative `TotalQty` increases each tick
   - Random `LastQty` per trade (100-5000)
   - Realistic total quantities (millions)

3. **Order Book**:
   - 5 levels of bids and asks
   - Descending bid prices, ascending ask prices
   - Realistic quantities (100-2000 per level)

4. **Open Interest**:
   - Only for derivatives (NIFTY, BANKNIFTY)
   - Random OI changes (±10,000)
   - Calculates OI change percentage

5. **Timestamps**:
   - Uses current system time
   - Proper `/Date(milliseconds)/` format for TickDt
   - Consistent across all messages

---

## Expected Output

```
================================================================================
StreamingCandle Test Data Producer
================================================================================
Kafka Broker: 13.203.60.173:9094
Topics: forwardtesting-data, Orderbook, OpenInterest
Stocks: 5 instruments
================================================================================

🔧 Creating Kafka producer...
✅ Producer created successfully

🚀 Starting data generation... (Press Ctrl+C to stop)

✅ Sent to forwardtesting-data | Partition: 0 | Offset: 12345
📊 Tick: NIFTY @ ₹19,505.50 | Vol: 2,500,000

✅ Sent to Orderbook | Partition: 0 | Offset: 6789
📖 OrderBook: NIFTY | Bid: ₹19,505.45 | Ask: ₹19,505.50

✅ Sent to OpenInterest | Partition: 0 | Offset: 1234
📈 OI: NIFTY | OI: 15,000,000 | Change: +5,000

================================================================================
Messages sent: 15 | Iteration: 3
================================================================================
```

---

## Verification

### 1. Check Topics Exist

```bash
kafka-topics --bootstrap-server 13.203.60.173:9094 --list | grep -E "forwardtesting|Orderbook|OpenInterest"
```

Expected output:
```
forwardtesting-data
Orderbook
OpenInterest
```

### 2. Consume Messages

#### TickData
```bash
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic forwardtesting-data \
  --from-beginning \
  --max-messages 5
```

#### OrderBook
```bash
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic Orderbook \
  --from-beginning \
  --max-messages 5
```

#### OpenInterest
```bash
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic OpenInterest \
  --from-beginning \
  --max-messages 5
```

### 3. Verify StreamingCandle is Processing

```bash
# Check enriched output topic
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic enriched-market-data \
  --from-beginning \
  --max-messages 5
```

Expected: Should see enriched messages with:
- multiTimeframeCandles (1m, 2m, 3m, 5m, 15m, 30m)
- openInterest (all timeframes)
- imbalanceBars
- microstructure features

---

## Customization

### Change Data Rate

Edit `time.sleep(2)` to adjust frequency:
```python
time.sleep(1)   # Faster (1 second)
time.sleep(5)   # Slower (5 seconds)
time.sleep(0.5) # Very fast (0.5 seconds)
```

### Add More Stocks

Edit the `STOCKS` list:
```python
STOCKS = [
    {"token": 26000, "scripCode": "NIFTY", "name": "NIFTY 50", "basePrice": 19500.0},
    {"token": 12345, "scripCode": "TCS", "name": "TCS LTD", "basePrice": 3500.0},
    # Add more...
]
```

### Change Kafka Broker

Edit the configuration:
```python
KAFKA_BROKER = "localhost:9092"  # For local testing
```

---

## Troubleshooting

### Issue: Cannot connect to Kafka

**Error**: `NoBrokersAvailable` or `Connection refused`

**Solution**:
1. Check Kafka is running: `telnet 13.203.60.173 9094`
2. Check firewall rules
3. Verify broker address in script

### Issue: Import error for kafka-python

**Error**: `ModuleNotFoundError: No module named 'kafka'`

**Solution**:
```bash
pip install kafka-python

# Or with specific version
pip install kafka-python==2.0.2
```

### Issue: Messages sent but not consumed

**Possible causes**:
1. Topics don't exist - Create them manually:
   ```bash
   kafka-topics --bootstrap-server 13.203.60.173:9094 \
     --create --topic forwardtesting-data --partitions 3 --replication-factor 1
   ```

2. Wrong topic names - Check script matches Java model expectations

3. Serialization mismatch - Script uses JSON, verify consumers expect JSON

---

## Integration Testing

### Full Test Flow

1. **Start Kafka** (if not running)
2. **Start StreamingCandle module**:
   ```bash
   cd ~/streamingcandle
   mvn spring-boot:run
   ```

3. **Start Test Data Producer**:
   ```bash
   python test_data_producer.py
   ```

4. **Monitor Logs**:
   ```bash
   tail -f ~/streamingcandle/streamingcandle.log
   ```

5. **Verify Output**:
   ```bash
   kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
     --topic enriched-market-data \
     --max-messages 10
   ```

### Success Criteria

- ✅ Producer sends messages without errors
- ✅ StreamingCandle consumes from all 3 topics
- ✅ No errors in streamingcandle.log
- ✅ enriched-market-data topic has messages
- ✅ All timeframes (1m-30m) present in output
- ✅ Microstructure features calculated
- ✅ Consumer lag < 100 messages

---

## Performance

### Expected Throughput

| Metric | Value |
|--------|-------|
| Iterations/min | 30 (sleep=2s) |
| Messages/iteration | 15 (5 stocks × 3 topics) |
| Total messages/min | ~450 |
| Total messages/hour | ~27,000 |

### Resource Usage

- CPU: < 5%
- Memory: ~50 MB
- Network: ~10 KB/s

---

## Schema Validation

The script generates data matching **exact schemas** from Java models:

✅ **TickData.java**: Uses @JsonProperty names (Exch, Token, LastRate, etc.)  
✅ **OrderBookSnapshot.java**: Uses camelCase (exchange, bidRate, offRate, etc.)  
✅ **OpenInterest.java**: Uses camelCase (openInterest, oiChange, etc.)  

All timestamps use milliseconds since epoch.

---

## Next Steps

After verifying test data:

1. Run for 10 minutes to accumulate data
2. Check all timeframes are being calculated
3. Verify microstructure features
4. Test with real market data
5. Performance testing with higher data rates

---

**Script Version**: 1.0  
**Date**: October 20, 2025  
**Status**: ✅ Ready for Testing

