package com.quickysoft.power.volume.models;

/**
 * Status of an individual delivery interval within a materialized volume series.
 */
public enum IntervalStatus {

    /** Exchange-confirmed or bilateral agreed */
    CONFIRMED,

    /** Derived from formula, not yet confirmed */
    ESTIMATED,

    /** Subject to reconciliation */
    PROVISIONAL,

    /** Amendment cancelled this interval */
    CANCELLED
}
