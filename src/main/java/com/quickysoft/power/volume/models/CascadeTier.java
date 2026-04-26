package com.quickysoft.power.volume.models;

/**
 * Identifies which tier a {@link VolumeSeries} belongs to
 * in the multi-granularity cascade materialization strategy.
 * <p>
 * A long-tenor PPA produces three series per trade leg, one per tier:
 * <ul>
 *   <li>NEAR_TERM — current week at contract base granularity (e.g. 15-min)</li>
 *   <li>MEDIUM_TERM — rest of month + M+1 + M+2 at DAILY granularity</li>
 *   <li>LONG_TERM — M+3 to delivery end at MONTHLY granularity</li>
 * </ul>
 * As time progresses, coarse intervals are disaggregated into finer ones
 * (LONG_TERM monthly → MEDIUM_TERM daily → NEAR_TERM base granularity).
 */
public enum CascadeTier {

    /** Current week at contract base granularity (MIN_5/MIN_15/MIN_30/HOURLY) */
    NEAR_TERM,

    /** Rest of month + M+1 + M+2 at DAILY granularity */
    MEDIUM_TERM,

    /** M+3 to delivery end at MONTHLY granularity */
    LONG_TERM
}
