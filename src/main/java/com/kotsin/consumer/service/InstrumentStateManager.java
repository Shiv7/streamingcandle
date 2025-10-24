package com.kotsin.consumer.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.processor.CandleAccumulator;
import com.kotsin.consumer.processor.MicrostructureAccumulator;
import com.kotsin.consumer.processor.ImbalanceBarAccumulator;
import com.kotsin.consumer.monitoring.Timeframe;
import com.kotsin.consumer.processor.WindowRotationService;
import java.text.SimpleDateFormat;
import java.util.*;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages per-INSTRUMENT state aggregation
 * Single Responsibility: Aggregate data for ONE specific instrument (no family grouping)
 *
 * Key Design: Each scripCode gets its own state - no mixing of instruments
 */
@Data
@Slf4j
public class InstrumentStateManager {

    // Candle accumulators for each timeframe
    private final EnumMap<Timeframe, CandleAccumulator> candleAccumulators = new EnumMap<>(Timeframe.class);

    // Microstructure accumulators per timeframe (reset on window rotation)
    private final EnumMap<Timeframe, MicrostructureAccumulator> microAccumulators = new EnumMap<>(Timeframe.class);
    // Imbalance bar accumulators per timeframe
    private final EnumMap<Timeframe, ImbalanceBarAccumulator> imbAccumulators = new EnumMap<>(Timeframe.class);
    // Orderbook depth accumulators per timeframe (reset on window rotation for clean metrics)
    private final EnumMap<Timeframe, com.kotsin.consumer.processor.OrderbookDepthAccumulator> orderbookAccumulators = new EnumMap<>(Timeframe.class);
    // Volume profile accumulators per timeframe (reset on window rotation)
    private final EnumMap<Timeframe, com.kotsin.consumer.processor.VolumeProfileAccumulator> volumeProfileAccumulators = new EnumMap<>(Timeframe.class);
    // Completed window snapshots waiting to be emitted
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    private final transient EnumMap<Timeframe, Deque<CompletedWindow>> completedWindows = new EnumMap<>(Timeframe.class);
    
    // Global orderbook accumulator (NEVER reset - for iceberg/spoofing detection across windows)
    private com.kotsin.consumer.processor.OrderbookDepthAccumulator globalOrderbookAccumulator;

    // Basic instrument info
    private String scripCode;
    private String companyName;
    private String exchange;
    private String exchangeType;
    private String instrumentType;              // EQUITY, FUTURE, OPTION, INDEX
    private String underlyingEquityScripCode;

    // Derivative-specific fields
    private String expiry;
    private Double strikePrice;
    private String optionType;                  // CE or PE

    // Tick tracking
    private Long firstTickTime;
    private Long lastTickTime;
    private Long messageCount = 0L;

    // Timeframe definitions
    private static final Timeframe[] TIMEFRAMES = {
        Timeframe.ONE_MIN, Timeframe.TWO_MIN, Timeframe.THREE_MIN,
        Timeframe.FIVE_MIN, Timeframe.FIFTEEN_MIN, Timeframe.THIRTY_MIN
    };

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    public InstrumentStateManager() {
        initializeAccumulators();
    }

    private void initializeAccumulators() {
        // Initialize global orderbook accumulator (never reset)
        globalOrderbookAccumulator = new com.kotsin.consumer.processor.OrderbookDepthAccumulator();
        
        // Initialize per-timeframe accumulators
        for (Timeframe timeframe : TIMEFRAMES) {
            candleAccumulators.put(timeframe, new CandleAccumulator());
            microAccumulators.put(timeframe, new MicrostructureAccumulator());
            imbAccumulators.put(timeframe, new ImbalanceBarAccumulator());
            orderbookAccumulators.put(timeframe, new com.kotsin.consumer.processor.OrderbookDepthAccumulator());
            volumeProfileAccumulators.put(timeframe, new com.kotsin.consumer.processor.VolumeProfileAccumulator());
            completedWindows.put(timeframe, new ArrayDeque<>());
        }
    }

    public void addTick(TickData tick) {
        if (scripCode == null) {
            scripCode = tick.getScripCode();
            companyName = tick.getCompanyName();
            exchange = tick.getExchange();
            exchangeType = tick.getExchangeType();
            firstTickTime = tick.getTimestamp();

            // Extract derivative info from company name if available
            extractDerivativeInfo(tick);
        }

        lastTickTime = tick.getTimestamp();
        messageCount++;

        // Update all timeframes
        updateAllTimeframes(tick);
    }

    public void addOrderbook(com.kotsin.consumer.model.OrderBookSnapshot orderbook) {
        if (orderbook == null || !orderbook.isValid()) {
            log.debug("⚠️ OB INVALID in manager: scripCode={} ob={} valid={}", 
                scripCode, orderbook != null, orderbook != null && orderbook.isValid());
            return;
        }
        
        log.debug("✅ OB ADD to manager: scripCode={} token={} bids={} asks={}", 
            scripCode, orderbook.getToken(), 
            orderbook.getBids() != null ? orderbook.getBids().size() : 0,
            orderbook.getAsks() != null ? orderbook.getAsks().size() : 0);
        
        // Update global accumulator (NEVER reset - for iceberg/spoofing detection)
        globalOrderbookAccumulator.addOrderbook(orderbook);
        
        // Update per-timeframe accumulators (reset on window rotation for clean metrics)
        for (com.kotsin.consumer.processor.OrderbookDepthAccumulator acc : orderbookAccumulators.values()) {
            acc.addOrderbook(orderbook);
        }
    }

    /**
     * Extract derivative information (expiry, strike, option type) from tick
     */
    private void extractDerivativeInfo(TickData tick) {
        if (tick.getCompanyName() == null) {
            return;
        }

        String name = tick.getCompanyName();

        // Extract option type (CE or PE)
        if (name.contains(" CE ")) {
            optionType = "CE";
            // Extract strike price: "LT 28 OCT 2025 CE 3850.00"
            String[] parts = name.split(" CE ");
            if (parts.length > 1) {
                try {
                    strikePrice = Double.parseDouble(parts[1].trim());
                } catch (NumberFormatException e) {
                    log.debug("Could not parse strike price from: {}", name);
                }
            }
            // Extract expiry
            expiry = extractExpiryFromName(name);
        } else if (name.contains(" PE ")) {
            optionType = "PE";
            // Extract strike price
            String[] parts = name.split(" PE ");
            if (parts.length > 1) {
                try {
                    strikePrice = Double.parseDouble(parts[1].trim());
                } catch (NumberFormatException e) {
                    log.debug("Could not parse strike price from: {}", name);
                }
            }
            // Extract expiry
            expiry = extractExpiryFromName(name);
        } else if (name.matches(".*\\d{2}\\s+[A-Z]{3}\\s+\\d{4}.*")) {
            // Future: "GOLDM 05 NOV 2025"
            expiry = extractExpiryFromName(name);
        }
    }

    /**
     * Extract expiry date from company name
     * Format: "DD MMM YYYY" (e.g., "28 OCT 2025")
     */
    private String extractExpiryFromName(String companyName) {
        try {
            // Pattern: DD MMM YYYY
            String[] parts = companyName.split("\\s+");
            for (int i = 0; i < parts.length - 2; i++) {
                String day = parts[i];
                String month = parts[i + 1];
                String year = parts[i + 2];

                // Check if day is 1-2 digits, month is 3 letters, year is 4 digits
                if (day.matches("\\d{1,2}") && month.matches("[A-Z]{3}") && year.matches("\\d{4}")) {
                    // Convert to YYYY-MM-DD format
                    String monthNum = convertMonthToNumber(month);
                    if (monthNum != null) {
                        return String.format("%s-%s-%02d", year, monthNum, Integer.parseInt(day));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract expiry from: {}", companyName);
        }
        return null;
    }

    private String convertMonthToNumber(String month) {
        switch (month.toUpperCase()) {
            case "JAN": return "01";
            case "FEB": return "02";
            case "MAR": return "03";
            case "APR": return "04";
            case "MAY": return "05";
            case "JUN": return "06";
            case "JUL": return "07";
            case "AUG": return "08";
            case "SEP": return "09";
            case "OCT": return "10";
            case "NOV": return "11";
            case "DEC": return "12";
            default: return null;
        }
    }

    private void updateAllTimeframes(TickData tick) {
        long tickTime = tick.getTimestamp();
        boolean isNse = "N".equalsIgnoreCase(exchange);

        for (Timeframe timeframe : TIMEFRAMES) {
            CandleAccumulator currentAcc = candleAccumulators.get(timeframe);
            MicrostructureAccumulator microAcc = microAccumulators.get(timeframe);
            ImbalanceBarAccumulator imbAcc = imbAccumulators.get(timeframe);
            com.kotsin.consumer.processor.OrderbookDepthAccumulator obAcc = orderbookAccumulators.get(timeframe);
            com.kotsin.consumer.processor.VolumeProfileAccumulator vpAcc = volumeProfileAccumulators.get(timeframe);

            // Apply NSE 09:15 anchoring ONLY for 30m timeframe
            CandleAccumulator rotatedAcc = (isNse && timeframe == Timeframe.THIRTY_MIN)
                ? WindowRotationService.rotateCandleIfNeeded(currentAcc, tickTime, timeframe.getMinutes(), 15)
                : WindowRotationService.rotateCandleIfNeeded(currentAcc, tickTime, timeframe.getMinutes());

            if (rotatedAcc != currentAcc) {
                CompletedWindow completed = buildCompletedWindow(currentAcc, microAcc, imbAcc, obAcc, vpAcc);
                if (completed != null) {
                    completedWindows.computeIfAbsent(timeframe, tf -> new ArrayDeque<>()).addLast(completed);
                }

                microAcc = new MicrostructureAccumulator();
                imbAcc = new ImbalanceBarAccumulator();
                obAcc = new com.kotsin.consumer.processor.OrderbookDepthAccumulator();
                vpAcc = new com.kotsin.consumer.processor.VolumeProfileAccumulator();
                microAccumulators.put(timeframe, microAcc);
                imbAccumulators.put(timeframe, imbAcc);
                orderbookAccumulators.put(timeframe, obAcc);
                volumeProfileAccumulators.put(timeframe, vpAcc);
            }

            candleAccumulators.put(timeframe, rotatedAcc);

            rotatedAcc.addTick(tick);
            if (microAcc != null) {
                microAcc.addTick(tick);
            }
            if (imbAcc != null) {
                imbAcc.addTick(tick);
            }
            if (vpAcc != null && tick.getDeltaVolume() != null && tick.getDeltaVolume() > 0) {
                vpAcc.addTrade(tick.getLastRate(), tick.getDeltaVolume());
            }
        }
    }

    public boolean hasAnyCompleteWindow() {
        return candleAccumulators.values().stream()
            .anyMatch(CandleAccumulator::isComplete);
    }

    public Set<String> getCompleteWindows() {
        return new HashSet<>();
    }

    /**
     * Extract finalized candle for a specific timeframe
     */
    public InstrumentCandle extractFinalizedCandle(Timeframe timeframe) {
        Deque<CompletedWindow> queue = completedWindows.get(timeframe);
        CompletedWindow completed = (queue != null && !queue.isEmpty()) ? queue.pollFirst() : null;

        if (completed == null) {
            CandleAccumulator accumulator = candleAccumulators.get(timeframe);
            if (accumulator == null ||
                !accumulator.isComplete() ||
                accumulator.getWindowStart() == null ||
                accumulator.getOpen() == null) {
                return null;
            }

            MicrostructureAccumulator microAcc = microAccumulators.get(timeframe);
            ImbalanceBarAccumulator imbAcc = imbAccumulators.get(timeframe);
            com.kotsin.consumer.processor.OrderbookDepthAccumulator obAcc = orderbookAccumulators.get(timeframe);
            com.kotsin.consumer.processor.VolumeProfileAccumulator vpAcc = volumeProfileAccumulators.get(timeframe);

            completed = buildCompletedWindow(accumulator, microAcc, imbAcc, obAcc, vpAcc);
            if (completed == null) {
                return null;
            }

            candleAccumulators.put(timeframe, new CandleAccumulator());
            microAccumulators.put(timeframe, new MicrostructureAccumulator());
            imbAccumulators.put(timeframe, new ImbalanceBarAccumulator());
            orderbookAccumulators.put(timeframe, new com.kotsin.consumer.processor.OrderbookDepthAccumulator());
            volumeProfileAccumulators.put(timeframe, new com.kotsin.consumer.processor.VolumeProfileAccumulator());
        }

        CandleData candleData = completed.candleData;
        MicrostructureData microstructure = completed.microstructure;
        ImbalanceBarData imbalanceBars = completed.imbalanceBars;
        com.kotsin.consumer.model.OrderbookDepthData windowOrderbook = completed.orderbookDepth;
        com.kotsin.consumer.model.VolumeProfileData volumeProfile = completed.volumeProfile;

        // Merge per-window depth metrics with ongoing global detectors
        com.kotsin.consumer.model.OrderbookDepthData globalDetection = globalOrderbookAccumulator != null ?
            globalOrderbookAccumulator.toOrderbookDepthData() : null;
        com.kotsin.consumer.model.OrderbookDepthData orderbookDepth = mergeOrderbookData(windowOrderbook, globalDetection);

        return InstrumentCandle.builder()
            .scripCode(scripCode)
            .instrumentType(instrumentType)
            .underlyingEquityScripCode(underlyingEquityScripCode)
            .companyName(companyName)
            .exchange(exchange)
            .exchangeType(exchangeType)
            .expiry(expiry)
            .strikePrice(strikePrice)
            .optionType(optionType)
            .open(candleData.getOpen())
            .high(candleData.getHigh())
            .low(candleData.getLow())
            .close(candleData.getClose())
            .volume(candleData.getVolume())
            .buyVolume(candleData.getBuyVolume())
            .sellVolume(candleData.getSellVolume())
            .volumeDelta(candleData.getVolumeDelta())
            .volumeDeltaPercent(candleData.getVolumeDeltaPercent())
            .vwap(candleData.getVwap())
            .hlc3((candleData.getHigh() != null && candleData.getLow() != null && candleData.getClose() != null)
                ? (candleData.getHigh() + candleData.getLow() + candleData.getClose()) / 3.0
                : null)
            .tickCount(candleData.getTickCount())
            .windowStartMillis(candleData.getWindowStart())
            .windowEndMillis(candleData.getWindowEnd())
            .isComplete(candleData.getIsComplete())
            .humanReadableStartTime(formatTimestamp(candleData.getWindowStart()))
            .humanReadableEndTime(formatTimestamp(candleData.getWindowEnd()))
            .processingTimestamp(System.currentTimeMillis())
            .timeframe(timeframe.getLabel())
            .microstructure(microstructure != null && microstructure.isValid() ? microstructure : null)
            .imbalanceBars(imbalanceBars)
            .volumeProfile(volumeProfile)
            .orderbookDepth(orderbookDepth)
            .build();
    }

    private CompletedWindow buildCompletedWindow(
        CandleAccumulator candleAcc,
        MicrostructureAccumulator microAcc,
        ImbalanceBarAccumulator imbalanceAcc,
        com.kotsin.consumer.processor.OrderbookDepthAccumulator orderbookAcc,
        com.kotsin.consumer.processor.VolumeProfileAccumulator volumeProfileAcc
    ) {
        if (candleAcc == null || candleAcc.getWindowStart() == null || candleAcc.getOpen() == null) {
            return null;
        }

        candleAcc.markComplete();
        CandleData candleData = candleAcc.toCandleData(exchange, exchangeType);

        MicrostructureData microstructure = null;
        if (microAcc != null) {
            microAcc.markComplete();
            microstructure = microAcc.toMicrostructureData(candleAcc.getWindowStart(), candleAcc.getWindowEnd());
        }

        ImbalanceBarData imbalanceBars = imbalanceAcc != null ? imbalanceAcc.toImbalanceBarData() : null;
        com.kotsin.consumer.model.OrderbookDepthData windowOrderbook =
            orderbookAcc != null ? orderbookAcc.toOrderbookDepthData() : null;
        com.kotsin.consumer.model.VolumeProfileData volumeProfile =
            volumeProfileAcc != null ? volumeProfileAcc.calculate() : null;

        return new CompletedWindow(candleData, microstructure, imbalanceBars, windowOrderbook, volumeProfile);
    }

    private static final class CompletedWindow {
        private final CandleData candleData;
        private final MicrostructureData microstructure;
        private final ImbalanceBarData imbalanceBars;
        private final com.kotsin.consumer.model.OrderbookDepthData orderbookDepth;
        private final com.kotsin.consumer.model.VolumeProfileData volumeProfile;

        private CompletedWindow(
            CandleData candleData,
            MicrostructureData microstructure,
            ImbalanceBarData imbalanceBars,
            com.kotsin.consumer.model.OrderbookDepthData orderbookDepth,
            com.kotsin.consumer.model.VolumeProfileData volumeProfile
        ) {
            this.candleData = candleData;
            this.microstructure = microstructure;
            this.imbalanceBars = imbalanceBars;
            this.orderbookDepth = orderbookDepth;
            this.volumeProfile = volumeProfile;
        }
    }

    /**
     * Merge per-window orderbook metrics with global iceberg/spoofing detection
     * 
     * Strategy:
     * - Use window metrics for: spread, depth, imbalances, VWAPs, slopes (time-aligned)
     * - Use global metrics for: iceberg detection, spoofing detection (cross-window history)
     */
    private com.kotsin.consumer.model.OrderbookDepthData mergeOrderbookData(
        com.kotsin.consumer.model.OrderbookDepthData windowData,
        com.kotsin.consumer.model.OrderbookDepthData globalData
    ) {
        if (windowData == null && globalData == null) return null;
        if (windowData == null) return globalData;  // Only global data available
        if (globalData == null) return windowData;  // Only window data available
        
        // Merge: window metrics + global iceberg/spoofing
        return com.kotsin.consumer.model.OrderbookDepthData.builder()
            // Use window-aligned metrics
            .spread(windowData.getSpread())
            .totalBidDepth(windowData.getTotalBidDepth())
            .totalAskDepth(windowData.getTotalAskDepth())
            .weightedDepthImbalance(windowData.getWeightedDepthImbalance())
            .level1Imbalance(windowData.getLevel1Imbalance())
            .level2to5Imbalance(windowData.getLevel2to5Imbalance())
            .level6to10Imbalance(windowData.getLevel6to10Imbalance())
            .bidVWAP(windowData.getBidVWAP())
            .askVWAP(windowData.getAskVWAP())
            .bidSlope(windowData.getBidSlope())
            .askSlope(windowData.getAskSlope())
            .slopeRatio(windowData.getSlopeRatio())
            .bidProfile(windowData.getBidProfile())
            .askProfile(windowData.getAskProfile())
            .timestamp(windowData.getTimestamp())
            .depthLevels(windowData.getDepthLevels())
            // Use global cross-window iceberg/spoofing detection
            .icebergDetectedBid(globalData.getIcebergDetectedBid())
            .icebergDetectedAsk(globalData.getIcebergDetectedAsk())
            .icebergProbabilityBid(globalData.getIcebergProbabilityBid())
            .icebergProbabilityAsk(globalData.getIcebergProbabilityAsk())
            .spoofingCountLast1Min(globalData.getSpoofingCountLast1Min())
            .activeSpoofingBid(globalData.getActiveSpoofingBid())
            .activeSpoofingAsk(globalData.getActiveSpoofingAsk())
            .spoofingEvents(globalData.getSpoofingEvents())
            // Flags (isValid() is a computed method, not a field)
            .isComplete(windowData.getIsComplete() != null ? windowData.getIsComplete() : globalData.getIsComplete())
            .build();
    }

    private String formatTimestamp(Long timestamp) {
        if (timestamp == null) {
            return null;
        }
        return DATE_FORMAT.format(new Date(timestamp));
    }

    /**
     * Force completion of all windows for finalized candle emission
     */
    public void forceCompleteWindows(long kafkaWindowEnd) {
        int completedCount = 0;

        for (Map.Entry<Timeframe, CandleAccumulator> entry : candleAccumulators.entrySet()) {
            Timeframe timeframe = entry.getKey();
            CandleAccumulator accumulator = entry.getValue();

            if (accumulator.getWindowStart() != null &&
                accumulator.getWindowEnd() != null &&
                !accumulator.isComplete() &&
                kafkaWindowEnd >= accumulator.getWindowEnd()) {

                accumulator.markComplete();
                completedCount++;
                log.info("✅ Completed {} window [{}→{}] at Kafka window {} (scripCode={})",
                    timeframe.getLabel(),
                    accumulator.getWindowStart(),
                    accumulator.getWindowEnd(),
                    kafkaWindowEnd,
                    scripCode);
            }
        }

        if (completedCount > 0) {
            log.info("🎉 Marked {} windows as complete for scripCode={} at Kafka window end {}",
                completedCount, scripCode, kafkaWindowEnd);
        }
    }

    public EnumMap<Timeframe, CandleAccumulator> getCandleAccumulators() {
        return candleAccumulators;
    }
}
