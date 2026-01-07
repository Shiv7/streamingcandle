package com.kotsin.consumer.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.Date;
import java.util.List;

/**
 * ScripGroup entity - Maps equity to its futures and options
 * Collection: ScripGroup
 * 
 * This provides complete family mapping in a single document:
 * - equity scripCode (primary key)
 * - list of futures scripCodes
 * - list of options scripCodes
 */
@Data
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ScripGroup")
public class ScripGroup {
    
    @Id
    private String id;  // equityScripCode (e.g., "14154")
    
    private String tradingType;  // "EQUITY"
    private String companyName;  // "UNOMINDA"
    
    private Scrip equity;
    private List<Scrip> futures;
    private List<Scrip> options;
    
    private double closePrice;
    private Date insertionDate;
    
    @Field("_class")
    private String className;
}
