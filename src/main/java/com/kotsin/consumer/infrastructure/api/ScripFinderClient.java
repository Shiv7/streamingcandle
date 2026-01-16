package com.kotsin.consumer.infrastructure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kotsin.consumer.domain.model.InstrumentFamily;
import com.kotsin.consumer.util.CircuitBreaker;
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
 * API Base: http://localhost:8102
 */
@Component
@Slf4j
public class ScripFinderClient {

    @Value("${scripfinder.api.base-url:http://localhost:8102}")
    private String baseUrl;

    @Value("${scripfinder.api.timeout.ms:3000}")
    private long timeoutMs;

    @Value("${scripfinder.api.circuit-breaker.failure-threshold:5}")
    private int circuitBreakerFailureThreshold;

    @Value("${scripfinder.api.circuit-breaker.timeout-ms:60000}")
    private long circuitBreakerTimeoutMs;

    private final RestTemplate restTemplate;
    private final CircuitBreaker circuitBreaker;

    public ScripFinderClient() {
        this.restTemplate = new RestTemplate();
        // Initialize circuit breaker with defaults (will be reconfigured in @PostConstruct if needed)
        this.circuitBreaker = new CircuitBreaker("ScripFinder", 5, 60000, 3);
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        // Note: Circuit breaker is already initialized with defaults in constructor
        // If you need to reinitialize with @Value properties, create a new instance here
        log.info("ScripFinderClient initialized with circuit breaker: failureThreshold={}, timeout={}ms",
                circuitBreakerFailureThreshold, circuitBreakerTimeoutMs);
    }

    /**
     * Fetch complete instrument family for an equity
     *
     * @param equityScripCode Equity scrip code (e.g., "14154" for UNOMINDA)
     * @param closePrice Current close price for ATM calculation
     * @return InstrumentFamily with future and options info
     * @throws IllegalArgumentException if equityScripCode is null/empty or closePrice is invalid
     */
    public InstrumentFamily getFamily(String equityScripCode, double closePrice) {
        // Input validation
        if (equityScripCode == null || equityScripCode.trim().isEmpty()) {
            throw new IllegalArgumentException("equityScripCode cannot be null or empty");
        }
        if (closePrice <= 0) {
            throw new IllegalArgumentException("closePrice must be positive, got: " + closePrice);
        }

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
     * Get future info for equity with circuit breaker protection
     * FIXED: Added circuit breaker to prevent cascading failures
     * @throws IllegalArgumentException if equityScripCode is null/empty
     */
    public FutureResponse getFuture(String equityScripCode) {
        if (equityScripCode == null || equityScripCode.trim().isEmpty()) {
            throw new IllegalArgumentException("equityScripCode cannot be null or empty");
        }

        String url = baseUrl + "/getRequiredFuture?equityScripCode=" + equityScripCode;
        log.debug("Fetching future: {}", url);

        return circuitBreaker.execute(() -> {
            try {
                CompletableFuture<FutureResponse> future = CompletableFuture.supplyAsync(() ->
                        restTemplate.getForObject(url, FutureResponse.class));
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("Timeout fetching future for scripCode {}", equityScripCode);
                throw new RuntimeException("Timeout", e);
            } catch (Exception e) {
                log.error("Error fetching future for scripCode {}: {}", equityScripCode, e.getMessage());
                throw new RuntimeException("API call failed", e);
            }
        }, null); // null fallback - caller handles null responses
    }

    /**
     * Get options info for equity (returns 4 ATM options) with circuit breaker protection
     * FIXED: Added circuit breaker to prevent cascading failures
     * @throws IllegalArgumentException if equityScripCode is null/empty
     */
    public OptionsResponse getOptions(String equityScripCode) {
        if (equityScripCode == null || equityScripCode.trim().isEmpty()) {
            throw new IllegalArgumentException("equityScripCode cannot be null or empty");
        }

        String url = baseUrl + "/getRequiredOptions?equityScripCode=" + equityScripCode;
        log.debug("Fetching options: {}", url);

        return circuitBreaker.execute(() -> {
            try {
                CompletableFuture<OptionsResponse> future = CompletableFuture.supplyAsync(() ->
                        restTemplate.getForObject(url, OptionsResponse.class));
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("Timeout fetching options for scripCode {}", equityScripCode);
                throw new RuntimeException("Timeout", e);
            } catch (Exception e) {
                log.error("Error fetching options for scripCode {}: {}", equityScripCode, e.getMessage());
                throw new RuntimeException("API call failed", e);
            }
        }, null); // null fallback - caller handles null responses
    }

    /**
     * Get ALL equities from ScripFinder API
     * Called on startup to preload all equity mappings
     * 
     * @return List of equity info with symbolRoot and scripCode
     */
    public AllEquitiesResponse getAllEquities() {
        String url = baseUrl + "/getDesiredWebSocket?tradingType=EQUITY";
        log.info("Fetching all equities from: {}", url);

        try {
            CompletableFuture<AllEquitiesResponse> future = CompletableFuture.supplyAsync(() ->
                    restTemplate.getForObject(url, AllEquitiesResponse.class));
            return future.get(30000, TimeUnit.MILLISECONDS); // 30 second timeout for bulk fetch
        } catch (TimeoutException e) {
            log.error("Timeout fetching all equities");
            return null;
        } catch (Exception e) {
            log.error("Error fetching all equities: {}", e.getMessage());
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

    /**
     * Response from /getDesiredWebSocket?tradingType=EQUITY
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AllEquitiesResponse {
        private int status;
        private String message;
        private List<EquityInfo> response;
    }

    /**
     * Equity info from getDesiredWebSocket API
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EquityInfo {
        private String equityScripCode;
        private String tradingType;
        private String companyName;
        private ScripInfo equity;
        private List<ScripInfo> futures;
        private List<ScripInfo> options;
        private double closePrice;
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
