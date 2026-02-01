package com.kotsin.consumer.logging;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TraceContext - Thread-local context for end-to-end request tracing.
 *
 * Provides correlation IDs to track requests from Kafka consumption
 * through signal generation and execution.
 *
 * Usage:
 *   TraceContext.start("NIFTY", "5m");
 *   try {
 *       // processing logic
 *       log.info("{} Processing started", TraceContext.getPrefix());
 *   } finally {
 *       TraceContext.clear();
 *   }
 */
public final class TraceContext {

    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();
    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    private TraceContext() {
        // Utility class
    }

    /**
     * Start a new trace context for a symbol processing cycle.
     *
     * @param symbol Symbol being processed
     * @param timeframe Timeframe being processed
     * @return Generated trace ID
     */
    public static String start(String symbol, String timeframe) {
        String traceId = generateTraceId();
        Context ctx = new Context(traceId, symbol, timeframe);
        CONTEXT.set(ctx);
        return traceId;
    }

    /**
     * Start a new trace context with a specific trace ID (for continuing existing trace).
     *
     * @param traceId Existing trace ID
     * @param symbol Symbol being processed
     * @param timeframe Timeframe being processed
     */
    public static void startWith(String traceId, String symbol, String timeframe) {
        Context ctx = new Context(traceId, symbol, timeframe);
        CONTEXT.set(ctx);
    }

    /**
     * Clear the current trace context.
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Get the current trace ID.
     *
     * @return Trace ID or "NO_TRACE" if not set
     */
    public static String getTraceId() {
        Context ctx = CONTEXT.get();
        return ctx != null ? ctx.traceId : "NO_TRACE";
    }

    /**
     * Get the current symbol.
     *
     * @return Symbol or "UNKNOWN" if not set
     */
    public static String getSymbol() {
        Context ctx = CONTEXT.get();
        return ctx != null ? ctx.symbol : "UNKNOWN";
    }

    /**
     * Get the current timeframe.
     *
     * @return Timeframe or "UNKNOWN" if not set
     */
    public static String getTimeframe() {
        Context ctx = CONTEXT.get();
        return ctx != null ? ctx.timeframe : "UNKNOWN";
    }

    /**
     * Get a formatted log prefix containing trace context.
     *
     * @return Formatted prefix like "[traceId=abc123|symbol=NIFTY|tf=5m]"
     */
    public static String getPrefix() {
        Context ctx = CONTEXT.get();
        if (ctx == null) {
            return "";
        }
        return String.format("[traceId=%s|symbol=%s|tf=%s]",
            ctx.traceId, ctx.symbol, ctx.timeframe);
    }

    /**
     * Get a short formatted log prefix.
     *
     * @return Formatted prefix like "[abc123|NIFTY]"
     */
    public static String getShortPrefix() {
        Context ctx = CONTEXT.get();
        if (ctx == null) {
            return "";
        }
        return String.format("[%s|%s]", ctx.traceId, ctx.symbol);
    }

    /**
     * Check if trace context is active.
     *
     * @return true if context is set
     */
    public static boolean isActive() {
        return CONTEXT.get() != null;
    }

    /**
     * Add a processing stage to the trace.
     *
     * @param stage Stage name
     */
    public static void addStage(String stage) {
        Context ctx = CONTEXT.get();
        if (ctx != null) {
            ctx.lastStage = stage;
            ctx.stageCount++;
        }
    }

    /**
     * Get the current processing stage.
     *
     * @return Current stage or "INIT"
     */
    public static String getStage() {
        Context ctx = CONTEXT.get();
        return ctx != null ? ctx.lastStage : "INIT";
    }

    /**
     * Get the stage count.
     *
     * @return Number of stages processed
     */
    public static int getStageCount() {
        Context ctx = CONTEXT.get();
        return ctx != null ? ctx.stageCount : 0;
    }

    /**
     * Generate a unique trace ID.
     * Format: 8 character alphanumeric string for readability.
     */
    private static String generateTraceId() {
        long seq = SEQUENCE.incrementAndGet();
        long time = System.currentTimeMillis() % 100000;
        return String.format("%04x%04x", (int)(time & 0xFFFF), (int)(seq & 0xFFFF));
    }

    /**
     * Internal context holder.
     */
    private static class Context {
        final String traceId;
        final String symbol;
        final String timeframe;
        final long startTime;
        String lastStage;
        int stageCount;

        Context(String traceId, String symbol, String timeframe) {
            this.traceId = traceId;
            this.symbol = symbol;
            this.timeframe = timeframe;
            this.startTime = System.currentTimeMillis();
            this.lastStage = "INIT";
            this.stageCount = 0;
        }
    }

    /**
     * Create a snapshot of current context for passing to async operations.
     *
     * @return Context snapshot or null if no context
     */
    public static ContextSnapshot snapshot() {
        Context ctx = CONTEXT.get();
        if (ctx == null) {
            return null;
        }
        return new ContextSnapshot(ctx.traceId, ctx.symbol, ctx.timeframe);
    }

    /**
     * Restore context from a snapshot.
     *
     * @param snapshot Context snapshot
     */
    public static void restore(ContextSnapshot snapshot) {
        if (snapshot != null) {
            startWith(snapshot.traceId, snapshot.symbol, snapshot.timeframe);
        }
    }

    /**
     * Immutable snapshot of trace context for async operations.
     */
    public static class ContextSnapshot {
        public final String traceId;
        public final String symbol;
        public final String timeframe;

        ContextSnapshot(String traceId, String symbol, String timeframe) {
            this.traceId = traceId;
            this.symbol = symbol;
            this.timeframe = timeframe;
        }
    }
}
