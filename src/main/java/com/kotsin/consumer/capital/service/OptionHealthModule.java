package com.kotsin.consumer.capital.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.capital.model.OptionHealthOutput;
import com.kotsin.consumer.capital.model.OptionHealthOutput.*;
import com.kotsin.consumer.util.CircuitBreaker;
import com.kotsin.consumer.util.TTLCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/**
 * OptionHealthModule - Module 12: OHM
 * 
 * Analyzes options chain for health metrics:
 * - IV Percentile calculation
 * - Max Pain detection
 * - PCR (Put-Call Ratio)
 * - Liquidity scoring
 * 
 * FIXED: Uses scheduled background fetch instead of per-candle API calls
 * FIXED: Uses circuit breaker to prevent API hammering
 * FIXED: Uses TTL cache for results
 */
@Slf4j
@Service
public class OptionHealthModule {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${options.api.url:http://localhost:8102/getRequiredOptions}")
    private String optionsApiUrl;

    @Value("${options.api.enabled:true}")
    private boolean apiEnabled;

    @Value("${ohm.cache.ttl.ms:300000}")
    private long cacheTtlMs;  // 5 minutes

    @Value("${ohm.refresh.interval.ms:60000}")
    private long refreshIntervalMs;  // 1 minute

    @Value("${ohm.quality.min:0.4}")
    private double minQuality;

    // Circuit breaker for API protection
    private CircuitBreaker circuitBreaker;

    // TTL Cache for OHM results
    private TTLCache<String, OptionHealthOutput> ohmCache;

    // Cache for IV history (scripCode -> list of historical IVs)
    private final Map<String, List<Double>> ivHistoryCache = new ConcurrentHashMap<>();

    // Scrip codes to refresh (populated by processors)
    private final Set<String> activeScripCodes = ConcurrentHashMap.newKeySet();

    // Scheduled executor for background refresh
    private ScheduledExecutorService refreshExecutor;

    @PostConstruct
    public void init() {
        // Initialize circuit breaker: 5 failures, 1 min timeout, 3 test calls
        circuitBreaker = new CircuitBreaker("OHM-API", 5, 60000, 3);

        // Initialize cache: 5 min TTL, max 500 entries, cleanup every 30s
        ohmCache = new TTLCache<>("OHM-Cache", cacheTtlMs, 500, 30000);

        // Start background refresh
        refreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OHM-Refresh");
            t.setDaemon(true);
            return t;
        });

        refreshExecutor.scheduleAtFixedRate(
                this::refreshActiveScripCodes,
                60000,  // Initial delay 1 min
                refreshIntervalMs,
                TimeUnit.MILLISECONDS
        );

        log.info("✅ OptionHealthModule initialized: apiEnabled={}, cacheTTL={}ms, refreshInterval={}ms",
                apiEnabled, cacheTtlMs, refreshIntervalMs);
    }

    @PreDestroy
    public void shutdown() {
        if (refreshExecutor != null) {
            refreshExecutor.shutdown();
        }
        if (ohmCache != null) {
            ohmCache.shutdown();
        }
        log.info("🛑 OptionHealthModule shutdown");
    }

    /**
     * Register a scripCode for background refresh
     */
    public void registerForRefresh(String scripCode) {
        if (scripCode != null && !scripCode.contains("999920")) {
            activeScripCodes.add(scripCode);
        }
    }

    /**
     * Get cached OHM output (fast, no API call)
     */
    public OptionHealthOutput getCached(String scripCode) {
        return ohmCache.get(scripCode);
    }

    /**
     * Get OHM output, computing if not cached
     * Uses circuit breaker, won't block on API failures
     */
    public OptionHealthOutput calculate(String scripCode, String companyName, double currentPrice) {
        if (scripCode == null || scripCode.isEmpty()) {
            return emptyResult(scripCode, companyName);
        }

        // Register for future refresh
        registerForRefresh(scripCode);

        // Check cache first
        OptionHealthOutput cached = ohmCache.get(scripCode);
        if (cached != null) {
            return cached;
        }

        // If API disabled or circuit open, return empty
        if (!apiEnabled || !circuitBreaker.isReady()) {
            return emptyResult(scripCode, companyName);
        }

        // Fetch from API with circuit breaker
        OptionHealthOutput result = circuitBreaker.execute(
                () -> fetchFromAPI(scripCode, companyName, currentPrice),
                emptyResult(scripCode, companyName)
        );

        // Cache result
        if (result != null && result.getQualityScore() > 0) {
            ohmCache.put(scripCode, result);
        }

        return result;
    }

    /**
     * Background refresh of active scrip codes
     */
    private void refreshActiveScripCodes() {
        if (!apiEnabled || activeScripCodes.isEmpty()) return;

        int refreshed = 0;
        int failed = 0;

        for (String scripCode : activeScripCodes) {
            if (!circuitBreaker.isReady()) {
                log.warn("⚠️ OHM refresh stopped - circuit breaker open");
                break;
            }

            try {
                OptionHealthOutput result = circuitBreaker.execute(
                        (java.util.function.Supplier<OptionHealthOutput>) () -> fetchFromAPI(scripCode, null, 0),
                        (OptionHealthOutput) null
                );

                if (result != null && result.getQualityScore() > 0) {
                    ohmCache.put(scripCode, result);
                    refreshed++;
                }
            } catch (Exception e) {
                failed++;
            }

            // Rate limit: 100ms between calls
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (refreshed > 0 || failed > 0) {
            log.info("🔄 OHM refresh complete: refreshed={}, failed={}, active={}", 
                    refreshed, failed, activeScripCodes.size());
        }

        // Log stats periodically
        ohmCache.logStats();
        circuitBreaker.logStats();
    }

    /**
     * Fetch from API (called by circuit breaker)
     */
    private OptionHealthOutput fetchFromAPI(String scripCode, String companyName, double currentPrice) {
        try {
            String url = optionsApiUrl + "?equityScripCode=" + scripCode;
            String response = restTemplate.getForObject(url, String.class);

            if (response == null || response.isEmpty()) {
                log.debug("OHM: Empty response for {}", scripCode);
                return emptyResult(scripCode, companyName);
            }

            JsonNode root = objectMapper.readTree(response);
            return analyzeOptionsChain(scripCode, companyName, currentPrice, root);

        } catch (Exception e) {
            log.warn("OHM API error for {}: {}", scripCode, e.getMessage());
            throw new RuntimeException("OHM API failed: " + e.getMessage(), e);
        }
    }

    /**
     * Analyze the options chain data
     */
    private OptionHealthOutput analyzeOptionsChain(String scripCode, String companyName,
                                                    double currentPrice, JsonNode root) {
        List<OptionData> calls = new ArrayList<>();
        List<OptionData> puts = new ArrayList<>();

        if (root.isArray()) {
            for (JsonNode option : root) {
                OptionData data = parseOption(option);
                if (data != null) {
                    if ("CE".equalsIgnoreCase(data.optionType)) {
                        calls.add(data);
                    } else if ("PE".equalsIgnoreCase(data.optionType)) {
                        puts.add(data);
                    }
                }
            }
        }

        if (calls.isEmpty() && puts.isEmpty()) {
            return emptyResult(scripCode, companyName);
        }

        // Use first option's LTP if currentPrice not provided
        if (currentPrice <= 0 && !calls.isEmpty()) {
            currentPrice = calls.get(0).strikePrice;
        }

        // Calculate metrics
        double totalCallOI = calls.stream().mapToDouble(o -> o.oi).sum();
        double totalPutOI = puts.stream().mapToDouble(o -> o.oi).sum();
        double pcr = totalCallOI > 0 ? totalPutOI / totalCallOI : 1.0;

        double maxPainPrice = calculateMaxPain(calls, puts, currentPrice);
        double maxPainDistance = currentPrice > 0 ?
                Math.abs(maxPainPrice - currentPrice) / currentPrice * 100 : 0;

        double atmIV = calculateATMIV(calls, puts, currentPrice);

        updateIVHistory(scripCode, atmIV);
        double ivPercentile = calculateIVPercentile(scripCode, atmIV);
        double ivMean = calculateIVMean(scripCode);

        double avgSpread = calculateAverageSpread(calls, puts, currentPrice);
        double liquidityScore = Math.max(0, 1 - avgSpread / 5);

        double callOiConc = calculateOIConcentration(calls);
        double putOiConc = calculateOIConcentration(puts);

        double nearestCall = findNearestStrike(calls, currentPrice);
        double nearestPut = findNearestStrike(puts, currentPrice);

        IVState ivState = IVState.fromPercentile(ivPercentile);
        PCRState pcrState = PCRState.fromPCR(pcr);
        LiquidityState liquidityState = LiquidityState.fromSpread(avgSpread);

        double qualityScore = calculateQualityScore(liquidityScore, ivPercentile, pcr, maxPainDistance);

        boolean allowNaked = ivPercentile > 60 && liquidityScore > 0.5;
        boolean allowBuy = ivPercentile < 50 && liquidityScore > 0.4;

        return OptionHealthOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .qualityScore(qualityScore)
                .allowNakedOptions(allowNaked)
                .allowBuyOptions(allowBuy)
                .ivPercentile(ivPercentile)
                .ivCurrent(atmIV)
                .ivMean(ivMean)
                .ivState(ivState)
                .maxPainPrice(maxPainPrice)
                .maxPainDistance(maxPainDistance)
                .putCallRatio(pcr)
                .pcrState(pcrState)
                .avgBidAskSpread(avgSpread)
                .liquidityScore(liquidityScore)
                .liquidityState(liquidityState)
                .callOiConcentration(callOiConc)
                .putOiConcentration(putOiConc)
                .atmIV(atmIV)
                .nearestCallStrike(nearestCall)
                .nearestPutStrike(nearestPut)
                .build();
    }

    private OptionData parseOption(JsonNode node) {
        try {
            OptionData data = new OptionData();
            data.strikePrice = node.path("strikeRate").asDouble(0);
            data.oi = node.path("openInterest").asDouble(0);
            data.iv = node.path("impVol").asDouble(0);
            data.bid = node.path("bidPrice").asDouble(0);
            data.ask = node.path("askPrice").asDouble(0);
            data.ltp = node.path("lastRate").asDouble(0);
            data.optionType = node.path("cpType").asText("");
            data.volume = node.path("tradedQty").asDouble(0);
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private static class OptionData {
        double strikePrice;
        double oi;
        double iv;
        double bid;
        double ask;
        double ltp;
        String optionType;
        double volume;
    }

    private double calculateMaxPain(List<OptionData> calls, List<OptionData> puts, double currentPrice) {
        if (calls.isEmpty() && puts.isEmpty()) return currentPrice;

        Set<Double> strikes = new TreeSet<>();
        calls.forEach(c -> strikes.add(c.strikePrice));
        puts.forEach(p -> strikes.add(p.strikePrice));

        double maxPain = currentPrice;
        double minPain = Double.MAX_VALUE;

        for (double strike : strikes) {
            double pain = 0;
            for (OptionData call : calls) {
                pain += call.oi * Math.max(0, strike - call.strikePrice);
            }
            for (OptionData put : puts) {
                pain += put.oi * Math.max(0, put.strikePrice - strike);
            }

            if (pain < minPain) {
                minPain = pain;
                maxPain = strike;
            }
        }

        return maxPain;
    }

    private double calculateATMIV(List<OptionData> calls, List<OptionData> puts, double currentPrice) {
        double callIV = 0, putIV = 0;
        double minCallDist = Double.MAX_VALUE, minPutDist = Double.MAX_VALUE;

        for (OptionData call : calls) {
            double dist = Math.abs(call.strikePrice - currentPrice);
            if (dist < minCallDist && call.iv > 0) {
                minCallDist = dist;
                callIV = call.iv;
            }
        }

        for (OptionData put : puts) {
            double dist = Math.abs(put.strikePrice - currentPrice);
            if (dist < minPutDist && put.iv > 0) {
                minPutDist = dist;
                putIV = put.iv;
            }
        }

        if (callIV > 0 && putIV > 0) {
            return (callIV + putIV) / 2;
        }
        return Math.max(callIV, putIV);
    }

    private double calculateAverageSpread(List<OptionData> calls, List<OptionData> puts, double currentPrice) {
        List<Double> spreads = new ArrayList<>();
        double nearRange = currentPrice * 0.05;

        for (OptionData call : calls) {
            if (Math.abs(call.strikePrice - currentPrice) < nearRange && call.ask > 0) {
                double spread = (call.ask - call.bid) / call.ask * 100;
                spreads.add(spread);
            }
        }

        for (OptionData put : puts) {
            if (Math.abs(put.strikePrice - currentPrice) < nearRange && put.ask > 0) {
                double spread = (put.ask - put.bid) / put.ask * 100;
                spreads.add(spread);
            }
        }

        if (spreads.isEmpty()) return 5.0;
        return spreads.stream().mapToDouble(d -> d).average().orElse(5.0);
    }

    private double calculateOIConcentration(List<OptionData> options) {
        if (options.size() < 3) return 1.0;

        double totalOI = options.stream().mapToDouble(o -> o.oi).sum();
        if (totalOI == 0) return 0;

        List<OptionData> sorted = new ArrayList<>(options);
        sorted.sort((a, b) -> Double.compare(b.oi, a.oi));

        double top3OI = sorted.stream().limit(3).mapToDouble(o -> o.oi).sum();
        return top3OI / totalOI;
    }

    private double findNearestStrike(List<OptionData> options, double currentPrice) {
        return options.stream()
                .min((a, b) -> Double.compare(
                        Math.abs(a.strikePrice - currentPrice),
                        Math.abs(b.strikePrice - currentPrice)))
                .map(o -> o.strikePrice)
                .orElse(currentPrice);
    }

    private void updateIVHistory(String scripCode, double iv) {
        if (iv <= 0) return;

        ivHistoryCache.computeIfAbsent(scripCode, k -> new ArrayList<>()).add(iv);

        List<Double> history = ivHistoryCache.get(scripCode);
        while (history.size() > 100) {
            history.remove(0);
        }
    }

    private double calculateIVPercentile(String scripCode, double currentIV) {
        List<Double> history = ivHistoryCache.get(scripCode);
        if (history == null || history.size() < 5) {
            return 50;
        }

        long count = history.stream().filter(iv -> iv < currentIV).count();
        return (double) count / history.size() * 100;
    }

    private double calculateIVMean(String scripCode) {
        List<Double> history = ivHistoryCache.get(scripCode);
        if (history == null || history.isEmpty()) return 0;
        return history.stream().mapToDouble(d -> d).average().orElse(0);
    }

    private double calculateQualityScore(double liq, double ivPct, double pcr, double maxPainDist) {
        double liqScore = liq;
        double ivScore = 1 - Math.abs(ivPct - 50) / 50;
        double pcrScore = pcr > 0.5 && pcr < 1.5 ? 1 : 0.5;
        double mpScore = Math.max(0, 1 - maxPainDist / 5);

        return 0.4 * liqScore + 0.3 * ivScore + 0.15 * pcrScore + 0.15 * mpScore;
    }

    private OptionHealthOutput emptyResult(String scripCode, String companyName) {
        return OptionHealthOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .qualityScore(0)
                .allowNakedOptions(false)
                .allowBuyOptions(false)
                .ivPercentile(50)
                .ivState(IVState.NORMAL)
                .putCallRatio(1.0)
                .pcrState(PCRState.NEUTRAL)
                .liquidityScore(0)
                .liquidityState(LiquidityState.ILLIQUID)
                .build();
    }
}
