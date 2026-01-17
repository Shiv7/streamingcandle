package com.kotsin.consumer.enrichment.pattern.matcher;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.model.DetectedEvent;
import com.kotsin.consumer.enrichment.pattern.model.*;
import com.kotsin.consumer.enrichment.pattern.registry.SequenceTemplateRegistry;
import com.kotsin.consumer.enrichment.pattern.tracker.SequenceTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PatternMatcher - Orchestrates event-to-pattern matching
 *
 * Main entry point for pattern recognition:
 * 1. Receives detected events from EventDetector
 * 2. Starts new sequences when first event of a pattern matches
 * 3. Progresses existing sequences when subsequent events match
 * 4. Generates PatternSignal when sequences complete
 *
 * PatternSignals are published to Kafka 'trading-signals' topic
 * for consumption by tradeExecutionModule.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatternMatcher {

    private final SequenceTemplateRegistry templateRegistry;
    private final SequenceTracker sequenceTracker;

    /**
     * Process detected events and match against patterns
     *
     * @param familyId     The family (instrument group) ID
     * @param events       List of detected events
     * @param quantScore   Current enriched quant score for context
     * @return List of PatternSignals for completed patterns
     */
    public List<PatternSignal> processEvents(String familyId, List<DetectedEvent> events,
                                              EnrichedQuantScore quantScore) {
        if (events == null || events.isEmpty()) {
            log.debug("[PATTERN_MATCHER] {} | No events to process", familyId);
            return Collections.emptyList();
        }

        List<PatternSignal> signals = new ArrayList<>();
        int sequencesStarted = 0;
        int sequencesCompleted = 0;

        for (DetectedEvent event : events) {
            // 1. Try to start new sequences
            int beforeStart = sequenceTracker.getActiveSequences(familyId).size();
            startNewSequences(familyId, event);
            int afterStart = sequenceTracker.getActiveSequences(familyId).size();
            sequencesStarted += (afterStart - beforeStart);

            // 2. Progress existing sequences
            List<ActiveSequence> completedSequences = sequenceTracker.processEvent(familyId, event);
            sequencesCompleted += completedSequences.size();

            // 3. Generate signals for completed sequences
            for (ActiveSequence completed : completedSequences) {
                PatternSignal signal = generateSignal(completed, quantScore);
                if (signal != null && signal.isActionable()) {
                    signals.add(signal);
                    sequenceTracker.markSignalEmitted(completed.getSequenceId(), signal.getSignalId());
                    log.info("[PATTERN_MATCHER] Generated signal: {}", signal);
                } else if (signal != null) {
                    log.debug("[PATTERN_MATCHER] Signal not actionable for {}: conf={}%, isActionable={}",
                            familyId, String.format("%.1f", signal.getConfidence() * 100), signal.isActionable());
                }
            }
        }

        // Log summary
        int activeSequences = sequenceTracker.getActiveSequences(familyId).size();
        log.info("[PATTERN_MATCHER] {} | events={}, started={}, completed={}, active={}, signals={}",
                familyId, events.size(), sequencesStarted, sequencesCompleted, activeSequences, signals.size());

        return signals;
    }

    /**
     * Try to start new sequences for patterns that begin with this event
     */
    private void startNewSequences(String familyId, DetectedEvent event) {
        // Get templates that could start with this event type
        List<SequenceTemplate> candidateTemplates = templateRegistry
                .getTemplatesStartingWith(event.getEventType());

        for (SequenceTemplate template : candidateTemplates) {
            // Check if first condition matches
            EventCondition firstCondition = template.getRequiredEvents().get(0);

            if (firstCondition.matches(event)) {
                ActiveSequence newSequence = sequenceTracker.startSequence(template, familyId, event);
                if (newSequence != null) {
                    log.debug("[PATTERN_MATCHER] Started new sequence for {} in family {}",
                            template.getTemplateId(), familyId);
                }
            }
        }
    }

    /**
     * Generate a PatternSignal from a completed sequence
     */
    private PatternSignal generateSignal(ActiveSequence sequence, EnrichedQuantScore quantScore) {
        Optional<SequenceTemplate> templateOpt = templateRegistry.getTemplate(sequence.getTemplateId());
        if (templateOpt.isEmpty()) {
            return null;
        }

        SequenceTemplate template = templateOpt.get();
        ExpectedOutcome outcome = template.getExpectedOutcome();

        // Calculate entry price (current price from sequence)
        double entryPrice = sequence.getCurrentPrice();

        // Calculate targets and stop based on direction
        PatternSignal.Direction direction = mapDirection(template.getDirection());
        double stopLoss, target1, target2, target3;

        // FIX: Use ATR-normalized stops instead of fixed percentage
        // This prevents premature stopouts in volatile instruments
        Double atrPct = quantScore != null ? quantScore.getAtrPct() : null;
        double stopDistancePct;

        if (atrPct != null && atrPct > 0) {
            // ATR-based stop: use max of (template stop, 1.5 * ATR)
            double atrBasedStop = 1.5 * atrPct;
            stopDistancePct = Math.max(outcome.getStopLossPct(), atrBasedStop);
            log.debug("[PATTERN_MATCHER] ATR-based stop: templateStop={}%, atrStop={}%, using={}%",
                    outcome.getStopLossPct(), atrBasedStop, stopDistancePct);
        } else {
            // Fallback to template stop if ATR not available
            stopDistancePct = outcome.getStopLossPct();
        }

        if (direction == PatternSignal.Direction.LONG) {
            stopLoss = entryPrice * (1 - stopDistancePct / 100);
            target1 = entryPrice * (1 + outcome.getTarget1Pct() / 100);
            target2 = entryPrice * (1 + outcome.getTarget2Pct() / 100);
            target3 = entryPrice * (1 + outcome.getTarget3Pct() / 100);
        } else {
            stopLoss = entryPrice * (1 + stopDistancePct / 100);
            target1 = entryPrice * (1 - outcome.getTarget1Pct() / 100);
            target2 = entryPrice * (1 - outcome.getTarget2Pct() / 100);
            target3 = entryPrice * (1 - outcome.getTarget3Pct() / 100);
        }

        // Get historical stats for confidence calculation
        HistoricalStats stats = template.getHistoricalStats();
        double historicalSuccessRate = stats != null ? stats.getSuccessRate() : 0.5;
        double historicalEV = stats != null ? stats.getExpectedValue() : 0;
        int sampleSize = stats != null ? stats.getOccurrences() : 0;

        // Calculate final confidence
        double confidence = calculateFinalConfidence(sequence, template, stats);

        // Build narrative
        String narrative = buildNarrative(sequence, template, quantScore);

        // Build matched events list
        List<String> matchedEventStrings = sequence.getMatchedEvents().stream()
                .map(me -> me.getEventType().name())
                .collect(Collectors.toList());

        List<String> matchedBoosterStrings = sequence.getMatchedBoosters().stream()
                .map(me -> me.getEventType().name())
                .collect(Collectors.toList());

        // Build entry reasons
        List<String> entryReasons = buildEntryReasons(sequence, template);

        // Build invalidation watch list
        List<String> invalidationWatch = template.getInvalidationEvents().stream()
                .map(ec -> ec.getDescription())
                .collect(Collectors.toList());

        // Build predicted events
        List<String> predictedEvents = new ArrayList<>();
        if (outcome.getPredictedEvents() != null) {
            predictedEvents.addAll(Arrays.asList(outcome.getPredictedEvents()));
        }

        return PatternSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .patternId(template.getTemplateId())
                .sequenceId(sequence.getSequenceId())
                .familyId(sequence.getFamilyId())
                .scripCode(quantScore != null ? quantScore.getScripCode() : null)
                .companyName(quantScore != null ? quantScore.getCompanyName() : null)
                .timestamp(Instant.now())
                .signalType("PATTERN")
                .category(template.getCategory().name())
                .direction(direction)
                .horizon(mapHorizon(template.getHorizon()))
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .target1(target1)
                .target2(target2)
                .target3(target3)
                .confidence(confidence)
                .patternConfidence(sequence.getCurrentConfidence())
                .historicalSuccessRate(historicalSuccessRate)
                .historicalExpectedValue(historicalEV)
                .historicalSampleSize(sampleSize)
                .matchedEvents(matchedEventStrings)
                .matchedBoosters(matchedBoosterStrings)
                .patternProgress(100.0) // Completed
                .patternDurationMs(sequence.getElapsedMs())
                .priceMoveDuringPattern(calculatePriceMove(sequence))
                .gexRegime(quantScore != null ? String.valueOf(quantScore.getGexRegime()) : null)
                .superTrendDirection(extractSuperTrendDirection(quantScore))
                .atConfluenceZone(quantScore != null && quantScore.getConfluenceScore() > 0.6)
                .nearestSupport(extractNearestSupport(quantScore))
                .nearestResistance(extractNearestResistance(quantScore))
                .narrative(narrative)
                .entryReasons(entryReasons)
                .predictedEvents(predictedEvents)
                .invalidationWatch(invalidationWatch)
                .positionSizeMultiplier(calculatePositionSizeMultiplier(confidence, stats))
                .build();
    }

    /**
     * Calculate final confidence combining pattern confidence and historical stats
     */
    private double calculateFinalConfidence(ActiveSequence sequence, SequenceTemplate template,
                                             HistoricalStats stats) {
        double patternConfidence = sequence.getCurrentConfidence();

        // Adjust based on historical performance
        if (stats != null && stats.hasSufficientData()) {
            double historicalFactor = stats.getSuccessRate();
            // Blend pattern confidence with historical success rate
            return patternConfidence * 0.6 + historicalFactor * 0.4;
        }

        // Use sample size confidence multiplier
        if (stats != null) {
            return patternConfidence * stats.getSampleSizeConfidence();
        }

        return patternConfidence * 0.8; // Default discount for unknown patterns
    }

    /**
     * Build a human-readable narrative explaining the signal
     */
    private String buildNarrative(ActiveSequence sequence, SequenceTemplate template,
                                   EnrichedQuantScore quantScore) {
        StringBuilder sb = new StringBuilder();

        sb.append(template.getDescription()).append(". ");

        // Event sequence
        sb.append("Sequence: ");
        List<String> eventNames = sequence.getMatchedEvents().stream()
                .map(me -> me.getEventType().name().replace("_", " ").toLowerCase())
                .collect(Collectors.toList());
        sb.append(String.join(" → ", eventNames)).append(". ");

        // Boosters
        if (!sequence.getMatchedBoosters().isEmpty()) {
            sb.append("Confirmed by: ");
            List<String> boosterNames = sequence.getMatchedBoosters().stream()
                    .map(me -> me.getEventType().name().replace("_", " ").toLowerCase())
                    .collect(Collectors.toList());
            sb.append(String.join(", ", boosterNames)).append(". ");
        }

        // Price context
        sb.append(String.format("Price moved %.2f%% during pattern formation. ",
                calculatePriceMove(sequence)));

        // Time context
        long durationMins = sequence.getElapsedMs() / 60_000;
        sb.append(String.format("Pattern completed in %d minutes. ", durationMins));

        // Historical context
        HistoricalStats stats = template.getHistoricalStats();
        if (stats != null && stats.getOccurrences() > 10) {
            sb.append(String.format("Historical: %.0f%% success rate over %d occurrences.",
                    stats.getSuccessRate() * 100, stats.getOccurrences()));
        }

        return sb.toString();
    }

    /**
     * Build list of entry reasons
     */
    private List<String> buildEntryReasons(ActiveSequence sequence, SequenceTemplate template) {
        List<String> reasons = new ArrayList<>();

        // Add reasons from matched events
        for (ActiveSequence.MatchedEvent me : sequence.getMatchedEvents()) {
            int idx = me.getConditionIndex();
            if (idx < template.getRequiredEvents().size()) {
                EventCondition condition = template.getRequiredEvents().get(idx);
                reasons.add(condition.getDescription());
            }
        }

        // Add reasons from boosters
        for (ActiveSequence.MatchedEvent me : sequence.getMatchedBoosters()) {
            reasons.add("Booster: " + me.getEventType().name().replace("_", " "));
        }

        return reasons;
    }

    /**
     * Calculate price move percentage during pattern
     */
    private double calculatePriceMove(ActiveSequence sequence) {
        if (sequence.getStartPrice() == 0) return 0;
        return (sequence.getCurrentPrice() - sequence.getStartPrice()) / sequence.getStartPrice() * 100;
    }

    /**
     * Calculate position size multiplier based on confidence and stats
     * FIX: More conservative sizing - never boost without proven edge
     */
    private double calculatePositionSizeMultiplier(double confidence, HistoricalStats stats) {
        // FIX: Start with confidence-based multiplier (capped at 1.0 as base)
        double multiplier;
        if (confidence >= 0.85) multiplier = 1.0;       // High conf = standard size
        else if (confidence >= 0.75) multiplier = 0.9;  // Good conf = slightly reduced
        else if (confidence >= 0.65) multiplier = 0.8;  // Moderate = reduced
        else multiplier = 0.6;                          // Low conf = significantly reduced

        // FIX: Only allow boost > 1.0 if there's PROVEN historical edge
        if (stats != null && stats.hasSufficientData()) {
            if (stats.hasEdge() && stats.getSuccessRate() >= 0.60) {
                // Proven edge with high win rate: allow small boost
                multiplier = Math.min(multiplier * 1.2, 1.3);
            } else if (!stats.isProfitable() || stats.getSuccessRate() < 0.50) {
                // No profitability or low win rate: reduce significantly
                multiplier *= 0.6;
            } else if (stats.getSuccessRate() < 0.55) {
                // No statistical edge (< 55% win rate): cap at 1.0 and reduce
                multiplier = Math.min(multiplier, 1.0) * 0.8;
            }
        } else {
            // No historical data: be conservative
            multiplier = Math.min(multiplier, 0.8);
        }

        // Clamp to reasonable range (0.3 to 1.3)
        return Math.max(0.3, Math.min(1.3, multiplier));
    }

    /**
     * Map template direction to signal direction
     */
    private PatternSignal.Direction mapDirection(SequenceTemplate.PatternDirection dir) {
        return dir == SequenceTemplate.PatternDirection.BULLISH ?
                PatternSignal.Direction.LONG : PatternSignal.Direction.SHORT;
    }

    /**
     * Map template horizon to signal horizon
     */
    private PatternSignal.Horizon mapHorizon(SequenceTemplate.TradingHorizon horizon) {
        return switch (horizon) {
            case SCALP -> PatternSignal.Horizon.SCALP;
            case SWING -> PatternSignal.Horizon.SWING;
            case POSITIONAL -> PatternSignal.Horizon.POSITIONAL;
        };
    }

    /**
     * Extract SuperTrend direction from quant score
     */
    private String extractSuperTrendDirection(EnrichedQuantScore qs) {
        if (qs == null || qs.getTechnicalContext() == null) return null;
        return qs.getTechnicalContext().isSuperTrendBullish() ? "BULLISH" : "BEARISH";
    }

    /**
     * Extract nearest support from quant score
     */
    private Double extractNearestSupport(EnrichedQuantScore qs) {
        if (qs == null || qs.getConfluenceZones() == null || qs.getConfluenceZones().isEmpty()) {
            return null;
        }
        // Find the nearest support zone below current price
        double currentPrice = qs.getClose();
        return qs.getConfluenceZones().stream()
                .filter(zone -> zone.getCenterPrice() < currentPrice)
                .mapToDouble(EnrichedQuantScore.ConfluenceZone::getCenterPrice)
                .max()
                .orElse(Double.NaN);
    }

    /**
     * Extract nearest resistance from quant score
     */
    private Double extractNearestResistance(EnrichedQuantScore qs) {
        if (qs == null || qs.getConfluenceZones() == null || qs.getConfluenceZones().isEmpty()) {
            return null;
        }
        // Find the nearest resistance zone above current price
        double currentPrice = qs.getClose();
        return qs.getConfluenceZones().stream()
                .filter(zone -> zone.getCenterPrice() > currentPrice)
                .mapToDouble(EnrichedQuantScore.ConfluenceZone::getCenterPrice)
                .min()
                .orElse(Double.NaN);
    }

    // ======================== STATISTICS AND MAINTENANCE ========================

    /**
     * Scheduled cleanup of expired sequences
     */
    @Scheduled(fixedRate = 60_000) // Every minute
    public void cleanupExpired() {
        sequenceTracker.cleanupExpiredSequences();
    }

    /**
     * Get matching statistics
     */
    public MatcherStats getStats() {
        SequenceTracker.TrackerStats trackerStats = sequenceTracker.getStats();
        return new MatcherStats(
                templateRegistry.getTemplateCount(),
                trackerStats
        );
    }

    public record MatcherStats(
            int registeredTemplates,
            SequenceTracker.TrackerStats trackerStats
    ) {
        @Override
        public String toString() {
            return String.format("PatternMatcher: %d templates, %s",
                    registeredTemplates, trackerStats);
        }
    }

    /**
     * Get active sequence summary for a family
     */
    public List<String> getActiveSequenceSummary(String familyId) {
        return sequenceTracker.getActiveSequences(familyId).stream()
                .map(ActiveSequence::getStateDescription)
                .collect(Collectors.toList());
    }
}
