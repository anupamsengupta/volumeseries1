package com.quickysoft.power.volume.models;

/**
 * Result of disaggregating coarse intervals into finer ones.
 * Source has the coarse interval removed; target has finer intervals appended.
 */
public record DisaggregationResult(
        VolumeSeries source,
        VolumeSeries target
) {}
