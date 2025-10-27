package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Represents tick data received from the market.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TickData {

    @JsonProperty("Exch")
    private String exchange;

    @JsonProperty("ExchType")
    private String exchangeType;

    @JsonProperty("Token")
    private int token;

    @JsonProperty("ScripCode")
    private String scripCode;

    @JsonProperty("LastRate")
    private double lastRate;

    @JsonProperty("LastQty")
    private int lastQuantity;

    @JsonProperty("TotalQty")
    private int totalQuantity;

    @JsonProperty("High")
    private double high;

    @JsonProperty("Low")
    private double low;

    @JsonProperty("OpenRate")
    private double openRate;

    @JsonProperty("PClose")
    private double previousClose;

    @JsonProperty("AvgRate")
    private double averageRate;

    @JsonProperty("Time")
    private long time;

    @JsonProperty("BidQty")
    private int bidQuantity;

    @JsonProperty("BidRate")
    private double bidRate;

    @JsonProperty("OffQty")
    private int offerQuantity;

    @JsonProperty("OffRate")
    private double offerRate;

    @JsonProperty("TBidQ")
    private int totalBidQuantity;

    @JsonProperty("TOffQ")
    private int totalOfferQuantity;

    @JsonProperty("TickDt")
    private String tickDt;

    @JsonProperty("ChgPcnt")
    private double changePercent;

    @JsonProperty("companyName")
    private String companyName;

    private long timestamp;

    @JsonProperty("DeltaQty")        // internal field used inside streams topology
    private Integer deltaVolume;     // null until we compute the delta
    
    @JsonProperty("ResetFlag")       // internal flag to indicate cumulative volume reset
    private Boolean resetFlag;       // true when volume reset detected (day rollover or feed restart)
    
    // Additional fields for unified processing
    private Long openInterest;
    private Long oiChange;

    // Transient field for full orderbook (not serialized, used during processing)
    private transient OrderBookSnapshot fullOrderbook;

    /**
     * Parses timestamp from TickDt field with validation.
     * CRITICAL: Never uses System.currentTimeMillis() to handle lag correctly.
     */
    public void parseTimestamp() {
        if (tickDt != null && tickDt.startsWith("/Date(")) {
            try {
                // Extract timestamp using proper regex to avoid concatenating multiple numbers
                String timestampStr = tickDt.replaceFirst("/Date\\(([0-9]+)\\)/.*", "$1");
                long parsedTimestamp = Long.parseLong(timestampStr);
                
                // Validate timestamp is reasonable (between year 2020 and 2050)
                long year2020 = 1577836800000L; // Jan 1, 2020
                long year2050 = 2524608000000L; // Jan 1, 2050
                
                if (parsedTimestamp >= year2020 && parsedTimestamp <= year2050) {
                    this.timestamp = parsedTimestamp;
                } else {
                    System.err.println("Invalid timestamp parsed from TickDt: " + parsedTimestamp + " (" + tickDt + "). Using record timestamp.");
                    this.timestamp = 0; // Let TimestampExtractor use record timestamp
                }
            } catch (NumberFormatException e) {
                // CRITICAL: Set to 0 (not current time) and let TimestampExtractor handle it
                this.timestamp = 0;
                System.err.println("Failed to parse TickDt: " + tickDt + ". Timestamp set to 0.");
            }
        }
        // REMOVED: else clause that was setting System.currentTimeMillis()
    }

    /**
     * Get scrip code, using token as fallback if not available.
     * This ensures backward compatibility with existing code.
     */
    public String getScripCode() {
        if (scripCode != null && !scripCode.isEmpty()) {
            return scripCode;
        }
        // Fallback to string representation of token
        return String.valueOf(token);
    }

    /**
     * Provides Kafka Serde for TickData.
     */
    public static Serde<TickData> serde() {
        return Serdes.serdeFrom(new TickDataSerializer(), new TickDataDeserializer());
    }

    // ---------------------------------------------------
    // Internal Serializer/Deserializer
    // ---------------------------------------------------
    public static class TickDataSerializer implements Serializer<TickData> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        @Override
        public byte[] serialize(String topic, TickData data) {
            if (data == null) return null;
            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for TickData", e);
            }
        }
    }

    public static class TickDataDeserializer implements Deserializer<TickData> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        @Override
        public TickData deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                TickData data = objectMapper.readValue(bytes, TickData.class);
                data.parseTimestamp();
                return data;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for TickData", e);
            }
        }
    }
}
