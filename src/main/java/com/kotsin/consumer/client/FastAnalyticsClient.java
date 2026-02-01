package com.kotsin.consumer.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.model.HistoricalCandle;
import com.kotsin.consumer.model.PivotLevels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * FastAnalyticsClient - HTTP client for the Python FastAnalytics API.
 *
 * Provides methods to:
 * 1. Fetch historical candle data from FivePaisa
 * 2. Fetch pivot data (daily/weekly/monthly)
 */
@Service
@Slf4j
public class FastAnalyticsClient {

    private static final String LOG_PREFIX = "[FAST-ANALYTICS]";

    @Value("${fastanalytics.base-url:http://localhost:8002}")
    private String baseUrl;

    @Value("${fastanalytics.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${fastanalytics.read-timeout-ms:60000}")
    private int readTimeoutMs;

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();

        log.info("{} Initialized with baseUrl={}, connectTimeout={}ms, readTimeout={}ms",
            LOG_PREFIX, baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * Fetch historical candle data from FivePaisa API.
     *
     * @param exch Exchange (N for NSE, B for BSE, M for MCX)
     * @param exchType Exchange type (C for cash, D for derivatives)
     * @param scripCode Instrument scrip code (same as token)
     * @param startDate Start date (YYYY-MM-DD)
     * @param endDate End date (YYYY-MM-DD)
     * @param interval Candle interval (1m, 5m, 15m, 30m, 1h, 1d)
     * @return List of historical candles
     */
    public List<HistoricalCandle> getHistoricalData(String exch, String exchType,
            String scripCode, String startDate, String endDate, String interval) {

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/getHisDataFromFivePaisa")
            .queryParam("exch", exch)
            .queryParam("exch_type", exchType)
            .queryParam("scrip_code", scripCode)
            .queryParam("start_date", startDate)
            .queryParam("end_date", endDate)
            .queryParam("interval", interval)
            .toUriString();

        log.debug("{} Fetching historical data: {}", LOG_PREFIX, url);

        try {
            long startTime = System.currentTimeMillis();

            String response = restTemplate.getForObject(url, String.class);

            if (response == null || response.isEmpty() || response.equals("[]")) {
                log.warn("{} Empty response for scripCode={}", LOG_PREFIX, scripCode);
                return Collections.emptyList();
            }

            List<HistoricalCandle> candles = objectMapper.readValue(
                response,
                new TypeReference<List<HistoricalCandle>>() {}
            );

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("{} Fetched {} candles for scripCode={} in {}ms",
                LOG_PREFIX, candles.size(), scripCode, elapsed);

            return candles;

        } catch (RestClientException e) {
            log.error("{} REST error fetching historical data for scripCode={}: {}",
                LOG_PREFIX, scripCode, e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("{} Error parsing historical data for scripCode={}: {}",
                LOG_PREFIX, scripCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Fetch pivot data for a given period.
     *
     * @param exch Exchange (N for NSE, B for BSE, M for MCX)
     * @param exchType Exchange type (C for cash, D for derivatives)
     * @param scripCode Instrument scrip code
     * @param startDate Start date of the period to calculate pivots from
     * @param endDate End date of the period
     * @param interval Interval for aggregation (1d for daily, 1wk for weekly, 1mo for monthly)
     * @return PivotLevels containing all pivot points
     */
    public PivotLevels getPivotData(String exch, String exchType,
            String scripCode, String startDate, String endDate, String interval) {

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/getPivotData")
            .queryParam("exch", exch)
            .queryParam("exch_type", exchType)
            .queryParam("scrip_code", scripCode)
            .queryParam("start_date", startDate)
            .queryParam("end_date", endDate)
            .queryParam("interval", interval)
            .toUriString();

        log.debug("{} Fetching pivot data: {}", LOG_PREFIX, url);

        try {
            long startTime = System.currentTimeMillis();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response == null || response.isEmpty()) {
                log.warn("{} Empty pivot response for scripCode={}", LOG_PREFIX, scripCode);
                return createEmptyPivotLevels();
            }

            PivotLevels pivots = mapToPivotLevels(response);

            long elapsed = System.currentTimeMillis() - startTime;
            log.debug("{} Fetched pivot data for scripCode={} (interval={}) in {}ms - Pivot: {}, CPR: {}-{}",
                LOG_PREFIX, scripCode, interval, elapsed,
                String.format("%.2f", pivots.getPivot()),
                String.format("%.2f", pivots.getBc()),
                String.format("%.2f", pivots.getTc()));

            return pivots;

        } catch (RestClientException e) {
            log.error("{} REST error fetching pivot data for scripCode={}: {}",
                LOG_PREFIX, scripCode, e.getMessage());
            return createEmptyPivotLevels();
        } catch (Exception e) {
            log.error("{} Error parsing pivot data for scripCode={}: {}",
                LOG_PREFIX, scripCode, e.getMessage());
            return createEmptyPivotLevels();
        }
    }

    /**
     * Check if the FastAnalytics service is available.
     */
    public boolean isAvailable() {
        try {
            String url = baseUrl + "/docs";
            restTemplate.getForObject(url, String.class);
            return true;
        } catch (Exception e) {
            log.warn("{} Service not available: {}", LOG_PREFIX, e.getMessage());
            return false;
        }
    }

    /**
     * Map API response to PivotLevels object.
     */
    private PivotLevels mapToPivotLevels(Map<String, Object> response) {
        return PivotLevels.builder()
            // Standard Pivots
            .pivot(getDouble(response, "pivot"))
            .s1(getDouble(response, "s1"))
            .s2(getDouble(response, "s2"))
            .s3(getDouble(response, "s3"))
            .s4(getDouble(response, "s4"))
            .r1(getDouble(response, "r1"))
            .r2(getDouble(response, "r2"))
            .r3(getDouble(response, "r3"))
            .r4(getDouble(response, "r4"))
            // Fibonacci Pivots
            .fibS1(getDouble(response, "fibS1"))
            .fibS2(getDouble(response, "fibS2"))
            .fibS3(getDouble(response, "fibS3"))
            .fibR1(getDouble(response, "fibR1"))
            .fibR2(getDouble(response, "fibR2"))
            .fibR3(getDouble(response, "fibR3"))
            // Camarilla Pivots
            .camS1(getDouble(response, "camS1"))
            .camS2(getDouble(response, "camS2"))
            .camS3(getDouble(response, "camS3"))
            .camS4(getDouble(response, "camS4"))
            .camR1(getDouble(response, "camR1"))
            .camR2(getDouble(response, "camR2"))
            .camR3(getDouble(response, "camR3"))
            .camR4(getDouble(response, "camR4"))
            // CPR
            .tc(getDouble(response, "tc"))
            .bc(getDouble(response, "bc"))
            .build();
    }

    /**
     * Safely get double value from map.
     */
    private double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0.0;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Create empty pivot levels (used when API fails).
     */
    private PivotLevels createEmptyPivotLevels() {
        return PivotLevels.builder()
            .pivot(0).s1(0).s2(0).s3(0).s4(0)
            .r1(0).r2(0).r3(0).r4(0)
            .tc(0).bc(0)
            .build();
    }
}
