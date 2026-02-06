package com.kotsin.consumer.signal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * FukaaAudit - Audit trail for FUKAA volume filter decisions.
 *
 * FUKAA filters FUDKII signals based on volume surge criteria:
 * - Immediate pass: T-1 or T candle volume > 2x avg of last 6 candles
 * - Watching mode: Neither passes, signal re-evaluated at T+1
 * - T+1 pass: T+1 candle volume > 2x avg
 *
 * This model stores all FUKAA decisions for analysis and debugging.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "fukaa_audit")
@CompoundIndexes({
    @CompoundIndex(name = "scripCode_signalTime_idx", def = "{'scripCode': 1, 'signalTime': -1}"),
    @CompoundIndex(name = "outcome_signalTime_idx", def = "{'outcome': 1, 'signalTime': -1}"),
    @CompoundIndex(name = "passedCandle_signalTime_idx", def = "{'passedCandle': 1, 'signalTime': -1}")
})
public class FukaaAudit {

    @Id
    private String id;

    // ==================== IDENTITY ====================
    private String scripCode;
    private String symbol;
    private String companyName;
    private String exchange;

    // ==================== SIGNAL INFO (from FUDKII) ====================
    private String direction;           // BULLISH or BEARISH
    private double triggerScore;        // FUDKII trigger score
    private double triggerPrice;
    @Indexed
    private Instant signalTime;         // Time T when signal was generated

    // ==================== VOLUME DATA ====================
    private long volumeTMinus1;         // T-1 candle volume
    private long volumeT;               // T candle volume (signal candle)
    private long volumeTPlus1;          // T+1 candle volume (if watching mode)
    private double avgVolume;           // Average of 6 candles (-3 to -8)
    private double volumeMultiplier;    // Configured multiplier (e.g., 2.0)

    // ==================== SURGE RATIOS ====================
    private double surgeTMinus1;        // volumeTMinus1 / avgVolume
    private double surgeT;              // volumeT / avgVolume
    private double surgeTPlus1;         // volumeTPlus1 / avgVolume (if T+1)

    // ==================== FUKAA DECISION ====================
    /**
     * Outcome of FUKAA evaluation:
     * - IMMEDIATE_PASS: T-1 or T passed volume filter, emitted immediately
     * - T_PLUS_1_PASS: Passed at T+1 re-evaluation
     * - EXPIRED: Neither T-1, T, nor T+1 passed, signal expired
     * - WATCHING: Currently in watching mode (intermediate state)
     */
    @Indexed
    private FukaaOutcome outcome;

    /**
     * Which candle triggered the pass (if passed):
     * - T_MINUS_1: Previous candle passed
     * - T: Signal candle passed
     * - T_PLUS_1: Next candle passed
     * - NONE: No candle passed (expired)
     */
    private PassedCandle passedCandle;

    /**
     * Final rank based on volume surge ratio.
     * Higher surge = higher rank.
     */
    private double rank;

    // ==================== TIMESTAMPS ====================
    @Indexed(expireAfter = "90d")
    private Instant createdAt;
    private Instant evaluatedAt;        // When T+1 was evaluated (if watching)
    private Instant emittedAt;          // When emitted to kotsin_FUKAA (if passed)

    // ==================== ENUMS ====================
    public enum FukaaOutcome {
        IMMEDIATE_PASS,     // Passed at T-1 or T
        T_PLUS_1_PASS,      // Passed at T+1
        EXPIRED,            // No candle passed
        WATCHING            // Currently waiting for T+1
    }

    public enum PassedCandle {
        T_MINUS_1,
        T,
        T_PLUS_1,
        NONE
    }
}
