package com.kotsin.consumer.fudkii;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.curated.service.BBSuperTrendDetector;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.fudkii.model.BBSuperTrendIgnitionSignal;
import com.kotsin.consumer.infrastructure.redis.RedisCandleHistoryService;
import com.kotsin.consumer.model.UnifiedCandle;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * FUDKIIProcessor - Standalone FUDKII Strategy
 * 
 * Simple strategy detecting BB + SuperTrend breakouts on 30m timeframe.
 * Both BB breakout AND SuperTrend flip must occur on SAME candle.
 * 
 * Input: family-candle-30m
 * Output: kotsin_FUDKII
 * 
 * Logic:
 * - BB Breakout: Close breaks above upper BB or below lower BB
 * - SuperTrend Flip: ST signal changes direction  
 * - STRICT SIMULTANEITY: Both must occur on SAME 30m candle (reject if not)
 * 
 * Emits signal when both conditions are met on the same candle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FUDKIIProcessor {

    private final BBSuperTrendDetector bbSuperTrendDetector;
    private final RedisCandleHistoryService candleHistoryService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${fudkii.processor.enabled:true}")
    private boolean enabled;

    @Value("${fudkii.processor.min.strength:0.5}")
    private double minStrength;

    @Value("${fudkii.processor.history.candles:50}")
    private int historyCandles;
    
    @Value("${supertrend.atr.period:10}")
    private int superTrendAtrPeriod;

    @PostConstruct
    public void init() {
        log.info("📊 FUDKIIProcessor initialized. Enabled: {}, MinStrength: {}", enabled, minStrength);
        log.info("📊 Output topic: {}", KafkaTopics.KOTSIN_FUDKII);
        log.info("📊 Strategy: Strict BB+ST simultaneity on 30m candles");
    }

    @KafkaListener(
        topics = KafkaTopics.FAMILY_CANDLE_30M,
        groupId = "fudkii-processor-v2",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void process30mCandle(String message) {
        if (!enabled) return;

        try {
            FamilyCandle familyCandle = objectMapper.readValue(message, FamilyCandle.class);
            
            if (familyCandle == null || familyCandle.getEquity() == null) {
                return;
            }

            InstrumentCandle instrumentCandle = familyCandle.getEquity();
            String scripCode = instrumentCandle.getScripCode();
            String companyName = instrumentCandle.getCompanyName();
            
            // Convert InstrumentCandle to UnifiedCandle for storage
            UnifiedCandle candle = toUnifiedCandle(instrumentCandle);

            // Store candle in Redis
            candleHistoryService.storeCandle(candle);

            // Get historical candles
            List<UnifiedCandle> candles30m = candleHistoryService.getCandles(
                scripCode, "30m", historyCandles,
                instrumentCandle.getExchange(), instrumentCandle.getExchangeType()
            );

            // Need at least 30 candles for reliable BB/ST calculation
            if (candles30m.size() < 30) {
                log.debug("Insufficient candles for FUDKII: {} has only {}", scripCode, candles30m.size());
                return;
            }

            // Get current and previous candles
            UnifiedCandle current = candles30m.get(candles30m.size() - 1);
            List<UnifiedCandle> prevCandles = candles30m.subList(0, candles30m.size() - 1);
            
            if (prevCandles.isEmpty()) {
                log.debug("Insufficient history for FUDKII: {} needs at least 2 candles", scripCode);
                return;
            }

            // Calculate Bollinger Bands
            double[] bb = bbSuperTrendDetector.calculateBollingerBands(candles30m);
            double bbUpper = bb[0];
            double bbMiddle = bb[1];
            double bbLower = bb[2];
            
            double close = current.getClose();
            double bbWidth = bbMiddle > 0 ? (bbUpper - bbLower) / bbMiddle : 0;
            double bbPercentB = (bbUpper - bbLower) > 0 ? (close - bbLower) / (bbUpper - bbLower) : 0.5;
            
            boolean bbBreakoutUp = close > bbUpper;
            boolean bbBreakoutDown = close < bbLower;
            boolean bbBreakoutOnCurrentCandle = bbBreakoutUp || bbBreakoutDown;

            // Calculate ATR for SuperTrend
            double atr = bbSuperTrendDetector.calculateATR(candles30m, superTrendAtrPeriod);
            
            if (atr <= 0) {
                log.debug("Invalid ATR for FUDKII: {} ATR={}", scripCode, atr);
                return;
            }

            // Calculate SuperTrend for current and previous candles
            double[] stCurrent = bbSuperTrendDetector.calculateSuperTrend(candles30m, atr);
            double[] stPrev = bbSuperTrendDetector.calculateSuperTrend(prevCandles, atr);
            
            double superTrend = stCurrent[0];
            boolean superTrendBullish = stCurrent[1] > 0;
            boolean prevSuperTrendBullish = stPrev[1] > 0;
            boolean superTrendFlipped = superTrendBullish != prevSuperTrendBullish;
            boolean stFlipOnCurrentCandle = superTrendFlipped;

            // STRICT SIMULTANEITY CHECK: Both must be on current candle
            boolean simultaneityValid = bbBreakoutOnCurrentCandle && stFlipOnCurrentCandle;
            
            if (!simultaneityValid) {
                log.debug("FUDKII rejected - not simultaneous: {} BB={} ST={}", 
                    scripCode, bbBreakoutOnCurrentCandle, stFlipOnCurrentCandle);
                return;
            }

            // Determine direction
            String direction;
            if (bbBreakoutUp && superTrendBullish) {
                direction = "BULLISH";
            } else if (bbBreakoutDown && !superTrendBullish) {
                direction = "BEARISH";
            } else {
                // BB and ST don't agree - reject
                log.debug("FUDKII rejected - direction mismatch: {} BBup={} STbull={}", 
                    scripCode, bbBreakoutUp, superTrendBullish);
                return;
            }

            // Calculate signal strength
            double signalStrength = calculateSignalStrength(
                bbUpper, bbLower, superTrend, close, atr, 
                bbBreakoutUp, bbBreakoutDown, superTrendBullish, 
                candles30m
            );

            // Check minimum strength threshold
            if (signalStrength < minStrength) {
                log.debug("FUDKII rejected - strength too low: {} strength={}", scripCode, signalStrength);
                return;
            }

            // Calculate volume Z-score
            double volumeZScore = bbSuperTrendDetector.calculateVolumeZScore(candles30m);

            // Build enhanced signal
            BBSuperTrendIgnitionSignal signal = BBSuperTrendIgnitionSignal.builder()
                    .scripCode(scripCode)
                    .companyName(companyName)
                    .timestamp(current.getWindowEndMillis())
                    .timeframe("30m")
                    // OHLCV
                    .open(current.getOpen())
                    .high(current.getHigh())
                    .low(current.getLow())
                    .close(current.getClose())
                    .volume(current.getVolume())
                    // BB metrics
                    .bbUpper(bbUpper)
                    .bbMiddle(bbMiddle)
                    .bbLower(bbLower)
                    .bbWidth(bbWidth)
                    .bbPercentB(bbPercentB)
                    .bbBreakoutUp(bbBreakoutUp)
                    .bbBreakoutDown(bbBreakoutDown)
                    .bbBreakoutOnCurrentCandle(bbBreakoutOnCurrentCandle)
                    // ST metrics
                    .superTrend(superTrend)
                    .atr(atr)
                    .superTrendBullish(superTrendBullish)
                    .superTrendFlipped(superTrendFlipped)
                    .stFlipOnCurrentCandle(stFlipOnCurrentCandle)
                    // Volume
                    .volumeZScore(volumeZScore)
                    // Signal
                    .direction(direction)
                    .signalStrength(signalStrength)
                    .simultaneityValid(simultaneityValid)
                    .build();

            // Emit to Kafka
            String outputJson = objectMapper.writeValueAsString(signal);
            kafkaTemplate.send(KafkaTopics.KOTSIN_FUDKII, scripCode, outputJson);
            
            log.info("📊 FUDKII Signal: {} {} | dir={} | strength={} | BB%B={} | STflip={} | volZ={}",
                scripCode, companyName, 
                direction,
                String.format("%.2f", signalStrength),
                String.format("%.2f", bbPercentB),
                superTrendFlipped,
                String.format("%.1f", volumeZScore));

        } catch (Exception e) {
            log.error("Error processing FUDKII candle: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Calculate signal strength based on BB distance, ST distance, and volume
     * 
     * @return Signal strength (0-1)
     */
    private double calculateSignalStrength(
            double bbUpper, double bbLower, double superTrend, double close, double atr,
            boolean bbBreakoutUp, boolean bbBreakoutDown, boolean superTrendBullish,
            List<UnifiedCandle> candles) {
        
        double strength = 0.0;
        
        // BB breakout strength: distance from band normalized by ATR
        if (bbBreakoutUp) {
            double bbDistance = close - bbUpper;
            strength += Math.min(1.0, bbDistance / atr) * 0.4;
        } else if (bbBreakoutDown) {
            double bbDistance = bbLower - close;
            strength += Math.min(1.0, bbDistance / atr) * 0.4;
        }
        
        // SuperTrend flip strength: distance from ST line normalized by ATR
        double stDistance = Math.abs(close - superTrend);
        strength += Math.min(1.0, stDistance / atr) * 0.3;
        
        // Volume bonus
        double volumeZScore = bbSuperTrendDetector.calculateVolumeZScore(candles);
        if (volumeZScore > 1.5) {
            strength += 0.2;
        } else if (volumeZScore > 1.0) {
            strength += 0.1;
        }
        
        // Cap at 1.0
        return Math.min(1.0, strength);
    }
    
    /**
     * Convert InstrumentCandle to UnifiedCandle
     */
    private UnifiedCandle toUnifiedCandle(InstrumentCandle ic) {
        UnifiedCandle uc = new UnifiedCandle();
        uc.setScripCode(ic.getScripCode());
        uc.setCompanyName(ic.getCompanyName());
        uc.setTimeframe(ic.getTimeframe());
        uc.setWindowStartMillis(ic.getWindowStartMillis());
        uc.setOpen(ic.getOpen());
        uc.setHigh(ic.getHigh());
        uc.setLow(ic.getLow());
        uc.setClose(ic.getClose());
        uc.setVolume(ic.getVolume());
        uc.setExchange(ic.getExchange());
        uc.setExchangeType(ic.getExchangeType());
        return uc;
    }
}
