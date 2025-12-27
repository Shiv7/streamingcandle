package com.kotsin.consumer.infrastructure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kotsin.consumer.domain.model.InstrumentFamily;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ScripFinderClient - Client for ScripFinder API
 * 
 * Calls:
 * - GET /getRequiredFuture?equityScripCode={scripCode} -> Returns future info
 * - GET /getRequiredOptions?equityScripCode={scripCode} -> Returns 4 ATM options
 * 
 * API Base: http://13.203.60.173:8102
 */
@Component
@Slf4j
public class ScripFinderClient {

    @Value("${scripfinder.api.base-url:http://13.203.60.173:8102}")
    private String baseUrl;

    @Value("${scripfinder.api.timeout.ms:3000}")
    private long timeoutMs;

    private final RestTemplate restTemplate;

    public ScripFinderClient() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * Fetch complete instrument family for an equity
     *
     * @param equityScripCode Equity scrip code (e.g., "14154" for UNOMINDA)
     * @param closePrice Current close price for ATM calculation
     * @return InstrumentFamily with future and options info
     */
    public InstrumentFamily getFamily(String equityScripCode, double closePrice) {
        try {
            FutureResponse futureResp = getFuture(equityScripCode);
            OptionsResponse optionsResp = getOptions(equityScripCode);

            List<InstrumentFamily.OptionInfo> options = new ArrayList<>();
            if (optionsResp != null && optionsResp.getResponse() != null) {
                for (ScripInfo scrip : optionsResp.getResponse()) {
                    options.add(InstrumentFamily.OptionInfo.builder()
                        .scripCode(scrip.getScripCode())
                        .name(scrip.getName())
                        .optionType(scrip.getScripType())  // "CE" or "PE"
                        .strikePrice(parseStrikePrice(scrip.getStrikeRate()))
                        .expiry(scrip.getExpiry())
                        .lotSize(parseLotSize(scrip.getLotSize()))
                        .build());
                }
            }

            InstrumentFamily.InstrumentFamilyBuilder builder = InstrumentFamily.builder()
                .equityScripCode(equityScripCode)
                .closePrice(closePrice)
                .lastUpdated(System.currentTimeMillis())
                .active(true)
                .options(options);

            if (futureResp != null && futureResp.getResponse() != null) {
                ScripInfo future = futureResp.getResponse();
                builder
                    .symbolRoot(future.getSymbolRoot())
                    .futureScripCode(future.getScripCode())
                    .futureName(future.getName())
                    .futureExpiry(future.getExpiry())
                    .futureLotSize(parseLotSize(future.getLotSize()));
            }

            // Calculate ATM strike from options
            if (!options.isEmpty()) {
                double atmStrike = options.stream()
                    .mapToDouble(InstrumentFamily.OptionInfo::getStrikePrice)
                    .filter(s -> s > 0)
                    .min()
                    .orElse(0.0);
                
                // Find strike interval
                double strikeInterval = 0.0;
                List<Double> strikes = options.stream()
                    .map(InstrumentFamily.OptionInfo::getStrikePrice)
                    .distinct()
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
                
                if (strikes.size() >= 2) {
                    strikeInterval = strikes.get(1) - strikes.get(0);
                }
                
                builder.atmStrike(atmStrike)
                       .strikeInterval(strikeInterval);
            }

            return builder.build();

        } catch (Exception e) {
            log.error("Error fetching family for scripCode {}: {}", equityScripCode, e.getMessage());
            return InstrumentFamily.builder()
                .equityScripCode(equityScripCode)
                .closePrice(closePrice)
                .lastUpdated(System.currentTimeMillis())
                .active(false)
                .options(new ArrayList<>())
                .build();
        }
    }

    /**
     * Get future info for equity
     */
    public FutureResponse getFuture(String equityScripCode) {
        try {
            String url = baseUrl + "/getRequiredFuture?equityScripCode=" + equityScripCode;
            log.debug("Fetching future: {}", url);
            
            CompletableFuture<FutureResponse> future = CompletableFuture.supplyAsync(() -> 
                restTemplate.getForObject(url, FutureResponse.class));
            
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            
        } catch (TimeoutException e) {
            log.warn("Timeout fetching future for scripCode {}", equityScripCode);
            return null;
        } catch (Exception e) {
            log.error("Error fetching future for scripCode {}: {}", equityScripCode, e.getMessage());
            return null;
        }
    }

    /**
     * Get options info for equity (returns 4 ATM options)
     */
    public OptionsResponse getOptions(String equityScripCode) {
        try {
            String url = baseUrl + "/getRequiredOptions?equityScripCode=" + equityScripCode;
            log.debug("Fetching options: {}", url);
            
            CompletableFuture<OptionsResponse> future = CompletableFuture.supplyAsync(() ->
                restTemplate.getForObject(url, OptionsResponse.class));
            
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            
        } catch (TimeoutException e) {
            log.warn("Timeout fetching options for scripCode {}", equityScripCode);
            return null;
        } catch (Exception e) {
            log.error("Error fetching options for scripCode {}: {}", equityScripCode, e.getMessage());
            return null;
        }
    }

    // ==================== RESPONSE DTOs ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FutureResponse {
        private int status;
        private String message;
        private ScripInfo response;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OptionsResponse {
        private int status;
        private String message;
        private List<ScripInfo> response;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScripInfo {
        private String id;
        private String scriptTypeKotsin;
        private String name;
        private String fullName;
        private String symbolRoot;
        private String scripCode;
        private String scripData;
        private String exch;
        private String exchType;
        private String scripType;  // "XX" for future, "CE" or "PE" for options
        private String strikeRate;
        private String expiry;
        private String tickSize;
        private String lotSize;
        private String qtyLimit;
        private String bocoallowed;
        private String isin;
        private String series;
    }

    // ==================== HELPER METHODS ====================

    private double parseStrikePrice(String strikeRate) {
        try {
            return strikeRate != null ? Double.parseDouble(strikeRate) : 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private int parseLotSize(String lotSize) {
        try {
            return lotSize != null ? Integer.parseInt(lotSize) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
