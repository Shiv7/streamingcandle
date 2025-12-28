package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.curated.model.BreakoutBar;
import com.kotsin.consumer.curated.model.ConsolidationPattern;
import com.kotsin.consumer.curated.model.MultiTFBreakout;
import com.kotsin.consumer.model.UnifiedCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * BreakoutDetector - Detects breakouts from consolidation patterns
 *
 * A breakout is confirmed when:
 * 1. Consolidation pattern exists (Lower Highs + Higher Lows)
 * 2. Price breaks above recent high
 * 3. Volume is abnormal (> 2x average)
 * 4. Kyle's Lambda shows liquidity shock
 * 5. OFI shows buying pressure
 */
@Service
public class BreakoutDetector {

    private static final Logger log = LoggerFactory.getLogger(BreakoutDetector.class);

    @Autowired
    private StructureTracker structureTracker;

    @Autowired
    private VolumeAnomalyDetector volumeDetector;

    /**
     * Detect breakout on a single timeframe
     */
    public BreakoutBar detectBreakout(String scripCode, String timeframe, UnifiedCandle currentCandle) {

        // 1. Check if consolidation pattern exists
        ConsolidationPattern consolidation = structureTracker.detectConsolidation(scripCode, timeframe);
        if (consolidation == null) {
            // Only log every 100th check to avoid spam
            if (System.currentTimeMillis() % 1000 < 10) {
                log.info("BREAKOUT_CHECK {} {} | FAIL: No consolidation pattern", scripCode, timeframe);
            }
            return null;  // No consolidation, no breakout possible
        }

        log.info("BREAKOUT_CHECK {} {} | ✓ Consolidation found | recentHigh={} close={}",
            scripCode, timeframe, 
            String.format("%.2f", consolidation.getRecentHigh()),
            String.format("%.2f", currentCandle.getClose()));

        // 2. Check if price breaks above recent high
        if (currentCandle.getClose() <= consolidation.getRecentHigh()) {
            log.info("BREAKOUT_CHECK {} {} | FAIL: close={} <= recentHigh={}", 
                scripCode, timeframe, 
                String.format("%.2f", currentCandle.getClose()), 
                String.format("%.2f", consolidation.getRecentHigh()));
            return null;  // No price breakout
        }

        log.info("BREAKOUT_CHECK {} {} | ✓ Price breakout confirmed", scripCode, timeframe);

        // 3. Get history for volume analysis
        List<UnifiedCandle> history = structureTracker.getHistory(scripCode, timeframe, 20);
        if (history.isEmpty()) {
            log.info("BREAKOUT_CHECK {} {} | FAIL: No history for volume analysis", scripCode, timeframe);
            return null;
        }

        // 4. Check volume anomaly
        if (!volumeDetector.isAbnormalVolume(currentCandle, history)) {
            log.info("BREAKOUT_CHECK {} {} | FAIL: Volume not abnormal (vol={}, avgVol={})",
                scripCode, timeframe, 
                currentCandle.getVolume(),
                String.format("%.0f", history.stream().mapToLong(c -> c.getVolume()).average().orElse(0)));
            return null;
        }

        log.info("BREAKOUT_CHECK {} {} | ✓ Abnormal volume confirmed", scripCode, timeframe);

        // 5. Check Kyle's Lambda spike (optional - allows missing orderbook data)
        boolean kylePass = volumeDetector.isKyleLambdaSpike(currentCandle, history);
        log.info("BREAKOUT_CHECK {} {} | Kyle Lambda check: {} (kyle={})",
            scripCode, timeframe, kylePass ? "✓" : "bypassed",
            String.format("%.4f", currentCandle.getKyleLambda()));

        // 6. Check OFI (buying pressure) - fallback to volume delta if no orderbook
        boolean ofiPass = volumeDetector.hasOFIBuyingPressure(currentCandle);
        log.info("BREAKOUT_CHECK {} {} | OFI/Delta check: {} (ofi={}, delta={})",
            scripCode, timeframe, ofiPass ? "✓" : "FAIL",
            String.format("%.2f", currentCandle.getOfi()),
            String.format("%.2f", currentCandle.getVolumeDelta()));
        
        if (!ofiPass) {
            return null;
        }

        // 7. Calculate metrics
        double volumeZScore = volumeDetector.calculateVolumeZScore(currentCandle, history);
        double avgVolume = volumeDetector.getAverageVolume(history);

        // ALL CONDITIONS MET - This is a breakout!
        BreakoutBar breakout = BreakoutBar.builder()
                .scripCode(scripCode)
                .timeframe(timeframe)
                .timestamp(currentCandle.getWindowEndMillis())
                .breakoutPrice(currentCandle.getClose())
                .breakoutHigh(currentCandle.getHigh())
                .breakoutLow(currentCandle.getLow())
                .breakoutOpen(currentCandle.getOpen())
                .volume(currentCandle.getVolume())
                .volumeZScore(volumeZScore)
                .avgVolume20(avgVolume)
                .kyleLambda(currentCandle.getKyleLambda())
                .ofi(currentCandle.getOfi())
                .vpin(currentCandle.getVpin())
                .volumeDelta(currentCandle.getVolumeDelta())
                .pivotLevel(consolidation.getRecentHigh())  // Old high = new support
                .compressionRatio(consolidation.getCompressionRatio())
                .build();

        log.info("🚀 BREAKOUT DETECTED: {} {} @ {} | Vol Z={} | Kyle={} | OFI={}",
                scripCode, timeframe, breakout.getBreakoutPrice(),
                String.format("%.2f", volumeZScore),
                String.format("%.2f", currentCandle.getKyleLambda()),
                String.format("%.2f", currentCandle.getOfi()));

        return breakout;
    }

    /**
     * Detect multi-timeframe breakout (1m, 2m, 3m)
     * Requires 2 out of 3 timeframes to confirm
     */
    public MultiTFBreakout detectMultiTFBreakout(String scripCode) {

        // Get latest candles from all timeframes
        UnifiedCandle candle1m = structureTracker.getLatestCandle(scripCode, "1m");
        UnifiedCandle candle2m = structureTracker.getLatestCandle(scripCode, "2m");
        UnifiedCandle candle3m = structureTracker.getLatestCandle(scripCode, "3m");

        // Need at least one candle
        if (candle1m == null && candle2m == null && candle3m == null) {
            log.debug("detectMultiTFBreakout: {} - No candles found in any timeframe", scripCode);
            return null;
        }
        
        log.debug("detectMultiTFBreakout: {} - Candles: 1m={}, 2m={}, 3m={}", 
            scripCode, 
            candle1m != null ? candle1m.getClose() : "null",
            candle2m != null ? candle2m.getClose() : "null",
            candle3m != null ? candle3m.getClose() : "null");

        // Detect breakout on each timeframe
        BreakoutBar breakout1m = candle1m != null ? detectBreakout(scripCode, "1m", candle1m) : null;
        BreakoutBar breakout2m = candle2m != null ? detectBreakout(scripCode, "2m", candle2m) : null;
        BreakoutBar breakout3m = candle3m != null ? detectBreakout(scripCode, "3m", candle3m) : null;

        // Count confirmations
        int confirmations = 0;
        if (breakout1m != null) confirmations++;
        if (breakout2m != null) confirmations++;
        if (breakout3m != null) confirmations++;

        // Need at least 2 out of 3
        if (confirmations < 2) {
            return null;
        }

        // Use 3m as primary (most stable), fallback to 2m, then 1m
        BreakoutBar primary = breakout3m != null ? breakout3m :
                              (breakout2m != null ? breakout2m : breakout1m);

        // Get the consolidation pattern
        ConsolidationPattern pattern = structureTracker.detectConsolidation(scripCode, primary.getTimeframe());

        MultiTFBreakout mtfBreakout = MultiTFBreakout.builder()
                .scripCode(scripCode)
                .timestamp(System.currentTimeMillis())
                .primaryBreakout(primary)
                .breakout1m(breakout1m)
                .breakout2m(breakout2m)
                .breakout3m(breakout3m)
                .confirmations(confirmations)
                .confluenceScore(confirmations / 3.0)
                .pattern(pattern)
                .build();

        log.info("✅ MULTI-TF BREAKOUT: {} | Confirmations={}/3 | Confluence={}",
                scripCode, confirmations, String.format("%.2f", mtfBreakout.getConfluenceScore()));

        return mtfBreakout;
    }
}
