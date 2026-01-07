package com.kotsin.consumer.gate;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.gate.model.GateResult;
import com.kotsin.consumer.model.IPUOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * SignalQualityGate - Layer 3 of the Gate Chain
 * 
 * Checks for institutional footprint and quality indicators:
 * 1. IPU institutional proxy threshold
 * 2. IPU direction matches signal direction
 * 3. Not exhausted (momentum still present)
 * 4. OI signal aligned with direction
 * 5. Volume confirmation
 * 
 * Ensures we only take signals with strong institutional backing.
 */
@Service
public class SignalQualityGate {

    private static final Logger log = LoggerFactory.getLogger(SignalQualityGate.class);

    @Value("${gate.quality.enabled:true}")
    private boolean enabled;

    @Value("${gate.quality.min.inst.proxy:0.4}")
    private double minInstProxy;

    @Value("${gate.quality.max.exhaustion:0.7}")
    private double maxExhaustion;

    @Value("${gate.quality.require.volume.confirmation:true}")
    private boolean requireVolumeConfirmation;

    /**
     * Evaluate signal quality
     * 
     * @param family    FamilyCandle with OI data
     * @param ipu       IPU output with institutional proxy
     * @param direction Signal direction ("BULLISH" or "BEARISH")
     * @return GateResult with pass/fail status
     */
    public GateResult evaluate(FamilyCandle family, IPUOutput ipu, String direction) {
        if (!enabled) {
            return GateResult.pass("QUALITY_GATE_DISABLED");
        }

        String scripCode = family != null ? family.getSymbol() : "unknown";
        boolean isBullish = "BULLISH".equals(direction) || "BUY".equals(direction);
        StringBuilder details = new StringBuilder();
        int qualityScore = 0;

        // Quality 3.1: IPU institutional proxy
        if (ipu != null) {
            double instProxy = ipu.getInstProxy();
            if (instProxy < minInstProxy) {
                String detail = String.format("instProxy=%.2f < min=%.2f", instProxy, minInstProxy);
                log.debug("QUALITY_GATE | {} | FAILED | reason=LOW_INST_PROXY | {}", scripCode, detail);
                return GateResult.fail("LOW_INST_PROXY", detail);
            }
            qualityScore++;
            details.append(String.format("InstProxy=%.2f✓ ", instProxy));
        }

        // Quality 3.2: IPU direction must match signal direction
        if (ipu != null && ipu.getDirection() != null) {
            boolean ipuBullish = ipu.getDirection() == IPUOutput.Direction.BULLISH;
            if (ipuBullish != isBullish) {
                String detail = String.format("signal=%s but IPU=%s", direction, ipu.getDirection());
                log.debug("QUALITY_GATE | {} | FAILED | reason=IPU_DIRECTION_MISMATCH | {}", scripCode, detail);
                return GateResult.fail("IPU_DIRECTION_MISMATCH", detail);
            }
            qualityScore++;
            details.append("IPU_dir✓ ");
        }

        // Quality 3.3: Not exhausted
        if (ipu != null) {
            double exhaustion = ipu.getExhaustionScore();
            if (exhaustion > maxExhaustion) {
                String detail = String.format("exhaustion=%.2f > max=%.2f", exhaustion, maxExhaustion);
                log.debug("QUALITY_GATE | {} | FAILED | reason=MOMENTUM_EXHAUSTED | {}", scripCode, detail);
                return GateResult.fail("MOMENTUM_EXHAUSTED", detail);
            }
            qualityScore++;
            details.append(String.format("Exhaust=%.2f✓ ", exhaustion));
        }

        // Quality 3.4: OI signal aligned (if available)
        if (family != null && family.getOiSignal() != null && !"NEUTRAL".equals(family.getOiSignal())) {
            boolean oiBullish = family.isBullishOI();
            boolean oiBearish = family.isBearishOI();
            
            if ((oiBullish && !isBullish) || (oiBearish && isBullish)) {
                String detail = String.format("signal=%s but OI=%s", direction, family.getOiSignal());
                log.debug("QUALITY_GATE | {} | FAILED | reason=OI_SIGNAL_MISMATCH | {}", scripCode, detail);
                return GateResult.fail("OI_SIGNAL_MISMATCH", detail);
            }
            
            if ((oiBullish && isBullish) || (oiBearish && !isBullish)) {
                qualityScore++;
                details.append("OI_aligned✓ ");
            }
        }

        // Quality 3.5: Volume confirmation
        if (requireVolumeConfirmation && family != null) {
            InstrumentCandle equity = family.getEquity();
            if (equity != null && equity.getVolume() > 0) {
                long buyVol = equity.getBuyVolume();
                long sellVol = equity.getSellVolume();
                
                if (buyVol > 0 || sellVol > 0) {
                    boolean volumeBullish = buyVol > sellVol;
                    if (volumeBullish != isBullish) {
                        String detail = String.format("buyVol=%d, sellVol=%d, signal=%s", buyVol, sellVol, direction);
                        log.debug("QUALITY_GATE | {} | FAILED | reason=VOLUME_NOT_CONFIRMING | {}", scripCode, detail);
                        return GateResult.fail("VOLUME_NOT_CONFIRMING", detail);
                    }
                    qualityScore++;
                    details.append("Vol_confirm✓ ");
                }
            }
        }

        // Calculate quality-based position multiplier
        double multiplier = 1.0;
        if (qualityScore >= 5) {
            multiplier = 1.1;  // All quality checks passed
        } else if (ipu != null && ipu.isXfactorFlag()) {
            multiplier = 1.15;  // X-factor present
        } else if (qualityScore <= 2) {
            multiplier = 0.9;  // Low quality - reduce size
        }

        String passReason = String.format("quality_score=%d/5", qualityScore);
        log.debug("QUALITY_GATE | {} | PASSED | {} | multiplier={} | {}", 
                scripCode, passReason, String.format("%.2f", multiplier), details.toString().trim());
        
        GateResult result = GateResult.pass(passReason, multiplier);
        result.setGateName("QUALITY_GATE");
        result.setDetail(details.toString().trim());
        return result;
    }
}

