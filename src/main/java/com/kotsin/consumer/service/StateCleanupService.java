package com.kotsin.consumer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🧹 STREAMING CANDLE STATE CLEANUP SERVICE
 * Prevents state directory conflicts and memory issues
 */
@Service
@Slf4j
public class StateCleanupService {

    @Value("${kafka.streams.state.dir:/var/lib/kafka-streams}")
    private String baseStateDir;
    
    private final AtomicLong cleanupCount = new AtomicLong(0);

    /**
     * 🧹 AUTO-CLEANUP SCHEDULER - Every 6 hours during non-market hours
     * Cleans up old state directories to prevent conflicts
     */
    @Scheduled(fixedRate = 21600000) // Every 6 hours
    public void performStateCleanup() {
        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalTime currentTime = LocalTime.now(istZone);
        
        // Run cleanup during non-market hours (2 AM to 8 AM)
        LocalTime cleanupStart = LocalTime.of(2, 0);
        LocalTime cleanupEnd = LocalTime.of(8, 0);
        boolean isCleanupTime = !currentTime.isBefore(cleanupStart) && currentTime.isBefore(cleanupEnd);
        
        if (!isCleanupTime) {
            return; // Skip during market hours
        }

        log.info("🧹 [STATE-CLEANUP] Starting state directory cleanup");
        
        try {
            cleanupOldStateDirectories();
            forceGarbageCollection();
            cleanupCount.incrementAndGet();
            
            log.info("✅ [STATE-CLEANUP] Cleanup completed (total cleanups: {})", cleanupCount.get());
            
        } catch (Exception e) {
            log.error("🚨 [STATE-CLEANUP] Cleanup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 🗑️ CLEANUP OLD STATE DIRECTORIES
     * Removes old Kafka Streams state directories to prevent conflicts
     */
    private void cleanupOldStateDirectories() {
        try {
            File stateBaseDir = new File(baseStateDir);
            
            if (!stateBaseDir.exists()) {
                log.debug("State directory doesn't exist: {}", baseStateDir);
                return;
            }
            
            File[] directories = stateBaseDir.listFiles(File::isDirectory);
            if (directories == null) {
                log.debug("No state directories found");
                return;
            }
            
            int deletedCount = 0;
            long currentTime = System.currentTimeMillis();
            long oneDayAgo = currentTime - (24 * 60 * 60 * 1000); // 24 hours ago
            
            for (File dir : directories) {
                // Delete directories older than 24 hours that match our naming pattern
                if (dir.getName().startsWith("realtime-candle-") && dir.lastModified() < oneDayAgo) {
                    try {
                        deleteDirectory(dir);
                        deletedCount++;
                        log.debug("Deleted old state directory: {}", dir.getName());
                    } catch (Exception e) {
                        log.warn("Failed to delete state directory {}: {}", dir.getName(), e.getMessage());
                    }
                }
            }
            
            if (deletedCount > 0) {
                log.info("🗑️ [STATE-CLEANUP] Deleted {} old state directories", deletedCount);
            } else {
                log.debug("No old state directories to clean");
            }
            
        } catch (Exception e) {
            log.error("Failed to cleanup state directories: {}", e.getMessage());
        }
    }

    /**
     * 🗂️ RECURSIVE DIRECTORY DELETION
     */
    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }

    /**
     * 🧠 FORCE GARBAGE COLLECTION
     * Helps with memory management for long-running Kafka Streams
     */
    private void forceGarbageCollection() {
        try {
            // Get memory stats before GC
            Runtime runtime = Runtime.getRuntime();
            long beforeUsed = runtime.totalMemory() - runtime.freeMemory();
            
            // Force garbage collection
            System.gc();
            
            // Wait a bit for GC to complete
            Thread.sleep(1000);
            
            // Get memory stats after GC
            long afterUsed = runtime.totalMemory() - runtime.freeMemory();
            long freed = beforeUsed - afterUsed;
            
            log.info("🧠 [STATE-CLEANUP] GC completed - freed {} MB", freed / (1024 * 1024));
            
        } catch (Exception e) {
            log.warn("Failed to perform garbage collection: {}", e.getMessage());
        }
    }

    /**
     * 🚑 EMERGENCY STATE CLEANUP
     * Can be called manually when state conflicts occur
     */
    public void performEmergencyStateCleanup() {
        log.warn("🚑 [STATE-CLEANUP] EMERGENCY state cleanup initiated");
        
        try {
            // More aggressive cleanup - remove all old state directories
            File stateBaseDir = new File(baseStateDir);
            
            if (stateBaseDir.exists()) {
                File[] directories = stateBaseDir.listFiles(File::isDirectory);
                if (directories != null) {
                    int deletedCount = 0;
                    for (File dir : directories) {
                        if (dir.getName().startsWith("realtime-candle-")) {
                            try {
                                deleteDirectory(dir);
                                deletedCount++;
                                log.info("Emergency deleted state directory: {}", dir.getName());
                            } catch (Exception e) {
                                log.error("Failed to emergency delete {}: {}", dir.getName(), e.getMessage());
                            }
                        }
                    }
                    log.info("🚑 [STATE-CLEANUP] Emergency cleanup deleted {} directories", deletedCount);
                }
            }
            
            // Force multiple GC cycles
            for (int i = 0; i < 3; i++) {
                System.gc();
                Thread.sleep(500);
            }
            
            cleanupCount.incrementAndGet();
            log.info("✅ [STATE-CLEANUP] Emergency cleanup completed");
            
        } catch (Exception e) {
            log.error("🚨 [STATE-CLEANUP] Emergency cleanup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 📊 GET CLEANUP STATISTICS
     */
    public String getCleanupStats() {
        try {
            File stateBaseDir = new File(baseStateDir);
            int currentDirectories = 0;
            
            if (stateBaseDir.exists()) {
                File[] directories = stateBaseDir.listFiles(File::isDirectory);
                if (directories != null) {
                    for (File dir : directories) {
                        if (dir.getName().startsWith("realtime-candle-")) {
                            currentDirectories++;
                        }
                    }
                }
            }
            
            return String.format("Cleanups performed: %d, Current state directories: %d", 
                    cleanupCount.get(), currentDirectories);
                    
        } catch (Exception e) {
            return String.format("Cleanups performed: %d, Error checking directories: %s", 
                    cleanupCount.get(), e.getMessage());
        }
    }
} 