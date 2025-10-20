package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.model.InstrumentFamily;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.service.MongoInstrumentFamilyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for resolving instrument keys (derivatives to underlying equity)
 * Single Responsibility: Key resolution logic
 *
 * Spring Best Practice: @Service annotation for business logic
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentKeyResolver {

    private final MongoInstrumentFamilyService cacheService;

    /**
     * Get underlying equity scripCode for a tick.
     * If it's a derivative (ExchType=D), resolve to underlying equity.
     * Otherwise, return the tick's own scripCode.
     */
    public String getUnderlyingEquityScripCode(TickData tick) {
        if (tick == null) {
            return null;
        }

        // If it's a derivative, resolve to underlying equity
        if ("D".equalsIgnoreCase(tick.getExchangeType())) {
            InstrumentFamily family = cacheService.resolveFamily(
                tick.getScripCode(),
                tick.getExchangeType(),
                tick.getCompanyName()
            );

            if (family != null && family.getEquityScripCode() != null) {
                log.debug("📍 Mapped derivative {} to underlying equity {}",
                    tick.getScripCode(), family.getEquityScripCode());
                return family.getEquityScripCode();
            }
        }

        // For equities or if resolution fails, use the tick's own scripCode
        return tick.getScripCode();
    }
}
