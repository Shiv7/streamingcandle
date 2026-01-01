package com.kotsin.consumer.fudkii;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.infrastructure.redis.RedisCandleHistoryService;
import com.kotsin.consumer.masterarch.calculator.MasterArchFUDKIICalculator;
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
 * - Simultaneity: Both must occur on SAME 30m candle (weight=1.0)
 * 
 * Emits signal when FUDKII strength >= 0.55
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FUDKIIProcessor {

    private final MasterArchFUDKIICalculator fudkiiCalculator;
    private final RedisCandleHistoryService candleHistoryService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${fudkii.processor.enabled:true}")
    private boolean enabled;

    @Value("${fudkii.processor.min.strength:0.55}")
    private double minStrength;

    @Value("${fudkii.processor.history.candles:50}")
    private int historyCandles;

    @PostConstruct
    public void init() {
        log.info("📊 FUDKIIProcessor initialized. Enabled: {}, MinStrength: {}", enabled, minStrength);
        log.info("📊 Output topic: {}", KafkaTopics.KOTSIN_FUDKII);
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

            if (candles30m.size() < 30) {
                log.debug("Insufficient candles for FUDKII: {} has only {}", scripCode, candles30m.size());
                return;
            }

            // Calculate FUDKII
            MasterArchFUDKIICalculator.FUDKIIOutput fudkiiOutput = fudkiiCalculator.calculate(
                scripCode,
                companyName,
                candles30m,
                0.0  // No previous score tracking for standalone
            );

            // Emit only if:
            // 1. Valid calculation
            // 2. Not rejected
            // 3. Strength >= threshold (means BB + ST happened on same candle)
            if (fudkiiOutput.isValid() && !fudkiiOutput.isRejected() && 
                fudkiiOutput.getStrength() >= minStrength) {
                
                String outputJson = objectMapper.writeValueAsString(fudkiiOutput);
                kafkaTemplate.send(KafkaTopics.KOTSIN_FUDKII, scripCode, outputJson);
                
                log.info("📊 FUDKII Signal: {} {} strength={} direction={}",
                    scripCode, companyName, 
                    String.format("%.2f", fudkiiOutput.getStrength()), 
                    fudkiiOutput.getDirection());
            }

        } catch (Exception e) {
            log.error("Error processing FUDKII candle: {}", e.getMessage(), e);
        }
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
