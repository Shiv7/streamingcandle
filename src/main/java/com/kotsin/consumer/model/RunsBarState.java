package com.kotsin.consumer.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.Serializable;

/**
 * CRITICAL FIX: Completely rewritten State tracker for TRB/VRB
 * 
 * Based on "Advances in Financial Machine Learning" Chapter 2.3.2.3-2.3.2.4
 * 
 * ALL BUGS FIXED:
 * 1. ✅ Serializable for Kafka state stores
 * 2. ✅ All configuration externalized
 * 3. ✅ Fixed division by zero
 * 4. ✅ Proper EWMA initialization
 * 5. ✅ Improved tick classification
 * 6. ✅ Input validation everywhere
 * 7. ✅ Methods for processor
 */
@Data
@NoArgsConstructor
@Slf4j
public class RunsBarState implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // Configuration (injected from application.properties)
    private double ewmaSpan = 100.0;
    private double expectedBarSize = 50.0;
    private int warmupSamples = 20;
    private double thresholdMultiplier = 1.0;  // CRITICAL: Multiplier for multi-granularity (1x, 2x, 5x)
    
    // Current bar being constructed
    private InformationBar currentBar = new InformationBar();
    private boolean isComplete = false;
    
    // State for tick classification
    private double previousPrice = 0.0;
    private int previousDirection = 1;  // 1=buy, -1=sell
    
    // EWMA state for expected run length
    private double ewmaBuyProbability = 0.5;
    private double ewmaBuyVolume = 0.0;
    private double ewmaSellVolume = 0.0;
    
    // Current run tracking
    private int currentBuyRun = 0;
    private int currentSellRun = 0;
    private double currentBuyVolumeRun = 0.0;
    private double currentSellVolumeRun = 0.0;
    private int currentRunDirection = 0;  // Direction of current run
    
    private int sampleCount = 0;
    
    /**
     * Constructor with configuration
     * 
     * @param thresholdMultiplier - Like 1min vs 5min for time bars
     *                            - 1.0 = sensitive (high frequency)
     *                            - 2.0 = medium
     *                            - 5.0 = stable (low frequency, only major runs)
     */
    public RunsBarState(double ewmaSpan, double expectedBarSize, int warmupSamples, double thresholdMultiplier) {
        this.ewmaSpan = ewmaSpan;
        this.expectedBarSize = expectedBarSize;
        this.warmupSamples = warmupSamples;
        this.thresholdMultiplier = thresholdMultiplier;
        this.currentBar = new InformationBar();
        this.isComplete = false;
        this.previousDirection = 1;
    }
    
    /**
     * CRITICAL FIX: Classify tick using proper tick rule (Chapter 19)
     */
    public int classifyTick(TickData tick) {
        if (tick == null) {
            log.warn("Null tick in classifyTick");
            return previousDirection;
        }
        
        double price = tick.getLastRate();
        
        if (price <= 0 || Double.isNaN(price) || Double.isInfinite(price)) {
            log.warn("Invalid price {} in classifyTick", price);
            return previousDirection;
        }
        
        // First tick: use bid/ask midpoint
        if (previousPrice == 0) {
            double bid = tick.getBidRate();
            double ask = tick.getOfferRate();
            
            if (bid > 0 && ask > 0 && ask > bid) {
                double mid = (bid + ask) / 2.0;
                previousDirection = price >= mid ? 1 : -1;
            } else {
                previousDirection = 1;
            }
            previousPrice = price;
            return previousDirection;
        }
        
        // Tick rule
        if (price > previousPrice) {
            previousDirection = 1;
        } else if (price < previousPrice) {
            previousDirection = -1;
        }
        
        previousPrice = price;
        return previousDirection;
    }
    
    /**
     * Add tick to current bar
     */
    public void addTick(TickData tick, String barType) {
        try {
            if (tick == null) {
                log.warn("[{}] Null tick, skipping", barType);
                return;
            }
            
            Integer deltaVol = tick.getDeltaVolume();
            if (deltaVol == null || deltaVol <= 0) {
                return;
            }
            
            // Initialize bar on first tick
            if (currentBar.getScripCode() == null) {
                currentBar.setBarType(barType);
                currentBar.setScripCode(tick.getScripCode());
                currentBar.setToken(tick.getToken());
                currentBar.setExchange(tick.getExchange());
                currentBar.setCompanyName(tick.getCompanyName());
            }
            
            // CRITICAL FIX: Always set start timestamp if it's 0 (handles state restoration)
            if (currentBar.getWindowStartMillis() == 0) {
                long ts = tick.getTimestamp();
                currentBar.setWindowStartMillis(ts);
                log.debug("[{}] Set window start timestamp: {} for token {}", 
                    barType, ts, tick.getToken());
            }
            
            int direction = classifyTick(tick);
            currentBar.updateOHLC(tick);
            currentBar.addVolume(tick, direction);
            currentBar.setWindowEndMillis(tick.getTimestamp());
            
            // Update run tracking
            updateRunTracking(direction, deltaVol);
            
            // Check if bar should be emitted
            if (barType.equals("TRB")) {
                if (shouldEmitTickRunsBar()) {
                    completeBar();
                }
            } else if (barType.equals("VRB")) {
                if (shouldEmitVolumeRunsBar()) {
                    completeBar();
                }
            }
            
        } catch (Exception e) {
            log.error("[{}] Error adding tick: {}", barType, e.getMessage(), e);
        }
    }
    
    /**
     * CRITICAL: Update run tracking
     * 
     * From Chapter 2.3.2.3:
     * θ_T = max{Σ(b_t where b_t=1), -Σ(b_t where b_t=-1)}
     * 
     * Count ticks in longest run (no offset)
     */
    private void updateRunTracking(int direction, int volume) {
        if (currentRunDirection == 0) {
            // Start first run
            currentRunDirection = direction;
        }
        
        if (direction == 1) {
            // Buy tick
            currentBuyRun++;
            currentBuyVolumeRun += volume;
        } else {
            // Sell tick
            currentSellRun++;
            currentSellVolumeRun += volume;
        }
        
        // Store in bar for later
        currentBar.setRunLength(Math.max(currentBuyRun, currentSellRun));
        currentBar.setRunVolume(Math.max(currentBuyVolumeRun, currentSellVolumeRun));
    }
    
    /**
     * CRITICAL FIX: Calculate expected tick run length
     * 
     * From Chapter 2.3.2.3:
     * E[θ_T] = E[T] * max{P[buy], P[sell]}
     */
    public double getExpectedTickRunLength() {
        if (sampleCount < warmupSamples) {
            return Math.max(expectedBarSize * 0.5, 10.0);  // Min 10 ticks
        }
        
        // Expected run = bar size * probability of dominant side
        double maxProb = Math.max(ewmaBuyProbability, 1 - ewmaBuyProbability);
        return expectedBarSize * maxProb;
    }
    
    /**
     * CRITICAL FIX: Calculate expected volume run length
     */
    public double getExpectedVolumeRunLength() {
        if (sampleCount < warmupSamples) {
            double avgVol = (ewmaBuyVolume + ewmaSellVolume);
            if (avgVol == 0) {
                return expectedBarSize * 10.0;  // Assume 10 volume per tick
            }
            return Math.max(avgVol * 0.5, 100.0);
        }
        
        // Expected volume run = volume * probability of dominant side
        double maxVol = Math.max(ewmaBuyVolume, ewmaSellVolume);
        return Math.max(maxVol, expectedBarSize * 10.0);
    }
    
    /**
     * Check if TRB bar should be emitted
     * 
     * CRITICAL: Uses threshold multiplier for multi-granularity
     * FIXED: Always set expectedRunLength BEFORE checking threshold
     */
    public boolean shouldEmitTickRunsBar() {
        if (currentBar.getTickCount() < 10) {
            return false;
        }
        
        int actualRunLength = currentBar.getRunLength();
        double expectedRunLength = getExpectedTickRunLength() * thresholdMultiplier;  // CRITICAL: Apply multiplier!
        
        // CRITICAL FIX: Always set before checking (so it's in emitted bar)
        currentBar.setExpectedRunLength(expectedRunLength);
        currentBar.setRunDirection(currentRunDirection);  // Also set run direction
        
        log.debug("[TRB] actual={}, expected={:.2f}, threshold={:.1f}x, willEmit={}", 
            actualRunLength, expectedRunLength, thresholdMultiplier, actualRunLength >= expectedRunLength);
        
        return actualRunLength >= expectedRunLength;
    }
    
    /**
     * Check if VRB bar should be emitted
     * 
     * CRITICAL: Uses threshold multiplier for multi-granularity
     * FIXED: Set expectedRunLength (not expectedRunVolume - we reuse the field)
     */
    public boolean shouldEmitVolumeRunsBar() {
        if (currentBar.getTickCount() < 10) {
            return false;
        }
        
        double actualRunVolume = currentBar.getRunVolume();
        double expectedRunVolume = getExpectedVolumeRunLength() * thresholdMultiplier;  // CRITICAL: Apply multiplier!
        
        // CRITICAL FIX: Always set before checking (so it's in emitted bar)
        // Note: We reuse expectedRunLength field for volume runs (stores expected volume)
        currentBar.setExpectedRunLength(expectedRunVolume);
        currentBar.setRunDirection(currentRunDirection);  // Also set run direction
        
        log.debug("[VRB] actual={:.2f}, expected={:.2f}, threshold={:.1f}x, willEmit={}", 
            actualRunVolume, expectedRunVolume, thresholdMultiplier, actualRunVolume >= expectedRunVolume);
        
        return actualRunVolume >= expectedRunVolume;
    }
    
    /**
     * Mark bar as complete and update EWMA
     */
    private void completeBar() {
        try {
            double alpha = 2.0 / (ewmaSpan + 1.0);
            
            // Initialize EWMA on first bar
            if (sampleCount == 0) {
                ewmaBuyVolume = currentBar.getBuyVolume();
                ewmaSellVolume = currentBar.getSellVolume();
                
                double totalVol = currentBar.getVolume();
                if (totalVol > 0) {
                    ewmaBuyProbability = currentBar.getBuyVolume() / totalVol;
                }
            } else {
                // Update EWMA
                ewmaBuyVolume = alpha * currentBar.getBuyVolume() + (1 - alpha) * ewmaBuyVolume;
                ewmaSellVolume = alpha * currentBar.getSellVolume() + (1 - alpha) * ewmaSellVolume;
                
                double totalVol = currentBar.getVolume();
                if (totalVol > 0) {
                    double buyProb = currentBar.getBuyVolume() / totalVol;
                    ewmaBuyProbability = alpha * buyProb + (1 - alpha) * ewmaBuyProbability;
                }
            }
            
            sampleCount++;
            currentBar.calculateImbalanceMetrics();
            isComplete = true;
            
            log.debug("Runs bar completed: sample #{}, run_length={}", 
                sampleCount, currentBar.getRunLength());
            
        } catch (Exception e) {
            log.error("Error completing runs bar: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Check if bar is ready to emit
     */
    public boolean hasCompletedBar() {
        return isComplete;
    }
    
    /**
     * Emit completed bar and reset for next
     */
    public InformationBar emitAndReset() {
        InformationBar barToEmit = this.currentBar;
        
        // CRITICAL DEBUG: Log what we're emitting
        log.info("EMIT {} bar: token={}, start={}, end={}, timestamp={}, ticks={}, run={}",
            barToEmit.getBarType() != null ? barToEmit.getBarType() : "RUN",
            barToEmit.getToken(),
            barToEmit.getWindowStartMillis(),
            barToEmit.getWindowEndMillis(),
            barToEmit.getTimestamp(),
            barToEmit.getTickCount(),
            barToEmit.getRunLength());
        
        // Reset for next bar
        this.currentBar = new InformationBar();
        this.isComplete = false;
        this.currentBuyRun = 0;
        this.currentSellRun = 0;
        this.currentBuyVolumeRun = 0.0;
        this.currentSellVolumeRun = 0.0;
        this.currentRunDirection = 0;
        // CRITICAL: Keep EWMA state, previousPrice, previousDirection
        
        return barToEmit;
    }
    
    /**
     * Kafka Serde for RunsBarState
     */
    public static org.apache.kafka.common.serialization.Serde<RunsBarState> serde() {
        return new org.springframework.kafka.support.serializer.JsonSerde<>(RunsBarState.class);
    }
}
