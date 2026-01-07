package com.kotsin.consumer.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
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

    @Field("Exch")
    private String exch;
    @Field("ExchType")
    private String exchType;
    @Field("ScripCode")
    private String scripCode;
    @Field("Name")
    private String name;
    @Field("Expiry")
    private String expiry;
    @Field("ScripType")
    private String scripType;
    @Field("StrikeRate")
    private String strikeRate;
    @Field("FullName")
    private String fullName;
    @Field("TickSize")
    private String tickSize;
    @Field("LotSize")
    private String lotSize;
    @Field("QtyLimit")
    private String qtyLimit;
    @Field("Multiplier")
    private String multiplier;
    @Field("SymbolRoot")
    private String symbolRoot;
    @Field("BOCOAllowed")
    private String bocoAllowed;
    @Field("ISIN")
    private String isin;
    @Field("ScripData")
    private String scripData;
    @Field("Series")
    private String series;
    private Date insertionDate;

    // Optional per-instrument overrides (add to DB if desired)
    @Field("SpoofSizeRatio")
    private String spoofSizeRatio;           // e.g., "0.25"
    @Field("SpoofPriceEpsilonTicks")
    private String spoofPriceEpsilonTicks;   // e.g., "2"
}
