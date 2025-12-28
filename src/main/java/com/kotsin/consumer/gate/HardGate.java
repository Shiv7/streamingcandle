package com.kotsin.consumer.gate;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.gate.model.GateResult;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.SecurityRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * HardGate - Layer 1 of the Gate Chain
 * 
 * Must-pass conditions that filter out signals in unfavorable conditions:
 * 1. Valid equity data
 * 2. Index regime is tradeable
 * 3. Not during OPENING/CLOSING sessions
 * 4. Security aligned with index
 * 5. OI data not stale (if available)
 * 
 * These are non-negotiable - if any fails, signal is rejected.
 */
@Service
public class HardGate {

    private static final Logger log = LoggerFactory.getLogger(HardGate.class);

    @Value("${gate.hard.enabled:true}")
    private boolean enabled;

    @Value("${gate.hard.max.oi.age.minutes:15}")
    private int maxOiAgeMinutes;

    /**
     * Evaluate hard gates
     * 
     * @param family      FamilyCandle with equity data
     * @param index       Index regime (NIFTY50, BANKNIFTY, etc.)
     * @param security    Security regime for this scrip
     * @return GateResult with pass/fail status
     */
    public GateResult evaluate(FamilyCandle family, IndexRegime index, SecurityRegime security) {
        if (!enabled) {
            return GateResult.pass("HARD_GATE_DISABLED");
        }

        String scripCode = family != null ? family.getSymbol() : "unknown";

        // Gate 1.1: Must have equity data
        if (family == null || family.getEquity() == null) {
            log.debug("HARD_GATE | {} | FAILED | reason=NO_EQUITY_DATA", scripCode);
            return GateResult.fail("NO_EQUITY_DATA");
        }

        if (!family.getEquity().isValid()) {
            log.debug("HARD_GATE | {} | FAILED | reason=INVALID_EQUITY_DATA", scripCode);
            return GateResult.fail("INVALID_EQUITY_DATA", family.getQualityReason());
        }

        // Gate 1.2: Index regime must be tradeable
        if (index == null) {
            log.debug("HARD_GATE | {} | FAILED | reason=INDEX_REGIME_NULL", scripCode);
            return GateResult.fail("INDEX_REGIME_NULL");
        }

        if (!index.isTradeable()) {
            String detail = String.format("label=%s, strength=%.2f, coherence=%.2f",
                    index.getLabel(), index.getRegimeStrength(), index.getRegimeCoherence());
            log.debug("HARD_GATE | {} | FAILED | reason=INDEX_NOT_TRADEABLE | {}", scripCode, detail);
            return GateResult.fail("INDEX_NOT_TRADEABLE", detail);
        }

        // Gate 1.3: Not during OPENING (09:15-09:30) or CLOSING (15:00-15:30)
        if (index.getSessionPhase() == IndexRegime.SessionPhase.OPENING) {
            log.debug("HARD_GATE | {} | FAILED | reason=OPENING_SESSION", scripCode);
            return GateResult.fail("AVOID_SESSION_PHASE", "OPENING (09:15-09:30)");
        }

        if (index.getSessionPhase() == IndexRegime.SessionPhase.CLOSING) {
            log.debug("HARD_GATE | {} | FAILED | reason=CLOSING_SESSION", scripCode);
            return GateResult.fail("AVOID_SESSION_PHASE", "CLOSING (15:00-15:30)");
        }

        // Gate 1.4: Security must be aligned with index
        if (security == null) {
            log.debug("HARD_GATE | {} | FAILED | reason=SECURITY_REGIME_NULL", scripCode);
            return GateResult.fail("SECURITY_REGIME_NULL");
        }

        if (!security.isAlignedWithIndex()) {
            String detail = String.format("secLabel=%s, idxLabel=%s, divergence=%.2f",
                    security.getLabel(), index.getLabel(), security.getDivergenceScore());
            log.debug("HARD_GATE | {} | FAILED | reason=SECURITY_NOT_ALIGNED | {}", scripCode, detail);
            return GateResult.fail("SECURITY_NOT_ALIGNED", detail);
        }

        // Gate 1.5: OI data not too stale (if available)
        if (family.getOiSignal() != null && !family.getOiSignal().equals("NEUTRAL")) {
            long oiAge = System.currentTimeMillis() - family.getTimestamp();
            long maxAgeMs = maxOiAgeMinutes * 60 * 1000L;
            if (oiAge > maxAgeMs) {
                String detail = String.format("age=%d min, max=%d min", oiAge / 60000, maxOiAgeMinutes);
                log.debug("HARD_GATE | {} | FAILED | reason=OI_DATA_STALE | {}", scripCode, detail);
                return GateResult.fail("OI_DATA_STALE", detail);
            }
        }

        // All gates passed
        log.debug("HARD_GATE | {} | PASSED | index={} | security={}", 
                scripCode, index.getLabel(), security.getLabel());
        
        GateResult result = GateResult.pass("ALL_HARD_GATES_OK");
        result.setGateName("HARD_GATE");
        return result;
    }
}

