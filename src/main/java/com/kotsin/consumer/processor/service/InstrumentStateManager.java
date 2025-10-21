package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.model.*;
import com.kotsin.consumer.processor.CandleAccumulator;
import com.kotsin.consumer.processor.MicrostructureAccumulator;
import com.kotsin.consumer.processor.ImbalanceBarAccumulator;
import com.kotsin.consumer.processor.Timeframe;
import com.kotsin.consumer.processor.WindowRotationService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.text.SimpleDateFormat;
import java.util.*;

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
        for (Timeframe timeframe : TIMEFRAMES) {
            candleAccumulators.put(timeframe, new CandleAccumulator());
            microAccumulators.put(timeframe, new MicrostructureAccumulator());
            imbAccumulators.put(timeframe, new ImbalanceBarAccumulator());
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

        for (Map.Entry<Timeframe, CandleAccumulator> entry : candleAccumulators.entrySet()) {
            Timeframe timeframe = entry.getKey();
            CandleAccumulator acc = entry.getValue();
            int minutes = timeframe.getMinutes();

            // Detect window rotation to reset per-timeframe microstructure accumulator
            Long prevWindowStart = acc.getWindowStart();
            acc = WindowRotationService.rotateCandleIfNeeded(acc, tickTime, minutes);
            candleAccumulators.put(timeframe, acc);
            if (prevWindowStart == null || !prevWindowStart.equals(acc.getWindowStart())) {
                // New window started → reset per-timeframe accumulators
                microAccumulators.put(timeframe, new MicrostructureAccumulator());
                imbAccumulators.put(timeframe, new ImbalanceBarAccumulator());
            }

            // Update accumulators
            acc.addTick(tick);
            MicrostructureAccumulator microAcc = microAccumulators.get(timeframe);
            if (microAcc != null) { microAcc.addTick(tick); }
            ImbalanceBarAccumulator imbAcc = imbAccumulators.get(timeframe);
            if (imbAcc != null) { imbAcc.addTick(tick); }
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
        CandleAccumulator accumulator = candleAccumulators.get(timeframe);
        if (accumulator == null || !accumulator.isComplete()) {
            return null;
        }

        CandleData candleData = accumulator.toCandleData(exchange, exchangeType);

        // Get microstructure data for this timeframe window
        MicrostructureAccumulator microAcc = microAccumulators.get(timeframe);
        MicrostructureData microstructure = microAcc != null ? 
            microAcc.toMicrostructureData(accumulator.getWindowStart(), accumulator.getWindowEnd()) : null;
        ImbalanceBarAccumulator imbAcc = imbAccumulators.get(timeframe);
        ImbalanceBarData imbalanceBars = imbAcc != null ? imbAcc.toImbalanceBarData() : null;

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
