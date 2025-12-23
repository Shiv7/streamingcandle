package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.service.IPUCalculator;
import com.kotsin.consumer.service.VCPCalculator;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * TradingSignalProcessor - Join VCP + IPU into unified TradingSignal
 * 
 * Data Flow:
 * 1. Input: vcp-combined, ipu-signals-5m
 * 2. Join by scripCode using KTable
 * 3. Apply signal classification from TradingSignal.from()
 * 4. Output: trading-signals
 * 
 * This is the FINAL output topic for downstream consumers (strategy, RL, dashboard)
 */
@Component
public class TradingSignalProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TradingSignalProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private IPUCalculator ipuCalculator;

    private KafkaStreams streams;

    // Topic configuration
    @Value("${trading.input.vcp:vcp-combined}")
    private String vcpTopic;

    @Value("${trading.input.ipu:ipu-signals-5m}")
    private String ipuTopic;

    @Value("${trading.output:trading-signals}")
    private String outputTopic;

    // Feature toggle
    @Value("${trading.enabled:true}")
    private boolean enabled;

    /**
     * Build and start the trading signal processor
     */
    public void start() {
        if (!enabled) {
            LOGGER.info("⏸️ TradingSignalProcessor is disabled");
            return;
        }

        String instanceKey = "trading-signal-processor";

        Properties props = kafkaConfig.getStreamProperties(instanceKey);
        StreamsBuilder builder = new StreamsBuilder();

        buildTradingSignalTopology(builder);

        streams = new KafkaStreams(builder.build(), props);

        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("TradingSignalProcessor state: {} -> {}", oldState, newState);
            if (newState == KafkaStreams.State.ERROR) {
                LOGGER.error("❌ TradingSignalProcessor entered ERROR state!");
            }
        });

        streams.setUncaughtExceptionHandler((Throwable e) -> {
            LOGGER.error("❌ Exception in TradingSignalProcessor: ", e);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        try {
            streams.start();
            LOGGER.info("✅ Started TradingSignalProcessor");
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start TradingSignalProcessor: ", e);
        }
    }

    /**
     * Build topology that joins VCP + IPU
     */
    private void buildTradingSignalTopology(StreamsBuilder builder) {
        // Read IPU stream - this drives signal emission
        KStream<String, IPUOutput> ipuStream = builder.stream(
                ipuTopic,
                Consumed.with(Serdes.String(), IPUOutput.serde())
        );

        // VCP as KTable for lookup (will be empty until VCP starts producing)
        // Note: Using KTable instead of GlobalKTable because GlobalKTable requires topic to exist at startup
        KTable<String, MTVCPOutput> vcpTable = builder.table(
                vcpTopic,
                Consumed.with(Serdes.String(), MTVCPOutput.serde()),
                Materialized.<String, MTVCPOutput, KeyValueStore<Bytes, byte[]>>
                        as("vcp-store")
                        .withKeySerde(Serdes.String())
                        .withValueSerde(MTVCPOutput.serde())
        );

        // Join IPU with VCP (IPU drives emission, VCP lookup)
        KStream<String, TradingSignal> tradingSignals = ipuStream
                .leftJoin(
                        vcpTable,
                        (ipu, vcp) -> {
                            // Build multi-TF IPU if we have cached results FOR THIS SCRIPCODE
                            IPUOutput ipuCombined = ipu;
                            String scripCode = ipu != null ? ipu.getScripCode() : null;
                            
                            if (scripCode != null) {
                                IPUOutput ipu15m = IPUProcessor.getCached15mResult(scripCode);
                                IPUOutput ipu30m = IPUProcessor.getCached30mResult(scripCode);
                                
                                if (ipu15m != null && ipu30m != null) {
                                    ipuCombined = ipuCalculator.buildCombinedOutput(ipu, ipu15m, ipu30m);
                                }
                            }
                            
                            return TradingSignal.from(vcp, ipuCombined);
                        }
                );

        // Emit trading signals
        tradingSignals
                .filter((k, v) -> v != null && v.getSignal() != TradingSignal.SignalType.NO_SIGNAL)
                .peek((k, v) -> {
                    LOGGER.info("🎯 TRADING SIGNAL | scrip={} signal={} confidence={} | {}",
                            k, v.getSignal(), String.format("%.2f", v.getConfidence()), v.getRationale());
                })
                .to(outputTopic, Produced.with(Serdes.String(), TradingSignal.serde()));

        // Also emit NO_SIGNAL for downstream awareness (optional - comment out if too noisy)
        tradingSignals
                .filter((k, v) -> v != null && v.getSignal() == TradingSignal.SignalType.NO_SIGNAL)
                .filter((k, v) -> v.getIpuFinalScore() > 0.3)  // Only emit if there's some activity
                .peek((k, v) -> {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("📊 NO_SIGNAL | scrip={} ipu={} vcp={}",
                                k, String.format("%.2f", v.getIpuFinalScore()), 
                                String.format("%.2f", v.getVcpCombinedScore()));
                    }
                })
                .to(outputTopic, Produced.with(Serdes.String(), TradingSignal.serde()));

        LOGGER.info("📐 Built TradingSignal join topology: {} + {} -> {}",
                vcpTopic, ipuTopic, outputTopic);
    }

    /**
     * Start after VCP and IPU processors
     */
    @PostConstruct
    public void init() {
        if (!enabled) {
            LOGGER.info("⏸️ TradingSignalProcessor is disabled");
            return;
        }

        LOGGER.info("🚀 Scheduling TradingSignalProcessor startup...");

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // Wait for VCP and IPU processors to start
                Thread.sleep(18000);

                start();
                LOGGER.info("✅ TradingSignalProcessor started");

            } catch (Exception e) {
                LOGGER.error("❌ Error starting TradingSignalProcessor", e);
            }
        });
    }

    /**
     * Get stream state
     */
    public KafkaStreams.State getState() {
        return streams != null ? streams.state() : null;
    }

    @PreDestroy
    public void stop() {
        if (streams != null) {
            LOGGER.info("🛑 Stopping TradingSignalProcessor");
            try {
                streams.close(Duration.ofSeconds(30));
                LOGGER.info("✅ TradingSignalProcessor stopped");
            } catch (Exception e) {
                LOGGER.error("Error stopping TradingSignalProcessor: ", e);
            }
        }
    }
}
