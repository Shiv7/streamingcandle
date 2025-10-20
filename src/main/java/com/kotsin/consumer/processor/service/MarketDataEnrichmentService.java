package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.model.*;
import com.kotsin.consumer.processor.MultiTimeframeState;
import com.kotsin.consumer.service.MongoInstrumentFamilyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for enriching market data with instrument family and metadata
 * Single Responsibility: Build EnrichedMarketData messages
 *
 * Spring Best Practice: @Service annotation for business logic
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataEnrichmentService {

    private final MongoInstrumentFamilyService cacheService;

    public EnrichedMarketData buildEnrichedMessage(MultiTimeframeState state) {
        try {
            // Get instrument family from cache
            InstrumentFamily family = cacheService.resolveFamily(
                state.getScripCode(),
                state.getExchangeType(),
                state.getCompanyName()
            );

            if (family == null) {
                log.warn("⚠️ No instrument family found for scripCode: {}", state.getScripCode());
                family = InstrumentFamily.builder()
                    .equityScripCode(state.getScripCode())
                    .companyName(state.getCompanyName())
                    .dataSource("CACHE_MISS")
                    .build();
            }

            EnrichedMarketData enrichedData = EnrichedMarketData.builder()
                .scripCode(state.getScripCode())
                .companyName(state.getCompanyName())
                .exchange(state.getExchange())
                .exchangeType(state.getExchangeType())
                .timestamp(state.getLastTickTime())
                .instrumentFamily(family)
                .multiTimeframeCandles(state.getMultiTimeframeCandles())
                .openInterest(state.getOpenInterest())
                .imbalanceBars(state.getImbalanceBars())
                .microstructure(state.getMicrostructure())
                .orderbookDepth(state.getOrderbookDepth())
                .metadata(MessageMetadata.builder()
                    .messageVersion("2.0")
                    .producedAt(System.currentTimeMillis())
                    .dataQuality(state.getDataQuality())
                    .completeWindows(state.getCompleteWindows())
                    .processingLatency((int) state.getProcessingLatency())
                    .source("unified-processor")
                    .sequenceNumber(state.getMessageCount())
                    .build())
                .build();

            log.debug("📤 Built enriched message for {} with {} complete timeframes",
                state.getScripCode(), state.getCompleteWindows().size());

            return enrichedData;

        } catch (Exception e) {
            log.error("❌ Failed to build enriched message for scripCode: {}", state.getScripCode(), e);

            // Return minimal message to prevent data loss
            return EnrichedMarketData.builder()
                .scripCode(state.getScripCode())
                .companyName(state.getCompanyName())
                .exchange(state.getExchange())
                .exchangeType(state.getExchangeType())
                .timestamp(state.getLastTickTime())
                .metadata(MessageMetadata.builder()
                    .messageVersion("2.0")
                    .producedAt(System.currentTimeMillis())
                    .dataQuality("ERROR")
                    .source("unified-processor")
                    .build())
                .build();
        }
    }
}
