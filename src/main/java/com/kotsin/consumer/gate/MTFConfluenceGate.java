package com.kotsin.consumer.gate;

import com.kotsin.consumer.curated.service.StructureTracker;
import com.kotsin.consumer.gate.model.GateResult;
import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.model.UnifiedCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * MTFConfluenceGate - Layer 2 of the Gate Chain
 * 
 * Multi-Timeframe Confluence Check:
 * Ensures multiple timeframes agree with the signal direction.
 * 
 * Checks:
 * 1. 5m candle direction
 * 2. 15m candle direction (previous COMPLETED candle)
 * 3. 30m candle direction (previous COMPLETED candle)
 * 4. VCP runway clear in direction
 * 5. 1H bias (bonus)
 * 
 * Requires 3/5 confirmations to pass.
 */
@Service
public class MTFConfluenceGate {

    private static final Logger log = LoggerFactory.getLogger(MTFConfluenceGate.class);

    @Autowired
    private StructureTracker structureTracker;

    @Value("${gate.mtf.enabled:true}")
    private boolean enabled;

    @Value("${gate.mtf.min.confirmations:3}")
    private int minConfirmations;

    @Value("${gate.mtf.vcp.runway.threshold:0.5}")
    private double vcpRunwayThreshold;

    /**
     * Evaluate MTF confluence
     * 
     * @param scripCode  The scrip to check
     * @param direction  The signal direction ("BULLISH" or "BEARISH")
     * @param vcp        VCP output with runway and structural bias
     * @return GateResult with pass/fail status
     */
    public GateResult evaluate(String scripCode, String direction, MTVCPOutput vcp) {
        if (!enabled) {
            return GateResult.pass("MTF_GATE_DISABLED");
        }

        boolean isBullish = "BULLISH".equals(direction) || "BUY".equals(direction);
        int confirmations = 0;
        int totalChecks = 0;
        StringBuilder details = new StringBuilder();

        // Check 5m trend (current candle)
        UnifiedCandle candle5m = structureTracker.getLatestCandle(scripCode, "5m");
        if (candle5m != null) {
            totalChecks++;
            boolean tf5mBullish = candle5m.getClose() > candle5m.getOpen();
            if (tf5mBullish == isBullish) {
                confirmations++;
                details.append("5m✓ ");
            } else {
                details.append("5m✗ ");
            }
        }

        // Check 15m trend (use previous COMPLETED candle - current may be forming)
        UnifiedCandle candle15m = structureTracker.getPreviousCandle(scripCode, "15m");
        if (candle15m != null) {
            totalChecks++;
            boolean tf15mBullish = candle15m.getClose() > candle15m.getOpen();
            if (tf15mBullish == isBullish) {
                confirmations++;
                details.append("15m✓ ");
            } else {
                details.append("15m✗ ");
            }
        }

        // Check 30m structure (use previous COMPLETED candle)
        UnifiedCandle candle30m = structureTracker.getPreviousCandle(scripCode, "30m");
        if (candle30m != null) {
            totalChecks++;
            boolean tf30mBullish = candle30m.getClose() > candle30m.getOpen();
            if (tf30mBullish == isBullish) {
                confirmations++;
                details.append("30m✓ ");
            } else {
                details.append("30m✗ ");
            }
        }

        // Check VCP runway (path clear in direction)
        if (vcp != null) {
            totalChecks++;
            double runway = vcp.getRunwayScore();
            double bias = vcp.getStructuralBias();
            
            boolean vcpConfirms = false;
            if (isBullish && runway > vcpRunwayThreshold && bias > 0) {
                vcpConfirms = true;
            } else if (!isBullish && runway > vcpRunwayThreshold && bias < 0) {
                vcpConfirms = true;
            }
            
            if (vcpConfirms) {
                confirmations++;
                details.append("VCP✓ ");
            } else {
                details.append(String.format("VCP✗(rwy=%.2f,bias=%.2f) ", runway, bias));
            }
        }

        // Check 1H bias (optional, bonus confirmation)
        UnifiedCandle candle1h = structureTracker.getPreviousCandle(scripCode, "1h");
        if (candle1h != null) {
            totalChecks++;
            boolean tf1hBullish = candle1h.getClose() > candle1h.getOpen();
            if (tf1hBullish == isBullish) {
                confirmations++;
                details.append("1h✓ ");
            } else {
                details.append("1h✗ ");
            }
        }

        // Evaluate result
        if (confirmations < minConfirmations) {
            String reason = String.format("%d/%d (min=%d)", confirmations, totalChecks, minConfirmations);
            log.debug("MTF_GATE | {} | FAILED | confluence={} | {}", 
                    scripCode, reason, details.toString().trim());
            return GateResult.fail("MTF_CONFLUENCE_WEAK", reason + " | " + details.toString().trim());
        }

        // Passed - calculate confidence multiplier based on confluence strength
        double confidenceMultiplier = 1.0;
        if (confirmations >= 5) {
            confidenceMultiplier = 1.15;  // Full confluence = boost
        } else if (confirmations == 4) {
            confidenceMultiplier = 1.05;
        }

        String passReason = String.format("%d/%d MTF confluence", confirmations, totalChecks);
        log.debug("MTF_GATE | {} | PASSED | {} | multiplier={}", 
                scripCode, passReason, String.format("%.2f", confidenceMultiplier));
        
        GateResult result = GateResult.pass(passReason, confidenceMultiplier);
        result.setGateName("MTF_GATE");
        result.setDetail(details.toString().trim());
        return result;
    }
}

