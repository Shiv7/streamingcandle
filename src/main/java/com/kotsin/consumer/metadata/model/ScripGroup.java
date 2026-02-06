package com.kotsin.consumer.metadata.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * ScripGroup - Maps equity instrument to its derivative family.
 *
 * MongoDB collection: ScripGroup (populated by scripFinder service)
 * _id = equity scripCode (e.g., "13611" for IRCTC)
 *
 * Provides direct lookup: equity scripCode → futures[] + options[]
 * Used by MicroAlpha to resolve equity → FUT OI + option chain data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ScripGroup")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScripGroup {
    @MongoId
    private String equityScripCode;
    private String tradingType;
    private String companyName;
    private Scrip equity;
    private List<Scrip> futures = new ArrayList<>();
    private List<Scrip> options = new ArrayList<>();
    private double closePrice;
    private Date insertionDate;
}
