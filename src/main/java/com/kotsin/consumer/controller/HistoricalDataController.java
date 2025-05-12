package com.kotsin.consumer.controller;

import com.kotsin.consumer.service.HistoricalDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller for managing historical data processing.
 */
@RestController
@RequestMapping("/api/historical")
public class HistoricalDataController {

    private static final Logger LOGGER = LoggerFactory.getLogger(HistoricalDataController.class);
    private final Map<String, JobStatus> jobStatusMap = new ConcurrentHashMap<>();
    
    @Autowired
    private HistoricalDataService historicalDataService;
    
    /**
     * Process historical data for a specific script and date range.
     */
    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processHistoricalData(
            @RequestParam String scripCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "100") int speedFactor) {
        
        String jobId = "hist-" + scripCode + "-" + System.currentTimeMillis();
        
        // Initialize job status
        JobStatus status = new JobStatus(jobId, scripCode, startDate, endDate, "CREATED");
        jobStatusMap.put(jobId, status);
        
        // Start processing in background
        CompletableFuture.runAsync(() -> {
            try {
                status.setStatus("PROCESSING");
                historicalDataService.processHistoricalData(scripCode, startDate, endDate, speedFactor);
                status.setStatus("COMPLETED");
            } catch (Exception e) {
                LOGGER.error("Error processing historical data", e);
                status.setStatus("FAILED");
                status.setErrorMessage(e.getMessage());
            }
        });
        
        // Return job ID and status
        Map<String, Object> response = new HashMap<>();
        response.put("jobId", jobId);
        response.put("status", "Started historical data processing");
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get status of a historical data processing job.
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
     * Job status tracking class.
     */
    public static class JobStatus {
        private final String jobId;
        private final String scripCode;
        private final LocalDate startDate;
        private final LocalDate endDate;
        private String status;
        private String errorMessage;
        
        public JobStatus(String jobId, String scripCode, LocalDate startDate, LocalDate endDate, String status) {
            this.jobId = jobId;
            this.scripCode = scripCode;
            this.startDate = startDate;
            this.endDate = endDate;
            this.status = status;
        }
        
        public String getJobId() { return jobId; }
        public String getScripCode() { return scripCode; }
        public LocalDate getStartDate() { return startDate; }
        public LocalDate getEndDate() { return endDate; }
        public String getStatus() { return status; }
        
        public void setStatus(String status) { this.status = status; }
        
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }
} 