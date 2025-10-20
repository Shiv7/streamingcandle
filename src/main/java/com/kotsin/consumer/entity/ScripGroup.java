package com.kotsin.consumer.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
    private Scrip equity;                // The equity scrip
    private List<Scrip> futures = new ArrayList<>(); // All FUTs
    private List<Scrip> options = new ArrayList<>(); // All OPTs (CE or PE)
    private double closePrice;                     // Dynamically-fetched close price
    private Date insertionDate;
}
