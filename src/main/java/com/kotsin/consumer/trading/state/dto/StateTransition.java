package com.kotsin.consumer.trading.state.dto;

import com.kotsin.consumer.trading.state.InstrumentState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * StateTransition - Records a state change for an instrument.
 * Used for historical tracking and timeline visualization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateTransition {

    /** Scrip/instrument code */
    private String scripCode;

    /** Previous state */
    private InstrumentState fromState;

    /** New state */
    private InstrumentState toState;

    /** Reason for transition (e.g., "SETUP_DETECTED", "ENTRY_TRIGGERED", "STOP_LOSS_HIT") */
    private String reason;

    /** Timestamp of the transition */
    private long timestamp;

    /** Additional context/metadata */
    private Map<String, Object> metadata;
}
