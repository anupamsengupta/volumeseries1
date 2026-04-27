package com.quickysoft.power.volume.models.enums;

/**
 * Tracks the materialization state of a volume series.
 * Used for the two-tier approach: short-term trades are FULL immediately,
 * long-tenor PPAs start as PARTIAL with rolling horizon extension.
 */
public enum MaterializationStatus {

    /** Not yet generated */
    PENDING,

    /** Rolling horizon: near-term materialized, far-dated as contract terms only */
    PARTIAL,

    /** All intervals generated */
    FULL,

    /** Generation failed, needs retry via DLQ */
    FAILED
}
