package com.quickysoft.power.volume.models.enums;

/**
 * Defines how the volume field on VolumeInterval is interpreted.
 *
 * EU power markets use both conventions depending on product type:
 * - Exchange-traded DA/ID: MW capacity (energy derived from duration)
 * - Some bilateral PPAs and tolerance band contracts: MWh per period
 */
public enum VolumeUnit {

    /**
     * Volume represents power capacity in MW.
     * Energy = volume × elapsed hours.
     * Standard for EPEX SPOT, Nord Pool, EEX DA/ID products.
     */
    MW_CAPACITY,

    /**
     * Volume represents energy delivered per period in MWh.
     * Energy = volume (regardless of interval duration).
     * Used in some bilateral PPAs, tolerance band settlements,
     * and generation-following contracts where the forecast
     * provides MWh per interval directly.
     */
    MWH_PER_PERIOD
}