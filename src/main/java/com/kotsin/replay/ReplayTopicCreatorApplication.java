package com.kotsin.replay;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka Streams runner to create single-partition replay topics and copy data.
 * Runs inside the main Spring Boot app when replay.copy.enabled=true (default in replay profile).
 */
@Component
@Profile("replay")
@ConditionalOnProperty(value = "replay.copy.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class ReplayTopicCreatorApplication implements CommandLineRunner {

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

    @Value("${replay.replication.factor:1}")
    private short replicationFactor;

    @Value("${replay.auto.create.topics:true}")
    private boolean autoCreateTopics;

    private final AtomicLong ticksCounter = new AtomicLong(0);
    private final AtomicLong orderbookCounter = new AtomicLong(0);
    private final AtomicLong oiCounter = new AtomicLong(0);

    @Override
    public void run(String... args) throws Exception {
        log.info("════════════════════════════════════════════════════════");
        log.info("🚀 Starting Replay Topic Creator");
        log.info("════════════════════════════════════════════════════════");
        log.info("📋 Source Topics (multi-partition): {}, {}, {}", sourceTicksTopic, sourceOrderbookTopic, sourceOiTopic);
        log.info("🎯 Target Topics (single-partition): {}, {}, {}", targetTicksTopic, targetOrderbookTopic, targetOiTopic);

        if (autoCreateTopics) {
            createReplayTopics();
        }

        Properties props = buildStreamsConfig();
        KafkaStreams streams = buildTopology(props);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("🛑 Shutting down Replay Topic Creator...");
            streams.close();
            printFinalStats();
            log.info("✅ Shutdown complete");
        }));

        streams.start();
        log.info("✅ Kafka Streams started. Copying data to replay topics...");
        monitorProgress(streams);
    }

    private void createReplayTopics() {
        log.info("📝 Creating single-partition replay topics (if missing)...");
        Properties adminProps = new Properties();
        adminProps.put("bootstrap.servers", bootstrapServers);
        try (AdminClient admin = AdminClient.create(adminProps)) {
            Map<String, String> topicConfigs = new HashMap<>();
            topicConfigs.put("retention.ms", "-1");
            topicConfigs.put("segment.ms", "86400000");
            topicConfigs.put("compression.type", "lz4");
            topicConfigs.put("min.insync.replicas", "1");
            topicConfigs.put("segment.bytes", "1073741824");
            topicConfigs.put("cleanup.policy", "delete");

            List<NewTopic> topics = Arrays.asList(
                createTopicWithConfig(targetTicksTopic, topicConfigs),
                createTopicWithConfig(targetOrderbookTopic, topicConfigs),
                createTopicWithConfig(targetOiTopic, topicConfigs)
            );

            admin.createTopics(topics).all().get();
            log.info("✅ Created replay topics");
        } catch (ExecutionException e) {
            if (e.getCause() != null && e.getCause().getMessage() != null && e.getCause().getMessage().contains("already exists")) {
                log.warn("⚠️  Topics already exist; proceeding");
            } else {
                log.error("❌ Failed to create topics", e);
                throw new RuntimeException("Failed to create replay topics", e);
            }
        } catch (Exception e) {
            log.error("❌ Failed to create topics", e);
            throw new RuntimeException("Failed to create replay topics", e);
        }
    }

    private NewTopic createTopicWithConfig(String name, Map<String, String> configs) {
        NewTopic topic = new NewTopic(name, 1, replicationFactor);
        topic.configs(configs);
        return topic;
    }

    private Properties buildStreamsConfig() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "replay-topic-creator-v1");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put("auto.offset.reset", "earliest");
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 3);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 10_000);
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 10_485_760); // 10MB
        props.put("max.poll.records", 10_000);
        props.put("fetch.min.bytes", 1_048_576);
        props.put("fetch.max.wait.ms", 500);
        props.put("compression.type", "lz4");
        props.put("batch.size", 131_072);
        props.put("linger.ms", 10);
        props.put("acks", "1");
        props.put("buffer.memory", 67_108_864);
        return props;
    }

    private KafkaStreams buildTopology(Properties props) {
        StreamsBuilder builder = new StreamsBuilder();

        log.info("🔄 Building stream: {} → {}", sourceTicksTopic, targetTicksTopic);
        KStream<String, String> ticksStream = builder.stream(
            sourceTicksTopic,
            Consumed.with(Serdes.String(), Serdes.String())
        );
        ticksStream
            .peek((k, v) -> {
                long c = ticksCounter.incrementAndGet();
                if (c % 100_000 == 0) log.info("📊 Ticks copied: {:,}", c);
            })
            .to(targetTicksTopic, Produced.with(Serdes.String(), Serdes.String()));

        log.info("🔄 Building stream: {} → {}", sourceOrderbookTopic, targetOrderbookTopic);
        KStream<String, String> orderbookStream = builder.stream(
            sourceOrderbookTopic,
            Consumed.with(Serdes.String(), Serdes.String())
        );
        orderbookStream
            .peek((k, v) -> {
                long c = orderbookCounter.incrementAndGet();
                if (c % 50_000 == 0) log.info("📊 Orderbook copied: {:,}", c);
            })
            .to(targetOrderbookTopic, Produced.with(Serdes.String(), Serdes.String()));

        log.info("🔄 Building stream: {} → {}", sourceOiTopic, targetOiTopic);
        KStream<String, String> oiStream = builder.stream(
            sourceOiTopic,
            Consumed.with(Serdes.String(), Serdes.String())
        );
        oiStream
            .peek((k, v) -> {
                long c = oiCounter.incrementAndGet();
                if (c % 10_000 == 0) log.info("📊 OI copied: {:,}", c);
            })
            .to(targetOiTopic, Produced.with(Serdes.String(), Serdes.String()));

        log.info("✅ Topology built with 3 independent copy streams");
        return new KafkaStreams(builder.build(), props);
    }

    private void monitorProgress(KafkaStreams streams) {
        Thread t = new Thread(() -> {
            long lastTick = 0, lastOb = 0, lastOi = 0; long start = System.currentTimeMillis();
            try {
                while (streams.state() != KafkaStreams.State.RUNNING) Thread.sleep(1000);
                log.info("📈 COPY PROGRESS (every 30s)");
                while (streams.state() == KafkaStreams.State.RUNNING) {
                    Thread.sleep(30_000);
                    long ct = ticksCounter.get(), co = orderbookCounter.get(), ci = oiCounter.get();
                    long tickRate = (ct - lastTick) / 30, obRate = (co - lastOb) / 30, oiRate = (ci - lastOi) / 30;
                    long elapsed = (System.currentTimeMillis() - start) / 1000;
                    log.info("⏱️ {}m {}s | Ticks {:,} ({}sps) | OB {:,} ({}sps) | OI {:,} ({}sps) | Total {:,}",
                        elapsed/60, elapsed%60, ct, tickRate, co, obRate, ci, oiRate, (ct+co+ci));
                    lastTick = ct; lastOb = co; lastOi = ci;
                }
            } catch (InterruptedException ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }

    private void printFinalStats() {
        log.info("📊 FINAL | Ticks {:,} | OB {:,} | OI {:,} | Total {:,}",
            ticksCounter.get(), orderbookCounter.get(), oiCounter.get(),
            ticksCounter.get() + orderbookCounter.get() + oiCounter.get());
    }
}
