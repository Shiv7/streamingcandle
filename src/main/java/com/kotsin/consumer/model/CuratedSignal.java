package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

import java.time.LocalDateTime;

/**
 * CuratedSignal - High-quality filtered signal with proper levels
 * 
 * Generated every 30 minutes from the best signals in that window.
 * Contains:
 * - Original signal data
 * - RL-validated module scores
 * - Pivot/Fibo/FBT-based entry/SL/target levels
 * - Rank within window
 * 
 * Topic: trading-signals-curated
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CuratedSignal {

    // ========== Metadata ==========
    private String scripCode;
    private String companyName;
    private String exchange;
    private String exchangeType;
    private long timestamp;
    private LocalDateTime windowStart;  // 30-min window start
    private LocalDateTime windowEnd;    // 30-min window end
    private int rankInWindow;           // 1, 2, or 3 (top 3 per window)

    // ========== Original Signal ==========
    private String signalType;          // From TradingSignal
    private String direction;           // BULLISH / BEARISH
    private double originalConfidence;
    private String rationale;

    // ========== RL-Validated Scores (only the ones that matter) ==========
    private boolean xfactorFlag;        // STRONG_POSITIVE in RL
    private double vcpCombinedScore;    // WEAK_POSITIVE in RL
    private double curatedScore;        // Combined ranking score

    // ========== Price Levels from Pivot/Fibo ==========
    private double currentPrice;
    private double atr;
    
    // Pivot-based levels
    private double pivotPoint;
    private double resistance1;
    private double resistance2;
    private double support1;
    private double support2;
    
    // Fibonacci retracement levels
    private double fib382;
    private double fib500;
    private double fib618;
    
    // ========== Calculated Trade Parameters ==========
    private double entryPrice;
    private double stopLoss;
    private double target1;
    private double target2;
    private double target3;
    private double riskRewardRatio;
    private double riskPercentage;
    
    // ========== Trade Reasoning ==========
    private String entryReason;         // "Near S1 + Fib 61.8% confluence"
    private String stopReason;          // "Below S2 with ATR buffer"
    private String targetReason;        // "R1 as T1, R2 as T2"

    // ========== Factory Methods ==========
    
    /**
     * Create from TradingSignal with calculated levels
     */
    public static CuratedSignal from(TradingSignal ts, int rank, 
                                      LocalDateTime windowStart, 
                                      LocalDateTime windowEnd) {
        return CuratedSignal.builder()
                .scripCode(ts.getScripCode())
                .companyName(ts.getCompanyName())
                .timestamp(System.currentTimeMillis())
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .rankInWindow(rank)
                .signalType(ts.getSignal() != null ? ts.getSignal().name() : "UNKNOWN")
                .direction(ts.getDirection() != null ? ts.getDirection().name() : "UNKNOWN")
                .originalConfidence(ts.getConfidence())
                .rationale(ts.getRationale())
                .xfactorFlag(ts.isXfactorFlag())
                .vcpCombinedScore(ts.getVcpCombinedScore())
                .currentPrice(ts.getCurrentPrice())
                .atr(ts.getAtr())
                .build();
    }

    /**
     * Calculate curated score based on RL insights
     * xfactor = STRONG_POSITIVE (weight: 0.6)
     * vcpCombined = WEAK_POSITIVE (weight: 0.3)
     * direction BEARISH = slight edge (weight: 0.1)
     */
    public void calculateCuratedScore() {
        double score = 0;
        
        // xfactor is STRONG_POSITIVE
        if (xfactorFlag) {
            score += 0.6;
        }
        
        // vcpCombined WEAK_POSITIVE (higher is better)
        score += vcpCombinedScore * 0.3;
        
        // BEARISH has slight edge over BULLISH
        if ("BEARISH".equals(direction)) {
            score += 0.1;
        }
        
        this.curatedScore = score;
    }

    /**
     * Calculate Pivot Point levels from OHLC
     */
    public void calculatePivotLevels(double high, double low, double close) {
        // Standard Pivot Point formula
        this.pivotPoint = (high + low + close) / 3;
        
        // Support levels
        this.support1 = (2 * pivotPoint) - high;
        this.support2 = pivotPoint - (high - low);
        
        // Resistance levels
        this.resistance1 = (2 * pivotPoint) - low;
        this.resistance2 = pivotPoint + (high - low);
    }

    /**
     * Calculate Fibonacci retracement levels
     */
    public void calculateFibLevels(double swingHigh, double swingLow) {
        double range = swingHigh - swingLow;
        
        if ("BULLISH".equals(direction)) {
            // Retracement in uptrend
            this.fib382 = swingHigh - (range * 0.382);
            this.fib500 = swingHigh - (range * 0.500);
            this.fib618 = swingHigh - (range * 0.618);
        } else {
            // Retracement in downtrend
            this.fib382 = swingLow + (range * 0.382);
            this.fib500 = swingLow + (range * 0.500);
            this.fib618 = swingLow + (range * 0.618);
        }
    }

    /**
     * Calculate trade parameters based on direction and levels
     */
    public void calculateTradeParams() {
        if (currentPrice <= 0 || atr <= 0) return;
        
        double atrBuffer = atr * 0.5;  // Half ATR buffer for precision
        
        if ("BULLISH".equals(direction)) {
            // LONG trade
            // Entry: Current price or near support confluence
            this.entryPrice = currentPrice;
            
            // Stop: Below S1 or Fib 61.8% with ATR buffer
            double stopLevel = Math.min(support1, fib618 > 0 ? fib618 : support1);
            this.stopLoss = stopLevel - atrBuffer;
            
            // Targets: R1, R2, and extended
            this.target1 = resistance1;
            this.target2 = resistance2;
            this.target3 = resistance2 + (resistance2 - resistance1);  // Extended
            
            this.entryReason = "Long entry at " + String.format("%.2f", entryPrice);
            this.stopReason = "Stop below S1/Fib with ATR buffer";
            this.targetReason = "T1=R1, T2=R2";
            
        } else {
            // SHORT trade
            this.entryPrice = currentPrice;
            
            // Stop: Above R1 or Fib 61.8% with ATR buffer
            double stopLevel = Math.max(resistance1, fib618 > 0 ? fib618 : resistance1);
            this.stopLoss = stopLevel + atrBuffer;
            
            // Targets: S1, S2, and extended
            this.target1 = support1;
            this.target2 = support2;
            this.target3 = support2 - (support1 - support2);  // Extended
            
            this.entryReason = "Short entry at " + String.format("%.2f", entryPrice);
            this.stopReason = "Stop above R1/Fib with ATR buffer";
            this.targetReason = "T1=S1, T2=S2";
        }
        
        // Calculate R:R
        if (stopLoss > 0 && entryPrice > 0) {
            double risk = Math.abs(entryPrice - stopLoss);
            double reward = Math.abs(target1 - entryPrice);
            this.riskRewardRatio = risk > 0 ? reward / risk : 0;
            this.riskPercentage = entryPrice > 0 ? (risk / entryPrice) * 100 : 0;
        }
    }

    /**
     * Check if this is a valid tradeable signal
     */
    public boolean isValid() {
        return entryPrice > 0 
                && stopLoss > 0 
                && target1 > 0 
                && riskRewardRatio >= 1.5;  // Minimum 1.5:1 R:R
    }

    // ========== Kafka Serde ==========
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();  // For LocalDateTime support

    public static Serde<CuratedSignal> serde() {
        return Serdes.serdeFrom(new CuratedSignalSerializer(), new CuratedSignalDeserializer());
    }

    public static class CuratedSignalSerializer implements Serializer<CuratedSignal> {
        @Override
        public byte[] serialize(String topic, CuratedSignal data) {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for CuratedSignal", e);
            }
        }
    }

    public static class CuratedSignalDeserializer implements Deserializer<CuratedSignal> {
        @Override
        public CuratedSignal deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, CuratedSignal.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for CuratedSignal", e);
            }
        }
    }
}
