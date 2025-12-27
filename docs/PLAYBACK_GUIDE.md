# Streamingcandle 7-Day Playback Guide

This guide explains how to replay 7 days of historical market data to test signal generation.

## Prerequisites

1. Kafka running at `13.203.60.173:9094`
2. Raw data topics have 7 days of data: `forwardtesting-data`, `OpenInterest`, `Orderbook`
3. SSH access to the Kafka server

## Step 1: Stop Streamingcandle Application

```bash
# Stop any running streamingcandle instance
pkill -f streamingcandle.jar
# Or use systemctl if running as service
sudo systemctl stop streamingcandle
```

## Step 2: Delete Derived Topics

```bash
# Copy script to server
scp src/main/resources/scripts/delete_derived_topics.sh user@13.203.60.173:~/

# SSH to server and run
ssh user@13.203.60.173

# Preview what will be deleted (dry run)
chmod +x delete_derived_topics.sh
./delete_derived_topics.sh --dry-run

# Actually delete (type DELETE to confirm)
./delete_derived_topics.sh
```

## Step 3: Create Fresh Topics + Reset Offsets

```bash
# Copy script to server (if not already done)
scp src/main/resources/scripts/create_fresh_topics.sh user@13.203.60.173:~/

# Run on server
chmod +x create_fresh_topics.sh
./create_fresh_topics.sh
```

This script will:
- Clean RocksDB state stores at `/tmp/kafka-streams/streamingcandle` and `/var/lib/kafka-streams/streamingcandle`
- Reset all consumer group offsets to `earliest`
- Create all required derived topics with partitions=1, replication=1

## Step 4: Configure Streamingcandle for Playback

Edit `application-prod.properties` to consume from earliest:

```properties
# Change this line
spring.kafka.streams.properties.auto.offset.reset=earliest
```

Or override via environment variable:
```bash
export SPRING_KAFKA_STREAMS_PROPERTIES_AUTO_OFFSET_RESET=earliest
```

## Step 5: Start Streamingcandle

```bash
# Start the application
java -jar target/streamingcandle-*.jar --spring.profiles.active=prod

# Or via Maven
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

## Step 6: Monitor Signal Generation

```bash
# Watch trading signals being generated
/opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server 13.203.60.173:9094 \
  --topic trading-signals \
  --from-beginning

# Watch curated signals
/opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server 13.203.60.173:9094 \
  --topic trading-signals-curated \
  --from-beginning
```

## Expected Behavior

During playback:
- Application will process 7 days of tick data from `forwardtesting-data`
- Candles will be built for all timeframes (1m to 1d)
- VCP and IPU scores will be calculated
- Trading signals will be emitted to `trading-signals` topic
- Processing may take several hours depending on data volume

## Troubleshooting

### Application stuck or not processing

Check RocksDB state was cleared:
```bash
ls -la /tmp/kafka-streams/streamingcandle
ls -la /var/lib/kafka-streams/streamingcandle
```

### Old data not being processed

Verify consumer group offset was reset:
```bash
/opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server 13.203.60.173:9094 \
  --group unified-market-processor-prod1 \
  --describe
```

### Check application logs

```bash
tail -f /var/log/streamingcandle/app.log
# Or if running in foreground, check stdout
```

## After Playback

To return to live mode, change back to `latest` offset:
```properties
spring.kafka.streams.properties.auto.offset.reset=latest
```
