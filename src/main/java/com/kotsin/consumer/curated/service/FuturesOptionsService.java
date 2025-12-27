package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.curated.model.FuturesData;
import com.kotsin.consumer.curated.model.FuturesOptionsAlignment;
import com.kotsin.consumer.curated.model.OptionsData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.*;

/**
 * FuturesOptionsService - Fetches and analyzes F&O data
 *
 * Integrates with existing F&O API to get futures and options data.
 * Analyzes the data to determine if it aligns with the trading signal.
 */
@Service
public class FuturesOptionsService {

    private static final Logger log = LoggerFactory.getLogger(FuturesOptionsService.class);

    @Value("${curated.fo.api.base-url:http://localhost:8080/api/fo}")
    private String foApiBaseUrl;

    @Value("${curated.fo.api.timeout-ms:3000}")
    private long apiTimeoutMs;

    @Value("${curated.fo.enabled:true}")
    private boolean foEnabled;

    private final RestTemplate restTemplate;
    private final ExecutorService executor;

    // Cache to avoid hammering API
    private final ConcurrentHashMap<String, FuturesData> futuresCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OptionsData> optionsCache = new ConcurrentHashMap<>();

    public FuturesOptionsService() {
        this.restTemplate = new RestTemplate();
        this.executor = Executors.newFixedThreadPool(5);
    }

    /**
     * Fetch futures data for a scrip (with timeout)
     */
    public FuturesData fetchFuturesData(String scripCode) {
        if (!foEnabled) {
            log.debug("F&O service disabled, returning null");
            return null;
        }

        // Check cache first (valid for 1 minute)
        FuturesData cached = futuresCache.get(scripCode);
        if (cached != null && cached.isFresh()) {
            return cached;
        }

        try {
            // Call API with timeout
            CompletableFuture<FuturesData> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String url = foApiBaseUrl + "/futures/" + scripCode;
                    return restTemplate.getForObject(url, FuturesData.class);
                } catch (Exception e) {
                    log.error("Error fetching futures data for {}: {}", scripCode, e.getMessage());
                    return null;
                }
            }, executor);

            FuturesData data = future.get(apiTimeoutMs, TimeUnit.MILLISECONDS);

            if (data != null) {
                // Set timestamp and calculate derived fields
                data.setTimestamp(System.currentTimeMillis());
                data.setStale(!data.isFresh());
                data.setPremiumPositive(data.getPremium() > 0);

                // Detect buildup type
                FuturesData.BuildupType buildup = FuturesData.detectBuildup(
                        data.getPriceChangePercent(),
                        data.getOiChangePercent()
                );
                data.setBuildup(buildup);

                // Cache it
                futuresCache.put(scripCode, data);

                log.debug("Futures data fetched for {}: premium={}%, OI change={}%, buildup={}",
                        scripCode, data.getPremium(), data.getOiChangePercent(), buildup);
            }

            return data;

        } catch (TimeoutException e) {
            log.error("Timeout fetching futures data for {} ({}ms)", scripCode, apiTimeoutMs);
            return null;
        } catch (Exception e) {
            log.error("Error fetching futures data for {}: {}", scripCode, e.getMessage());
            return null;
        }
    }

    /**
     * Fetch options data for a scrip (with timeout)
     */
    public OptionsData fetchOptionsData(String scripCode, double spotPrice) {
        if (!foEnabled) {
            log.debug("F&O service disabled, returning null");
            return null;
        }

        // Check cache first
        OptionsData cached = optionsCache.get(scripCode);
        if (cached != null && cached.isFresh()) {
            return cached;
        }

        try {
            // Call API with timeout
            CompletableFuture<OptionsData> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String url = foApiBaseUrl + "/options/" + scripCode + "?spotPrice=" + spotPrice;
                    return restTemplate.getForObject(url, OptionsData.class);
                } catch (Exception e) {
                    log.error("Error fetching options data for {}: {}", scripCode, e.getMessage());
                    return null;
                }
            }, executor);

            OptionsData data = future.get(apiTimeoutMs, TimeUnit.MILLISECONDS);

            if (data != null) {
                // Set timestamp
                data.setTimestamp(System.currentTimeMillis());
                data.setStale(!data.isFresh());

                // Cache it
                optionsCache.put(scripCode, data);

                log.debug("Options data fetched for {}: PCR={}, sentiment={}",
                        scripCode, data.getPcr(), data.getSentiment());
            }

            return data;

        } catch (TimeoutException e) {
            log.error("Timeout fetching options data for {} ({}ms)", scripCode, apiTimeoutMs);
            return null;
        } catch (Exception e) {
            log.error("Error fetching options data for {}: {}", scripCode, e.getMessage());
            return null;
        }
    }

    /**
     * Calculate F&O alignment score
     */
    public FuturesOptionsAlignment calculateAlignment(String scripCode, double spotPrice) {

        FuturesData futuresData = fetchFuturesData(scripCode);
        OptionsData optionsData = fetchOptionsData(scripCode, spotPrice);

        // Check data availability
        boolean dataAvailable = (futuresData != null || optionsData != null);
        boolean dataFresh = (futuresData != null && futuresData.isFresh())
                || (optionsData != null && optionsData.isFresh());

        FuturesOptionsAlignment.FuturesOptionsAlignmentBuilder builder = FuturesOptionsAlignment.builder()
                .scripCode(scripCode)
                .timestamp(System.currentTimeMillis())
                .futuresData(futuresData)
                .optionsData(optionsData)
                .dataAvailable(dataAvailable)
                .dataFresh(dataFresh);

        if (!dataAvailable) {
            log.warn("No F&O data available for {}", scripCode);
            return builder
                    .futuresScore(0.0)
                    .optionsScore(0.0)
                    .alignmentScore(0.0)
                    .isAligned(false)
                    .bias(FuturesOptionsAlignment.DirectionalBias.NEUTRAL)
                    .build();
        }

        // Calculate futures score
        double futuresScore = calculateFuturesScore(futuresData, builder);

        // Calculate options score
        double optionsScore = calculateOptionsScore(optionsData, builder);

        // Combined score (weighted average)
        double alignmentScore;
        if (futuresData != null && optionsData != null) {
            alignmentScore = (futuresScore * 0.6) + (optionsScore * 0.4);  // Futures weighted more
        } else if (futuresData != null) {
            alignmentScore = futuresScore;
        } else {
            alignmentScore = optionsScore;
        }

        // Determine bias
        FuturesOptionsAlignment.DirectionalBias bias = determineBias(futuresData, optionsData);

        FuturesOptionsAlignment alignment = builder
                .futuresScore(futuresScore)
                .optionsScore(optionsScore)
                .alignmentScore(alignmentScore)
                .isAligned(alignmentScore >= 0.6)
                .bias(bias)
                .build();

        log.info("F&O Alignment for {}: score={}, bias={}, aligned={}",
                scripCode, String.format("%.2f", alignmentScore), bias, alignment.isAligned());

        return alignment;
    }

    /**
     * Calculate futures score (0 to 1)
     */
    private double calculateFuturesScore(FuturesData futures,
                                         FuturesOptionsAlignment.FuturesOptionsAlignmentBuilder builder) {
        if (futures == null || !futures.isFresh()) {
            return 0.0;
        }

        double score = 0.0;

        // Premium analysis (0-0.3 points)
        if (futures.isPremiumPositive()) {
            score += 0.3;
            builder.reasons.add("Futures at premium (" + String.format("%.2f", futures.getPremium()) + "%)");
        }

        // OI change analysis (0-0.3 points)
        if (futures.getOiChangePercent() > 5) {
            score += 0.3;
            builder.reasons.add("Futures OI increasing (" + String.format("%.1f", futures.getOiChangePercent()) + "%)");
        } else if (futures.getOiChangePercent() > 2) {
            score += 0.15;
        }

        // Buildup type analysis (0-0.4 points)
        FuturesData.BuildupType buildup = futures.getBuildup();
        switch (buildup) {
            case LONG_BUILDUP:
                score += 0.4;
                builder.reasons.add("LONG BUILDUP (Price ↑ + OI ↑)");
                break;
            case SHORT_COVERING:
                score += 0.2;
                builder.reasons.add("SHORT COVERING (Price ↑ + OI ↓)");
                break;
            case SHORT_BUILDUP:
                score += 0.0;  // Bearish, bad for long
                builder.reasons.add("SHORT BUILDUP (Price ↓ + OI ↑) - BEARISH");
                break;
            case LONG_UNWINDING:
                score += 0.0;  // Bearish, bad for long
                builder.reasons.add("LONG UNWINDING (Price ↓ + OI ↓) - BEARISH");
                break;
            default:
                score += 0.1;
        }

        return Math.min(score, 1.0);
    }

    /**
     * Calculate options score (0 to 1)
     */
    private double calculateOptionsScore(OptionsData options,
                                          FuturesOptionsAlignment.FuturesOptionsAlignmentBuilder builder) {
        if (options == null || !options.isFresh()) {
            return 0.0;
        }

        double score = 0.0;

        // PCR analysis (0-0.4 points)
        OptionsData.Sentiment sentiment = options.getSentiment();
        if (sentiment == OptionsData.Sentiment.BULLISH) {
            score += 0.4;
            builder.reasons.add("Options BULLISH (PCR=" + String.format("%.2f", options.getPcr()) + ")");
        } else if (sentiment == OptionsData.Sentiment.NEUTRAL) {
            score += 0.2;
        } else {
            score += 0.0;
            builder.reasons.add("Options BEARISH (PCR=" + String.format("%.2f", options.getPcr()) + ")");
        }

        // OI change analysis (0-0.3 points)
        if (options.getTotalCallOIChange() > options.getTotalPutOIChange()) {
            score += 0.3;
            builder.reasons.add("Call OI increasing faster than Put OI");
        } else if (options.getTotalCallOIChange() > 0) {
            score += 0.15;
        }

        // Strong directional move (0-0.3 points)
        if (options.indicatesStrongMove()) {
            score += 0.3;
            builder.reasons.add("Strong directional OI positioning detected");
        }

        // Max pain check (penalty)
        if (options.isNearMaxPain()) {
            score *= 0.7;  // Reduce score by 30% if near max pain (pinning risk)
            builder.reasons.add("Near max pain - pinning risk");
        }

        return Math.min(score, 1.0);
    }

    /**
     * Determine directional bias from F&O
     */
    private FuturesOptionsAlignment.DirectionalBias determineBias(
            FuturesData futures, OptionsData options) {

        int bullishPoints = 0;
        int bearishPoints = 0;

        // Futures analysis
        if (futures != null && futures.isFresh()) {
            if (futures.getBuildup() == FuturesData.BuildupType.LONG_BUILDUP) {
                bullishPoints += 2;
            } else if (futures.getBuildup() == FuturesData.BuildupType.SHORT_COVERING) {
                bullishPoints += 1;
            } else if (futures.getBuildup() == FuturesData.BuildupType.SHORT_BUILDUP) {
                bearishPoints += 2;
            } else if (futures.getBuildup() == FuturesData.BuildupType.LONG_UNWINDING) {
                bearishPoints += 1;
            }

            if (futures.isPremiumPositive()) {
                bullishPoints += 1;
            } else {
                bearishPoints += 1;
            }
        }

        // Options analysis
        if (options != null && options.isFresh()) {
            OptionsData.Sentiment sentiment = options.getSentiment();
            if (sentiment == OptionsData.Sentiment.BULLISH) {
                bullishPoints += 2;
            } else if (sentiment == OptionsData.Sentiment.BEARISH) {
                bearishPoints += 2;
            }
        }

        // Determine bias
        int netPoints = bullishPoints - bearishPoints;

        if (netPoints >= 3) return FuturesOptionsAlignment.DirectionalBias.STRONG_BULLISH;
        if (netPoints >= 1) return FuturesOptionsAlignment.DirectionalBias.BULLISH;
        if (netPoints <= -3) return FuturesOptionsAlignment.DirectionalBias.STRONG_BEARISH;
        if (netPoints <= -1) return FuturesOptionsAlignment.DirectionalBias.BEARISH;
        return FuturesOptionsAlignment.DirectionalBias.NEUTRAL;
    }

    /**
     * Clear cache (call periodically)
     */
    public void clearStaleCache() {
        futuresCache.entrySet().removeIf(entry -> !entry.getValue().isFresh());
        optionsCache.entrySet().removeIf(entry -> !entry.getValue().isFresh());
    }
}
