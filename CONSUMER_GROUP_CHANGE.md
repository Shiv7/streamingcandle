# Consumer Group Change Summary

## ✅ Changed: Application ID Prefix

**Old Value**: `comprehensive-logging-scriprepo-20260104-`  
**New Value**: `comprehensive-logging-scriprepo-20260104-v2-`

### Impact

This change will create BRAND NEW consumer groups for all processors:
- `comprehensive-logging-scriprepo-20260104-v2-unified-instrument-candle-processor`
- `comprehensive-logging-scriprepo-20260104-v2-family-candle-processor`  
- All other processors will get -v2- suffix

### Benefits

1. ✅ **Fresh Start**: Will consume from `earliest` offset (configured in properties)
2. ✅ **No Offset Conflicts**: Old consumer groups won't interfere
3. ✅ **Clean State Stores**: New state store directories (no corruption)
4. ✅ **Replay All Data**: Will process all available data from topics

### Configuration

```properties
# Line 22 in application.properties
kafka.streams.app-id-prefix=comprehensive-logging-scriprepo-20260104-v2-

# With auto.offset.reset=earliest (line 38)
spring.kafka.streams.properties.auto.offset.reset=earliest
```

### What Happens on Restart

1. Kafka sees new consumer group IDs
2. No existing offsets found
3. Falls back to `auto.offset.reset=earliest`
4. Starts consuming from beginning of all topics
5. Processes all historical data

### State Store Location

Old: `/tmp/kafka-streams/comprehensive-logging-scriprepo-20260104-*`  
New: `/tmp/kafka-streams/comprehensive-logging-scriprepo-20260104-v2-*`

Old state stores can be safely deleted after verification.

---

## Combined with Timestamp Fix

**Issue RT-2 (Timestamp Rejection)**: ✅ FIXED - Removed trading hours filter  
**Consumer Group Reset**: ✅ DONE - Changed to -v2- suffix  
**Issue RT-1 (Missing Topics)**: ⏳ PENDING - Need broker configuration or manual topic creation

### Next Steps

1. **Restart application** - Will create new consumer groups
2. **Enable auto topic creation** on broker (or manually create topics)
3. **Monitor logs** - Should see consumption from earliest offset
4. **Verify data flow** - Check logs for successful candle production

### Expected Log Messages

```
INFO - Starting with NEW consumer group: comprehensive-logging-scriprepo-20260104-v2-<processor-name>
INFO - No committed offsets found, using earliest
INFO - πAssigned partitions: [0, 1, 2, ...] starting from offset 0
```
