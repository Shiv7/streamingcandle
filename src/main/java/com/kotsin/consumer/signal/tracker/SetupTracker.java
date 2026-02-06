package com.kotsin.consumer.signal.tracker;

import com.kotsin.consumer.breakout.model.BreakoutEvent;
import com.kotsin.consumer.signal.model.TradingSetup;
import com.kotsin.consumer.signal.model.TradingSetup.SetupState;
import com.kotsin.consumer.signal.repository.TradingSetupRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SetupTracker - Tracks setup lifecycle from breakout detection through retest confirmation.
 *
 * Called from SignalEngine.processSymbol() after breakout detection.
 * When a setup reaches RETEST_CONFIRMED, it feeds directly into signal creation path.
 */
@Component
@Slf4j
public class SetupTracker {

    private static final String LOG_PREFIX = "[SETUP-TRACKER]";

    // In-memory active setups per symbol (non-terminal only)
    private final ConcurrentHashMap<String, List<TradingSetup>> activeSetups = new ConcurrentHashMap<>();

    // Approach zone: price within this % of level triggers APPROACH_DETECTED
    private static final double APPROACH_ZONE_PCT = 0.5;  // 0.5%

    // Retest zone: price within this % of level triggers RETEST_IN_PROGRESS
    private static final double RETEST_ZONE_PCT = 0.3;    // 0.3%

    // Setup expiry: auto-expire if no progression within this duration
    private static final Duration SETUP_EXPIRY = Duration.ofHours(4);

    // Max active setups per symbol
    private static final int MAX_SETUPS_PER_SYMBOL = 10;

    @Autowired
    private TradingSetupRepository tradingSetupRepository;

    /**
     * Called when a breakout is detected. Creates LEVEL_WATCHING setup.
     */
    public void onBreakoutDetected(String symbol, BreakoutEvent event) {
        if (event == null || event.getBreakoutLevel() <= 0) return;

        // Check for duplicate — don't create if we already have an active setup at this level
        List<TradingSetup> existing = activeSetups.getOrDefault(symbol, Collections.emptyList());
        boolean duplicate = existing.stream().anyMatch(s ->
            Math.abs(s.getLevelPrice() - event.getBreakoutLevel()) / event.getBreakoutLevel() < 0.001
            && !s.isTerminal());
        if (duplicate) return;

        String direction = event.isBullish() ? "BULLISH" : "BEARISH";

        TradingSetup setup = TradingSetup.builder()
            .symbol(symbol)
            .levelPrice(event.getBreakoutLevel())
            .levelSource(event.getLevelSource() != null ? event.getLevelSource().name() : "UNKNOWN")
            .levelDescription(event.getLevelDescription())
            .direction(direction)
            .state(SetupState.LEVEL_WATCHING)
            .createdAt(Instant.now())
            .lastUpdatedAt(Instant.now())
            .expiresAt(Instant.now().plus(SETUP_EXPIRY))
            .breakoutPrice(event.getBreakoutPrice())
            .breakoutTime(event.getTimestamp())
            .stateHistory(new ArrayList<>())
            .build();

        activeSetups.computeIfAbsent(symbol, k -> new ArrayList<>()).add(setup);

        // Persist
        try {
            tradingSetupRepository.save(setup);
        } catch (Exception e) {
            log.debug("{} {} Failed to persist setup: {}", LOG_PREFIX, symbol, e.getMessage());
        }

        log.info("{} {} LEVEL_WATCHING: {} at {} ({})",
            LOG_PREFIX, symbol, setup.getLevelDescription(),
            String.format("%.2f", setup.getLevelPrice()), direction);

        // Trim old setups
        trimSetups(symbol);
    }

    /**
     * Called on each candle update to progress setup state machines.
     */
    public void onPriceUpdate(String symbol, double price) {
        List<TradingSetup> setups = activeSetups.get(symbol);
        if (setups == null || setups.isEmpty()) return;

        Instant now = Instant.now();
        List<TradingSetup> toRemove = new ArrayList<>();

        for (TradingSetup setup : setups) {
            if (setup.isTerminal()) {
                toRemove.add(setup);
                continue;
            }

            // Check expiry
            if (setup.getExpiresAt() != null && now.isAfter(setup.getExpiresAt())) {
                setup.transitionTo(SetupState.EXPIRED, price, "Setup expired without progression");
                persistSetup(setup);
                log.debug("{} {} EXPIRED: {} at {}", LOG_PREFIX, symbol,
                    setup.getLevelDescription(), String.format("%.2f", setup.getLevelPrice()));
                toRemove.add(setup);
                continue;
            }

            double level = setup.getLevelPrice();
            double approachZone = level * APPROACH_ZONE_PCT / 100;
            double retestZone = level * RETEST_ZONE_PCT / 100;
            double distanceToLevel = Math.abs(price - level);
            boolean isBullish = "BULLISH".equals(setup.getDirection());

            switch (setup.getState()) {
                case LEVEL_WATCHING:
                    // Transition to APPROACH when price nears broken level
                    if (distanceToLevel <= approachZone) {
                        boolean correctSide = isBullish ? price >= level : price <= level;
                        if (correctSide) {
                            setup.setApproachPrice(price);
                            setup.setApproachDetectedAt(now);
                            setup.transitionTo(SetupState.APPROACH_DETECTED, price, "Price approaching level");
                            persistSetup(setup);
                            log.info("{} {} APPROACH_DETECTED: {} at {} (price={})",
                                LOG_PREFIX, symbol, setup.getLevelDescription(),
                                String.format("%.2f", level), String.format("%.2f", price));
                        }
                    }
                    break;

                case APPROACH_DETECTED:
                    // Transition to RETEST when price enters retest zone
                    if (distanceToLevel <= retestZone) {
                        setup.setRetestPrice(price);
                        setup.setRetestStartedAt(now);
                        setup.transitionTo(SetupState.RETEST_IN_PROGRESS, price, "Price in retest zone");
                        persistSetup(setup);
                        log.info("{} {} RETEST_IN_PROGRESS: {} at {} (price={})",
                            LOG_PREFIX, symbol, setup.getLevelDescription(),
                            String.format("%.2f", level), String.format("%.2f", price));
                    }
                    // Check if price moved too far away (invalid approach)
                    else if (distanceToLevel > approachZone * 3) {
                        setup.transitionTo(SetupState.LEVEL_WATCHING, price, "Price moved away from approach");
                        persistSetup(setup);
                    }
                    break;

                case RETEST_IN_PROGRESS:
                    // Check if retest held (price bounced back in breakout direction)
                    boolean held = isBullish ? price > level : price < level;
                    boolean bounced = isBullish ? price > level + retestZone : price < level - retestZone;

                    if (bounced && held) {
                        // Retest confirmed — bounced in breakout direction
                        String quality = determineRetestQuality(setup.getRetestPrice(), level, price, isBullish);
                        setup.setRetestQuality(quality);
                        setup.setRetestHeld(true);
                        setup.transitionTo(SetupState.RETEST_CONFIRMED, price,
                            "Retest held with " + quality + " quality");
                        persistSetup(setup);
                        log.info("{} {} RETEST_CONFIRMED: {} at {} quality={} (price={})",
                            LOG_PREFIX, symbol, setup.getLevelDescription(),
                            String.format("%.2f", level), quality, String.format("%.2f", price));
                    }
                    // Check if retest failed (price pushed through level significantly)
                    else if ((isBullish && price < level - retestZone * 2)
                        || (!isBullish && price > level + retestZone * 2)) {
                        setup.setRetestHeld(false);
                        setup.transitionTo(SetupState.FAILED_RETEST, price, "Price failed through level");
                        persistSetup(setup);
                        log.info("{} {} FAILED_RETEST: {} at {} (price={})",
                            LOG_PREFIX, symbol, setup.getLevelDescription(),
                            String.format("%.2f", level), String.format("%.2f", price));
                        toRemove.add(setup);
                    }
                    break;

                default:
                    break;
            }
        }

        // Remove terminal setups from active tracking
        if (!toRemove.isEmpty()) {
            setups.removeAll(toRemove);
            if (setups.isEmpty()) {
                activeSetups.remove(symbol);
            }
        }
    }

    /**
     * Get confirmed setups ready for signal creation.
     */
    public List<TradingSetup> getConfirmedSetups(String symbol) {
        List<TradingSetup> setups = activeSetups.getOrDefault(symbol, Collections.emptyList());
        return setups.stream()
            .filter(s -> s.getState() == SetupState.RETEST_CONFIRMED)
            .collect(Collectors.toList());
    }

    /**
     * Get all non-terminal setups for a symbol.
     */
    public List<TradingSetup> getActiveSetups(String symbol) {
        return activeSetups.getOrDefault(symbol, Collections.emptyList()).stream()
            .filter(s -> !s.isTerminal())
            .collect(Collectors.toList());
    }

    /**
     * Mark a confirmed setup as consumed (after signal creation).
     */
    public void markConsumed(TradingSetup setup) {
        setup.transitionTo(SetupState.EXPIRED, 0, "Consumed by signal creation");
        persistSetup(setup);
        List<TradingSetup> setups = activeSetups.get(setup.getSymbol());
        if (setups != null) {
            setups.remove(setup);
        }
    }

    private String determineRetestQuality(double retestPrice, double level, double currentPrice, boolean bullish) {
        double penetration = Math.abs(retestPrice - level) / level * 100;
        double bounce = Math.abs(currentPrice - retestPrice) / level * 100;

        if (penetration < 0.1 && bounce > 0.2) return "PERFECT";
        if (penetration < 0.3 && bounce > 0.1) return "GOOD";
        if (penetration < 0.5) return "POOR";
        return "FAILED";
    }

    private void persistSetup(TradingSetup setup) {
        try {
            tradingSetupRepository.save(setup);
        } catch (Exception e) {
            log.debug("{} {} Failed to persist setup: {}", LOG_PREFIX, setup.getSymbol(), e.getMessage());
        }
    }

    private void trimSetups(String symbol) {
        List<TradingSetup> setups = activeSetups.get(symbol);
        if (setups != null && setups.size() > MAX_SETUPS_PER_SYMBOL) {
            // Remove oldest non-terminal setups
            setups.sort(Comparator.comparing(TradingSetup::getCreatedAt));
            while (setups.size() > MAX_SETUPS_PER_SYMBOL) {
                setups.remove(0);
            }
        }
    }
}
