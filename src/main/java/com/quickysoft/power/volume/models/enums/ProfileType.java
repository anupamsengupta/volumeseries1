package com.quickysoft.power.volume.models.enums;

/**
 * Delivery profile types for EU physical power contracts.
 */
public enum ProfileType {

    /** Flat volume 24/7 */
    BASELOAD,

    /** Mon-Fri 08:00-20:00 (EPEX SPOT definition) */
    PEAKLOAD,

    /** Inverse of peakload */
    OFFPEAK,

    /** Custom volume per interval from shaping table */
    SHAPED,

    /** Named block product (e.g., HH01-HH04) */
    BLOCK,

    /** Linked to renewable generation forecast/actuals */
    GENERATION_FOLLOWING
}
