package com.kotsin.consumer.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.annotation.Id;

import java.util.Date;

@Data
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "scripData")
public class Scrip {
    @Id
    private String id;
    private String scriptTypeKotsin;
    private String Exch;
    private String ExchType;
    private String ScripCode;
    private String Name;
    private String Expiry;
    private String ScripType;
    private String StrikeRate;
    private String FullName;
    private String TickSize;
    private String LotSize;
    private String QtyLimit;
    private String Multiplier;
    private String SymbolRoot;
    private String BOCOAllowed;
    private String ISIN;
    private String ScripData;
    private String Series;
    private Date insertionDate;

    // Optional per-instrument overrides (add to DB if desired)
    private String SpoofSizeRatio;           // e.g., "0.25"
    private String SpoofPriceEpsilonTicks;   // e.g., "2"
}
