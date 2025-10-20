# Test Data Producer - Currently Running

## Status: ✅ ACTIVE

The test data producer script is currently running in the background and sending dummy market data to your Kafka broker.

---

## What It's Doing

### Sending data to 3 topics on **13.203.60.173:9094**:

1. **`forwardtesting-data`** - Tick data (every 2 seconds)
2. **`Orderbook`** - Order book snapshots (every 4 seconds)  
3. **`OpenInterest`** - Open interest data (every 10 seconds)

### For 5 instruments:
- NIFTY (26000)
- BANKNIFTY (26009)
- RELIANCE (2885)
- HDFC (1330)
- SBIN (3045)

---

## How to Monitor

### View Live Output
```bash
# Check if still running
ps aux | grep test_data_producer | grep -v grep

# View background job
jobs -l

# Check Kafka topics are receiving data
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic forwardtesting-data \
  --max-messages 5
```

---

## How to Stop

### Option 1: Find and Kill Process
```bash
# Find process ID
ps aux | grep test_data_producer | grep -v grep

# Kill the process (replace PID with actual process ID)
kill <PID>

# Or force kill if needed
kill -9 <PID>
```

### Option 2: Kill All Python Processes (Careful!)
```bash
pkill -f test_data_producer.py
```

### Option 3: Bring to Foreground and Stop
```bash
# Find job number
jobs -l

# Bring to foreground (replace %1 with actual job number)
fg %1

# Then press Ctrl+C to stop
```

---

## Verify Data is Being Sent

### Check Each Topic

#### 1. TickData (forwardtesting-data)
```bash
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic forwardtesting-data \
  --from-beginning \
  --max-messages 3
```

Expected: JSON with Exch, Token, LastRate, TotalQty, etc.

#### 2. OrderBook
```bash
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic Orderbook \
  --from-beginning \
  --max-messages 3
```

Expected: JSON with bidRate, offRate arrays, totalBidQty, etc.

#### 3. OpenInterest
```bash
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic OpenInterest \
  --from-beginning \
  --max-messages 3
```

Expected: JSON with openInterest, oiChange, oiChangePercent, etc.

---

## Check StreamingCandle Output

If your streamingcandle module is running, check if it's processing the data:

```bash
# Check enriched output topic
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic enriched-market-data \
  --from-beginning \
  --max-messages 5
```

Expected: JSON with:
- multiTimeframeCandles (1m, 2m, 3m, 5m, 15m, 30m)
- openInterest (all timeframes)
- imbalanceBars
- microstructure features
- metadata

---

## Performance Stats

### Expected Message Rates

| Metric | Value |
|--------|-------|
| Iterations/min | 30 (sleep=2s) |
| Tick messages/min | 150 (5 stocks × 30) |
| OrderBook messages/min | 75 (every other iteration) |
| OI messages/min | 6 (NIFTY + BANKNIFTY × 30/5) |
| **Total messages/min** | **~231** |
| **Total messages/hour** | **~13,860** |

### Resource Usage
- CPU: < 5%
- Memory: ~50 MB
- Network: ~10 KB/s

---

## Troubleshooting

### If No Messages Appear

1. **Check producer is running**:
   ```bash
   ps aux | grep test_data_producer
   ```

2. **Check Kafka connection**:
   ```bash
   telnet 13.203.60.173 9094
   ```

3. **Check topics exist**:
   ```bash
   kafka-topics --bootstrap-server 13.203.60.173:9094 --list
   ```

4. **View producer logs** (if running in foreground):
   ```bash
   # The script prints to stdout, so you should see:
   # ✅ Sent to forwardtesting-data | Partition: X | Offset: Y
   # 📊 Tick: NIFTY @ ₹19,505.50 | Vol: 2,500,000
   ```

### If Producer Crashed

Check for error messages and restart:
```bash
cd /Users/shivendrapratap/Documents/kotsin/streamingcandle
python3 test_data_producer.py
```

Common errors:
- **Connection refused**: Kafka broker not accessible
- **Topic not found**: Topics need to be created first
- **Serialization error**: Check data format

---

## Next Steps

1. Let it run for **5-10 minutes** to accumulate data
2. Verify StreamingCandle is consuming and processing
3. Check enriched-market-data topic has output
4. Monitor consumer lag (should be low)
5. Stop when testing is complete

---

## Quick Commands

```bash
# Status check
ps aux | grep test_data_producer | grep -v grep

# Stop producer
pkill -f test_data_producer.py

# Verify Kafka has data
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic forwardtesting-data --max-messages 1

# Check StreamingCandle output
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic enriched-market-data --max-messages 1

# Check consumer lag
kafka-consumer-groups --bootstrap-server 13.203.60.173:9094 \
  --describe --group unified-market-processor
```

---

**Started**: October 20, 2025  
**Status**: 🟢 Running  
**Kafka Broker**: 13.203.60.173:9094  
**Rate**: ~231 messages/minute  

---

**Remember**: Stop the producer when you're done testing with:
```bash
pkill -f test_data_producer.py
```

