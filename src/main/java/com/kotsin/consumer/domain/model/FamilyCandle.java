package com.kotsin.consumer.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.model.GreeksPortfolio;
import com.kotsin.consumer.model.IVSurface;
import com.kotsin.consumer.model.MTFDistribution;
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
    private InstrumentCandle equity;        // Equity/Index candle (may be null for commodity families or mapping failures)
    private InstrumentCandle future;        // Future candle (may be null)
    private InstrumentCandle primaryInstrument;  // Primary instrument for analysis (equity if available, else future)
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

    // ==================== OFI VELOCITY & EXHAUSTION (Priority 1) ====================
    /**
     * OFI Velocity = current OFI - previous OFI
     * Positive velocity = buying pressure increasing
     * Negative velocity = selling pressure increasing
     */
    private Double ofiVelocity;

    /**
     * OFI Acceleration = current velocity - previous velocity
     * Deceleration (positive when OFI was negative) = exhaustion signal
     */
    private Double ofiAcceleration;

    /**
     * True when OFI velocity indicates exhaustion:
     * - Selling exhaustion: OFI was very negative but velocity turns positive
     * - Buying exhaustion: OFI was very positive but velocity turns negative
     */
    private boolean exhaustionDetected;

    /**
     * Type of exhaustion detected:
     * SELLING_EXHAUSTION - sellers giving up, potential reversal up
     * BUYING_EXHAUSTION - buyers giving up, potential reversal down
     * NONE - no exhaustion detected
     */
    private String exhaustionType;

    /**
     * Previous candle's OFI for velocity calculation
     * Used internally, serialized for state continuity
     */
    private Double previousOfi;

    // ==================== DELTA DIVERGENCE (Priority 4) ====================
    /**
     * Delta divergence detected when:
     * - Price makes new low but volume delta is positive (bullish divergence)
     * - Price makes new high but volume delta is negative (bearish divergence)
     */
    private boolean deltaDivergenceDetected;

    /**
     * Type of delta divergence:
     * BULLISH_DIVERGENCE - price low but buyers dominating (reversal up signal)
     * BEARISH_DIVERGENCE - price high but sellers dominating (reversal down signal)
     * NONE - no divergence
     */
    private String deltaDivergenceType;

    // ==================== REVERSAL SCORE (Priority 3) ====================
    /**
     * Composite reversal score (0-10):
     * - OFI flip: +3
     * - Volume spike on reversal: +2
     * - Options flow confirmation: +2
     * - At support/resistance: +1
     * - Delta divergence: +1
     * - Exhaustion detected: +1
     */
    private Double reversalScore;

    /**
     * Human-readable list of signals contributing to reversal score
     * e.g., ["OFI_FLIP_POSITIVE", "VOLUME_SPIKE_60PCT_BUY", "CALL_SURGE_13PCT"]
     */
    private List<String> reversalSignals;

    /**
     * Is this a high-confidence reversal setup?
     * True when reversalScore >= 6 (multiple confirming signals)
     */
    private boolean highConfidenceReversal;

    // ==================== OPTIONS FLOW REVERSAL SIGNALS ====================
    /**
     * Call premium change percentage (current vs previous candle)
     * Positive = calls getting expensive = bullish flow
     */
    private Double callPremiumChange;

    /**
     * Put premium change percentage (current vs previous candle)
     * Negative = puts getting cheaper = bullish flow
     */
    private Double putPremiumChange;

    /**
     * Options flow confirms reversal when:
     * - Calls surge (>10%) while puts drop (>5%) = bullish reversal
     * - Puts surge (>10%) while calls drop (>5%) = bearish reversal
     */
    private boolean optionsFlowConfirmsReversal;

    /**
     * Short squeeze signal detected:
     * CE premium explosion (>10%) + PE collapse (>5%) + high buy pressure
     */
    private boolean shortSqueezeDetected;

    // ==================== ENHANCED OI INTERPRETATION (Priority 2) ====================
    /**
     * Proper OI interpretation based on price + OI change:
     * LONG_BUILDUP: Price ↑ + OI ↑ (new longs entering - bullish continuation)
     * SHORT_COVERING: Price ↑ + OI ↓ (shorts exiting - bullish, but may exhaust)
     * SHORT_BUILDUP: Price ↓ + OI ↑ (new shorts entering - bearish continuation)
     * LONG_UNWINDING: Price ↓ + OI ↓ (longs exiting - bearish, but may exhaust)
     *
     * This REPLACES the old futuresBuildup field with proper interpretation
     */
    private String oiInterpretation;

    /**
     * Confidence in OI interpretation (0-1)
     * Higher when price change and OI change are both significant
     */
    private Double oiInterpretationConfidence;

    /**
     * True if OI interpretation suggests potential reversal:
     * - SHORT_COVERING during downtrend = bullish reversal
     * - LONG_UNWINDING during uptrend = bearish reversal
     */
    private boolean oiSuggestsReversal;

    // ==================== DATA QUALITY ====================
    private DataQuality quality;
    private String qualityReason;
    
    // ========== PHASE 2: MTF Distribution ==========
    /**
     * Multi-timeframe distribution metrics
     * Tracks sub-candle patterns (e.g., 5x 1m candles for 5m aggregate)
     * Null if not calculated or insufficient sub-candles
     */
    private MTFDistribution mtfDistribution;

    // ========================================================================
    // PHASE 3: INSTITUTIONAL-GRADE ANALYTICS
    // ========================================================================

    /**
     * Family-level aggregated Greeks portfolio
     * Contains:
     * - Total delta/gamma/vega/theta exposure
     * - Delta ladder (directional exposure by strike)
     * - Gamma ladder with squeeze detection
     * - Vega bucketing by expiry
     * - Theta decay profile
     */
    private GreeksPortfolio greeksPortfolio;

    /**
     * Implied Volatility Surface analytics
     * Contains:
     * - Smile curve (IV by strike)
     * - Skew metrics (25Δ skew, risk reversal)
     * - Term structure (IV by expiry)
     * - IV rank/percentile
     * - IV dynamics (velocity, crush risk)
     */
    private IVSurface ivSurface;

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
     * Check if primary instrument is available (equity or future)
     */
    public boolean hasPrimaryInstrument() {
        return primaryInstrument != null;
    }

    /**
     * Get the primary instrument for analysis (equity if available, else future)
     * This is the main reference for price, volume, and microstructure analysis
     *
     * @JsonIgnore prevents this getter from being serialized as a separate field
     * (the data is already in equity/future/primaryInstrument fields)
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public InstrumentCandle getPrimaryInstrumentOrFallback() {
        if (primaryInstrument != null) {
            return primaryInstrument;
        }
        // Fallback chain: equity → future
        return equity != null ? equity : future;
    }

    /**
     * Get equity close price
     * FIX: For commodities (no equity), returns future price instead of 0
     */
    public double getSpotPrice() {
        if (equity != null) {
            return equity.getClose();
        }
        // For commodities: use future price as spot
        if (isCommodity && future != null) {
            return future.getClose();
        }
        return 0.0;
    }

    /**
     * Get primary instrument close price (most reliable price for this family)
     * Uses equity if available, else future
     */
    public double getPrimaryPrice() {
        InstrumentCandle primary = getPrimaryInstrumentOrFallback();
        return primary != null ? primary.getClose() : 0.0;
    }

    /**
     * Get future close price
     * For commodities: uses primaryInstrument which is the future
     */
    public double getFuturePrice() {
        if (future != null) {
            return future.getClose();
        }
        // For commodities: future is in primaryInstrument (NOT equity slot anymore)
        if (isCommodity && primaryInstrument != null) {
            return primaryInstrument.getClose();
        }
        return 0.0;
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

        // Check PCR confirmation with CONTRARIAN LOGIC
        if (pcr != null) {
            signals++;

            // 🎯 CRITICAL FIX: Respect contrarian PCR extremes
            // Extreme PCR values often predict reversals (fear/greed indicators)
            boolean extremeBearishPCR = pcr > 1.5;  // Panic = Contrarian BUY signal
            boolean extremeBullishPCR = pcr < 0.5;  // Euphoria = Contrarian SELL signal
            boolean normalBullishPCR = pcr < 1.0 && pcr >= 0.5;
            boolean normalBearishPCR = pcr > 1.0 && pcr <= 1.5;

            boolean pcrConfirms = false;

            // Case 1: Normal PCR - confirm if matches equity direction (trend-following)
            if ((normalBullishPCR && equityBullish) || (normalBearishPCR && equityBearish)) {
                pcrConfirms = true;
            }
            // Case 2: Extreme bearish PCR (panic) - confirms if setup for reversal
            else if (extremeBearishPCR) {
                // Extreme fear confirms BULLISH setup (contrarian)
                // Either price already turning up, OR still falling (early entry)
                pcrConfirms = true;  // Always confirm - extreme fear is tradeable
            }
            // Case 3: Extreme bullish PCR (euphoria) - confirms if setup for reversal
            else if (extremeBullishPCR) {
                // Extreme greed confirms BEARISH setup (contrarian)
                // Either price already turning down, OR still rising (early entry)
                pcrConfirms = true;  // Always confirm - extreme greed is tradeable
            }

            if (pcrConfirms) {
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

    // ==================== GREEKS & IV CONVENIENCE METHODS ====================

    /**
     * Check if Greeks portfolio is available
     */
    public boolean hasGreeksPortfolio() {
        return greeksPortfolio != null && greeksPortfolio.hasExposure();
    }

    /**
     * Get total delta exposure
     */
    public double getTotalDelta() {
        return greeksPortfolio != null ? greeksPortfolio.getTotalDelta() : 0.0;
    }

    /**
     * Get total gamma exposure
     */
    public double getTotalGamma() {
        return greeksPortfolio != null ? greeksPortfolio.getTotalGamma() : 0.0;
    }

    /**
     * Check if gamma squeeze risk exists
     */
    public boolean hasGammaSqueezeRisk() {
        return greeksPortfolio != null && greeksPortfolio.isGammaSqueezeRisk();
    }

    /**
     * Check if IV surface is available
     */
    public boolean hasIVSurface() {
        return ivSurface != null && ivSurface.hasData();
    }

    /**
     * Get IV rank (0-100)
     */
    public double getIVRank() {
        return ivSurface != null ? ivSurface.getIvRank() : 50.0; // Default neutral
    }

    /**
     * Check if IV is elevated
     */
    public boolean isIVElevated() {
        return ivSurface != null && ivSurface.isIVElevated();
    }

    /**
     * Get IV-based trading signal
     */
    public IVSurface.IVSignal getIVSignal() {
        return ivSurface != null ? ivSurface.getIvSignal() : IVSurface.IVSignal.NEUTRAL;
    }

    /**
     * Get recommended options strategy based on IV
     */
    public String getRecommendedStrategy() {
        return ivSurface != null ? ivSurface.getRecommendedStrategy() : "NEUTRAL";
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
