package com.kotsin.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Bulk sorter + replayer: consumes ALL messages from source topics, sorts by event-time,
 * and emits to single-partition replay topics with original keys and event timestamps.
 *
 * WARNING: This buffers records in memory. Use for scoped test ranges or smaller datasets.
 */
@Component
@Profile("replay")
@ConditionalOnProperty(value = "replay.sort.bulk.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class ReplayBulkSorterRunner implements CommandLineRunner {

    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${replay.source.ticks:forwardtesting-data}")
    private String sourceTicksTopic;
    @Value("${replay.source.orderbook:Orderbook}")
    private String sourceOrderbookTopic;
    @Value("${replay.source.oi:OpenInterest}")
    private String sourceOiTopic;

    @Value("${replay.target.ticks:forwardtesting-data-replay}")
    private String targetTicksTopic;
    @Value("${replay.target.orderbook:Orderbook-replay}")
    private String targetOrderbookTopic;
    @Value("${replay.target.oi:OpenInterest-replay}")
    private String targetOiTopic;

    @Value("${replay.sort.bulk.max.idle.ms:3000}")
    private long maxIdleMs;

    @Value("${replay.sort.date:}")
    private String sortDate; // ISO yyyy-MM-dd

    @Value("${replay.sort.zone:Asia/Kolkata}")
    private String sortZone; // ZoneId id

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void run(String... args) throws Exception {
        log.info("🔁 Bulk replay sort enabled. Consuming, sorting by event-time, then producing to replay topics.");

        // Compute optional day filter
        Long fromMs = null, toMs = null;
        try {
            if (sortDate != null && !sortDate.isBlank()) {
                java.time.LocalDate d = java.time.LocalDate.parse(sortDate);
                java.time.ZoneId zone = java.time.ZoneId.of(sortZone);
                java.time.ZonedDateTime start = d.atStartOfDay(zone);
                java.time.ZonedDateTime end = start.plusDays(1);
                fromMs = start.toInstant().toEpochMilli();
                toMs = end.toInstant().toEpochMilli();
                log.info("📅 Day filter active: {} [{} → {}] ({})", sortDate, fromMs, toMs, sortZone);
            }
        } catch (Exception e) {
            log.warn("⚠️ Could not parse replay.sort.date='{}' or zone='{}' — proceeding without day filter", sortDate, sortZone);
        }

        final Long fFromMs = fromMs; final Long fToMs = toMs;

        Thread t1 = new Thread(() -> processTopic(sourceTicksTopic, targetTicksTopic, this::extractTickTs, fFromMs, fToMs), "bulk-replay-ticks");
        Thread t2 = new Thread(() -> processTopic(sourceOrderbookTopic, targetOrderbookTopic, this::extractOrderbookTs, fFromMs, fToMs), "bulk-replay-ob");
        Thread t3 = new Thread(() -> processTopic(sourceOiTopic, targetOiTopic, this::extractOiTs, fFromMs, fToMs), "bulk-replay-oi");

        t1.start(); t2.start(); t3.start();
        log.info("▶ Bulk replay threads started for ticks, orderbook, oi.");
    }

    private void processTopic(String source, String target, TimestampExtractor extractor, Long fromMs, Long toMs) {
        Properties cprops = new Properties();
        cprops.put("bootstrap.servers", bootstrapServers);
        cprops.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        cprops.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        cprops.put("group.id", "bulk-replay-" + source);
        cprops.put("enable.auto.commit", "false");
        cprops.put("auto.offset.reset", OffsetResetStrategy.EARLIEST.toString().toLowerCase());

        Properties pprops = new Properties();
        pprops.put("bootstrap.servers", bootstrapServers);
        pprops.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        pprops.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        // small batching
        pprops.put("acks", "1");
        pprops.put("compression.type", "lz4");

        List<Record> buffer = new ArrayList<>(1024);
        long lastRecordTime = System.currentTimeMillis();
        long count = 0;
        long skipped = 0;

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cprops);
             KafkaProducer<String, String> producer = new KafkaProducer<>(pprops)) {

            List<TopicPartition> tps = new ArrayList<>();
            consumer.partitionsFor(source).forEach(pi -> tps.add(new TopicPartition(pi.topic(), pi.partition())));
            consumer.assign(tps);
            consumer.seekToBeginning(tps);
            log.info("📥 [{}] Assigned {} partitions and seeking to beginning", source, tps.size());

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) {
                    if (System.currentTimeMillis() - lastRecordTime > maxIdleMs) {
                        log.info("⏸ [{}] No records for {} ms, finishing consume phase.", source, maxIdleMs);
                        break;
                    }
                    continue;
                }
                lastRecordTime = System.currentTimeMillis();
                for (ConsumerRecord<String, String> rec : records) {
                    long ts = extractor.extract(rec.value());
                    // Apply optional day filter
                    if (fromMs != null && toMs != null) {
                        if (ts < fromMs || ts >= toMs) { skipped++; continue; }
                    }
                    buffer.add(new Record(rec.key(), rec.value(), ts));
                    count++;
                    if (count % 100_000 == 0) {
                        log.info("📊 [{}] Buffered {:,} (skipped {:,}) so far", source, count, skipped);
                    }
                }
            }

            log.info("🧮 [{}] Sorting {:,} records by event-time (skipped {:,})", source, buffer.size(), skipped);
            buffer.sort(Comparator.comparingLong(r -> r.ts));
            log.info("🚚 [{}] Producing sorted records to {}", source, target);

            int produced = 0;
            for (Record r : buffer) {
                // Single partition (0). Set event-time as the record timestamp.
                ProducerRecord<String, String> pr = new ProducerRecord<>(target, 0, r.ts, r.key, r.value);
                producer.send(pr);
                produced++;
                if (produced % 100_000 == 0) {
                    log.info("✅ [{}] Produced {:,} records", source, produced);
                }
            }
            producer.flush();
            log.info("🎉 [{}] Done. Produced {:,} records", source, produced);
        } catch (Exception e) {
            log.error("❌ [{}] Bulk replay failed", source, e);
        }
    }

    private long extractTickTs(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            // Prefer TickDt: "/Date(…)/"
            if (n.hasNonNull("TickDt")) {
                String s = n.get("TickDt").asText("");
                String digits = s.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) return Long.parseLong(digits);
            }
            // Fallbacks
            if (n.hasNonNull("Time")) return n.get("Time").asLong();
            if (n.hasNonNull("timestamp")) return n.get("timestamp").asLong();
        } catch (Exception ignore) {}
        return System.currentTimeMillis();
    }

    private long extractOrderbookTs(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            if (n.hasNonNull("receivedTimestamp")) return n.get("receivedTimestamp").asLong();
            if (n.hasNonNull("timestamp")) return n.get("timestamp").asLong();
            // Infer from nested if present
            if (n.hasNonNull("bids") && n.has("bids")) {
                // no standard ts; fall back
            }
        } catch (Exception ignore) {}
        return System.currentTimeMillis();
    }

    private long extractOiTs(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            // Try generic fields
            if (n.hasNonNull("receivedTimestamp")) return n.get("receivedTimestamp").asLong();
            if (n.hasNonNull("timestamp")) return n.get("timestamp").asLong();
        } catch (Exception ignore) {}
        return System.currentTimeMillis();
    }

    interface TimestampExtractor { long extract(String json); }

    static class Record {
        final String key; final String value; final long ts;
        Record(String key, String value, long ts) { this.key = key; this.value = value; this.ts = ts; }
    }
}
