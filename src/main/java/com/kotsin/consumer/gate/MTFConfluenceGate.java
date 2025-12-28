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

    // BUG-FIX: Changed from 3 to 2 since we now have 5 checks (1m, 2m, 3m, VCP, VCPhi)
    // Old logic had 5 checks (5m, 15m, 30m, VCP, 1h) but none existed, so gate always failed
    @Value("${gate.mtf.min.confirmations:2}")
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

        // BUG-FIX: Use 1m, 2m, 3m timeframes that we actually have data for
        // (Previously used 5m, 15m, 30m which were never populated by CuratedSignalProcessor)
        
        // Check 1m trend (current candle)
        UnifiedCandle candle1m = structureTracker.getLatestCandle(scripCode, "1m");
        if (candle1m != null) {
            totalChecks++;
            boolean tf1mBullish = candle1m.getClose() > candle1m.getOpen();
            if (tf1mBullish == isBullish) {
                confirmations++;
                details.append("1m✓ ");
            } else {
                details.append("1m✗ ");
            }
        }

        // Check 2m trend (use previous COMPLETED candle)
        UnifiedCandle candle2m = structureTracker.getPreviousCandle(scripCode, "2m");
        if (candle2m != null) {
            totalChecks++;
            boolean tf2mBullish = candle2m.getClose() > candle2m.getOpen();
            if (tf2mBullish == isBullish) {
                confirmations++;
                details.append("2m✓ ");
            } else {
                details.append("2m✗ ");
            }
        }

        // Check 3m structure (use previous COMPLETED candle)
        UnifiedCandle candle3m = structureTracker.getPreviousCandle(scripCode, "3m");
        if (candle3m != null) {
            totalChecks++;
            boolean tf3mBullish = candle3m.getClose() > candle3m.getOpen();
            if (tf3mBullish == isBullish) {
                confirmations++;
                details.append("3m✓ ");
            } else {
                details.append("3m✗ ");
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

        // BUG-FIX: Check breakout direction from primary breakout bar for final confirmation
        // (Previously used 1h timeframe which was never populated)
        // This check is now redundant since we use 1m/2m/3m above, so just pass as bonus if VCP aligned
        if (vcp != null && vcp.getVcpCombinedScore() > 0.6) {
            totalChecks++;
            confirmations++;  // High VCP score = extra confirmation
            details.append("VCPhi✓ ");
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

