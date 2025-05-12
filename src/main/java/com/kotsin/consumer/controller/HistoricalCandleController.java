package com.kotsin.consumer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.processor.CandlestickProcessor;
import com.kotsin.consumer.service.HistoricalDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controller for handling historical data processing requests.
 * Provides endpoints for:
 * 1. Loading and processing historical tick data to generate candles
 * 2. Checking the status of historical processing jobs
 * 3. Generating candles for specific date ranges
 */
@RestController
@RequestMapping("/api/historical")
public class HistoricalCandleController {

    private static final Logger LOGGER = LoggerFactory.getLogger(HistoricalCandleController.class);
    private final Map<String, JobStatus> jobStatusMap = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private KafkaTemplate<String, TickData> kafkaTemplate;

    @Autowired
    private HistoricalDataService historicalDataService;

    /**
     * Process historical tick data for the given date range.
     * This endpoint loads tick data from the database or file storage and
     * sends it to Kafka to be processed by the existing Candlestick processor.
     *
     * @param scripCode The script code to process
     * @param startDate The start date for historical data (inclusive)
     * @param endDate The end date for historical data (inclusive)
     * @param speedMultiplier Optional speed multiplier for faster processing
     * @return Response with job ID and initial status
     */
    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processHistoricalData(
            @RequestParam String scripCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "100") int speedMultiplier) {

        String jobId = "historical-" + scripCode + "-" + System.currentTimeMillis();
        
        // Initialize job status
        JobStatus status = new JobStatus(jobId, scripCode, startDate, endDate);
        jobStatusMap.put(jobId, status);
        
        // Start processing in background
        CompletableFuture.runAsync(() -> {
            try {
                status.setStatus("LOADING");
                
                // Load historical tick data for the date range
                List<TickData> tickData = historicalDataService.getHistoricalTickData(
                        scripCode, startDate, endDate);
                
                status.setTotalTicks(tickData.size());
                status.setStatus("PROCESSING");
                
                // Stream tick data to Kafka with timestamps
                streamTicksToKafka(tickData, speedMultiplier, status);
                
                status.setStatus("COMPLETED");
                LOGGER.info("Historical data processing job completed: {}", jobId);
            } catch (Exception e) {
                status.setStatus("FAILED");
                status.setErrorMessage(e.getMessage());
                LOGGER.error("Error in historical data processing job: {}", jobId, e);
            }
        });
        
        // Return job ID and initial status
        Map<String, Object> response = new HashMap<>();
        response.put("jobId", jobId);
        response.put("status", status.getStatus());
        response.put("totalTicks", status.getTotalTicks());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get the status of a historical data processing job.
     *
     * @param jobId The job ID returned from the process endpoint
     * @return Response with current job status
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<JobStatus> getJobStatus(@PathVariable String jobId) {
        JobStatus status = jobStatusMap.get(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }
    
    /**
     * List all historical data processing jobs.
     *
     * @return Response with all jobs and their statuses
     */
    @GetMapping("/jobs")
    public ResponseEntity<List<JobStatus>> getAllJobs() {
        return ResponseEntity.ok(jobStatusMap.values().stream().toList());
    }
    
    /**
     * Cancel a running historical data processing job.
     *
     * @param jobId The job ID to cancel
     * @return Response indicating if the job was cancelled
     */
    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable String jobId) {
        JobStatus status = jobStatusMap.get(jobId);
        Map<String, Object> response = new HashMap<>();
        
        if (status == null) {
            response.put("success", false);
            response.put("message", "Job not found");
            return ResponseEntity.notFound().build();
        }
        
        if (status.getStatus().equals("PROCESSING") || status.getStatus().equals("LOADING")) {
            status.setCancelled(true);
            status.setStatus("CANCELLED");
            response.put("success", true);
            response.put("message", "Job cancelled");
        } else {
            response.put("success", false);
            response.put("message", "Job is already " + status.getStatus());
        }
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Stream tick data to Kafka for processing.
     * This will produce ticks to the same topic that real-time data goes to,
     * but with historical timestamps. The Candlestick processor will handle
     * them as if they were real-time data.
     *
     * @param ticks Tick data to process
     * @param speedMultiplier Speed multiplier for faster processing
     * @param status Job status to update during processing
     */
    private void streamTicksToKafka(List<TickData> ticks, int speedMultiplier, JobStatus status) {
        if (ticks.isEmpty()) {
            return;
        }
        
        LOGGER.info("Streaming {} historical ticks to Kafka", ticks.size());
        
        // Get the first tick's timestamp as a reference
        TickData firstTick = ticks.get(0);
        long processingStartTime = System.currentTimeMillis();
        
        AtomicInteger processedCount = new AtomicInteger(0);
        
        // Stream ticks to Kafka with appropriate pacing
        for (TickData tick : ticks) {
            // Check if job was cancelled
            if (status.isCancelled()) {
                LOGGER.info("Historical data processing job cancelled after {} ticks", processedCount.get());
                break;
            }
            
            // Send tick to Kafka
            kafkaTemplate.send("forwardtesting-data", tick.getScripCode(), tick);
            
            // Update counts and status
            int processed = processedCount.incrementAndGet();
            if (processed % 1000 == 0) {
                status.setProcessedTicks(processed);
                LOGGER.info("Processed {} of {} historical ticks", processed, ticks.size());
            }
            
            // Sleep to simulate realistic timing if needed
            if (speedMultiplier < 1000) {
                try {
                    // Simple delay to prevent overwhelming the system
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        status.setProcessedTicks(processedCount.get());
        LOGGER.info("Completed sending {} historical ticks to Kafka", processedCount.get());
    }
    
    /**
     * Class to track the status of historical data processing jobs.
     */
    public static class JobStatus {
        private final String jobId;
        private final String scripCode;
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final LocalDateTime createdAt;
        private String status = "CREATED";
        private int totalTicks = 0;
        private int processedTicks = 0;
        private boolean cancelled = false;
        private String errorMessage = null;
        
        public JobStatus(String jobId, String scripCode, LocalDate startDate, LocalDate endDate) {
            this.jobId = jobId;
            this.scripCode = scripCode;
            this.startDate = startDate;
            this.endDate = endDate;
            this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        }
        
        // Getters and setters
        public String getJobId() { return jobId; }
        public String getScripCode() { return scripCode; }
        public LocalDate getStartDate() { return startDate; }
        public LocalDate getEndDate() { return endDate; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getTotalTicks() { return totalTicks; }
        public void setTotalTicks(int totalTicks) { this.totalTicks = totalTicks; }
        public int getProcessedTicks() { return processedTicks; }
        public void setProcessedTicks(int processedTicks) { this.processedTicks = processedTicks; }
        public boolean isCancelled() { return cancelled; }
        public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        
        public double getProgressPercentage() {
            if (totalTicks == 0) return 0;
            return (double) processedTicks / totalTicks * 100;
        }
    }
} 