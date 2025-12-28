package com.kotsin.consumer.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * SignalHistory - Complete record of every signal with all features
 * 
 * Stores:
 * - All input features at signal time (for future ML)
 * - Gate decisions (pass/fail + reasons)
 * - Final outcome (filled later when trade completes)
 * 
 * This is self-labeling data for future reinforcement learning.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "signal_history")
public class SignalHistory {

    @Id
    private String id;
    
    @Indexed
    private LocalDateTime timestamp;
    
    // ========== Signal Identity ==========
    @Indexed
    private String scripCode;
    private String companyName;
    private String signalType;
    private String direction;  // BULLISH, BEARISH
    private String signalId;   // For linking to TradeOutcome
    
    // ========== VCP Features ==========
    private Double vcpCombined;
    private Double vcpRunway;
    private Double vcpStructuralBias;
    private Double vcpSupportScore;
    private Double vcpResistanceScore;
    private Double vcpClustersFound;
    
    // ========== IPU Features ==========
    private Double ipuFinalScore;
    private Double ipuInstProxy;
    private Double ipuMomentum;
    private Double ipuExhaustion;
    private Double ipuUrgency;
    private Double ipuDirectionalConviction;
    private Boolean ipuXfactor;
    private String ipuMomentumState;
    private String ipuDirection;
    
    // ========== Regime Features ==========
    private String indexRegimeLabel;
    private Double indexRegimeStrength;
    private Double indexRegimeCoherence;
    private String indexVolatilityState;
    private String sessionPhase;
    
    private String securityRegimeLabel;
    private Double securityStrength;
    private Boolean securityAligned;
    private String securityEmaAlignment;
    private String securityAtrState;
    
    // ========== OI/F&O Features ==========
    private String oiSignal;
    private Double pcr;
    private Double pcrChange;
    private Double spotFuturePremium;
    private String futuresBuildup;
    private Long totalCallOI;
    private Long totalPutOI;
    
    // ========== F&O Alignment (if available) ==========
    private Double foAlignmentScore;
    private String foBias;
    private Boolean foAligned;
    
    // ========== MTF Features ==========
    private Integer mtfConfluenceCount;
    private Boolean tf5mBullish;
    private Boolean tf15mBullish;
    private Boolean tf30mBullish;
    private Boolean tf1hBullish;
    
    // ========== Breakout Features ==========
    private Double breakoutPivotLevel;
    private Double breakoutHigh;
    private Double breakoutLow;
    private Double breakoutVolumeZScore;
    private Double breakoutKyleLambda;
    private Integer breakoutConfirmations;
    private String breakoutPattern;
    
    // ========== Entry Features ==========
    private Double entryPrice;
    private Double stopLoss;
    private Double target;
    private Double riskRewardRatio;
    private Double positionMultiplier;
    
    // ========== Gate Decisions ==========
    private Boolean hardGatePassed;
    private String hardGateReason;
    private String hardGateDetail;
    
    private Boolean mtfGatePassed;
    private String mtfGateReason;
    private String mtfGateDetail;
    
    private Boolean qualityGatePassed;
    private String qualityGateReason;
    private String qualityGateDetail;
    
    private Boolean statsGatePassed;
    private String statsGateReason;
    private String statsGateDetail;
    
    // ========== Final Decision ==========
    private Boolean signalEmitted;
    private Double finalCuratedScore;
    private String rejectReason;  // If not emitted
    
    // ========== Outcome (filled later) ==========
    private Boolean wasWin;
    private Double rMultiple;
    private Double pnl;
    private String exitReason;
    private LocalDateTime outcomeTime;
    private Long holdingMinutes;
    
    // ========== Metadata ==========
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // ========== Factory Method ==========
    
    /**
     * Create a new signal history record
     */
    public static SignalHistory create(String scripCode, String signalType, String direction) {
        String signalId = scripCode + "_" + System.currentTimeMillis();
        return SignalHistory.builder()
                .id(java.util.UUID.randomUUID().toString())
                .signalId(signalId)
                .scripCode(scripCode)
                .signalType(signalType)
                .direction(direction)
                .timestamp(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .signalEmitted(false)
                .build();
    }
    
    /**
     * Update outcome when trade completes
     */
    public void updateOutcome(boolean win, double rMultiple, double pnl, String exitReason) {
        this.wasWin = win;
        this.rMultiple = rMultiple;
        this.pnl = pnl;
        this.exitReason = exitReason;
        this.outcomeTime = LocalDateTime.now();
        if (this.timestamp != null && this.outcomeTime != null) {
            this.holdingMinutes = java.time.Duration.between(this.timestamp, this.outcomeTime).toMinutes();
        }
        this.updatedAt = LocalDateTime.now();
    }
}

