# StreamingCandle + Trading Dashboard Integration Plan

## Overview

Integrate the **InstrumentStateManager** from StreamingCandle with the **Trading Dashboard** to provide real-time visibility into:
- Every instrument's current state (IDLE, WATCHING, READY, POSITIONED, COOLDOWN)
- Active setups being watched
- Entry conditions status (what's passing, what's blocking)
- Next opportunity predictions
- Historical state transitions

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           STREAMINGCANDLE (Backend)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐      │
│  │ InstrumentState  │    │  StateSnapshot   │    │  ConditionCheck  │      │
│  │    Manager       │───▶│    Publisher     │───▶│    Publisher     │      │
│  └──────────────────┘    └──────────────────┘    └──────────────────┘      │
│           │                       │                       │                 │
│           │                       ▼                       ▼                 │
│           │              ┌─────────────────────────────────────┐           │
│           │              │           KAFKA TOPICS              │           │
│           │              ├─────────────────────────────────────┤           │
│           │              │ • instrument-state-snapshots        │           │
│           │              │ • instrument-condition-checks       │           │
│           │              │ • instrument-state-transitions      │           │
│           │              │ • strategy-opportunities            │           │
│           │              └─────────────────────────────────────┘           │
│           │                              │                                  │
└───────────┼──────────────────────────────┼──────────────────────────────────┘
            │                              │
            ▼                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         TRADING-DASHBOARD (Backend)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐      │
│  │  Kafka Consumer  │───▶│  State Aggregator│───▶│  WebSocket       │      │
│  │  (State Topics)  │    │  Service         │    │  Publisher       │      │
│  └──────────────────┘    └──────────────────┘    └──────────────────┘      │
│                                   │                       │                 │
│                                   ▼                       │                 │
│                          ┌──────────────┐                 │                 │
│                          │   MongoDB    │                 │                 │
│                          │ (History)    │                 │                 │
│                          └──────────────┘                 │                 │
│                                                           │                 │
│  ┌────────────────────────────────────────────────────────┘                │
│  │                                                                          │
│  │  REST API Endpoints:                                                     │
│  │  • GET /api/state-machine/instruments                                   │
│  │  • GET /api/state-machine/instruments/{scripCode}                       │
│  │  • GET /api/state-machine/instruments/{scripCode}/conditions            │
│  │  • GET /api/state-machine/instruments/{scripCode}/history               │
│  │  • GET /api/state-machine/opportunities                                 │
│  │  • GET /api/state-machine/stats                                         │
│  │                                                                          │
│  │  WebSocket Topics:                                                       │
│  │  • /topic/state-machine/snapshots                                       │
│  │  • /topic/state-machine/conditions/{scripCode}                          │
│  │  • /topic/state-machine/transitions                                     │
│  │  • /topic/state-machine/opportunities                                   │
│  │                                                                          │
└─────────────────────────────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         TRADING-DASHBOARD (Frontend)                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     STATE MACHINE DASHBOARD                          │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                      │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐│   │
│  │  │    IDLE     │  │  WATCHING   │  │   READY     │  │ POSITIONED  ││   │
│  │  │    (42)     │  │    (8)      │  │    (2)      │  │    (5)      ││   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘│   │
│  │                                                                      │   │
│  │  ┌───────────────────────────────────────────────────────────────┐  │   │
│  │  │              INSTRUMENT STATE CARDS                            │  │   │
│  │  │  ┌─────────────────────────────────────────────────────────┐  │  │   │
│  │  │  │ ALUMINI (467014)                        State: WATCHING │  │  │   │
│  │  │  │ ══════════════════════════════════════════════════════ │  │  │   │
│  │  │  │ Strategies: [FUDKII] [PIVOT_RETEST]                    │  │  │   │
│  │  │  │                                                         │  │  │   │
│  │  │  │ PIVOT_RETEST (SHORT at CAM_H3 5506.38)                 │  │  │   │
│  │  │  │ ├─ At Level:     ████████████ 100%  ✓                  │  │  │   │
│  │  │  │ ├─ OFI Aligned:  ████████████ 100%  ✓                  │  │  │   │
│  │  │  │ ├─ VPIN OK:      ████████████ 100%  ✓                  │  │  │   │
│  │  │  │ ├─ ST Aligned:   ████████████ 100%  ✓                  │  │  │   │
│  │  │  │ └─ R:R >= 1.5:   ██░░░░░░░░░░  19%  ✗ (0.28)          │  │  │   │
│  │  │  │                                                         │  │  │   │
│  │  │  │ FUDKII (BB Squeeze + ST SHORT)                         │  │  │   │
│  │  │  │ └─ ST Flip:      ░░░░░░░░░░░░   0%  ✗ (waiting)        │  │  │   │
│  │  │  │                                                         │  │  │   │
│  │  │  │ Fresh Data: price=5506 | ofi=-1.54 | atr=16           │  │  │   │
│  │  │  └─────────────────────────────────────────────────────────┘  │  │   │
│  │  └───────────────────────────────────────────────────────────────┘  │   │
│  │                                                                      │   │
│  │  ┌───────────────────────────────────────────────────────────────┐  │   │
│  │  │              STATE TRANSITION TIMELINE                         │  │   │
│  │  │  17:30:00 ──●── IDLE                                          │  │   │
│  │  │  17:30:15 ──●── WATCHING (FUDKII + PIVOT_RETEST detected)    │  │   │
│  │  │  17:35:00 ──○── Still WATCHING (OFI flipped, R:R blocking)   │  │   │
│  │  │  17:40:00 ──○── Still WATCHING (R:R still 0.28)              │  │   │
│  │  └───────────────────────────────────────────────────────────────┘  │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 1: Backend Changes (StreamingCandle)

### 1.1 New Data Models

```java
// InstrumentStateSnapshot.java
@Data
@Builder
public class InstrumentStateSnapshot {
    private String scripCode;
    private String companyName;
    private InstrumentState state;  // IDLE, WATCHING, READY, POSITIONED, COOLDOWN
    private long stateTimestamp;
    private long stateEntryTime;
    private long stateDurationMs;

    // Current market data
    private double currentPrice;
    private double ofiZscore;
    private double atr;
    private double vpin;
    private boolean superTrendBullish;
    private boolean superTrendFlip;
    private double bbPercentB;

    // Active setups (when WATCHING)
    private List<ActiveSetupInfo> activeSetups;

    // Position info (when POSITIONED)
    private PositionInfo position;

    // Cooldown info (when COOLDOWN)
    private long cooldownRemainingMs;

    // Stats
    private int signalsToday;
    private int maxSignalsPerDay;
}

// ActiveSetupInfo.java
@Data
@Builder
public class ActiveSetupInfo {
    private String strategyId;        // FUDKII, PIVOT_RETEST
    private String setupDescription;  // "BB squeeze + ST SHORT"
    private String direction;         // LONG, SHORT
    private double keyLevel;
    private long watchingStartTime;
    private long watchingDurationMs;

    // Condition checks (real-time)
    private List<ConditionCheck> conditions;

    // Overall progress (0-100%)
    private int progressPercent;
    private String blockingCondition;  // Which condition is blocking entry
}

// ConditionCheck.java
@Data
@Builder
public class ConditionCheck {
    private String conditionName;     // "At Level", "OFI Aligned", "R:R >= 1.5"
    private boolean passed;
    private double currentValue;
    private double requiredValue;
    private String comparison;        // ">", "<", ">=", "within"
    private int progressPercent;      // 0-100
    private String displayValue;      // "0.28 (need 1.5)"
}

// StateTransition.java
@Data
@Builder
public class StateTransition {
    private String scripCode;
    private InstrumentState fromState;
    private InstrumentState toState;
    private String reason;
    private long timestamp;
    private Map<String, Object> metadata;  // Additional context
}

// StrategyOpportunity.java
@Data
@Builder
public class StrategyOpportunity {
    private String scripCode;
    private String companyName;
    private String strategyId;
    private String direction;
    private double opportunityScore;  // 0-100, how close to triggering
    private List<ConditionCheck> conditions;
    private String nextConditionNeeded;
    private String estimatedTimeframe;  // "OFI trending, may flip in 5-10 candles"
}
```

### 1.2 New Publisher Service

```java
// StateSnapshotPublisher.java
@Service
@RequiredArgsConstructor
@Slf4j
public class StateSnapshotPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final InstrumentStateManager stateManager;

    private static final String SNAPSHOTS_TOPIC = "instrument-state-snapshots";
    private static final String CONDITIONS_TOPIC = "instrument-condition-checks";
    private static final String TRANSITIONS_TOPIC = "instrument-state-transitions";
    private static final String OPPORTUNITIES_TOPIC = "strategy-opportunities";

    /**
     * Called after each candle processing to publish current state
     */
    public void publishSnapshot(String scripCode, EnrichedQuantScore score) {
        InstrumentStateSnapshot snapshot = buildSnapshot(scripCode, score);
        kafkaTemplate.send(SNAPSHOTS_TOPIC, scripCode, snapshot);
    }

    /**
     * Called when state transitions
     */
    public void publishTransition(String scripCode, InstrumentState from,
                                   InstrumentState to, String reason) {
        StateTransition transition = StateTransition.builder()
            .scripCode(scripCode)
            .fromState(from)
            .toState(to)
            .reason(reason)
            .timestamp(System.currentTimeMillis())
            .build();
        kafkaTemplate.send(TRANSITIONS_TOPIC, scripCode, transition);
    }

    /**
     * Publish condition check details for UI visualization
     */
    public void publishConditionChecks(String scripCode, String strategyId,
                                        List<ConditionCheck> conditions) {
        Map<String, Object> payload = Map.of(
            "scripCode", scripCode,
            "strategyId", strategyId,
            "conditions", conditions,
            "timestamp", System.currentTimeMillis()
        );
        kafkaTemplate.send(CONDITIONS_TOPIC, scripCode, payload);
    }

    /**
     * Publish near-opportunity instruments
     */
    @Scheduled(fixedRate = 5000)  // Every 5 seconds
    public void publishOpportunities() {
        List<StrategyOpportunity> opportunities = stateManager.getNearOpportunities();
        kafkaTemplate.send(OPPORTUNITIES_TOPIC, "all", opportunities);
    }
}
```

### 1.3 Modify InstrumentStateManager

Add methods to expose state for publishing:

```java
// Add to InstrumentStateManager.java

/**
 * Get snapshot of instrument state for publishing
 */
public InstrumentStateSnapshot getSnapshot(String scripCode, EnrichedQuantScore score) {
    InstrumentContext ctx = instrumentStates.get(scripCode);
    if (ctx == null) {
        return InstrumentStateSnapshot.builder()
            .scripCode(scripCode)
            .state(InstrumentState.IDLE)
            .build();
    }

    return InstrumentStateSnapshot.builder()
        .scripCode(scripCode)
        .state(ctx.state)
        .stateEntryTime(getStateEntryTime(ctx))
        .stateDurationMs(System.currentTimeMillis() - getStateEntryTime(ctx))
        .currentPrice(score.getClose())
        .ofiZscore(getOfiZscore(score))
        .atr(score.getTechnicalContext() != null ? score.getTechnicalContext().getAtr() : 0)
        .vpin(getVpin(score))
        .superTrendBullish(score.getTechnicalContext() != null && score.getTechnicalContext().isSuperTrendBullish())
        .superTrendFlip(score.getTechnicalContext() != null && score.getTechnicalContext().isSuperTrendFlip())
        .bbPercentB(score.getTechnicalContext() != null ? score.getTechnicalContext().getBbPercentB() : 0.5)
        .activeSetups(buildActiveSetups(ctx, score))
        .signalsToday((int) dailySignalCounts.getOrDefault(scripCode, new AtomicLong(0)).get())
        .maxSignalsPerDay(MAX_SIGNALS_PER_INSTRUMENT_PER_DAY)
        .build();
}

/**
 * Get instruments close to triggering (for opportunity feed)
 */
public List<StrategyOpportunity> getNearOpportunities() {
    return instrumentStates.entrySet().stream()
        .filter(e -> e.getValue().state == InstrumentState.WATCHING)
        .map(e -> buildOpportunity(e.getKey(), e.getValue()))
        .filter(o -> o.getOpportunityScore() >= 60)  // At least 60% conditions met
        .sorted((a, b) -> Double.compare(b.getOpportunityScore(), a.getOpportunityScore()))
        .limit(20)
        .toList();
}

/**
 * Get all instrument states summary
 */
public Map<InstrumentState, List<String>> getStatesSummary() {
    return instrumentStates.entrySet().stream()
        .collect(Collectors.groupingBy(
            e -> e.getValue().state,
            Collectors.mapping(Map.Entry::getKey, Collectors.toList())
        ));
}
```

---

## Part 2: Backend Changes (Trading-Dashboard)

### 2.1 Kafka Consumer

```java
// StateMachineKafkaConsumer.java
@Service
@RequiredArgsConstructor
@Slf4j
public class StateMachineKafkaConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final StateMachineService stateMachineService;

    @KafkaListener(topics = "instrument-state-snapshots", groupId = "dashboard-state")
    public void consumeSnapshots(InstrumentStateSnapshot snapshot) {
        // Store in service (in-memory + MongoDB)
        stateMachineService.updateSnapshot(snapshot);

        // Push to WebSocket
        messagingTemplate.convertAndSend(
            "/topic/state-machine/snapshots",
            snapshot
        );
    }

    @KafkaListener(topics = "instrument-condition-checks", groupId = "dashboard-conditions")
    public void consumeConditions(Map<String, Object> payload) {
        String scripCode = (String) payload.get("scripCode");

        // Push to WebSocket (scripCode-specific topic)
        messagingTemplate.convertAndSend(
            "/topic/state-machine/conditions/" + scripCode,
            payload
        );
    }

    @KafkaListener(topics = "instrument-state-transitions", groupId = "dashboard-transitions")
    public void consumeTransitions(StateTransition transition) {
        // Store in MongoDB for history
        stateMachineService.recordTransition(transition);

        // Push to WebSocket
        messagingTemplate.convertAndSend(
            "/topic/state-machine/transitions",
            transition
        );
    }

    @KafkaListener(topics = "strategy-opportunities", groupId = "dashboard-opportunities")
    public void consumeOpportunities(List<StrategyOpportunity> opportunities) {
        // Push to WebSocket
        messagingTemplate.convertAndSend(
            "/topic/state-machine/opportunities",
            opportunities
        );
    }
}
```

### 2.2 REST API Controller

```java
// StateMachineController.java
@RestController
@RequestMapping("/api/state-machine")
@RequiredArgsConstructor
public class StateMachineController {

    private final StateMachineService service;

    /**
     * Get all instruments with their current states
     */
    @GetMapping("/instruments")
    public ResponseEntity<StateMachineSummary> getAllInstruments() {
        return ResponseEntity.ok(service.getSummary());
    }

    /**
     * Get specific instrument state details
     */
    @GetMapping("/instruments/{scripCode}")
    public ResponseEntity<InstrumentStateSnapshot> getInstrument(
            @PathVariable String scripCode) {
        return ResponseEntity.ok(service.getSnapshot(scripCode));
    }

    /**
     * Get condition check details for an instrument
     */
    @GetMapping("/instruments/{scripCode}/conditions")
    public ResponseEntity<List<ConditionCheckDetail>> getConditions(
            @PathVariable String scripCode) {
        return ResponseEntity.ok(service.getConditions(scripCode));
    }

    /**
     * Get state transition history for an instrument
     */
    @GetMapping("/instruments/{scripCode}/history")
    public ResponseEntity<List<StateTransition>> getHistory(
            @PathVariable String scripCode,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(service.getHistory(scripCode, limit));
    }

    /**
     * Get near-opportunities (instruments close to triggering)
     */
    @GetMapping("/opportunities")
    public ResponseEntity<List<StrategyOpportunity>> getOpportunities() {
        return ResponseEntity.ok(service.getOpportunities());
    }

    /**
     * Get overall stats
     */
    @GetMapping("/stats")
    public ResponseEntity<StateMachineStats> getStats() {
        return ResponseEntity.ok(service.getStats());
    }
}

// Response DTOs
@Data
@Builder
public class StateMachineSummary {
    private Map<String, Integer> stateCountsMap;  // {IDLE: 42, WATCHING: 8, ...}
    private List<InstrumentStateSnapshot> watchingInstruments;
    private List<InstrumentStateSnapshot> positionedInstruments;
    private int totalInstruments;
    private long lastUpdateTime;
}

@Data
@Builder
public class StateMachineStats {
    private int totalInstruments;
    private int idleCount;
    private int watchingCount;
    private int readyCount;
    private int positionedCount;
    private int cooldownCount;
    private int signalsToday;
    private int tradesCompleted;
    private double winRate;
    private List<String> topOpportunities;
}
```

---

## Part 3: Frontend Changes (Trading-Dashboard)

### 3.1 New TypeScript Types

```typescript
// types/stateMachine.ts

export type InstrumentState = 'IDLE' | 'WATCHING' | 'READY' | 'POSITIONED' | 'COOLDOWN';

export interface ConditionCheck {
  conditionName: string;
  passed: boolean;
  currentValue: number;
  requiredValue: number;
  comparison: '>' | '<' | '>=' | '<=' | 'within';
  progressPercent: number;
  displayValue: string;
}

export interface ActiveSetupInfo {
  strategyId: string;
  setupDescription: string;
  direction: 'LONG' | 'SHORT';
  keyLevel: number;
  watchingStartTime: number;
  watchingDurationMs: number;
  conditions: ConditionCheck[];
  progressPercent: number;
  blockingCondition: string | null;
}

export interface InstrumentStateSnapshot {
  scripCode: string;
  companyName: string;
  state: InstrumentState;
  stateTimestamp: number;
  stateEntryTime: number;
  stateDurationMs: number;

  // Market data
  currentPrice: number;
  ofiZscore: number;
  atr: number;
  vpin: number;
  superTrendBullish: boolean;
  superTrendFlip: boolean;
  bbPercentB: number;

  // Active setups
  activeSetups: ActiveSetupInfo[];

  // Stats
  signalsToday: number;
  maxSignalsPerDay: number;
}

export interface StateTransition {
  scripCode: string;
  fromState: InstrumentState;
  toState: InstrumentState;
  reason: string;
  timestamp: number;
}

export interface StrategyOpportunity {
  scripCode: string;
  companyName: string;
  strategyId: string;
  direction: 'LONG' | 'SHORT';
  opportunityScore: number;
  conditions: ConditionCheck[];
  nextConditionNeeded: string;
}

export interface StateMachineSummary {
  stateCounts: Record<InstrumentState, number>;
  watchingInstruments: InstrumentStateSnapshot[];
  positionedInstruments: InstrumentStateSnapshot[];
  totalInstruments: number;
  lastUpdateTime: number;
}
```

### 3.2 Zustand Store

```typescript
// store/stateMachineStore.ts

import { create } from 'zustand';
import {
  InstrumentStateSnapshot,
  StateTransition,
  StrategyOpportunity,
  StateMachineSummary
} from '../types/stateMachine';

interface StateMachineState {
  // Current snapshots by scripCode
  snapshots: Map<string, InstrumentStateSnapshot>;

  // Recent transitions
  transitions: StateTransition[];

  // Near opportunities
  opportunities: StrategyOpportunity[];

  // Summary stats
  summary: StateMachineSummary | null;

  // Selected instrument for detail view
  selectedScripCode: string | null;

  // Actions
  updateSnapshot: (snapshot: InstrumentStateSnapshot) => void;
  addTransition: (transition: StateTransition) => void;
  setOpportunities: (opportunities: StrategyOpportunity[]) => void;
  setSummary: (summary: StateMachineSummary) => void;
  selectInstrument: (scripCode: string | null) => void;

  // Computed
  getByState: (state: InstrumentState) => InstrumentStateSnapshot[];
  getWatchingCount: () => number;
}

export const useStateMachineStore = create<StateMachineState>((set, get) => ({
  snapshots: new Map(),
  transitions: [],
  opportunities: [],
  summary: null,
  selectedScripCode: null,

  updateSnapshot: (snapshot) => set((state) => {
    const newSnapshots = new Map(state.snapshots);
    newSnapshots.set(snapshot.scripCode, snapshot);
    return { snapshots: newSnapshots };
  }),

  addTransition: (transition) => set((state) => ({
    transitions: [transition, ...state.transitions].slice(0, 100)
  })),

  setOpportunities: (opportunities) => set({ opportunities }),

  setSummary: (summary) => set({ summary }),

  selectInstrument: (scripCode) => set({ selectedScripCode: scripCode }),

  getByState: (targetState) => {
    const { snapshots } = get();
    return Array.from(snapshots.values())
      .filter(s => s.state === targetState);
  },

  getWatchingCount: () => {
    const { snapshots } = get();
    return Array.from(snapshots.values())
      .filter(s => s.state === 'WATCHING').length;
  }
}));
```

### 3.3 WebSocket Hook Update

```typescript
// hooks/useWebSocket.ts - Add new subscriptions

// Add to existing subscriptions:
client.subscribe('/topic/state-machine/snapshots', (message) => {
  const snapshot = JSON.parse(message.body);
  useStateMachineStore.getState().updateSnapshot(snapshot);
});

client.subscribe('/topic/state-machine/transitions', (message) => {
  const transition = JSON.parse(message.body);
  useStateMachineStore.getState().addTransition(transition);
});

client.subscribe('/topic/state-machine/opportunities', (message) => {
  const opportunities = JSON.parse(message.body);
  useStateMachineStore.getState().setOpportunities(opportunities);
});
```

### 3.4 New Components

#### 3.4.1 State Machine Dashboard Page

```typescript
// pages/StateMachinePage.tsx

import React from 'react';
import { StateSummaryCards } from '../components/StateMachine/StateSummaryCards';
import { InstrumentStateList } from '../components/StateMachine/InstrumentStateList';
import { InstrumentDetailPanel } from '../components/StateMachine/InstrumentDetailPanel';
import { OpportunityFeed } from '../components/StateMachine/OpportunityFeed';
import { TransitionTimeline } from '../components/StateMachine/TransitionTimeline';
import { useStateMachineStore } from '../store/stateMachineStore';

export const StateMachinePage: React.FC = () => {
  const { selectedScripCode } = useStateMachineStore();

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex justify-between items-center">
        <h1 className="text-2xl font-bold text-white">
          Strategy State Machine
        </h1>
        <div className="text-sm text-gray-400">
          Real-time instrument monitoring
        </div>
      </div>

      {/* State Summary Cards */}
      <StateSummaryCards />

      {/* Main Content */}
      <div className="grid grid-cols-12 gap-6">
        {/* Left: Instrument List */}
        <div className="col-span-4">
          <InstrumentStateList />
        </div>

        {/* Center: Detail Panel */}
        <div className="col-span-5">
          {selectedScripCode ? (
            <InstrumentDetailPanel scripCode={selectedScripCode} />
          ) : (
            <div className="bg-gray-800 rounded-lg p-8 text-center text-gray-400">
              Select an instrument to view details
            </div>
          )}
        </div>

        {/* Right: Opportunities & Timeline */}
        <div className="col-span-3 space-y-6">
          <OpportunityFeed />
          <TransitionTimeline />
        </div>
      </div>
    </div>
  );
};
```

#### 3.4.2 State Summary Cards

```typescript
// components/StateMachine/StateSummaryCards.tsx

import React from 'react';
import { useStateMachineStore } from '../../store/stateMachineStore';

const stateConfig = {
  IDLE: { color: 'bg-gray-600', label: 'Idle', icon: '⏸' },
  WATCHING: { color: 'bg-yellow-600', label: 'Watching', icon: '👁' },
  READY: { color: 'bg-blue-600', label: 'Ready', icon: '🎯' },
  POSITIONED: { color: 'bg-green-600', label: 'Positioned', icon: '📈' },
  COOLDOWN: { color: 'bg-purple-600', label: 'Cooldown', icon: '⏳' },
};

export const StateSummaryCards: React.FC = () => {
  const { snapshots } = useStateMachineStore();

  const counts = React.useMemo(() => {
    const result = { IDLE: 0, WATCHING: 0, READY: 0, POSITIONED: 0, COOLDOWN: 0 };
    snapshots.forEach(s => result[s.state]++);
    return result;
  }, [snapshots]);

  return (
    <div className="grid grid-cols-5 gap-4">
      {Object.entries(stateConfig).map(([state, config]) => (
        <div
          key={state}
          className={`${config.color} rounded-lg p-4 text-white`}
        >
          <div className="flex items-center justify-between">
            <span className="text-2xl">{config.icon}</span>
            <span className="text-3xl font-bold">{counts[state]}</span>
          </div>
          <div className="mt-2 text-sm opacity-80">{config.label}</div>
        </div>
      ))}
    </div>
  );
};
```

#### 3.4.3 Instrument State Card

```typescript
// components/StateMachine/InstrumentStateCard.tsx

import React from 'react';
import { InstrumentStateSnapshot } from '../../types/stateMachine';
import { ConditionProgressBar } from './ConditionProgressBar';

interface Props {
  snapshot: InstrumentStateSnapshot;
  isSelected: boolean;
  onClick: () => void;
}

export const InstrumentStateCard: React.FC<Props> = ({
  snapshot,
  isSelected,
  onClick
}) => {
  const { state, scripCode, companyName, activeSetups, currentPrice } = snapshot;

  const stateColors = {
    IDLE: 'border-gray-600',
    WATCHING: 'border-yellow-500',
    READY: 'border-blue-500',
    POSITIONED: 'border-green-500',
    COOLDOWN: 'border-purple-500',
  };

  const overallProgress = React.useMemo(() => {
    if (!activeSetups?.length) return 0;
    const maxProgress = Math.max(...activeSetups.map(s => s.progressPercent));
    return maxProgress;
  }, [activeSetups]);

  return (
    <div
      onClick={onClick}
      className={`
        bg-gray-800 rounded-lg p-4 cursor-pointer
        border-l-4 ${stateColors[state]}
        ${isSelected ? 'ring-2 ring-blue-500' : 'hover:bg-gray-750'}
        transition-all
      `}
    >
      {/* Header */}
      <div className="flex justify-between items-start mb-3">
        <div>
          <div className="font-semibold text-white">{companyName || scripCode}</div>
          <div className="text-xs text-gray-400">{scripCode}</div>
        </div>
        <div className="flex flex-col items-end">
          <span className={`
            px-2 py-0.5 rounded text-xs font-medium
            ${state === 'WATCHING' ? 'bg-yellow-500/20 text-yellow-400' : ''}
            ${state === 'IDLE' ? 'bg-gray-500/20 text-gray-400' : ''}
            ${state === 'POSITIONED' ? 'bg-green-500/20 text-green-400' : ''}
          `}>
            {state}
          </span>
          <span className="text-sm text-white mt-1">₹{currentPrice.toFixed(2)}</span>
        </div>
      </div>

      {/* Active Setups (when WATCHING) */}
      {state === 'WATCHING' && activeSetups?.length > 0 && (
        <div className="space-y-2">
          {activeSetups.map((setup, idx) => (
            <div key={idx} className="bg-gray-700/50 rounded p-2">
              <div className="flex justify-between text-xs mb-1">
                <span className="text-gray-300">{setup.strategyId}</span>
                <span className={setup.direction === 'LONG' ? 'text-green-400' : 'text-red-400'}>
                  {setup.direction}
                </span>
              </div>
              <div className="text-xs text-gray-400 mb-2">
                {setup.setupDescription}
              </div>
              {/* Progress bar */}
              <div className="h-1.5 bg-gray-600 rounded-full overflow-hidden">
                <div
                  className={`h-full transition-all ${
                    setup.progressPercent >= 80 ? 'bg-green-500' :
                    setup.progressPercent >= 50 ? 'bg-yellow-500' : 'bg-gray-400'
                  }`}
                  style={{ width: `${setup.progressPercent}%` }}
                />
              </div>
              <div className="flex justify-between text-xs mt-1">
                <span className="text-gray-500">{setup.progressPercent}% ready</span>
                {setup.blockingCondition && (
                  <span className="text-red-400">⚠ {setup.blockingCondition}</span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Overall progress indicator */}
      {state === 'WATCHING' && (
        <div className="mt-3 flex items-center gap-2">
          <div className="flex-1 h-1 bg-gray-700 rounded-full overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-yellow-500 to-green-500"
              style={{ width: `${overallProgress}%` }}
            />
          </div>
          <span className="text-xs text-gray-400">{overallProgress}%</span>
        </div>
      )}
    </div>
  );
};
```

#### 3.4.4 Instrument Detail Panel

```typescript
// components/StateMachine/InstrumentDetailPanel.tsx

import React from 'react';
import { useStateMachineStore } from '../../store/stateMachineStore';
import { ConditionCheckList } from './ConditionCheckList';
import { FreshDataDisplay } from './FreshDataDisplay';
import { StateFlowDiagram } from './StateFlowDiagram';

interface Props {
  scripCode: string;
}

export const InstrumentDetailPanel: React.FC<Props> = ({ scripCode }) => {
  const snapshot = useStateMachineStore(
    state => state.snapshots.get(scripCode)
  );

  if (!snapshot) {
    return <div className="text-gray-400">Loading...</div>;
  }

  return (
    <div className="bg-gray-800 rounded-lg p-6 space-y-6">
      {/* Header */}
      <div className="flex justify-between items-start">
        <div>
          <h2 className="text-xl font-bold text-white">
            {snapshot.companyName || snapshot.scripCode}
          </h2>
          <p className="text-gray-400">{snapshot.scripCode}</p>
        </div>
        <div className="text-right">
          <div className="text-2xl font-bold text-white">
            ₹{snapshot.currentPrice.toFixed(2)}
          </div>
          <div className={`
            px-3 py-1 rounded-full text-sm font-medium
            ${snapshot.state === 'WATCHING' ? 'bg-yellow-500/20 text-yellow-400' : ''}
            ${snapshot.state === 'IDLE' ? 'bg-gray-500/20 text-gray-400' : ''}
            ${snapshot.state === 'POSITIONED' ? 'bg-green-500/20 text-green-400' : ''}
          `}>
            {snapshot.state}
          </div>
        </div>
      </div>

      {/* State Flow Diagram */}
      <StateFlowDiagram currentState={snapshot.state} />

      {/* Fresh Market Data */}
      <FreshDataDisplay snapshot={snapshot} />

      {/* Active Setups with Condition Checks */}
      {snapshot.activeSetups?.map((setup, idx) => (
        <div key={idx} className="border border-gray-700 rounded-lg p-4">
          <div className="flex justify-between items-center mb-4">
            <div>
              <h3 className="font-semibold text-white">{setup.strategyId}</h3>
              <p className="text-sm text-gray-400">{setup.setupDescription}</p>
            </div>
            <span className={`
              px-2 py-1 rounded text-sm
              ${setup.direction === 'LONG' ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'}
            `}>
              {setup.direction} @ {setup.keyLevel.toFixed(2)}
            </span>
          </div>

          {/* Condition Checks */}
          <ConditionCheckList conditions={setup.conditions} />

          {/* Progress */}
          <div className="mt-4 pt-4 border-t border-gray-700">
            <div className="flex justify-between text-sm mb-2">
              <span className="text-gray-400">Entry Progress</span>
              <span className="text-white font-medium">{setup.progressPercent}%</span>
            </div>
            <div className="h-3 bg-gray-700 rounded-full overflow-hidden">
              <div
                className={`h-full transition-all duration-500 ${
                  setup.progressPercent >= 80 ? 'bg-gradient-to-r from-green-500 to-green-400' :
                  setup.progressPercent >= 50 ? 'bg-gradient-to-r from-yellow-500 to-yellow-400' :
                  'bg-gray-500'
                }`}
                style={{ width: `${setup.progressPercent}%` }}
              />
            </div>
            {setup.blockingCondition && (
              <p className="text-sm text-red-400 mt-2">
                ⚠ Blocked by: {setup.blockingCondition}
              </p>
            )}
          </div>
        </div>
      ))}
    </div>
  );
};
```

#### 3.4.5 Condition Check Visualization

```typescript
// components/StateMachine/ConditionCheckList.tsx

import React from 'react';
import { ConditionCheck } from '../../types/stateMachine';

interface Props {
  conditions: ConditionCheck[];
}

export const ConditionCheckList: React.FC<Props> = ({ conditions }) => {
  return (
    <div className="space-y-3">
      {conditions.map((condition, idx) => (
        <div key={idx} className="flex items-center gap-3">
          {/* Status Icon */}
          <div className={`
            w-6 h-6 rounded-full flex items-center justify-center
            ${condition.passed ? 'bg-green-500/20 text-green-400' : 'bg-gray-600 text-gray-400'}
          `}>
            {condition.passed ? '✓' : '○'}
          </div>

          {/* Condition Name */}
          <div className="flex-1">
            <div className="text-sm text-white">{condition.conditionName}</div>
            <div className="text-xs text-gray-400">{condition.displayValue}</div>
          </div>

          {/* Progress Bar */}
          <div className="w-24">
            <div className="h-2 bg-gray-700 rounded-full overflow-hidden">
              <div
                className={`h-full transition-all ${
                  condition.passed ? 'bg-green-500' : 'bg-gray-500'
                }`}
                style={{ width: `${condition.progressPercent}%` }}
              />
            </div>
          </div>

          {/* Percentage */}
          <div className={`
            text-xs font-medium w-10 text-right
            ${condition.passed ? 'text-green-400' : 'text-gray-400'}
          `}>
            {condition.progressPercent}%
          </div>
        </div>
      ))}
    </div>
  );
};
```

#### 3.4.6 State Flow Diagram

```typescript
// components/StateMachine/StateFlowDiagram.tsx

import React from 'react';
import { InstrumentState } from '../../types/stateMachine';

interface Props {
  currentState: InstrumentState;
}

const states: InstrumentState[] = ['IDLE', 'WATCHING', 'READY', 'POSITIONED', 'COOLDOWN'];

export const StateFlowDiagram: React.FC<Props> = ({ currentState }) => {
  const currentIndex = states.indexOf(currentState);

  return (
    <div className="bg-gray-900 rounded-lg p-4">
      <div className="flex items-center justify-between">
        {states.map((state, idx) => (
          <React.Fragment key={state}>
            {/* State Node */}
            <div className="flex flex-col items-center">
              <div className={`
                w-10 h-10 rounded-full flex items-center justify-center
                border-2 transition-all
                ${idx === currentIndex
                  ? 'bg-blue-500 border-blue-400 text-white scale-110'
                  : idx < currentIndex
                    ? 'bg-green-500/20 border-green-500 text-green-400'
                    : 'bg-gray-700 border-gray-600 text-gray-400'
                }
              `}>
                {idx === currentIndex ? '●' : idx < currentIndex ? '✓' : '○'}
              </div>
              <span className={`
                text-xs mt-2 font-medium
                ${idx === currentIndex ? 'text-blue-400' : 'text-gray-500'}
              `}>
                {state}
              </span>
            </div>

            {/* Connector Arrow */}
            {idx < states.length - 1 && (
              <div className={`
                flex-1 h-0.5 mx-2
                ${idx < currentIndex ? 'bg-green-500' : 'bg-gray-600'}
              `}>
                <div className="relative">
                  <div className={`
                    absolute right-0 top-1/2 -translate-y-1/2
                    w-0 h-0 border-l-4 border-y-4 border-y-transparent
                    ${idx < currentIndex ? 'border-l-green-500' : 'border-l-gray-600'}
                  `} />
                </div>
              </div>
            )}
          </React.Fragment>
        ))}
      </div>
    </div>
  );
};
```

---

## Part 4: Implementation Phases

### Phase 1: Backend Foundation (2-3 days)
1. Create data models in StreamingCandle
2. Implement StateSnapshotPublisher
3. Modify InstrumentStateManager to expose state
4. Create Kafka topics
5. Test publishing

### Phase 2: Dashboard Backend (2 days)
1. Create Kafka consumers
2. Implement StateMachineService
3. Create REST API endpoints
4. Set up WebSocket publishing
5. MongoDB schema for history

### Phase 3: Frontend Foundation (2-3 days)
1. Add TypeScript types
2. Create Zustand store
3. Update WebSocket hook
4. Create basic page structure

### Phase 4: UI Components (3-4 days)
1. State summary cards
2. Instrument list with state cards
3. Instrument detail panel
4. Condition check visualization
5. State flow diagram
6. Opportunity feed
7. Transition timeline

### Phase 5: Polish & Testing (2 days)
1. Animations and transitions
2. Error handling
3. Loading states
4. Mobile responsiveness
5. End-to-end testing

---

## Summary

This integration will provide:

1. **Real-time State Visibility**: See every instrument's current state (IDLE, WATCHING, etc.)

2. **Condition Progress**: Visual progress bars showing how close each setup is to triggering

3. **Fresh Data Confirmation**: Display that shows data is being evaluated fresh on each candle

4. **Opportunity Feed**: Ranked list of instruments closest to generating signals

5. **Historical Timeline**: Track state transitions over time

6. **Beautiful State Machine**: Visual flow diagram showing current position in the state machine

The architecture ensures:
- Real-time updates via WebSocket
- Historical data persistence in MongoDB
- Efficient state management with Zustand
- Type-safe development with TypeScript
