package com.kotsin.consumer.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

import java.util.List;

/**
 * FamilyCandle - The ULTIMATE unified candle for cross-instrument analysis.
 * 
 * Groups related instruments for a single underlying:
 * - Equity (or Index) candle
 * - Future candle (if available)
 * - 4 Option candles (ATM ± 1 strike, CE/PE)
 * 
 * Contains cross-instrument derived metrics:
 * - Spot-Future premium analysis
 * - Put/Call Ratio (PCR)
 * - Max Pain strike
 * - OI buildup signals
 * - Pivot retest confluence
 * - Directional bias
 * 
 * This is the PRIMARY OUTPUT for downstream strategy modules.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FamilyCandle {

    // ==================== IDENTITY ====================
    private String familyId;         // Equity scripCode (e.g., "14154" for UNOMINDA)
    private String symbol;           // Symbol name (e.g., "UNOMINDA")
    private long timestamp;
    private long windowStartMillis;
    private long windowEndMillis;
    private String timeframe;        // "1m", "5m", "15m", etc.
    private String humanReadableTime;

    // ==================== INSTRUMENTS ====================
    private InstrumentCandle equity;        // Equity/Index candle (always present if family exists)
    private InstrumentCandle future;        // Future candle (may be null)
    private List<OptionCandle> options;     // 4 options: CE/PE at ATM ± 1 strike

    // ==================== AVAILABILITY FLAGS ====================
    private boolean hasFuture;
    private boolean hasOptions;
    private int optionCount;
    private boolean isCommodity;     // True for MCX commodities (future is primary, no equity)

    // ==================== SPOT-FUTURE ANALYSIS ====================
    private Double spotFuturePremium;       // (futureClose - equityClose) / equityClose * 100
    private Double spotFuturePremiumChange; // Change from previous candle
    private boolean premiumExpanding;       // Premium increasing
    private String futuresBuildup;          // LONG_BUILDUP, SHORT_BUILDUP, LONG_UNWINDING, SHORT_COVERING

    // ==================== OPTIONS ANALYSIS ====================
    private Double pcr;                     // Put/Call Ratio (sum of Put OI / sum of Call OI)
    private Double pcrChange;               // Change from previous candle
    private Double maxPain;                 // Max Pain strike
    private String highestOIStrike;         // Strike with highest OI concentration
    private Double atmIV;                   // ATM Implied Volatility (average of ATM CE and PE)
    private Double ivSkew;                  // Call IV - Put IV (positive = put skew)

    // ==================== OI DYNAMICS ====================
    private boolean callOiBuildingUp;       // Call OI increasing
    private boolean putOiUnwinding;         // Put OI decreasing
    private boolean futureOiBuildingUp;     // Future OI increasing
    private Long totalCallOI;
    private Long totalPutOI;
    private Long totalCallOIChange;
    private Long totalPutOIChange;
    private Long futureOIChange;
    private String oiSignal;                // BULLISH_ACCUMULATION, BEARISH_DISTRIBUTION, etc.

    // ==================== CROSS-INSTRUMENT CONFLUENCE ====================
    private boolean pivotRetestAcrossAll;   // All instruments at same pivot level
    private double volumeConfluence;        // Volume agreement score (0-1)
    private String directionalBias;         // STRONG_BULLISH, BULLISH, NEUTRAL, BEARISH, STRONG_BEARISH
    private double biasConfidence;          // 0-1 confidence score

    // ==================== REVERSAL DETECTION ====================
    private boolean equityShowingReversal;  // Equity candlestick pattern detected
    private boolean oiConfirmsReversal;     // OI pattern confirms reversal
    private String reversalType;            // e.g., "MORNING_STAR + CALL_ACCUMULATION"

    // ==================== DATA QUALITY ====================
    private DataQuality quality;
    private String qualityReason;

    // ==================== HELPER METHODS ====================

    /**
     * Check if complete family data is available
     */
    public boolean isComplete() {
        return equity != null && hasFuture && hasOptions && optionCount >= 4;
    }

    /**
     * Check if at least equity is available
     */
    public boolean hasEquity() {
        return equity != null;
    }

    /**
     * Get equity close price
     */
    public double getSpotPrice() {
        return equity != null ? equity.getClose() : 0.0;
    }

    /**
     * Get future close price
     */
    public double getFuturePrice() {
        return future != null ? future.getClose() : 0.0;
    }

    /**
     * Check if bullish signal from OI dynamics
     */
    public boolean isBullishOI() {
        return "BULLISH_ACCUMULATION".equals(oiSignal) || "SHORT_COVERING_RALLY".equals(oiSignal);
    }

    /**
     * Check if bearish signal from OI dynamics
     */
    public boolean isBearishOI() {
        return "BEARISH_DISTRIBUTION".equals(oiSignal) || "LONG_UNWINDING".equals(oiSignal);
    }

    /**
     * Get signal strength (0-1) based on confluence
     * 
     * FIX: OI signal no longer auto-confirms - must align with equity direction
     */
    public double getSignalStrength() {
        int signals = 0;
        int confirmedSignals = 0;
        
        // First determine equity direction (baseline)
        boolean equityBullish = equity != null && equity.getClose() > equity.getOpen();
        boolean equityBearish = equity != null && equity.getClose() < equity.getOpen();

        // Check equity trend (always counts as one signal/confirmation)
        if (equity != null && equity.getClose() != equity.getOpen()) {
            signals++;
            confirmedSignals++;  // Equity defines the direction, so it's always "confirmed"
        }

        // Check future trend confirmation
        if (future != null && equity != null) {
            signals++;
            boolean futureBullish = future.getClose() > future.getOpen();
            if (futureBullish == equityBullish) {
                confirmedSignals++;
            }
        }

        // Check PCR confirmation
        if (pcr != null) {
            signals++;
            // PCR > 1 = bearish sentiment, PCR < 1 = bullish sentiment
            // Confirmation: sentiment matches equity direction (not contrarian here)
            boolean pcrBullish = pcr < 1.0;
            if (pcrBullish == equityBullish) {
                confirmedSignals++;
            }
        }

        // FIX: Check OI signal alignment with equity direction (was double-counting before)
        if (oiSignal != null && !oiSignal.equals("NEUTRAL")) {
            signals++;
            // OI signal must ALIGN with equity direction to confirm
            boolean oiIsBullish = isBullishOI();
            boolean oiIsBearish = isBearishOI();
            
            if ((oiIsBullish && equityBullish) || (oiIsBearish && equityBearish)) {
                confirmedSignals++;
            }
            // If OI disagrees with equity, it doesn't confirm (0 added to confirmedSignals)
        }

        return signals > 0 ? (double) confirmedSignals / signals : 0.0;
    }

    // ==================== SERDE ====================

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static Serde<FamilyCandle> serde() {
        return Serdes.serdeFrom(new FamilyCandleSerializer(), new FamilyCandleDeserializer());
    }

    public static class FamilyCandleSerializer implements Serializer<FamilyCandle> {
        @Override
        public byte[] serialize(String topic, FamilyCandle data) {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for FamilyCandle", e);
            }
        }
    }

    public static class FamilyCandleDeserializer implements Deserializer<FamilyCandle> {
        @Override
        public FamilyCandle deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, FamilyCandle.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for FamilyCandle", e);
            }
        }
    }
}
