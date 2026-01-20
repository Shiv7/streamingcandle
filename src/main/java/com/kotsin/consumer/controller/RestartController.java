package com.kotsin.consumer.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * REST controller for restart operations.
 */
@Slf4j
@RestController
@RequestMapping("/api/restart")
@RequiredArgsConstructor
public class RestartController {

    private final ApplicationContext applicationContext;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private volatile String restartStatus = "IDLE";
    private volatile LocalDateTime restartRequestTime;

    /**
     * Initiate graceful restart
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> restart() {
        if ("RESTARTING".equals(restartStatus)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Restart already in progress",
                    "status", restartStatus,
                    "requestedAt", restartRequestTime != null ? restartRequestTime.toString() : null
            ));
        }

        restartStatus = "RESTARTING";
        restartRequestTime = LocalDateTime.now();
        log.warn("Graceful restart requested at {}", restartRequestTime);

        // Schedule restart after 2 seconds to allow response to be sent
        executor.schedule(() -> {
            log.warn("Executing graceful restart...");
            SpringApplication.exit(applicationContext, () -> 0);
        }, 2, TimeUnit.SECONDS);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Graceful restart initiated. Service will restart in 2 seconds.",
                "status", restartStatus,
                "requestedAt", restartRequestTime.toString()
        ));
    }

    /**
     * Force restart (immediate)
     */
    @PostMapping("/force")
    public ResponseEntity<Map<String, Object>> forceRestart() {
        restartStatus = "FORCE_RESTARTING";
        restartRequestTime = LocalDateTime.now();
        log.warn("Force restart requested at {}", restartRequestTime);

        // Force restart immediately
        executor.execute(() -> {
            log.warn("Executing force restart...");
            System.exit(0);
        });

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Force restart initiated.",
                "status", restartStatus,
                "requestedAt", restartRequestTime.toString()
        ));
    }

    /**
     * Get restart status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getRestartStatus() {
        return ResponseEntity.ok(Map.of(
                "status", restartStatus,
                "requestedAt", restartRequestTime != null ? restartRequestTime.toString() : null,
                "currentTime", LocalDateTime.now().toString()
        ));
    }
}
