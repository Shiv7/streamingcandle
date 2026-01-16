package com.kotsin.consumer.enrichment.intelligence.narrative;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.intelligence.model.ActiveSetup;
import com.kotsin.consumer.enrichment.intelligence.tracker.SetupTracker;
import com.kotsin.consumer.enrichment.model.*;
import com.kotsin.consumer.enrichment.pattern.model.PatternSignal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * NarrativeGenerator - Synthesizes market context into human-readable stories
 *
 * Creates comprehensive market narratives by analyzing:
 * 1. "Why is price here?" - Technical and structural context
 * 2. "What happened?" - Recent events and changes
 * 3. "Who's in control?" - BUYERS, SELLERS, or BALANCED
 * 4. "Smart money activity" - Institutional signals (VPIN, absorption)
 * 5. "Options signal" - GEX regime, max pain analysis
 * 6. "Active setups" - Trading opportunities in progress
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NarrativeGenerator {

    private final SetupTracker setupTracker;

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    // ======================== MAIN GENERATION ========================

    /**
     * Generate complete market narrative for a family
     *
     * @param familyId   Family identifier
     * @param quantScore Current enriched quant score
     * @param events     Recent detected events
     * @return Complete market narrative
     */
    public MarketNarrative generateNarrative(String familyId, EnrichedQuantScore quantScore,
                                              List<DetectedEvent> events) {
        if (quantScore == null) {
            return MarketNarrative.empty(familyId);
        }

        Instant now = Instant.now();

        // Build each section
        String whyPriceHere = buildWhyPriceHereSection(quantScore);
        String whatHappened = buildWhatHappenedSection(quantScore, events);
        String whoInControl = buildWhoInControlSection(quantScore);
        String smartMoneyActivity = buildSmartMoneySection(quantScore, events);
        String optionsSignal = buildOptionsSignalSection(quantScore);
        String activeSetupsSection = buildActiveSetupsSection(familyId, quantScore);
        String actionItems = buildActionItemsSection(familyId, quantScore, events);

        // Determine overall market posture
        MarketPosture posture = determineMarketPosture(quantScore, events);

        // Generate headline
        String headline = generateHeadline(quantScore, posture, events);

        // Generate one-liner summary
        String oneLiner = generateOneLiner(quantScore, posture);

        // Build full narrative text
        String fullNarrative = buildFullNarrative(
                headline, whyPriceHere, whatHappened, whoInControl,
                smartMoneyActivity, optionsSignal, activeSetupsSection, actionItems
        );

        return MarketNarrative.builder()
                .familyId(familyId)
                .generatedAt(now)
                .headline(headline)
                .oneLiner(oneLiner)
                .fullNarrative(fullNarrative)
                // Individual sections
                .whyPriceHere(whyPriceHere)
                .whatHappened(whatHappened)
                .whoInControl(whoInControl)
                .smartMoneyActivity(smartMoneyActivity)
                .optionsSignal(optionsSignal)
                .activeSetups(activeSetupsSection)
                .actionItems(actionItems)
                // Context
                .posture(posture)
                .controlSide(determineControlSide(quantScore))
                .confidenceLevel(calculateConfidenceLevel(quantScore))
                .eventCount(events != null ? events.size() : 0)
                .hasActionableSetups(hasActionableSetups(familyId))
                .build();
    }

    // ======================== SECTION BUILDERS ========================

    /**
     * Build "Why is price here?" section
     * Explains technical and structural context
     */
    private String buildWhyPriceHereSection(EnrichedQuantScore qs) {
        StringBuilder section = new StringBuilder();

        // Technical context
        TechnicalContext tech = qs.getTechnicalContext();
        if (tech != null) {
            // SuperTrend context
            if (tech.getSuperTrendValue() > 0) {
                String stDir = tech.isSuperTrendBullish() ? "bullish" : "bearish";
                section.append(String.format("SuperTrend is %s at %.2f. ",
                        stDir, tech.getSuperTrendValue()));

                if (tech.isSuperTrendFlip()) {
                    section.append("JUST FLIPPED - trend change signal. ");
                }
            }

            // Bollinger Band context
            Double bbPctB = tech.getBbPercentB();
            if (bbPctB != null) {
                if (bbPctB <= 0.1) {
                    section.append("Price at lower Bollinger Band - oversold territory. ");
                } else if (bbPctB >= 0.9) {
                    section.append("Price at upper Bollinger Band - overbought territory. ");
                } else if (tech.isBbSqueezing()) {
                    section.append("Bollinger Bands squeezing - volatility expansion imminent. ");
                }
            }

            // Volatility context
            if (tech.isVolatilityExpanding()) {
                section.append(String.format("Volatility expanding (ATR: %.2f%%). ",
                        tech.getAtrPct()));
            }
        }

        // Confluence zones context
        if (qs.getConfluenceResult() != null) {
            var confluence = qs.getConfluenceResult();
            if (confluence.isInConfluenceZone() && confluence.getCurrentZone() != null) {
                var zone = confluence.getCurrentZone();
                section.append(String.format("Price in %s zone at %.2f (%d confluent levels). ",
                        zone.getType().toString().toLowerCase(),
                        zone.getCenterPrice(),
                        zone.getSourceCount()));
            } else {
                // Distance to key levels
                if (confluence.getSupportDistance() != null && confluence.getSupportDistance() < 1.0) {
                    section.append(String.format("Near support at %.2f (%.2f%% away). ",
                            confluence.getNearestSupportPrice(),
                            confluence.getSupportDistance()));
                }
                if (confluence.getResistanceDistance() != null && confluence.getResistanceDistance() < 1.0) {
                    section.append(String.format("Near resistance at %.2f (%.2f%% away). ",
                            confluence.getNearestResistancePrice(),
                            confluence.getResistanceDistance()));
                }
            }
        }

        // Historical regime context
        HistoricalContext hist = qs.getHistoricalContext();
        if (hist != null && !hist.isInLearningMode()) {
            section.append(String.format("Market in %s regime based on order flow. ",
                    hist.getDominantRegime().toString().toLowerCase().replace("_", " ")));
        }

        if (section.length() == 0) {
            section.append("Insufficient context data available. ");
        }

        return section.toString().trim();
    }

    /**
     * Build "What happened?" section
     * Describes recent events and changes
     */
    private String buildWhatHappenedSection(EnrichedQuantScore qs, List<DetectedEvent> events) {
        StringBuilder section = new StringBuilder();

        if (events == null || events.isEmpty()) {
            section.append("No significant events in the last period. ");
        } else {
            // Sort by recency
            List<DetectedEvent> sortedEvents = events.stream()
                    .sorted(Comparator.comparing(DetectedEvent::getDetectedAt).reversed())
                    .collect(Collectors.toList());

            // High priority events first
            List<DetectedEvent> highPriority = sortedEvents.stream()
                    .filter(e -> e.getStrength() >= 0.75)
                    .limit(3)
                    .collect(Collectors.toList());

            if (!highPriority.isEmpty()) {
                section.append("Key events: ");
                for (DetectedEvent event : highPriority) {
                    section.append(describeEvent(event)).append(" ");
                }
            }

            // Count events by type
            Map<DetectedEvent.EventType, Long> eventCounts = sortedEvents.stream()
                    .collect(Collectors.groupingBy(DetectedEvent::getEventType, Collectors.counting()));

            // Describe significant patterns
            if (eventCounts.containsKey(DetectedEvent.EventType.OFI_FLIP)) {
                section.append("Order flow imbalance flipped. ");
            }
            if (eventCounts.containsKey(DetectedEvent.EventType.ABSORPTION)) {
                section.append("Absorption detected - possible institutional activity. ");
            }
            if (eventCounts.containsKey(DetectedEvent.EventType.SUPERTREND_FLIP)) {
                section.append("SuperTrend direction changed. ");
            }

            int totalEvents = events.size();
            if (totalEvents > 3) {
                section.append(String.format("(%d total events detected). ", totalEvents));
            }
        }

        // Pattern signals from Phase 4
        if (qs.hasPatternSignals()) {
            section.append(String.format("%d pattern signal(s) active. ", qs.getPatternSignalCount()));
            PatternSignal best = qs.getBestPatternSignal();
            if (best != null) {
                section.append(String.format("Best: %s (%.0f%% confidence). ",
                        best.getPatternId(), best.getConfidence() * 100));
            }
        }

        return section.toString().trim();
    }

    /**
     * Build "Who's in control?" section
     * Determines BUYERS, SELLERS, or BALANCED
     */
    private String buildWhoInControlSection(EnrichedQuantScore qs) {
        StringBuilder section = new StringBuilder();

        ControlSide control = determineControlSide(qs);
        String controlStr = control.toString().replace("_", " ");
        section.append(String.format("Control: %s. ", controlStr));

        // Evidence for control assessment
        HistoricalContext hist = qs.getHistoricalContext();
        if (hist != null) {
            // OFI regime
            if (hist.getOfiContext() != null) {
                double ofi = hist.getOfiContext().getCurrentValue();
                String ofiDir = ofi > 0 ? "positive" : ofi < 0 ? "negative" : "neutral";
                section.append(String.format("OFI is %s (%.2f). ", ofiDir, ofi));

                if (hist.getOfiContext().getConsecutiveCount() >= 3) {
                    section.append(String.format("%d consecutive periods in same direction. ",
                            hist.getOfiContext().getConsecutiveCount()));
                }
            }

            // Absorption detection
            if (hist.isAbsorptionDetected()) {
                section.append("Institutional absorption detected - smart money active. ");
            }

            // Momentum building
            if (hist.isMomentumBuilding()) {
                section.append(String.format("Momentum building %s. ",
                        hist.getMomentumDirection() != null ?
                                hist.getMomentumDirection().toLowerCase() : ""));
            }
        }

        // Volume delta context
        if (hist != null && hist.getVolumeDeltaContext() != null) {
            double volDelta = hist.getVolumeDeltaContext().getCurrentValue();
            if (Math.abs(volDelta) > 0.3) {
                String deltaDir = volDelta > 0 ? "BUYERS" : "SELLERS";
                section.append(String.format("Volume delta favors %s (%.2f). ", deltaDir, volDelta));
            }
        }

        return section.toString().trim();
    }

    /**
     * Build "Smart money activity" section
     * VPIN, absorption, informed flow
     */
    private String buildSmartMoneySection(EnrichedQuantScore qs, List<DetectedEvent> events) {
        StringBuilder section = new StringBuilder();

        HistoricalContext hist = qs.getHistoricalContext();
        if (hist != null) {
            // VPIN context
            if (hist.getVpinContext() != null) {
                double vpin = hist.getVpinContext().getCurrentValue();
                String vpinLevel = vpin > 0.7 ? "HIGH" : vpin > 0.5 ? "MODERATE" : "LOW";
                section.append(String.format("VPIN is %s (%.2f) - ", vpinLevel, vpin));

                if (vpin > 0.7) {
                    section.append("informed trading likely. ");
                } else if (vpin < 0.3) {
                    section.append("retail-dominated flow. ");
                } else {
                    section.append("mixed participant profile. ");
                }
            }

            // Absorption
            if (hist.isAbsorptionDetected()) {
                section.append("ABSORPTION DETECTED: Large orders being absorbed at this level. ");
            }

            // Informed flow
            if (hist.isInformedFlowActive()) {
                section.append(String.format("Informed flow active (%.0f%% intensity). ",
                        hist.getInformedFlowIntensity() * 100));
            }

            // Liquidity
            if (hist.isLiquidityWithdrawal()) {
                section.append("WARNING: Liquidity withdrawal detected - market makers stepping back. ");
            }
        }

        // Check for absorption events
        if (events != null) {
            long absorptionCount = events.stream()
                    .filter(e -> e.getEventType() == DetectedEvent.EventType.ABSORPTION)
                    .count();
            if (absorptionCount > 0) {
                section.append(String.format("%d absorption event(s) detected. ", absorptionCount));
            }
        }

        if (section.length() == 0) {
            section.append("No significant smart money signals detected. ");
        }

        return section.toString().trim();
    }

    /**
     * Build "Options signal" section
     * GEX regime, max pain analysis
     */
    private String buildOptionsSignalSection(EnrichedQuantScore qs) {
        StringBuilder section = new StringBuilder();

        // GEX regime
        GEXProfile gex = qs.getGexProfile();
        if (gex != null && gex.getRegime() != null) {
            section.append(String.format("GEX Regime: %s. ", gex.getRegime().name()));

            switch (gex.getRegime()) {
                case STRONG_TRENDING, TRENDING -> {
                    section.append("Market will follow momentum - breakouts will run. ");
                }
                case STRONG_MEAN_REVERTING, MEAN_REVERTING -> {
                    section.append("Market will revert - fade extreme moves. ");
                }
            }

            if (gex.isNearGammaFlip()) {
                section.append("WARNING: Near gamma flip level - regime change possible. ");
            }

            if (gex.getTotalGex() != 0) {
                section.append(String.format("Net GEX: %.2fM. ", gex.getTotalGex() / 1_000_000.0));
            }
        }

        // Max Pain analysis
        MaxPainProfile maxPain = qs.getMaxPainProfile();
        ExpiryContext expiry = qs.getExpiryContext();

        if (maxPain != null && maxPain.getMaxPainStrike() > 0) {
            section.append(String.format("Max Pain: %.0f (%.1f%% away). ",
                    maxPain.getMaxPainStrike(),
                    maxPain.getAbsDistancePct()));

            if (maxPain.getBias() != null) {
                section.append(String.format("Bias: %s. ", maxPain.getBiasDescription()));
            }

            if (maxPain.isInPinZone()) {
                section.append("IN PIN ZONE - price likely to gravitate toward max pain. ");
            }

            // Expiry relevance
            if (expiry != null && expiry.isExpiryDay()) {
                section.append("EXPIRY DAY - max pain effect strongest in afternoon. ");
            }
        }

        // OI changes from events
        if (qs.getDetectedEvents() != null) {
            boolean callOiSurge = qs.getDetectedEvents().stream()
                    .anyMatch(e -> e.getEventType() == DetectedEvent.EventType.CALL_OI_SURGE);
            boolean putOiSurge = qs.getDetectedEvents().stream()
                    .anyMatch(e -> e.getEventType() == DetectedEvent.EventType.PUT_OI_SURGE);

            if (callOiSurge) {
                section.append("Call OI building - bullish positioning. ");
            }
            if (putOiSurge) {
                section.append("Put OI building - bearish positioning/hedging. ");
            }
        }

        if (section.length() == 0) {
            section.append("No options intelligence available. ");
        }

        return section.toString().trim();
    }

    /**
     * Build "Active setups" section
     * Trading opportunities in progress
     */
    private String buildActiveSetupsSection(String familyId, EnrichedQuantScore qs) {
        StringBuilder section = new StringBuilder();

        List<ActiveSetup> activeSetups = setupTracker.getActiveSetups(familyId);
        List<ActiveSetup> readySetups = setupTracker.getReadySetups(familyId);

        if (readySetups.isEmpty() && activeSetups.isEmpty()) {
            section.append("No active trading setups. ");
        } else {
            // Ready setups first (actionable)
            if (!readySetups.isEmpty()) {
                section.append(String.format("READY SETUPS (%d): ", readySetups.size()));
                for (ActiveSetup setup : readySetups) {
                    section.append(String.format("%s [%s, %.0f%% confidence]. ",
                            setup.getSetupId(),
                            setup.getDirection().name(),
                            setup.getCurrentConfidence() * 100));
                }
            }

            // Forming setups
            List<ActiveSetup> forming = activeSetups.stream()
                    .filter(s -> s.getStatus() == ActiveSetup.SetupStatus.FORMING)
                    .collect(Collectors.toList());

            if (!forming.isEmpty()) {
                section.append(String.format("FORMING (%d): ", forming.size()));
                for (ActiveSetup setup : forming.stream().limit(3).collect(Collectors.toList())) {
                    section.append(String.format("%s (%.0f%% complete). ",
                            setup.getSetupId(),
                            setup.getProgress()));
                }
            }
        }

        return section.toString().trim();
    }

    /**
     * Build action items section
     * Specific recommendations
     */
    private String buildActionItemsSection(String familyId, EnrichedQuantScore qs,
                                            List<DetectedEvent> events) {
        StringBuilder section = new StringBuilder();
        List<String> actions = new ArrayList<>();

        // Check for ready setups
        List<ActiveSetup> readySetups = setupTracker.getActionableSetups(familyId, 0.55);
        if (!readySetups.isEmpty()) {
            ActiveSetup best = readySetups.get(0);
            actions.add(String.format("CONSIDER: %s %s setup ready (%.0f%% confidence)",
                    best.getDirection().name(),
                    best.getSetupId(),
                    best.getCurrentConfidence() * 100));
        }

        // Check for actionable moments
        if (qs.isActionableMoment()) {
            actions.add("ALERT: Actionable moment - " + qs.getActionableMomentReason());
        }

        // SuperTrend flip warning
        if (qs.hasSuperTrendFlip()) {
            String direction = qs.isSuperTrendBullish() ? "BULLISH" : "BEARISH";
            actions.add(String.format("SIGNAL: SuperTrend flipped %s - consider trend trades", direction));
        }

        // Confluence zone opportunities
        if (qs.isAtSupport()) {
            actions.add("OPPORTUNITY: At support level - watch for bounce entry");
        }
        if (qs.isAtResistance()) {
            actions.add("OPPORTUNITY: At resistance level - watch for rejection or breakout");
        }

        // High-confidence events
        if (events != null) {
            List<DetectedEvent> highConf = events.stream()
                    .filter(e -> e.getStrength() >= 0.8)
                    .limit(2)
                    .collect(Collectors.toList());

            for (DetectedEvent event : highConf) {
                actions.add(String.format("EVENT: %s detected (%.0f%% confidence)",
                        event.getEventType().name().replace("_", " "),
                        event.getStrength() * 100));
            }
        }

        // Warnings
        TimeContext time = qs.getTimeContext();
        if (time != null && time.shouldAvoidTrading()) {
            actions.add("WARNING: " + time.getLowConfidenceReason() + " - reduce position sizes");
        }

        if (qs.isExpiryDay()) {
            actions.add("CAUTION: Expiry day - increased unpredictability");
        }

        // Build section
        if (actions.isEmpty()) {
            section.append("No specific action items at this time. ");
        } else {
            for (String action : actions) {
                section.append(action).append(" | ");
            }
        }

        return section.toString().trim();
    }

    // ======================== HELPER METHODS ========================

    /**
     * Describe a single event in human-readable form
     */
    private String describeEvent(DetectedEvent event) {
        String time = TIME_FORMATTER.format(event.getDetectedAt());
        String direction = event.getDirection() != null ?
                event.getDirection().name().toLowerCase() : "";

        return switch (event.getEventType()) {
            case OFI_FLIP -> String.format("OFI flipped %s at %s", direction, time);
            case SUPERTREND_FLIP -> String.format("SuperTrend flipped %s at %s", direction, time);
            case ABSORPTION -> String.format("Absorption detected at %s (%.0f%% strength)",
                    time, event.getStrength() * 100);
            case BUYING_EXHAUSTION -> String.format("Buying exhaustion at %s", time);
            case SELLING_EXHAUSTION -> String.format("Selling exhaustion at %s", time);
            case CALL_OI_SURGE -> String.format("Call OI surge at %s", time);
            case PUT_OI_SURGE -> String.format("Put OI surge at %s", time);
            case GAMMA_SQUEEZE_SETUP -> String.format("Gamma squeeze setup at %s", time);
            case INFORMED_FLOW -> String.format("Informed flow detected at %s", time);
            default -> String.format("%s at %s", event.getEventType().name().replace("_", " "), time);
        };
    }

    /**
     * Determine overall market posture
     */
    private MarketPosture determineMarketPosture(EnrichedQuantScore qs, List<DetectedEvent> events) {
        // Count bullish vs bearish signals
        int bullishSignals = 0;
        int bearishSignals = 0;

        // Technical signals
        if (qs.isSuperTrendBullish()) bullishSignals++;
        else bearishSignals++;

        if (qs.getBbPercentB() != null) {
            if (qs.getBbPercentB() < 0.2) bullishSignals++; // Oversold = bullish opportunity
            if (qs.getBbPercentB() > 0.8) bearishSignals++; // Overbought = bearish opportunity
        }

        // Order flow
        HistoricalContext hist = qs.getHistoricalContext();
        if (hist != null && hist.getOfiContext() != null) {
            if (hist.getOfiContext().getCurrentValue() > 0) bullishSignals++;
            else if (hist.getOfiContext().getCurrentValue() < 0) bearishSignals++;
        }

        // GEX and max pain bias
        GEXProfile gex = qs.getGexProfile();
        MaxPainProfile maxPain = qs.getMaxPainProfile();

        if (maxPain != null && maxPain.getBias() != null) {
            switch (maxPain.getBias()) {
                case BULLISH -> bullishSignals++;
                case BEARISH -> bearishSignals++;
                default -> {} // PIN_EXPECTED and NEUTRAL don't affect direction
            }
        }

        // Confluence zones
        if (qs.isAtSupport()) bullishSignals++;
        if (qs.isAtResistance()) bearishSignals++;

        // Determine posture
        int diff = bullishSignals - bearishSignals;
        if (diff >= 3) return MarketPosture.STRONGLY_BULLISH;
        if (diff >= 1) return MarketPosture.BULLISH;
        if (diff <= -3) return MarketPosture.STRONGLY_BEARISH;
        if (diff <= -1) return MarketPosture.BEARISH;
        return MarketPosture.NEUTRAL;
    }

    /**
     * Determine who is in control
     */
    private ControlSide determineControlSide(EnrichedQuantScore qs) {
        if (qs == null) return ControlSide.BALANCED;

        int buyerScore = 0;
        int sellerScore = 0;

        // OFI direction
        HistoricalContext hist = qs.getHistoricalContext();
        if (hist != null && hist.getOfiContext() != null) {
            double ofi = hist.getOfiContext().getCurrentValue();
            if (ofi > 0.3) buyerScore += 2;
            else if (ofi > 0) buyerScore++;
            else if (ofi < -0.3) sellerScore += 2;
            else if (ofi < 0) sellerScore++;

            // Consecutive count adds strength
            if (hist.getOfiContext().getConsecutiveCount() >= 3) {
                if (ofi > 0) buyerScore++;
                else if (ofi < 0) sellerScore++;
            }
        }

        // Volume delta
        if (hist != null && hist.getVolumeDeltaContext() != null) {
            double volDelta = hist.getVolumeDeltaContext().getCurrentValue();
            if (volDelta > 0.3) buyerScore += 2;
            else if (volDelta > 0) buyerScore++;
            else if (volDelta < -0.3) sellerScore += 2;
            else if (volDelta < 0) sellerScore++;
        }

        // SuperTrend direction
        if (qs.isSuperTrendBullish()) buyerScore++;
        else sellerScore++;

        // Determine control
        if (buyerScore >= sellerScore + 2) return ControlSide.BUYERS;
        if (sellerScore >= buyerScore + 2) return ControlSide.SELLERS;
        if (buyerScore > sellerScore) return ControlSide.BUYERS_SLIGHT;
        if (sellerScore > buyerScore) return ControlSide.SELLERS_SLIGHT;
        return ControlSide.BALANCED;
    }

    /**
     * Calculate overall confidence level
     */
    private ConfidenceLevel calculateConfidenceLevel(EnrichedQuantScore qs) {
        if (qs == null) return ConfidenceLevel.LOW;

        double confidence = qs.getAdjustedConfidence();

        // Adjust based on data availability
        int availableContexts = 0;
        if (qs.hasHistoricalContext()) availableContexts++;
        if (qs.hasOptionsIntelligence()) availableContexts++;
        if (qs.hasTechnicalContext()) availableContexts++;
        if (qs.hasPatternSignals()) availableContexts++;

        // Boost confidence if more contexts available
        double contextBoost = availableContexts * 0.05;
        confidence = Math.min(1.0, confidence + contextBoost);

        if (confidence >= 0.8) return ConfidenceLevel.VERY_HIGH;
        if (confidence >= 0.65) return ConfidenceLevel.HIGH;
        if (confidence >= 0.5) return ConfidenceLevel.MODERATE;
        if (confidence >= 0.35) return ConfidenceLevel.LOW;
        return ConfidenceLevel.VERY_LOW;
    }

    /**
     * Generate headline
     */
    private String generateHeadline(EnrichedQuantScore qs, MarketPosture posture,
                                     List<DetectedEvent> events) {
        StringBuilder headline = new StringBuilder();

        // Posture
        headline.append(posture.getDescription()).append(" | ");

        // Key signal
        if (qs.hasSuperTrendFlip()) {
            String dir = qs.isSuperTrendBullish() ? "BULLISH" : "BEARISH";
            headline.append("SuperTrend FLIP to ").append(dir).append(" | ");
        } else if (qs.isInConfluenceZone()) {
            headline.append("At Confluence Zone | ");
        } else if (qs.isActionableMoment()) {
            headline.append("Actionable Moment | ");
        }

        // GEX regime
        if (qs.getGexRegime() != null) {
            headline.append("GEX: ").append(qs.getGexRegime().name()).append(" | ");
        }

        // Event count
        int eventCount = events != null ? events.size() : 0;
        if (eventCount > 0) {
            headline.append(eventCount).append(" event(s)");
        }

        return headline.toString().trim();
    }

    /**
     * Generate one-liner summary
     */
    private String generateOneLiner(EnrichedQuantScore qs, MarketPosture posture) {
        ControlSide control = determineControlSide(qs);
        String gexRegime = qs.getGexRegime() != null ? qs.getGexRegime().name() : "UNKNOWN";

        return String.format("%s market, %s in control, %s regime",
                posture.getDescription().toLowerCase(),
                control.toString().toLowerCase().replace("_", " "),
                gexRegime.toLowerCase().replace("_", "-"));
    }

    /**
     * Build full narrative text
     */
    private String buildFullNarrative(String headline, String whyPriceHere, String whatHappened,
                                       String whoInControl, String smartMoney, String optionsSignal,
                                       String activeSetups, String actionItems) {
        StringBuilder narrative = new StringBuilder();

        narrative.append("=== MARKET NARRATIVE ===\n\n");
        narrative.append("HEADLINE: ").append(headline).append("\n\n");

        narrative.append("WHY IS PRICE HERE?\n");
        narrative.append(whyPriceHere).append("\n\n");

        narrative.append("WHAT HAPPENED?\n");
        narrative.append(whatHappened).append("\n\n");

        narrative.append("WHO'S IN CONTROL?\n");
        narrative.append(whoInControl).append("\n\n");

        narrative.append("SMART MONEY ACTIVITY\n");
        narrative.append(smartMoney).append("\n\n");

        narrative.append("OPTIONS SIGNAL\n");
        narrative.append(optionsSignal).append("\n\n");

        narrative.append("ACTIVE SETUPS\n");
        narrative.append(activeSetups).append("\n\n");

        narrative.append("ACTION ITEMS\n");
        narrative.append(actionItems).append("\n");

        return narrative.toString();
    }

    /**
     * Check if family has actionable setups
     */
    private boolean hasActionableSetups(String familyId) {
        return !setupTracker.getActionableSetups(familyId, 0.55).isEmpty();
    }

    // ======================== ENUMS ========================

    public enum MarketPosture {
        STRONGLY_BULLISH("Strongly Bullish"),
        BULLISH("Bullish"),
        NEUTRAL("Neutral"),
        BEARISH("Bearish"),
        STRONGLY_BEARISH("Strongly Bearish");

        private final String description;

        MarketPosture(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum ControlSide {
        BUYERS,
        BUYERS_SLIGHT,
        BALANCED,
        SELLERS_SLIGHT,
        SELLERS
    }

    public enum ConfidenceLevel {
        VERY_HIGH,
        HIGH,
        MODERATE,
        LOW,
        VERY_LOW
    }

    // ======================== NARRATIVE MODEL ========================

    @Data
    @Builder
    @AllArgsConstructor
    public static class MarketNarrative {
        private String familyId;
        private Instant generatedAt;

        // Summary
        private String headline;
        private String oneLiner;
        private String fullNarrative;

        // Individual sections
        private String whyPriceHere;
        private String whatHappened;
        private String whoInControl;
        private String smartMoneyActivity;
        private String optionsSignal;
        private String activeSetups;
        private String actionItems;

        // Context
        private MarketPosture posture;
        private ControlSide controlSide;
        private ConfidenceLevel confidenceLevel;
        private int eventCount;
        private boolean hasActionableSetups;

        public static MarketNarrative empty(String familyId) {
            return MarketNarrative.builder()
                    .familyId(familyId)
                    .generatedAt(Instant.now())
                    .headline("No data available")
                    .oneLiner("Insufficient data for narrative")
                    .fullNarrative("No narrative available - waiting for market data.")
                    .whyPriceHere("No data")
                    .whatHappened("No events")
                    .whoInControl("Unknown")
                    .smartMoneyActivity("No data")
                    .optionsSignal("No data")
                    .activeSetups("No setups")
                    .actionItems("Wait for data")
                    .posture(MarketPosture.NEUTRAL)
                    .controlSide(ControlSide.BALANCED)
                    .confidenceLevel(ConfidenceLevel.VERY_LOW)
                    .eventCount(0)
                    .hasActionableSetups(false)
                    .build();
        }

        @Override
        public String toString() {
            return fullNarrative;
        }
    }
}
