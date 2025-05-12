package com.kotsin.consumer.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * MongoDB entity for storing historical tick data
 * Used for backtesting and historical analysis
 */
@Document(collection = "historical_ticks")
@Data
@CompoundIndex(name = "idx_scrip_timestamp", def = "{'scripCode': 1, 'timestamp': 1}")
public class HistoricalTickData {
    @Id
    private String id;
    
    @Indexed
    private String scripCode;
    
    private String exchange;
    private String exchangeType;
    private String companyName;
    private double lastRate;
    private double openRate;
    private double high;
    private double low;
    private double previousClose;
    private int lastQuantity;
    private int totalQuantity;
    private int totalBidQuantity;
    private int totalOfferQuantity;
    
    @Indexed
    private Date timestamp;
    
    @Indexed
    private LocalDate tradeDate;
    
    // String representation of timestamp for easier readability
    private String tickDt;
    
    // Pre-process hook
    public void prePersist() {
        if (tradeDate == null && timestamp != null) {
            LocalDateTime ldt = LocalDateTime.ofInstant(timestamp.toInstant(), 
                java.time.ZoneId.of("Asia/Kolkata"));
            this.tradeDate = ldt.toLocalDate();
        }
    }
} 