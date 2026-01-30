# Bootstrap Architecture Fix - Implementation Plan

## Overview
This document outlines the comprehensive fixes for the candle bootstrap system to ensure:
1. Version-based bootstrap tracking (re-bootstrap when schema changes)
2. Retry mechanism when historical data fails
3. 4 dedicated threads for historical API calls
4. Admin endpoints for manual re-bootstrap
5. Data completeness validation
6. Proper Java-style logging

## Current Issues
1. `bootstrappedScripCodes` marks instruments as "done" even on failure
2. No mechanism to re-bootstrap when new timeframes are added
3. No retry on subsequent candles when bootstrap fails
4. HTTP client timeout too short (5s connect, 10s read)
5. No data completeness validation

## Implementation Tasks

### Task 1: Enhanced RedisCandleHistoryService
- Add `BOOTSTRAP_VERSION` constant (increment when adding timeframes)
- Add `BootstrapState` enum: `NOT_STARTED`, `IN_PROGRESS`, `SUCCESS`, `FAILED`
- Replace `bootstrappedScripCodes` Set with `bootstrapStates` Map
- Add retry logic: failed bootstraps can retry on next candle
- Add `clearBootstrapState()` and `clearAllBootstrapState()` methods
- Add `hasCompleteData()` validation method
- Increase HTTP timeouts: 15s connect, 30s read

### Task 2: Dedicated Bootstrap Thread Pool
- Create `BootstrapExecutorConfig` with 4 threads
- Named threads: "bootstrap-hist-api-1", "bootstrap-hist-api-2", etc.
- Queue capacity: 100 (to handle burst of new instruments)

### Task 3: Admin Controller Endpoints
- `POST /admin/bootstrap/clear/{scripCode}` - Clear bootstrap state for one
- `POST /admin/bootstrap/clear-all` - Clear all bootstrap states
- `POST /admin/bootstrap/trigger/{scripCode}` - Manually trigger bootstrap
- `GET /admin/bootstrap/status` - Get bootstrap statistics
- `GET /admin/bootstrap/incomplete` - List instruments with incomplete data

### Task 4: Application Properties Updates
- Kafka stability: session.timeout.ms=60000, max.poll.interval.ms=300000
- Window grace periods: 60 seconds across all aggregators
- API timeouts: 15000ms for level calculator
- Bootstrap thread pool size: 4

### Task 5: HtfCandleAggregator Integration
- Use RedisCandleHistoryService as single source of truth
- Remove duplicate `smtis:htf:candles:*` key pattern
- Integrate with new retry mechanism

## File Changes

### Modified Files:
1. `RedisCandleHistoryService.java` - Major refactor
2. `application.properties` - Config updates
3. `HtfCandleAggregator.java` - Use single key pattern

### New Files:
1. `BootstrapExecutorConfig.java` - Thread pool config
2. `AdminController.java` - Admin endpoints
3. `BootstrapState.java` - Enum for bootstrap status

## Logging Standards
All logs follow Java/SLF4J style:
- `log.info("[BOOTSTRAP] {} Starting bootstrap for scripCode={}", logPrefix, scripCode)`
- `log.warn("[BOOTSTRAP] {} API timeout for scripCode={}, attempt={}/{}", logPrefix, scripCode, attempt, maxAttempts)`
- `log.error("[BOOTSTRAP] {} Failed to bootstrap scripCode={}: {}", logPrefix, scripCode, e.getMessage())`

## Testing Plan
1. Clear Redis: `redis-cli FLUSHDB`
2. Restart application
3. Verify bootstrap logs show all timeframes
4. Check Redis keys: `redis-cli keys "candles:*:236" | wc -l` should be 10
5. Test admin endpoints
