package com.kotsin.consumer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

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

    @Value("${bootstrap.executor.pool.size:4}")
    private int bootstrapPoolSize;

    @Value("${bootstrap.executor.queue.capacity:100}")
    private int bootstrapQueueCapacity;

    @Value("${bootstrap.executor.thread.prefix:bootstrap-hist-api-}")
    private String bootstrapThreadPrefix;

    /**
     * Executor for bootstrap operations (API calls to fetch historical candles).
     * Dedicated pool to prevent blocking Kafka consumer threads.
     *
     * Uses CallerRunsPolicy: If queue is full, caller thread executes (backpressure).
     * This ensures bootstrap is never skipped, just slowed down.
     */
    @Bean(name = "bootstrapExecutor")
    public Executor bootstrapExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Fixed pool size for predictable behavior
        executor.setCorePoolSize(bootstrapPoolSize);
        executor.setMaxPoolSize(bootstrapPoolSize);
        executor.setQueueCapacity(bootstrapQueueCapacity);
        executor.setThreadNamePrefix(bootstrapThreadPrefix);

        // CallerRunsPolicy: If queue is full, caller thread executes (backpressure)
        executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                log.warn("[BOOTSTRAP-EXECUTOR] Queue full, executing in caller thread. " +
                        "activeCount={}, queueSize={}",
                        e.getActiveCount(), e.getQueue().size());
                if (!e.isShutdown()) {
                    r.run();
                }
            }
        });

        // Allow core threads to timeout when idle
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);

        // Wait for tasks to complete on shutdown
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();

        log.info("[BOOTSTRAP-EXECUTOR] Initialized: poolSize={}, queueCapacity={}, threadPrefix={}",
                bootstrapPoolSize, bootstrapQueueCapacity, bootstrapThreadPrefix);
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
