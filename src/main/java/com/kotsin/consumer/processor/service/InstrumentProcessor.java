package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.processor.InstrumentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Service for processing individual instrument data
 * 
 * SINGLE RESPONSIBILITY: Instrument-level processing logic
 * EXTRACTED FROM: UnifiedMarketDataProcessor (God class refactoring)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstrumentProcessor {

    private final TradingHoursValidationService tradingHoursService;
    private final InstrumentKeyResolver keyResolver;

    /**
     * Process a single tick for an instrument
     */
    public void processTick(String scripCode, TickData tick, InstrumentState state) {
        if (tick == null || state == null) {
            return;
        }

        // Validate trading hours
        if (!tradingHoursService.withinTradingHours(tick)) {
            log.debug("⏰ Tick outside trading hours for {}", scripCode);
            return;
        }

        // Set instrument metadata if not already set
        if (state.getScripCode() == null || state.getScripCode().isEmpty()) {
            String instrumentType = keyResolver.getInstrumentType(tick);
            String familyKey = keyResolver.getFamilyKey(tick);
            state.setInstrumentType(instrumentType);
            state.setUnderlyingEquityScripCode(familyKey);
        }

        // Add tick to state (this will initialize scripCode and companyName automatically)
        state.addTick(tick);
        
        log.debug("📊 Processed tick for {}: price={}, volume={}", 
            scripCode, tick.getLastRate(), tick.getDeltaVolume());
    }

    /**
     * Check if instrument state is ready for emission
     */
    public boolean isReadyForEmission(InstrumentState state) {
        if (state == null) {
            return false;
        }

        // Check if we have any completed windows
        return state.hasAnyCompleteWindow();
    }

    /**
     * Get emission statistics for an instrument
     */
    public String getEmissionStats(InstrumentState state) {
        if (state == null) {
            return "No state";
        }

        return String.format("ScripCode: %s, Ticks: %d, Last: %d", 
            state.getScripCode(), 
            state.getMessageCount() != null ? state.getMessageCount() : 0,
            state.getLastTickTime() != null ? state.getLastTickTime() : 0);
    }
}
