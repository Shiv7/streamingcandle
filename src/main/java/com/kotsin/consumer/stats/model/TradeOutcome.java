package com.kotsin.consumer.stats.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * TradeOutcome - Record of a completed paper trade
 * 
 * Contains:
 * - Entry/exit details
 * - P&L and R-multiple
 * - Exit reason (TARGET_HIT, STOP_LOSS, TIME_EXIT)
 * - Link back to signal
 * 
 * Used for:
 * - Updating SignalStats
 * - Linking to SignalHistory
 * - Future RL training
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "trade_outcomes")
public class TradeOutcome {

    @Id
    private String id;

    // Signal linkage
    private String signalId;      // Links back to SignalHistory
    private String scripCode;
    private String signalType;    // e.g., "BREAKOUT_RETEST"
    private String direction;     // "BULLISH" or "BEARISH"

    // Trade details
    private double entryPrice;
    private double exitPrice;
    private double stopLoss;
    private double target;
    private double quantity;

    // Outcome
    private String exitReason;    // TARGET_HIT, STOP_LOSS, TIME_EXIT, MANUAL
    private double pnl;           // Absolute P&L in rupees
    private double rMultiple;     // Risk-adjusted return
    private boolean win;          // pnl > 0

    // Timing
    private LocalDateTime signalTime;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private long holdingPeriodMinutes;

    // Position info
    private double positionValue;
    private double positionSizeMultiplier;

    // ========== Computed Fields ==========

    /**
     * Calculate holding period
     */
    public long calculateHoldingPeriod() {
        if (entryTime == null || exitTime == null) return 0;
        return java.time.Duration.between(entryTime, exitTime).toMinutes();
    }

    /**
     * Calculate P&L
     */
    public double calculatePnL(boolean isLong) {
        if (isLong) {
            return (exitPrice - entryPrice) * quantity;
        } else {
            return (entryPrice - exitPrice) * quantity;
        }
    }

    /**
     * Calculate R-multiple
     */
    public double calculateRMultiple() {
        double risk = Math.abs(entryPrice - stopLoss) * quantity;
        return risk > 0 ? pnl / risk : 0;
    }

    // ========== Kafka Serde ==========

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();  // For Java 8 date/time support

    public static Serde<TradeOutcome> serde() {
        return Serdes.serdeFrom(new TradeOutcomeSerializer(), new TradeOutcomeDeserializer());
    }

    public static class TradeOutcomeSerializer implements Serializer<TradeOutcome> {
        @Override
        public byte[] serialize(String topic, TradeOutcome data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for TradeOutcome", e);
            }
        }
    }

    public static class TradeOutcomeDeserializer implements Deserializer<TradeOutcome> {
        @Override
        public TradeOutcome deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, TradeOutcome.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for TradeOutcome", e);
            }
        }
    }

    // ========== Builder Enhancement ==========

    /**
     * Build with calculated fields
     */
    public static TradeOutcome create(String signalId, String scripCode, String signalType,
                                      String direction, double entryPrice, double exitPrice,
                                      double stopLoss, double target, double quantity,
                                      String exitReason, LocalDateTime entryTime) {
        
        boolean isLong = "BULLISH".equals(direction) || "BUY".equals(direction);
        double pnl = isLong 
                ? (exitPrice - entryPrice) * quantity 
                : (entryPrice - exitPrice) * quantity;
        double risk = Math.abs(entryPrice - stopLoss) * quantity;
        double rMultiple = risk > 0 ? pnl / risk : 0;
        
        LocalDateTime now = LocalDateTime.now();
        
        return TradeOutcome.builder()
                .id(java.util.UUID.randomUUID().toString())
                .signalId(signalId)
                .scripCode(scripCode)
                .signalType(signalType)
                .direction(direction)
                .entryPrice(entryPrice)
                .exitPrice(exitPrice)
                .stopLoss(stopLoss)
                .target(target)
                .quantity(quantity)
                .exitReason(exitReason)
                .pnl(pnl)
                .rMultiple(rMultiple)
                .win(pnl > 0)
                .entryTime(entryTime)
                .exitTime(now)
                .holdingPeriodMinutes(java.time.Duration.between(entryTime, now).toMinutes())
                .positionValue(entryPrice * quantity)
                .build();
    }
}

