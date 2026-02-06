package com.kotsin.consumer.aggregator;

import java.time.Instant;

/**
 * WindowedState - Common interface for all aggregate state objects.
 *
 * <p>Provides the minimal read-only view that {@link BaseAggregator} needs
 * to manage window emission and stale state cleanup without knowing
 * the concrete state type.</p>
 *
 * @see BaseAggregator
 */
public interface WindowedState {

    /**
     * Get the window end time for this state.
     *
     * @return window end instant
     */
    Instant getWindowEnd();

    /**
     * Get the last update time for this state.
     *
     * @return last update instant
     */
    Instant getLastUpdate();

    /**
     * Check if this state has any data worth emitting.
     *
     * @return true if state has data (tickCount > 0 or updateCount > 0)
     */
    boolean hasData();
}
