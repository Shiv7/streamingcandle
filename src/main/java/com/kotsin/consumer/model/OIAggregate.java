package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * OI (Open Interest) Aggregate - Complete OI metrics in ONE model
 * Pattern: Simple aggregation model managed by Kafka Streams
 * 
 * Features (ALL calculated from OI updates):
 * - OI OHLC tracking (track OI like price)
 * - Put/Call OI separation
 * - OI change metrics
 * - Volume correlation
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OIAggregate {

    // ========== Metadata ==========
    private String scripCode;
    private String companyName;
    private String exchange;
    private String exchangeType;
    private long windowStartMillis;
    private long windowEndMillis;
    private String humanReadableStartTime;
    private String humanReadableEndTime;

    // ========== OI OHLC ==========
    private Long oiOpen;
    private Long oiHigh;
    private Long oiLow;
    private Long oiClose;

    // ========== Put/Call Tracking ==========
    private Long putOI = 0L;
    private Long callOI = 0L;
    private Long putOIStart;
    private Long callOIStart;

    // ========== Derived Metrics ==========
    private Long oiChange;
    private Double oiChangePercent;
    private Double putCallRatio;
    private Long putOIChange;
    private Long callOIChange;

    // ========== Processing State ==========
    private int updateCount = 0;

    /**
     * Creates a new empty OI aggregate
     */
    public OIAggregate() {
        // Initialized with defaults
    }

    /**
     * MAIN UPDATE METHOD: Process OI update and calculate metrics
     */
    public void updateWithOI(OpenInterest oi) {
        if (oi == null || oi.getOpenInterest() == null) {
            return;
        }

        // Initialize metadata on first update
        if (scripCode == null) {
            scripCode = String.valueOf(oi.getToken());
            companyName = oi.getCompanyName();
            exchange = oi.getExchange();
            exchangeType = oi.getExchangeType();
        }

        long currentOI = oi.getOpenInterest();

        // ========== OI OHLC Tracking ==========
        if (oiOpen == null) {
            oiOpen = currentOI;
        }
        oiClose = currentOI;

        if (oiHigh == null || currentOI > oiHigh) {
            oiHigh = currentOI;
        }

        if (oiLow == null || currentOI < oiLow) {
            oiLow = currentOI;
        }

        // ========== Put/Call Tracking ==========
        // BUG-042 FIX: Robust option type detection using multiple patterns
        if (companyName != null) {
            String upperName = companyName.toUpperCase();
            boolean isCall = upperName.contains(" CE ") || 
                            upperName.endsWith("CE") ||
                            upperName.contains("-CE-") ||
                            upperName.contains("CALL");
            boolean isPut = upperName.contains(" PE ") || 
                           upperName.endsWith("PE") ||
                           upperName.contains("-PE-") ||
                           upperName.contains("PUT");
            
            if (isCall) {
                // Call option
                if (callOIStart == null) {
                    callOIStart = currentOI;
                }
                callOI = currentOI;
            } else if (isPut) {
                // Put option
                if (putOIStart == null) {
                    putOIStart = currentOI;
                }
                putOI = currentOI;
            }
        }

        updateCount++;
    }

    /**
     * Calculate derived metrics (call after window close)
     */
    public void calculateDerivedMetrics() {
        // OI Change
        if (oiOpen != null && oiClose != null) {
            oiChange = oiClose - oiOpen;
            oiChangePercent = oiOpen != 0 ? (double) oiChange / oiOpen * 100.0 : 0.0;
        }

        // Put/Call Ratio
        if (putOI > 0 && callOI > 0) {
            putCallRatio = (double) putOI / callOI;
        }

        // Put/Call Changes
        if (putOIStart != null && putOI > 0) {
            putOIChange = putOI - putOIStart;
        }
        if (callOIStart != null && callOI > 0) {
            callOIChange = callOI - callOIStart;
        }
    }

    /**
     * Merge another OI aggregate (for multi-minute aggregation)
     */
    public void updateAggregate(OIAggregate other) {
        if (other == null) return;

        // Set open from first aggregate
        if (this.oiOpen == null) {
            this.oiOpen = other.oiOpen;
        }

        // Always use latest close
        this.oiClose = other.oiClose;

        // Track high/low
        if (other.oiHigh != null) {
            this.oiHigh = (this.oiHigh == null) ? other.oiHigh : Math.max(this.oiHigh, other.oiHigh);
        }
        if (other.oiLow != null) {
            this.oiLow = (this.oiLow == null) ? other.oiLow : Math.min(this.oiLow, other.oiLow);
        }

        // Aggregate put/call (use latest values)
        if (other.putOI > 0) {
            this.putOI = other.putOI;
        }
        if (other.callOI > 0) {
            this.callOI = other.callOI;
        }

        // Preserve starting put/call OI from earliest minute in the window
        if (this.putOIStart == null && other.putOIStart != null) {
            this.putOIStart = other.putOIStart;
        }
        if (this.callOIStart == null && other.callOIStart != null) {
            this.callOIStart = other.callOIStart;
        }

        // Update metadata
        this.exchange = other.exchange;
        this.exchangeType = other.exchangeType;
        this.companyName = other.companyName;
        this.scripCode = other.scripCode;

        this.updateCount += other.updateCount;
    }

    /**
     * Updates the human-readable timestamps
     */
    public void updateHumanReadableTimestamps() {
        if (windowStartMillis > 0) {
            ZonedDateTime startTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowStartMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            this.humanReadableStartTime = startTime.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
        }

        if (windowEndMillis > 0) {
            ZonedDateTime endTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowEndMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            this.humanReadableEndTime = endTime.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
        }
    }

    public void setWindowStartMillis(long windowStartMillis) {
        this.windowStartMillis = windowStartMillis;
        updateHumanReadableTimestamps();
    }

    public void setWindowEndMillis(long windowEndMillis) {
        this.windowEndMillis = windowEndMillis;
        updateHumanReadableTimestamps();
    }

    /**
     * Provides a Kafka Serde for OIAggregate.
     */
    public static Serde<OIAggregate> serde() {
        return Serdes.serdeFrom(new OIAggregateSerializer(), new OIAggregateDeserializer());
    }

    // ---------------------------------------------------
    // Internal Serializer/Deserializer
    // BUG-046 FIX: Share ObjectMapper for better performance
    // ---------------------------------------------------
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();
    
    public static class OIAggregateSerializer implements Serializer<OIAggregate> {
        @Override
        public byte[] serialize(String topic, OIAggregate data) {
            if (data == null) return null;
            try {
                return SHARED_OBJECT_MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for OIAggregate", e);
            }
        }
    }

    public static class OIAggregateDeserializer implements Deserializer<OIAggregate> {
        @Override
        public OIAggregate deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return SHARED_OBJECT_MAPPER.readValue(bytes, OIAggregate.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for OIAggregate", e);
            }
        }
    }
}
