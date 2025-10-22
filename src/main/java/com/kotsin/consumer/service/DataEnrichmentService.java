package com.kotsin.consumer.service;

import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.processor.InstrumentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Service for enriching instrument data with external sources
 * 
 * SINGLE RESPONSIBILITY: Data enrichment logic
 * EXTRACTED FROM: UnifiedMarketDataProcessor (God class refactoring)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataEnrichmentService {

    /**
     * Enrich instrument state with orderbook data
     */
    public void enrichWithOrderbook(InstrumentState state, OrderBookSnapshot orderbook) {
        if (state == null || orderbook == null) {
            return;
        }

        if (orderbook.isValid()) {
            state.addOrderbook(orderbook);
            log.debug("📊 Enriched state {} with orderbook data", state.getScripCode());
        } else {
            log.warn("⚠️ Invalid orderbook data for {}", state.getScripCode());
        }
    }

    /**
     * Enrich candle with Open Interest data
     */
    public void enrichWithOpenInterest(InstrumentCandle candle, OpenInterest oi) {
        if (candle == null || oi == null) {
            return;
        }

        candle.setOpenInterest(oi.getOpenInterest());
        candle.setOiChange(oi.getOiChange());
        
        log.debug("📊 Enriched candle {} with OI: {} (change: {})", 
            candle.getScripCode(), oi.getOpenInterest(), oi.getOiChange());
    }

    /**
     * Validate enrichment data quality
     */
    public boolean isEnrichmentDataValid(OrderBookSnapshot orderbook) {
        if (orderbook == null) {
            return false;
        }

        // Check if orderbook has valid data
        return orderbook.isValid() && 
               orderbook.getBids() != null && !orderbook.getBids().isEmpty() &&
               orderbook.getAsks() != null && !orderbook.getAsks().isEmpty();
    }

    /**
     * Get enrichment statistics
     */
    public String getEnrichmentStats(InstrumentState state) {
        if (state == null) {
            return "No state";
        }

        boolean hasOrderbook = state.getLatestOrderbook() != null;
        boolean hasValidOrderbook = hasOrderbook && state.getLatestOrderbook().isValid();
        
        return String.format("Orderbook: %s (valid: %s)", 
            hasOrderbook ? "Yes" : "No",
            hasValidOrderbook ? "Yes" : "No");
    }
}
