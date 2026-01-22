package com.kotsin.consumer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * AsyncConfig - Configuration for async operations.
 *
 * Provides thread pools for:
 * - bootstrapExecutor: For async API calls (candle history bootstrap)
 * - enrichmentExecutor: For parallel enrichment operations
 *
 * This prevents blocking Kafka consumer threads during:
 * - Historical data bootstrap from 5paisa API
 * - Heavy enrichment calculations
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /**
     * Executor for bootstrap operations (API calls to fetch historical candles).
     * Dedicated pool to prevent blocking Kafka consumer threads.
     */
    @Bean(name = "bootstrapExecutor")
    public Executor bootstrapExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("bootstrap-");
        executor.setRejectedExecutionHandler((r, e) ->
            log.warn("Bootstrap task rejected - queue full, skipping bootstrap"));
        executor.initialize();
        log.info("Created bootstrapExecutor: core=4, max=8, queue=100");
        return executor;
    }

    /**
     * Executor for parallel enrichment operations.
     * Used by EnrichedQuantScoreCalculator for parallel phase execution.
     */
    @Bean(name = "enrichmentExecutor")
    public Executor enrichmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("enrichment-");
        executor.setRejectedExecutionHandler((r, e) ->
            log.warn("Enrichment task rejected - queue full"));
        executor.initialize();
        log.info("Created enrichmentExecutor: core=8, max=16, queue=500");
        return executor;
    }
}
