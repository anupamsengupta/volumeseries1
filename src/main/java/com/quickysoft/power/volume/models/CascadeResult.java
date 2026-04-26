package com.quickysoft.power.volume.models;

/**
 * Result of building a cascade series — one series per tier.
 * Near-term is FULL (materialized upfront), medium and long-term start as PENDING.
 */
public record CascadeResult(
        VolumeSeries nearTerm,
        VolumeSeries mediumTerm,
        VolumeSeries longTerm
) {}
