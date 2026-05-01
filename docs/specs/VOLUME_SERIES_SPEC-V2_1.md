# Volume Series Domain Model — Specification

**Module:** `power-volume-series`
**Group:** `com.quickysoft.power`
**Domain:** EU Physical Power Trade Capture (CTRM/ETRM)
**Version:** 2.1.0-SNAPSHOT
**Date:** May 2026

### Change Log (V2.1 from V2.0)

| Section | Change | Rationale |
|---|---|---|
| 3.2.9 BucketType (NEW) | New enum: CONTRACTUAL / PLAN / ACTUAL | Captures the lifecycle bucket orthogonal to cascade tier |
| 3.2.10 ScalarColumn (was 3.2.9) | Renumbered; added `bucketType` population rules per scalar | Scalars are now bucket-aware |
| 3.3.1 VolumeSeries | Removed `nearTermBoundary`, `mediumTermBoundary`; cascade boundaries are implicit from contractual interval tiers | Contractual intervals are immutable; boundaries are a derived concept |
| 3.3.2 VolumeInterval | Added `bucketType` field | Distinguishes contractual source-of-truth from derived plan and metered actuals |
| 4.5 Granularity Cascade | Disaggregation redefined as **derivation**: creates PLAN rows alongside immutable CONTRACTUAL rows instead of replacing them | Preserves contractual quantities as trade-capture source of truth |
| 4.6 Data Retention Lifecycle | Updated row count estimates for three-bucket model | 3× multiplier on settled months (contractual + plan + actual) |
| 6.8 Scalar Disaggregation Invariant | Updated: applies to PLAN derivation; CONTRACTUAL intervals are never modified | Immutable contractual layer |
| 6.9 Bucket Coexistence Invariant (NEW) | CONTRACTUAL rows are immutable; PLAN rows are derived; ACTUAL rows are metered; all coexist for the same delivery period | Formalizes the three-bucket model |
| 6.10 Contractual Energy Coverage (NEW) | Sum of contractual energy must equal VolumeFormula-derived total for the full delivery window | Ensures no gaps or overlaps in the contractual layer |
| 7.4 VolumeSeriesService | Disaggregation methods create PLAN rows; CONTRACTUAL rows untouched; new `getContractualIntervals()`, `getPlanIntervals()`, `getActualIntervals()` | Reflects derivation model |
| 7.1 Test Structure | Added bucket coexistence tests, contractual immutability tests | Validates three-bucket model |

### Change Log (V2.0 from V1.1)

| Section | Change | Rationale |
|---|---|---|
| 3.2.6 CascadeTier | Windows redefined: NEAR_TERM=M+M+1+M+2, MEDIUM_TERM=end-of-M+2 to +1yr, LONG_TERM=+1yr to end | Aligns with operational scheduling; monthly disaggregation cron instead of weekly |
| 3.2.7 WeekendOn | **Removed entirely** | Near-term boundary is now end-of-M+2, not a weekend day |
| 3.2.8 ScalarClassification (NEW) | New enum: RATE vs ABSOLUTE | Governs disaggregation behavior for wide-table scalar columns |
| 3.2.9 ScalarColumn (NEW) | Metadata registry for wide-table columns | Maps each scalar to its classification and cascade population rules |
| 3.3.1 VolumeSeries | `granularity` → `baseGranularity`; `cascadeTier` removed from series level | Single series per trade with mixed-granularity intervals |
| 3.3.2 VolumeInterval | Added `cascadeTier`, `effectiveGranularity`, and 25 wide-table scalar columns | Supports single-series model with per-interval granularity and co-located scalars |
| 4.5 Granularity Cascade | **Major rewrite**: single VolumeSeries per trade; "coarser of tier default and trade granularity" rule; disaggregation with scalar classification | Eliminates three-series-per-trade complexity |
| 4.6 Data Retention Lifecycle (NEW) | Three-tier storage: Hot/Warm/Purge with TimescaleDB compression | REMIT 5yr, MiFID II 7yr retention; GDPR-mandated purge |
| 6.2 Contiguity | Relaxed: contiguity within each cascade tier, boundary alignment across tiers | Mixed granularities in single series cannot be globally contiguous |
| 6.7 Cascade Granularity Rule (NEW) | `effectiveGranularity = max(tierDefault, baseGranularity)` | Prevents finer-than-trade granularity in any tier |
| 6.8 Scalar Disaggregation Invariant (NEW) | RATE scalars copy; ABSOLUTE scalars pro-rate by energy weight | Ensures financial consistency during disaggregation |
| 7.4 VolumeSeriesService | Updated: `buildCascadeSeries` returns single series; removed `CascadeResult`; disaggregation methods updated for scalar handling | Reflects single-series model |
| 7.1 Test Structure | Updated cascade test expectations | Reflects single-series model and scalar disaggregation |

---

## 1. Purpose

This specification defines the domain model for representing, materializing, and validating delivery volume series in EU physical power trading. The model supports the full spectrum of traded products — from single-interval intraday blocks to multi-year Power Purchase Agreements (PPAs) — and is designed to operate within a microservices architecture where trade capture, detail generation, position calculation, and pricing are independent, event-driven services.

The core design principle is **"store the recipe, not the meal"**: the contractual volume definition (the `VolumeFormula`) is the source of truth, and the materialized interval rows (the `VolumeInterval` list) are a derived, regenerable artifact. This separation enables a two-tier materialization strategy that keeps trade capture instantaneous regardless of contract tenor.

---

## 2. Domain Context

### 2.1 EU Physical Power Market Characteristics

EU physical power markets operate on delivery intervals that vary by market and product type. The German bidding zone (DE-LU) uses 15-minute intervals for intraday continuous trading (EPEX SPOT / XBID), while day-ahead auctions clear on hourly or 15-minute products depending on the exchange. Bilateral OTC contracts and PPAs may use 30-minute, hourly, daily, or monthly granularity.

All delivery times are expressed in the delivery timezone (typically `Europe/Berlin` for CET/CEST), not UTC. This is critical because:

- CET/CEST observes two DST transitions per year
- The October fall-back creates a 25-hour day (02:00–03:00 occurs twice)
- The March spring-forward creates a 23-hour day (02:00–03:00 is skipped)
- Energy settlement is based on actual elapsed time, not nominal hours

### 2.2 Product Types

| Product | Typical Granularity | Typical Tenor | Materialization |
|---|---|---|---|
| DA Auction (EPEX SPOT) | 15 min | Single day | Full, immediate |
| Intraday Continuous | 15 min | Single interval to hours | Full, immediate |
| Short Block | 30 min / 1 hr | 1–6 hours | Full, immediate |
| Monthly Forward | Monthly | 1 month | Full, immediate |
| Annual Baseload | Monthly / Hourly | 1 year | Rolling horizon |
| PPA (Solar/Wind) | 15 min | 3–15 years | Rolling horizon |

### 2.3 Position in the Trade Capture Pipeline

The volume series is generated by the **Detail Generation Service** after a trade is captured and the `trade.captured` event is published to Kafka. It sits between trade capture (synchronous, ~200ms) and position calculation (async, downstream consumer).

```
Trade Capture → trade.captured → Detail Generation Service → trade.details.generated → Position Service
```

---

## 3. Domain Model

### 3.1 Entity Relationship

```
VolumeSeries (root aggregate)
├── VolumeFormula (1:1, the "recipe")
│   ├── ShapingEntry (0:N, time-of-use blocks)
│   └── SeasonalAdjustment (0:N, year/season modifiers)
└── VolumeInterval (0:N, materialized delivery intervals)
```

### 3.2 Enumerations

#### 3.2.1 TimeGranularity

Defines the interval width for delivery schedule decomposition.

| Value | Duration | Fixed? | Notes |
|---|---|---|---|
| `MIN_5` | 5 minutes | Yes | Used in some intraday markets |
| `MIN_15` | 15 minutes | Yes | EPEX SPOT standard, XBID |
| `MIN_30` | 30 minutes | Yes | UK market (ELEXON), some OTC |
| `HOURLY` | 60 minutes | Yes | DA auction, bilateral OTC |
| `DAILY` | Variable | No | Settlement period granularity; see note below |
| `MONTHLY` | Variable | No | Forward contracts, PPAs |

`DAILY` has variable duration due to DST transitions (23 or 25 hours on transition days). Like `MONTHLY`, it stores `null` for `fixedDuration` and throws `UnsupportedOperationException` from `getFixedDuration()`. The materialization loop uses `ZonedDateTime.plusDays(1)` for DST-safe day arithmetic, producing correct interval boundaries and energy values on DST transition days. `isFixedDuration()` returns `false` for both `DAILY` and `MONTHLY`.

`MONTHLY` has variable duration (28–31 days) and throws `UnsupportedOperationException` from `getFixedDuration()`. Callers must use `YearMonth` arithmetic instead.

The `isSubDaily()` method returns `true` for `MIN_5` through `HOURLY`. The `isFixedDuration()` method returns `true` for `MIN_5` through `HOURLY` and `false` for `DAILY` and `MONTHLY`.

#### 3.2.2 ProfileType

| Value | Description | Formula Fields Used |
|---|---|---|
| `BASELOAD` | Flat volume 24/7 | `baseVolume` |
| `PEAKLOAD` | Mon–Fri 08:00–20:00 (market-specific) | `baseVolume`, `calendarId` |
| `OFFPEAK` | Inverse of peakload | `baseVolume`, `calendarId` |
| `SHAPED` | Custom volume per time-of-use block | `shapingEntries` |
| `BLOCK` | Named block product (e.g., HH01–HH04) | `baseVolume` |
| `GENERATION_FOLLOWING` | Linked to renewable forecast | `forecastSourceId`, `forecastMultiplier` |

#### 3.2.3 MaterializationStatus

Tracks the two-tier materialization state of the volume series.

| Value | Meaning | `materializedThrough` |
|---|---|---|
| `PENDING` | Not yet generated | null |
| `PARTIAL` | Near-term materialized, far-dated as contract terms | Set (e.g., `2026-07`) |
| `FULL` | All intervals generated | null |
| `FAILED` | Generation failed, awaiting DLQ retry | null |

#### 3.2.4 IntervalStatus

| Value | Meaning |
|---|---|
| `CONFIRMED` | Exchange-confirmed or bilateral agreed |
| `ESTIMATED` | Derived from formula, not yet confirmed |
| `PROVISIONAL` | Subject to reconciliation |
| `CANCELLED` | Amendment cancelled this interval |

#### 3.2.5 VolumeUnit

Defines how the `volume` field on `VolumeInterval` is interpreted. EU power markets use both conventions depending on product type.

| Value | Meaning | Energy Derivation |
|---|---|---|
| `MW_CAPACITY` | Volume represents power capacity in MW | `energy = volume × elapsed hours` |
| `MWH_PER_PERIOD` | Volume represents energy delivered per period in MWh | `energy = volume` (regardless of interval duration) |

`MW_CAPACITY` is the standard for exchange-traded DA/ID products (EPEX SPOT, Nord Pool, EEX). `MWH_PER_PERIOD` is used in some bilateral PPAs, tolerance band settlements, and generation-following contracts where the forecast provides MWh per interval directly.

#### 3.2.6 CascadeTier

Identifies which tier a `VolumeInterval` belongs to in the multi-granularity cascade materialization strategy (Section 4.5). A single `VolumeSeries` contains intervals across all three tiers; each interval carries its own `cascadeTier` and `effectiveGranularity`.

| Value | Window | Default Granularity | Effective Granularity | Description |
|---|---|---|---|---|
| `NEAR_TERM` | Current month + M+1 + M+2 | Contract base (e.g., MIN_15) | `max(base, base)` = base | Materialized upfront, status=FULL for this tier |
| `MEDIUM_TERM` | End of M+2 to +1 year from M+2 | DAILY | `max(DAILY, base)` | Generated via Kafka chunks |
| `LONG_TERM` | +1 year from M+2 to delivery end | MONTHLY | `max(MONTHLY, base)` | Generated via Kafka chunks |

The effective granularity for each tier is `max(tierDefault, baseGranularity)` where `max` selects the **coarser** granularity. This means:

- A 15-min PPA: near=MIN_15, mid=DAILY, long=MONTHLY (cascade applies fully)
- An hourly bilateral: near=HOURLY, mid=DAILY, long=MONTHLY (cascade applies fully)
- A daily settlement contract: near=DAILY, mid=DAILY, long=MONTHLY (only long-term cascades)
- A monthly forward: near=MONTHLY, mid=MONTHLY, long=MONTHLY (cascade is a no-op; all intervals are MONTHLY)

A long-tenor PPA produces a single `VolumeSeries` containing CONTRACTUAL intervals at up to three different granularities. As time progresses, PLAN intervals at finer granularity are **derived from** CONTRACTUAL intervals (LONG_TERM monthly → MEDIUM_TERM daily PLAN → NEAR_TERM base granularity PLAN). The CONTRACTUAL intervals remain untouched. Non-cascade series (short-term DA/intraday trades) have `cascadeTier = null` on all intervals and a single uniform granularity.

The disaggregation cron runs **monthly** (not weekly), promoting one month of long-term intervals to medium-term daily, and the earliest medium-term daily intervals to near-term base granularity.

#### 3.2.7 ScalarClassification

Governs how a scalar column behaves during disaggregation (monthly → daily → base granularity). This classification is critical for financial consistency when breaking coarse intervals into finer ones.

| Value | Disaggregation Rule | Examples |
|---|---|---|
| `RATE` | **Copy directly** to each child interval. The value represents a per-unit rate that applies uniformly. | `basePriceEurMwh`, `greenPremiumEurMwh`, `contractedMw`, `planMw`, `actualMw`, `nominatedMw` |
| `ABSOLUTE` | **Pro-rate by energy weight** across child intervals. The value represents a total amount for the period. `child.value = parent.value × (child.energy / parent.energy)` | `balancingCostEur`, `imbalancePenaltyEur`, `congestionRentEur`, `profileCostEur` |

The `RATE` vs `ABSOLUTE` distinction maps directly to the financial concept: rates are intensive properties (€/MWh, MW capacity), absolutes are extensive properties (total € for a period).

#### 3.2.8 BucketType

Identifies the lifecycle bucket of a `VolumeInterval`. Orthogonal to `CascadeTier` — a delivery period can have intervals in multiple buckets simultaneously, each serving a different purpose.

| Value | Mutability | Source | Granularity | Description |
|---|---|---|---|---|
| `CONTRACTUAL` | **Immutable** — never modified or deleted after trade capture | Trade capture + cascade materialization | As materialized by cascade tier (monthly, daily, or base) | The contracted quantity as agreed at trade execution. Source of truth for what was traded. |
| `PLAN` | **Mutable** — updated during scheduling and nomination | Disaggregation from CONTRACTUAL intervals; scheduling adjustments | Always at base granularity (e.g., 15-min) | The operational plan: what we intend to deliver/receive. Updated as nominations and schedules change. |
| `ACTUAL` | **Append-only** — written post-delivery, corrected during reconciliation | TSO metering data, settlement feeds | Always at base granularity (e.g., 15-min) | What was actually delivered. Arrives after the delivery period passes. |

**Coexistence rule:** For a given delivery period (e.g., 01 Aug 2026 00:00–00:15), all three bucket types can coexist in the same `VolumeSeries`:

- 1 CONTRACTUAL row (at the cascade tier's granularity — may be daily or monthly, covering a wider window)
- 1 PLAN row (at 15-min, derived from the contractual)
- 1 ACTUAL row (at 15-min, from metering)

The CONTRACTUAL row may cover a different (wider) interval than the PLAN and ACTUAL rows. For example, a CONTRACTUAL daily row covers all of Aug 1, while the PLAN and ACTUAL rows cover individual 15-min slots within that day. This is not a conflict — they are different views of the same delivery period at different granularities.

#### 3.2.9 ScalarColumn

Metadata registry for the wide-table scalar columns on `VolumeInterval`. Each entry defines the column's classification, unit, which cascade tiers it is populated in, and which bucket types it applies to.

| Column Name | Classification | Unit | Populated In (Tier) | Populated In (Bucket) | Description |
|---|---|---|---|---|---|
| `contractedMw` | `RATE` | MW | All tiers | CONTRACTUAL only | Contracted capacity from VolumeFormula |
| `planMw` | `RATE` | MW | NEAR_TERM, MEDIUM_TERM | PLAN only | Planned/scheduled volume |
| `actualMw` | `RATE` | MW | NEAR_TERM only | ACTUAL only | Metered actual delivery |
| `nominatedMw` | `RATE` | MW | NEAR_TERM only | PLAN (updated pre-delivery) | Nominated volume for TSO |
| `basePriceEurMwh` | `RATE` | EUR/MWh | All tiers | CONTRACTUAL, PLAN | Base contract price |
| `greenPremiumEurMwh` | `RATE` | EUR/MWh | All tiers | CONTRACTUAL, PLAN | Green certificate premium |
| `toleranceFloorMw` | `RATE` | MW | All tiers | CONTRACTUAL only | Min delivery tolerance |
| `toleranceCeilingMw` | `RATE` | MW | All tiers | CONTRACTUAL only | Max delivery tolerance |
| `forecastMw` | `RATE` | MW | NEAR_TERM, MEDIUM_TERM | PLAN only | Generation forecast (GENERATION_FOLLOWING) |
| `balancingCostEur` | `ABSOLUTE` | EUR | NEAR_TERM only | ACTUAL only | Balancing market cost for period |
| `imbalancePenaltyEur` | `ABSOLUTE` | EUR | NEAR_TERM only | ACTUAL only | Imbalance settlement penalty |
| `congestionRentEur` | `ABSOLUTE` | EUR | NEAR_TERM only | ACTUAL only | Cross-border congestion rent |
| `profileCostEur` | `ABSOLUTE` | EUR | NEAR_TERM, MEDIUM_TERM | PLAN, ACTUAL | Profile shaping cost allocation |
| `customScalars` | N/A | JSONB | All tiers | All buckets | Tenant-specific dynamic scalars |

Columns not populated for a given `(tier, bucket)` combination are stored as `NULL`. TimescaleDB compression eliminates NULL column overhead in compressed chunks.

### 3.3 Core Entities

#### 3.3.1 VolumeSeries

Root aggregate. One trade version has exactly one `VolumeSeries`, which contains intervals across all cascade tiers at their respective effective granularities.

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `tradeId` | `String` | FK to parent trade |
| `tradeLegId` | `String` | FK to parent trade leg |
| `tradeVersion` | `int` | Trade version (optimistic lock) |
| `volumeUnit` | `VolumeUnit` | How to interpret volume on intervals (MW capacity vs MWh per period) |
| `deliveryStart` | `ZonedDateTime` | Start of delivery window (inclusive) |
| `deliveryEnd` | `ZonedDateTime` | End of delivery window (exclusive) |
| `deliveryTimezone` | `ZoneId` | Delivery timezone (e.g., `Europe/Berlin`) |
| `baseGranularity` | `TimeGranularity` | Contract-level interval width (the finest granularity this trade uses) |
| `profileType` | `ProfileType` | Delivery profile classification |
| `materializationStatus` | `MaterializationStatus` | Two-tier tracking |
| `materializedThrough` | `YearMonth` | Last fully materialized month (null if FULL/PENDING) |
| `totalExpectedIntervals` | `int` | Pre-calculated for progress tracking (CONTRACTUAL intervals only; PLAN and ACTUAL intervals are tracked separately) |
| `materializedIntervalCount` | `int` | Current count of materialized CONTRACTUAL intervals |
| `transactionTime` | `Instant` | Bi-temporal: when system recorded this |
| `validTime` | `Instant` | Bi-temporal: when economically effective |
| `intervals` | `List<VolumeInterval>` | Ordered by `intervalStart`, mixed granularities across cascade tiers |
| `formula` | `VolumeFormula` | Contract-level volume definition |

**Key Methods:**

- `calculateExpectedIntervals()`: Computes total expected CONTRACTUAL intervals across all cascade tiers at their effective granularities. For non-cascade trades, walks the full delivery timeline at `baseGranularity`. For cascade trades, computes the cascade boundaries from `deliveryStart` (end of M+2 for near/mid boundary, +1yr for mid/long boundary), then sums: near-term CONTRACTUAL intervals at `baseGranularity` + medium-term CONTRACTUAL intervals at effective mid granularity + long-term CONTRACTUAL intervals at effective long granularity. Uses `ZonedDateTime` arithmetic for DST correctness.
- `getUnmaterializedWindow()`: Returns a `DeliveryWindow` representing the portion of the delivery period not yet materialized. Returns `null` if fully materialized. Used by the Position Service to determine when contract-level (aggregate) position is needed vs detail-level (interval) position.
- `getIntervalsForTier(CascadeTier tier)`: Returns intervals filtered by `cascadeTier`. Returns all intervals if `tier` is `null`.

#### 3.3.2 VolumeInterval

Single delivery interval within the materialized series. In a cascade series, intervals at different granularities coexist within the same `VolumeSeries`, distinguished by `cascadeTier` and `effectiveGranularity`.

**Core Fields:**

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `seriesId` | `UUID` | FK to parent VolumeSeries |
| `bucketType` | `BucketType` | CONTRACTUAL, PLAN, or ACTUAL — determines mutability and source |
| `cascadeTier` | `CascadeTier` | Which cascade tier produced this interval (lineage: which algorithm materialized it) |
| `effectiveGranularity` | `TimeGranularity` | Actual granularity of this interval (may differ from series `baseGranularity` in cascade tiers) |
| `intervalStart` | `ZonedDateTime` | Start (inclusive), in delivery timezone |
| `intervalEnd` | `ZonedDateTime` | End (exclusive), in delivery timezone |
| `volume` | `BigDecimal` | Volume value; interpretation depends on parent series `volumeUnit` |
| `energy` | `BigDecimal` | Derived MWh (see `calculateEnergy`) |
| `status` | `IntervalStatus` | Lifecycle status |
| `chunkMonth` | `YearMonth` | Which materialization chunk produced this |

**Wide-Table Scalar Columns (RATE classification — copy on disaggregation):**

| Field | Type | Description |
|---|---|---|
| `contractedMw` | `BigDecimal` | Contracted capacity from VolumeFormula |
| `planMw` | `BigDecimal` | Planned/scheduled volume (null in LONG_TERM) |
| `actualMw` | `BigDecimal` | Metered actual delivery (null except NEAR_TERM settled) |
| `nominatedMw` | `BigDecimal` | Nominated volume for TSO (null except NEAR_TERM) |
| `basePriceEurMwh` | `BigDecimal` | Base contract price per MWh |
| `greenPremiumEurMwh` | `BigDecimal` | Green certificate premium per MWh |
| `toleranceFloorMw` | `BigDecimal` | Minimum delivery tolerance band |
| `toleranceCeilingMw` | `BigDecimal` | Maximum delivery tolerance band |
| `forecastMw` | `BigDecimal` | Generation forecast (GENERATION_FOLLOWING profile) |

**Wide-Table Scalar Columns (ABSOLUTE classification — pro-rate by energy weight on disaggregation):**

| Field | Type | Description |
|---|---|---|
| `balancingCostEur` | `BigDecimal` | Balancing market cost for this period |
| `imbalancePenaltyEur` | `BigDecimal` | Imbalance settlement penalty |
| `congestionRentEur` | `BigDecimal` | Cross-border congestion rent |
| `profileCostEur` | `BigDecimal` | Profile shaping cost allocation |

**Dynamic Overflow:**

| Field | Type | Description |
|---|---|---|
| `customScalars` | `Map<String, BigDecimal>` | Tenant-specific scalars (persisted as JSONB) |

**Key Methods:**

- `calculateEnergy(VolumeUnit volumeUnit)`: Derives energy (MWh) based on the `VolumeUnit` mode:
  - **`MW_CAPACITY`**: Derives energy from volume (MW) and actual elapsed duration. Uses `Instant`-based arithmetic (`intervalStart.toInstant()` to `intervalEnd.toInstant()`) to correctly handle DST transitions. This is critical: a naive `volume × 1 hour` calculation produces wrong energy values on the two DST transition days per year.
  - **`MWH_PER_PERIOD`**: Returns the volume value directly (it already represents MWh).

**Energy Calculation Formula (MW_CAPACITY mode):**

```
seconds = Duration.between(intervalStart.toInstant(), intervalEnd.toInstant()).getSeconds()
hours = seconds / 3600 (high intermediate precision, HALF_UP)
energy = (volume × hours).setScale(6, HALF_UP)
```

The intermediate hours division uses high precision (scale 20) to avoid rounding drift when summing many short intervals (e.g., 12 five-minute intervals must produce the same total energy as 1 hourly interval). The final energy result is rounded to scale 6.

**Energy Calculation (MWH_PER_PERIOD mode):**

```
energy = volume
```

#### 3.3.3 VolumeFormula

The "recipe" — contract-level volume definition that serves as source of truth.

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `seriesId` | `UUID` | FK to parent VolumeSeries |
| `baseVolume` | `BigDecimal` | Base volume in MW (for flat profiles) |
| `minVolume` | `BigDecimal` | Tolerance band floor (MW) |
| `maxVolume` | `BigDecimal` | Tolerance band cap (MW) |
| `shapingEntries` | `List<ShapingEntry>` | Time-of-use volume blocks (null if baseload) |
| `forecastSourceId` | `String` | External generation forecast reference |
| `forecastMultiplier` | `BigDecimal` | Fraction of forecast (e.g., 0.9 = 90%) |
| `seasonalAdjustments` | `List<SeasonalAdjustment>` | Year/season volume modifiers |
| `calendarId` | `String` | Reference to holiday/trading calendar |

#### 3.3.4 ShapingEntry

Defines volume for a specific time-of-use block within a shaped delivery profile.

| Field | Type | Description |
|---|---|---|
| `applicableDays` | `Set<DayOfWeek>` | Which days this block applies to |
| `blockStart` | `LocalTime` | Block start (inclusive) |
| `blockEnd` | `LocalTime` | Block end (exclusive) |
| `volume` | `BigDecimal` | Volume in MW for this block |
| `appliesToHolidays` | `boolean` | Whether block applies on public holidays |
| `validFromMonth` | `Month` | Seasonal start (null = all months) |
| `validToMonth` | `Month` | Seasonal end |

Uses `DayOfWeek` and time ranges instead of market-specific block names (e.g., EPEX "Peak") so the model works across markets (EPEX SPOT, Nord Pool, EEX, bilateral OTC with custom blocks).

#### 3.3.5 SeasonalAdjustment

Year-over-year or seasonal volume modifiers for long-tenor contracts.

| Field | Type | Description |
|---|---|---|
| `fromMonth` | `Month` | Adjustment period start |
| `toMonth` | `Month` | Adjustment period end |
| `fromYear` | `Integer` | Year start (null = all years) |
| `toYear` | `Integer` | Year end |
| `multiplier` | `BigDecimal` | Multiplicative factor (e.g., 1.02 = +2%) |
| `absoluteAdj` | `BigDecimal` | Additive MW adjustment |

Application order when both are set: `adjustedVolume = (baseVolume × multiplier) + absoluteAdj`.

#### 3.3.6 DeliveryWindow

Value object representing a contiguous delivery time window.

| Field | Type | Description |
|---|---|---|
| `start` | `ZonedDateTime` | Window start (inclusive) |
| `end` | `ZonedDateTime` | Window end (exclusive) |

Constructor validates that `end` is after `start`, throwing `IllegalArgumentException` otherwise. The `duration()` method uses `Instant`-based arithmetic for DST correctness.

---

## 4. Two-Tier Materialization Strategy

### 4.1 Problem Statement

A 10-year PPA at 15-minute granularity produces ~3.5 million intervals. Generating all rows at trade capture time is unacceptable — it would block the async pipeline for minutes, and most of those intervals won't be needed for months or years.

### 4.2 Solution: Rolling Horizon

The Detail Generation Service classifies each incoming `trade.captured` event by product tenor and applies one of two strategies:

**Short-Term (DA / Intraday / Short Block)**

- Generate all intervals in a single pass
- Set `materializationStatus = FULL`
- Emit `trade.details.generated` with `scope = FULL`
- Typical interval count: 1–96

**Long-Term (PPA / Multi-year)**

- Materialize a rolling window: M+1 through M+3 (configurable)
- Set `materializationStatus = PARTIAL`, `materializedThrough = M+3`
- Emit `trade.details.generated` with `scope = PARTIAL`
- Enqueue monthly chunk jobs for remaining months as separate Kafka messages
- A monthly cron job extends the materialization window as time progresses

### 4.3 Chunk Processing

Each monthly chunk is a separate Kafka message (`trade.detail.chunk.requested`) containing `{tradeId, tradeLegId, monthStart, monthEnd}`. This provides:

- **Parallelism**: Multiple months can be generated concurrently
- **Partial failure isolation**: If month 37 fails, months 1–36 remain valid
- **Progress visibility**: `materializedIntervalCount / totalExpectedIntervals` gives progress percentage
- **Independent retry**: Failed chunks go to a DLQ and can be retried without regenerating the entire series

### 4.4 Impact on Downstream Services

**Position Service** operates at two tiers:

| Tier | Source Data | Purpose |
|---|---|---|
| Detail-level position | Materialized `VolumeInterval` rows | Per-interval net volume for nomination and scheduling |
| Contract-level position | `VolumeFormula` terms for unmaterialized period | Aggregate exposure for risk reporting |

The Position Service uses `VolumeSeries.getUnmaterializedWindow()` to determine which tier to apply.

### 4.5 Granularity Cascade

For long-tenor PPAs (e.g., 10-year at 15-min granularity = ~3.5M intervals at full resolution), a multi-granularity cascade reduces the materialization footprint by using coarser granularity for far-dated periods. All intervals reside in a **single `VolumeSeries`** per trade, tagged with their `cascadeTier` and `effectiveGranularity`.

**Three-Tier Layout** (example: 10-year PPA, base=MIN_15, trade captured May 1 2026):

| Tier | Window | Effective Granularity | Intervals | Notes |
|---|---|---|---|---|
| NEAR_TERM | May 1 2026 → July 31 2026 (end of M+2) | MIN_15 (= max(MIN_15, MIN_15)) | ~8,736 | Materialized upfront |
| MEDIUM_TERM | Aug 1 2026 → Jul 31 2027 (+1yr from M+2) | DAILY (= max(DAILY, MIN_15)) | ~365 | Generated via Kafka chunks |
| LONG_TERM | Aug 1 2027 → May 1 2036 (delivery end) | MONTHLY (= max(MONTHLY, MIN_15)) | ~105 | Generated via Kafka chunks |

**Cascade Granularity Rule:** `effectiveGranularity = max(tierDefault, baseGranularity)` where `max` selects the **coarser** interval width. This prevents generating intervals finer than the trade's own granularity. A monthly forward contract produces MONTHLY intervals in all three tiers (cascade is a no-op).

**Boundary Computation (used at materialization time, not stored on entity):**

The cascade boundaries are computed from `deliveryStart` at trade capture time and used to determine which CONTRACTUAL intervals go into which tier. These are algorithmic boundaries — they are not stored as fields on `VolumeSeries` because the CONTRACTUAL intervals themselves are the permanent record of the cascade structure.

```
nearMidBoundary = deliveryStart
    .with(TemporalAdjusters.lastDayOfMonth())  // end of current month
    .plusMonths(2)                               // M+2
    .plusDays(1)                                  // start of M+3
    .atStartOfDay(deliveryTimezone)
    
midLongBoundary = nearMidBoundary.plusYears(1)

// Clamp to delivery window
nearMidBoundary  = min(nearMidBoundary, deliveryEnd)
midLongBoundary = min(midLongBoundary, deliveryEnd)
```

If `deliveryEnd` falls within the near-term window, the series has only NEAR_TERM CONTRACTUAL intervals and no cascade. If it falls within the medium-term window, only NEAR_TERM and MEDIUM_TERM tiers exist.

**Generation Flow:**
1. `buildCascadeSeries()` creates a single `VolumeSeries` with:
   - Near-term CONTRACTUAL intervals materialized immediately (FULL for this tier) at `baseGranularity`, each tagged `cascadeTier=NEAR_TERM`, `bucketType=CONTRACTUAL`
   - `materializationStatus = PARTIAL` (medium and long-term CONTRACTUAL intervals not yet generated)
2. Medium-term daily intervals are generated via Kafka chunk messages, each tagged `cascadeTier=MEDIUM_TERM`, `effectiveGranularity=DAILY`
3. Long-term monthly intervals are generated via Kafka chunk messages, each tagged `cascadeTier=LONG_TERM`, `effectiveGranularity=MONTHLY`
4. When all chunks complete, `materializationStatus → FULL`

**Monthly Disaggregation Cron (Derivation, NOT Replacement):**

A monthly cron job derives PLAN intervals from CONTRACTUAL intervals as delivery approaches. **CONTRACTUAL intervals are never modified or deleted.**

- `deriveDaily(month)`: Reads the LONG_TERM monthly CONTRACTUAL interval for `month`. Creates MEDIUM_TERM daily PLAN intervals derived from it. RATE scalars are copied; ABSOLUTE scalars are pro-rated by energy weight. The original monthly CONTRACTUAL interval remains untouched.
- `deriveBaseGranularity(fromDate, toDate)`: Reads the MEDIUM_TERM daily CONTRACTUAL (or previously derived daily PLAN) intervals in `[fromDate, toDate)`. Creates NEAR_TERM base-granularity PLAN intervals derived from them. Same scalar derivation rules apply. Source intervals remain untouched.
- Both methods create new rows with `bucketType=PLAN` and `cascadeTier` set to the tier they were derived into (not the source tier).

**What each method produces:**

| Method | Source | Creates | cascadeTier on new rows | bucketType on new rows |
|---|---|---|---|---|
| `deriveDaily(month)` | 1 CONTRACTUAL monthly (LONG_TERM) | ~30 PLAN daily intervals | MEDIUM_TERM | PLAN |
| `deriveBaseGranularity(from, to)` | ~30 CONTRACTUAL daily (MEDIUM_TERM) | ~2,880 PLAN 15-min intervals | NEAR_TERM | PLAN |

**Cascade Tier on PLAN intervals:** The `cascadeTier` on a derived PLAN row records **where in the cascade it was derived into**, not where it came from. A PLAN daily interval derived from a LONG_TERM monthly contractual gets `cascadeTier=MEDIUM_TERM`. A PLAN 15-min interval derived from a MEDIUM_TERM daily contractual gets `cascadeTier=NEAR_TERM`. This preserves the lineage: you know which algorithm path produced the row.

**Actual Intervals:** ACTUAL intervals arrive from TSO metering feeds post-delivery. They are always at base granularity (15-min), always `bucketType=ACTUAL`, and always `cascadeTier=NEAR_TERM`. They are written by the settlement/metering integration service, not by the disaggregation cron.

**Scalar Derivation Rules (unchanged from V2.0, but now applied to PLAN creation):**

When deriving PLAN intervals from a CONTRACTUAL interval:

| Classification | Rule | Example |
|---|---|---|
| `RATE` | Copy `parent.value` to every child | Monthly `contractedMw=50` → each daily plan interval gets `planMw=50` |
| `ABSOLUTE` | `child.value = parent.value × (child.energy / parent.energy)` | Monthly `profileCostEur=3000` with 30 days → each day gets `profileCostEur ≈ 100` (weighted by actual daily energy, not uniform) |

The energy-weighted pro-ration for ABSOLUTE scalars accounts for DST transition days (23h or 25h) producing different energy weights than normal 24h days.

**Energy Invariant:** Derivation preserves the energy invariant — the sum of derived PLAN energies equals the source CONTRACTUAL interval's energy. For ABSOLUTE scalars, the sum of derived PLAN values equals the source CONTRACTUAL value (within BigDecimal scale 6 tolerance).

**Result Records:**
- `DerivationResult(createdPlanIntervals, sourceContractualInterval)`: Returned by derivation methods. The source CONTRACTUAL interval is included for reference but is never modified.

### 4.6 Data Retention Lifecycle

Settled volume intervals are retained at their materialized granularity (including 15-min base resolution for near-term intervals) until regulatory retention periods expire. Intervals are **not** re-aggregated after settlement — the full-resolution data is preserved for audit and regulatory access.

**Regulatory Retention Requirements:**

| Regulation | Retention Period | Scope |
|---|---|---|
| REMIT | 5 years from transaction | Transaction records, orders, communications |
| MiFID II | 5 years (extendable to 7) | Transaction records, communications, order details |
| EMIR | 5 years from contract termination | Derivative contract records |
| GDPR | Delete after retention period expires | Personal data must be anonymized or purged |

The **binding retention ceiling is 7 years post-settlement** (MiFID II worst case with regulatory extension). The **binding floor is "delete after that"** (GDPR).

**Three-Tier Storage Strategy:**

| Tier | Interval State | Storage | Access Pattern | Write Pattern |
|---|---|---|---|---|
| Hot | Delivery periods within near-term (M + M+1 + M+2) | Uncompressed PostgreSQL/TimescaleDB | Full R/W, Redis position cache | Kafka consumers, schedulers, nominations |
| Warm | Settled, within 7-year retention window | Compressed TimescaleDB chunks | Read-only (audit, regulatory, recon) | Rare corrections only (requires decompression) |
| Purge | > 7 years post-settlement finalization | Dropped | None | Automated chunk deletion |

**Compression Policy:** Chunks containing intervals whose delivery periods are > 6 months in the past are auto-compressed. The `compress_segmentby` columns are `(tenant_id, trade_id, bucket_type)` to align with the dominant query pattern (queries almost always filter by bucket type).

**Retention Policy:** Chunks are auto-dropped when their delivery periods exceed `settlement_finalized_at + 7 years + 6 months` (buffer for dispute resolution delays). The purge job uses the trade's `settlement_finalized_at` timestamp, not the raw delivery date. All three bucket types (CONTRACTUAL, PLAN, ACTUAL) for a given delivery period are purged together.

**Row Count Estimates (three-bucket model):**

For a fully settled delivery month with all three buckets populated:

| Bucket | Rows per PPA per month | Notes |
|---|---|---|
| CONTRACTUAL (monthly, from LONG_TERM) | 1 | Immutable, write-once at trade capture |
| CONTRACTUAL (daily, from MEDIUM_TERM) | ~31 | Immutable, write-once at trade capture |
| CONTRACTUAL (15-min, from NEAR_TERM) | ~2,976 | Immutable, write-once at trade capture |
| PLAN (15-min) | ~2,976 | Derived from contractual at disaggregation time |
| ACTUAL (15-min) | ~2,976 | Written post-delivery from metering |
| **Total per PPA per settled month** | **~8,960** | |

For 200 tenants × 20 PPAs × 12 months actively settling: ~430M rows in the hot tier. The CONTRACTUAL rows at coarser granularities (monthly + daily) add negligible volume (~32 rows vs ~8,928 at 15-min) but are critical for audit.

**GDPR Compliance:** Volume interval data itself typically does not contain personal data (keyed by tenant_id and trade_id). Personal identifiers on the parent trade record (trader name, counterparty contacts) are anonymized at the same 7-year boundary. The trade commercial structure is preserved for financial reporting; only personal identifiers are replaced with hashes or NULLs.

**Pricing Service** mirrors this:

| Tier | Source | Method |
|---|---|---|
| Near-term spot/interval | Detail-level positions | MtM per interval from market prices |
| Far-dated forward curve | Contract-level positions | Curve-based forward valuation |

---

## 5. DST Handling

### 5.1 Why DST Matters

EU power settlement is based on actual delivered energy (MWh), which depends on actual elapsed time. On DST transition days, the nominal 24-hour day becomes 23 or 25 hours. Failing to handle this correctly creates reconciliation breaks against exchange settlement data.

### 5.2 Fall-Back (October, Last Sunday)

Clocks go from 03:00 CEST back to 02:00 CET. The hour 02:00–03:00 occurs twice.

- A full day has **25 hours**
- At 15-min granularity: **100 intervals** (not 96)
- At hourly granularity: **25 intervals** (not 24)
- Baseload 15 MW for the full day: **375 MWh** (not 360)

`ZonedDateTime.plus(Duration.ofMinutes(15))` handles this correctly because it operates on the underlying `Instant` and resolves back to the delivery timezone.

### 5.3 Spring-Forward (March, Last Sunday)

Clocks go from 02:00 CET to 03:00 CEST. The hour 02:00–03:00 does not exist.

- A full day has **23 hours**
- At 15-min granularity: **92 intervals** (not 96)
- At hourly granularity: **23 intervals** (not 24)
- Baseload 15 MW for the full day: **345 MWh** (not 360)

### 5.4 Annual Net Effect

For a full-year delivery period, the DST effects cancel out (-1 hour in spring + 1 hour in fall = net zero). However, for partial-year contracts that span only one transition, the effect is real and must be reflected in interval counts and energy totals.

### 5.5 Implementation Rule

All duration and energy calculations must use `Instant`-based arithmetic:

```java
Duration.between(intervalStart.toInstant(), intervalEnd.toInstant())
```

Never use `Duration.between(intervalStart, intervalEnd)` on `ZonedDateTime` directly — this uses wall-clock time and produces wrong results across DST boundaries.

---

## 6. Key Design Invariants

These invariants must hold at all times and are enforced by the test suite:

### 6.1 Energy Conservation Across Granularities

For any given delivery window and flat volume **in `MW_CAPACITY` mode**, the total energy must be identical regardless of the granularity used to decompose it:

```
totalEnergy(5-min) == totalEnergy(15-min) == totalEnergy(30-min) == totalEnergy(hourly)
```

If this invariant breaks, the Position Service will produce different exposure numbers depending on granularity, which is a critical bug.

**This invariant does NOT hold for `MWH_PER_PERIOD` mode.** In that mode, each interval delivers the full volume as MWh regardless of duration, so finer granularity produces more total energy by design (e.g., 4 × 15 MWh = 60 MWh at 15-min vs 2 × 15 MWh = 30 MWh at 30-min for the same 1-hour window). This is correct semantics for MWh-per-period contracts.

### 6.2 Contiguity

Materialized intervals must be contiguous and non-overlapping **within each cascade tier**:

```
∀ tier ∈ {NEAR_TERM, MEDIUM_TERM, LONG_TERM}:
  intervals_tier = intervals.filter(i -> i.cascadeTier == tier), ordered by intervalStart
  ∀ i: intervals_tier[i].intervalEnd == intervals_tier[i+1].intervalStart
```

**Cross-tier boundary alignment:** The end of one tier's last interval must equal the start of the next tier's first interval:

```
nearTermIntervals.last().intervalEnd == mediumTermIntervals.first().intervalStart
mediumTermIntervals.last().intervalEnd == longTermIntervals.first().intervalStart
```

For non-cascade trades (DA, intraday), all intervals have `cascadeTier = null` and global contiguity applies:

```
intervals[0].intervalStart == series.deliveryStart
intervals[last].intervalEnd == series.deliveryEnd (or materializedEnd for PARTIAL)
```

### 6.3 Interval Count Determinism

For a given `(deliveryStart, deliveryEnd, granularity, deliveryTimezone)`, the expected interval count is deterministic and reproducible. `calculateExpectedIntervals()` must return the same value every time.

### 6.4 Energy Derivation

For `MW_CAPACITY` volume unit:

```
interval.energy == interval.volume × (elapsed seconds / 3600)
```

This uses actual elapsed time (Instant-based), not nominal wall-clock time.

For `MWH_PER_PERIOD` volume unit:

```
interval.energy == interval.volume
```

### 6.5 Bi-Temporal Completeness

Every `VolumeSeries` must have both `transactionTime` (when the system recorded this) and `validTime` (when this became economically effective) set. These are required for REMIT regulatory reporting, which demands the ability to reconstruct "what we knew at point in time T."

### 6.6 Formula Regenerability

Any materialized interval set must be exactly reproducible from the `VolumeFormula` + `TradingCalendar` + `granularity` + `deliveryTimezone`. This enables safe retry of failed chunks, re-materialization after amendment, and audit verification.

### 6.7 Cascade Granularity Rule

For cascade series, the effective granularity of each tier is deterministic:

```
effectiveGranularity(tier) = max(tier.defaultGranularity, series.baseGranularity)
```

Where `max` selects the **coarser** granularity (`MONTHLY > DAILY > HOURLY > MIN_30 > MIN_15 > MIN_5`). This ensures no tier produces intervals finer than the trade's contractual granularity. If `baseGranularity` is already coarser than a tier's default, that tier uses `baseGranularity` (cascade is a no-op for that tier).

### 6.8 Scalar Derivation Invariant

When deriving PLAN intervals from a CONTRACTUAL interval:

**RATE scalars:** Each derived PLAN row gets the source CONTRACTUAL value verbatim.

```
∀ planChild: planChild.rateScalar == contractualParent.rateScalar
```

**ABSOLUTE scalars:** Derived PLAN values sum to source CONTRACTUAL value (within BigDecimal scale 6 tolerance).

```
sum(planChild.absoluteScalar for all derived children) ≈ contractualParent.absoluteScalar
```

Each child's share is proportional to its energy weight:

```
planChild.absoluteScalar = contractualParent.absoluteScalar × (planChild.energy / contractualParent.energy)
```

This invariant is critical for financial reconciliation — the total cost allocated across derived PLAN intervals must equal the original CONTRACTUAL cost. **The CONTRACTUAL interval itself is never modified.**

### 6.9 Bucket Coexistence Invariant

For any delivery period, all three bucket types can coexist independently:

```
CONTRACTUAL intervals are immutable:
  ∀ contractual: contractual.volume at time T == contractual.volume at time T+N (for any N)
  ∀ contractual: contractual rows are never deleted (until regulatory purge)

PLAN intervals are derived from CONTRACTUAL:
  ∀ plan: ∃ contractual such that plan.intervalStart ≥ contractual.intervalStart 
          AND plan.intervalEnd ≤ contractual.intervalEnd
          AND plan.seriesId == contractual.seriesId

ACTUAL intervals are independent of both:
  ∀ actual: actual.bucketType == ACTUAL AND actual.effectiveGranularity == series.baseGranularity
```

**Deletion rules:**
- CONTRACTUAL: Never deleted (except by regulatory purge after 7-year retention)
- PLAN: Can be regenerated from CONTRACTUAL at any time (idempotent derivation)
- ACTUAL: Append-only; corrections create new version rows, not updates

### 6.10 Contractual Energy Coverage Invariant

The sum of CONTRACTUAL interval energies across all cascade tiers must equal the total energy derivable from the `VolumeFormula` for the full delivery window:

```
sum(energy for all intervals WHERE bucketType == CONTRACTUAL) 
  == VolumeFormula.totalEnergy(deliveryStart, deliveryEnd)
```

This ensures the contractual layer has no gaps or overlaps — every delivery period is covered exactly once by a CONTRACTUAL interval at some granularity (monthly, daily, or base).

---

## 7. Test Specification

### 7.1 Test Structure

The test suite uses JUnit 5 `@Nested` classes to organize scenarios:

```
VolumeSeriesTest
├── Scenario 1: SingleQuarterHourInterval
│   ├── Interval count, boundaries, materialization status
│   ├── Energy calculation (MWH_PER_PERIOD mode)
│   └── Energy calculation (MW_CAPACITY mode)
├── Scenario 2: OneHourIn15MinIntervals
│   ├── Interval count, contiguity, start times
│   ├── Energy calculation (MW_CAPACITY mode)
│   └── Chunk month assignment
├── Scenario 3: NinetyMinutesIn30MinIntervals
│   ├── Interval count, boundaries
│   ├── Energy calculation (MW_CAPACITY mode)
│   └── Energy calculation (MWH_PER_PERIOD mode)
├── Scenario 4: OneHourIn30MinIntervals
│   ├── Interval count
│   ├── Cross-granularity energy invariant (MW_CAPACITY mode)
│   └── Per-interval energy verification
├── Scenario 5: OneYearPPA
│   ├── Full materialization interval count
│   ├── Contiguity across full year
│   ├── Annual energy total (MW_CAPACITY mode)
│   ├── DST fall-back test (Oct 25 2026, 100 intervals, 375 MWh)
│   ├── DST spring-forward test (Mar 28 2027, 92 intervals, 345 MWh)
│   ├── Partial materialization (M+3 rolling horizon via buildPartialSeries)
│   ├── Chunk materialization extends window (materializeChunk)
│   ├── Completing all chunks promotes status to FULL
│   ├── Chunk month partitioning (13 months)
│   ├── Formula attachment test
│   └── Fully materialized has no unmaterialized window
├── CrossCuttingTests
│   ├── Energy invariant across granularities (MW_CAPACITY only)
│   ├── MWH_PER_PERIOD: energy equals volume regardless of interval duration
│   ├── MW_CAPACITY vs MWH_PER_PERIOD: different energy for same volume value
│   ├── TradeLegId: unique per leg, shared tradeId
│   ├── Interval count scaling (inverse with granularity)
│   ├── Bi-temporal timestamp validation
│   ├── MONTHLY granularity guard (throws UnsupportedOperationException)
│   └── DeliveryWindow validation (rejects end before start)
└── CascadeMaterializationTests
    ├── buildCascadeSeries produces single series with three tiers of CONTRACTUAL intervals
    ├── All intervals from buildCascadeSeries have bucketType=CONTRACTUAL
    ├── Near-term intervals have cascadeTier=NEAR_TERM and effectiveGranularity=baseGranularity
    ├── materializeMediumTermChunk generates daily CONTRACTUAL intervals tagged MEDIUM_TERM
    ├── materializeLongTermChunk generates a monthly CONTRACTUAL interval tagged LONG_TERM
    ├── Cross-tier boundary alignment (nearTerm.last.end == mediumTerm.first.start)
    ├── Contractual energy coverage: sum of all CONTRACTUAL energies == formula total
    ├── deriveDaily creates PLAN daily intervals; source CONTRACTUAL monthly unchanged
    ├── deriveDaily PLAN intervals have cascadeTier=MEDIUM_TERM, bucketType=PLAN
    ├── deriveDaily copies RATE scalars to PLAN, pro-rates ABSOLUTE scalars by energy weight
    ├── deriveDaily energy invariant: sum(PLAN daily) == CONTRACTUAL monthly energy
    ├── deriveBaseGranularity creates PLAN 15-min intervals; source CONTRACTUAL daily unchanged
    ├── deriveBaseGranularity PLAN intervals have cascadeTier=NEAR_TERM, bucketType=PLAN
    ├── deriveBaseGranularity copies RATE scalars, pro-rates ABSOLUTE scalars
    ├── deriveBaseGranularity energy invariant: sum(PLAN 15-min) == CONTRACTUAL daily energy
    ├── Bucket coexistence: contractual + plan + actual rows coexist for same delivery period
    ├── CONTRACTUAL immutability: intervals unchanged after deriveDaily and deriveBaseGranularity
    ├── ABSOLUTE scalar sum after derivation equals source value (within scale 6 tolerance)
    ├── DST day derivation uses actual energy weights for ABSOLUTE pro-ration (not uniform 1/N)
    ├── Daily intervals on DST days have correct energy (25h fall-back, 23h spring-forward)
    ├── Monthly forward (baseGranularity=MONTHLY) produces no cascade (all tiers MONTHLY)
    ├── Cascade granularity rule: max(tierDefault, baseGranularity) holds for all combinations
    ├── getContractualIntervals returns only CONTRACTUAL rows, ordered by intervalStart
    ├── getPlanIntervals returns only PLAN rows, ordered by intervalStart
    ├── getIntervalsForDeliveryPeriod returns all buckets overlapping the query window
    └── Series promotes to FULL when all CONTRACTUAL chunks across all tiers are materialized
```

### 7.2 Scenario Details

#### Scenario 1 — Single 15-min Interval

| Parameter | Value |
|---|---|
| Delivery window | 24 Apr 2026, 17:45 – 18:00 CET |
| Granularity | 15 min |
| Volume | 15 |
| Profile | BLOCK |

**Expected outcomes (tested with both `VolumeUnit` modes):**

- Exactly 1 interval produced
- Interval boundaries: 17:45 – 18:00
- **`MW_CAPACITY`:** Energy: 15 MW × 0.25h = **3.75 MWh**
- **`MWH_PER_PERIOD`:** Energy: **15 MWh** (volume equals energy)
- Status: `FULL` materialization, `CONFIRMED` interval
- No unmaterialized window

#### Scenario 2 — Four 15-min Intervals

| Parameter | Value |
|---|---|
| Delivery window | 24 Apr 2026, 17:00 – 18:00 CET |
| Granularity | 15 min |
| Volume | 15 |
| Volume Unit | `MW_CAPACITY` (for energy tests) |
| Profile | BLOCK |

**Expected outcomes:**

- Exactly 4 intervals produced
- Start times: 17:00, 17:15, 17:30, 17:45
- Contiguous, non-overlapping
- **`MW_CAPACITY`:** Each interval energy: **3.75 MWh**, total: **15.00 MWh**
- All intervals in chunk month `2026-04`

#### Scenario 3 — Three 30-min Intervals

| Parameter | Value |
|---|---|
| Delivery window | 24 Apr 2026, 17:00 – 18:30 CET |
| Granularity | 30 min |
| Volume | 15 |
| Profile | BLOCK |

**Expected outcomes (tested with both `VolumeUnit` modes):**

- Exactly 3 intervals produced
- Boundaries: 17:00–17:30, 17:30–18:00, 18:00–18:30
- **`MW_CAPACITY`:** Each interval energy: **7.50 MWh**, total: **22.50 MWh**
- **`MWH_PER_PERIOD`:** Each interval energy: **15 MWh**, total: **45.00 MWh**

#### Scenario 4 — Two 30-min Intervals

| Parameter | Value |
|---|---|
| Delivery window | 24 Apr 2026, 17:00 – 18:00 CET |
| Granularity | 30 min |
| Volume | 15 |
| Volume Unit | `MW_CAPACITY` (for energy tests) |
| Profile | BLOCK |

**Expected outcomes:**

- Exactly 2 intervals produced
- **`MW_CAPACITY`:** Each interval energy: **7.50 MWh**, total: **15.00 MWh**
- **Cross-granularity invariant (`MW_CAPACITY` only)**: Total energy equals Scenario 2 (same window, different granularity)

#### Scenario 5 — One-Year PPA

| Parameter | Value |
|---|---|
| Delivery window | 24 Apr 2026, 17:00 – 24 Apr 2027, 17:00 CET |
| Granularity | 15 min |
| Volume | 15 MW |
| Profile | BASELOAD |

**Expected outcomes (full materialization):**

- Approximately **35,040 intervals** (365 × 96, ± 4 for DST)
- All intervals contiguous
- Total energy: approximately **131,400 MWh** (15 MW × 8,760h, ± 15 MWh tolerance)
- Spans **13 chunk months** (partial April 2026 through partial April 2027)
- May 2026 chunk: exactly **2,976 intervals** (31 × 96)
- June 2026 chunk: exactly **2,880 intervals** (30 × 96)

**DST fall-back (25 Oct 2026):**

- 100 intervals for the day (not 96)
- Energy: **375 MWh** (not 360)

**DST spring-forward (28 Mar 2027):**

- 92 intervals for the day (not 96)
- Energy: **345 MWh** (not 360)

**Partial materialization (M+3 rolling horizon):**

- Materialize through July 2026 only
- `materializationStatus = PARTIAL`
- `materializedThrough = 2026-07`
- `materializedIntervalCount < totalExpectedIntervals`
- `getUnmaterializedWindow()` returns Aug 1, 2026 – Apr 24, 2027

**Formula attachment:**

- `VolumeFormula` with `baseVolume = 15`, `minVolume = 12`, `maxVolume = 18`
- Represents tolerance band for PPA delivery

### 7.3 Cross-Cutting Tests

| Test | VolumeUnit | Assertion |
|---|---|---|
| Energy invariant across granularities | `MW_CAPACITY` | 5-min, 15-min, 30-min, hourly all produce 15 MWh for 17:00–18:00 at volume=15 |
| MWH_PER_PERIOD: energy equals volume | `MWH_PER_PERIOD` | Each interval's energy equals its volume (15 MWh) regardless of interval duration; total energy scales with interval count (4 × 15 = 60 MWh at 15-min vs 2 × 15 = 30 MWh at 30-min) |
| MW_CAPACITY vs MWH_PER_PERIOD comparison | Both | Same volume value (15) produces different per-interval energy: 3.75 MWh (`MW_CAPACITY`, 15-min) vs 15 MWh (`MWH_PER_PERIOD`, 15-min) |
| TradeLegId uniqueness | N/A | Two legs of the same trade share `tradeId` but have distinct `tradeLegId` values; both are non-null |
| Interval count scaling | Any | 12, 4, 2, 1 intervals for 5-min, 15-min, 30-min, hourly respectively |
| Bi-temporal timestamps | Any | Both `transactionTime` and `validTime` are non-null |
| MONTHLY guard | N/A | `TimeGranularity.MONTHLY.getFixedDuration()` throws `UnsupportedOperationException` |
| DeliveryWindow validation | N/A | Constructor rejects `end` before `start` with `IllegalArgumentException` |

### 7.4 VolumeSeriesService

The `VolumeSeriesService` (singleton, `com.quickysoft.power.volume.service`) provides reusable methods for building and querying volume series. These are used by both the test suite and the materialization pipeline. The domain model classes are Java records in `com.quickysoft.power.volume.models`.

- `buildSeries(tradeId, tradeLegId, start, end, baseGranularity, volume, profileType, matStatus, volumeUnit, zoneId)`: Constructs a `VolumeSeries` with fully materialized intervals at a single granularity (no cascade). The `tradeId` and `tradeLegId` (both `String`) identify the parent trade and leg. Handles all granularities including `MONTHLY`. Sets bi-temporal timestamps and calculates expected interval count. The `volumeUnit` parameter determines how `calculateEnergy()` behaves on each produced interval. All intervals have `cascadeTier = null`.
- `buildPartialSeries(tradeId, tradeLegId, deliveryStart, deliveryEnd, baseGranularity, volume, profileType, volumeUnit, zoneId, materializedThrough)`: Constructs a `VolumeSeries` with `materializationStatus = PARTIAL`, materializing only through the given `YearMonth`. Records the full delivery window (`deliveryStart` to `deliveryEnd`) and computes `totalExpectedIntervals` from the full range for progress tracking. Used by the Detail Generation Service for long-term PPAs with rolling-horizon materialization (Section 4.2). All intervals have `cascadeTier = null`.
- `materializeChunk(series, chunkMonth, volume)`: Materializes a single monthly chunk and appends it to an existing PARTIAL series. Returns a new `VolumeSeries` record with updated intervals, `materializedThrough`, and `materializedIntervalCount`. Automatically promotes `materializationStatus` to `FULL` (with `materializedThrough = null`) when all expected intervals have been materialized. Chunk boundaries are clamped to the delivery window. Used by the monthly chunk processor (Section 4.3).
- `calculateExpectedIntervals(start, end, granularity)` (static): Computes the expected interval count for a delivery window and granularity using ZonedDateTime arithmetic (DST-safe). Used by both `buildPartialSeries()` and `VolumeSeries.calculateExpectedIntervals()`.
- `materializeIntervals(start, end, granularity, volume, volumeUnit)`: Walks the timeline and produces `VolumeInterval` records with calculated energy and chunk month assignment. Dispatches to `materializeDailyIntervals()` for `DAILY` (using `ZonedDateTime.plusDays(1)` for DST safety) and `materializeMonthlyIntervals()` for `MONTHLY`.
- `totalEnergy(series)`: Sums energy across all intervals in a series, rounded to scale 6.
- `buildCascadeSeries(tradeId, tradeLegId, deliveryStart, deliveryEnd, baseGranularity, volume, profileType, volumeUnit, zoneId)`: Creates a **single `VolumeSeries`** with a three-tier cascade (Section 4.5). Near-term CONTRACTUAL intervals are materialized immediately at `baseGranularity` with `cascadeTier=NEAR_TERM`, `bucketType=CONTRACTUAL`. Medium-term and long-term CONTRACTUAL intervals start unmaterialized. RATE scalar columns (`contractedMw`, `basePriceEurMwh`, etc.) are populated from `VolumeFormula` on all CONTRACTUAL intervals. Returns a single `VolumeSeries` with `materializationStatus=PARTIAL`.
- `materializeMediumTermChunk(series, chunkMonth, volume)`: Generates daily CONTRACTUAL intervals for the medium-term portion of a cascade series chunk. Each interval is tagged `cascadeTier=MEDIUM_TERM`, `effectiveGranularity=DAILY`, `bucketType=CONTRACTUAL`. Returns updated series with intervals appended. Promotes to FULL when all expected CONTRACTUAL intervals across all tiers are materialized.
- `materializeLongTermChunk(series, chunkMonth, volume)`: Generates a single monthly CONTRACTUAL interval for the long-term portion of a cascade series chunk. Tagged `cascadeTier=LONG_TERM`, `effectiveGranularity=MONTHLY`, `bucketType=CONTRACTUAL`. Returns updated series. Promotes to FULL when all expected CONTRACTUAL intervals are materialized.
- `deriveDaily(series, month, volume)`: Reads the LONG_TERM monthly CONTRACTUAL interval for `month`. Creates MEDIUM_TERM daily PLAN intervals derived from it. RATE scalars are copied; ABSOLUTE scalars are pro-rated by energy weight (Section 6.8). The CONTRACTUAL interval is untouched. Returns `DerivationResult(createdPlanIntervals, sourceContractualInterval)`.
- `deriveBaseGranularity(series, fromDate, toDate, volume)`: Reads MEDIUM_TERM daily CONTRACTUAL intervals in `[fromDate, toDate)`. Creates NEAR_TERM base-granularity PLAN intervals derived from them. Same scalar derivation rules. CONTRACTUAL intervals untouched. Returns `DerivationResult(createdPlanIntervals, sourceContractualIntervals)`.
- `getContractualIntervals(series)`: Returns all intervals with `bucketType=CONTRACTUAL`, ordered by `intervalStart`. These represent the immutable trade-capture source of truth.
- `getPlanIntervals(series)`: Returns all intervals with `bucketType=PLAN`, ordered by `intervalStart`. These represent the operational delivery plan.
- `getActualIntervals(series)`: Returns all intervals with `bucketType=ACTUAL`, ordered by `intervalStart`. These represent metered delivery data.
- `getIntervalsForDeliveryPeriod(series, start, end)`: Returns all intervals (across all buckets) whose delivery window overlaps `[start, end)`. Used for reconciliation views that need contractual vs plan vs actual side-by-side.

---

## 8. Design Decisions and Rationale

### 8.1 ZonedDateTime Over UTC

Delivery times are stored in the delivery timezone (`Europe/Berlin`), not UTC. This preserves the trader's mental model and the contractual delivery semantics. UTC conversion loses information about which "02:00–03:00" an interval belongs to on DST fall-back days.

### 8.2 BigDecimal Over double

All volume and energy values use `BigDecimal` to avoid floating-point precision issues. Energy settlement in EU power markets is calculated to 6 decimal places (MWh). A `double` representation would introduce rounding errors that accumulate across 35,000+ intervals in a PPA.

### 8.3 Price Excluded From VolumeInterval

Price is deliberately absent from `VolumeInterval`. The Pricing Service owns price data. If price were on the interval, every price curve update would force writes to millions of interval rows. Keeping them separate and joining at query time preserves service autonomy and reduces write amplification.

### 8.4 Chunk Month on VolumeInterval

Each interval carries its `chunkMonth` to support the chunked async generation pattern. This enables independent retry of failed monthly chunks without regenerating the entire series, and allows the Detail Generation Service to track materialization progress per chunk.

### 8.5 DeliveryWindow as a Record

`DeliveryWindow` is a Java `record` (immutable value object) because it has no identity — two windows with the same start/end are semantically identical. The constructor validation ensures invalid windows cannot be created.

### 8.6 No Lombok Dependency

The model uses explicit getters/setters instead of Lombok annotations. This is a deliberate choice for a domain model module that may be shared across services — avoiding a compile-time annotation processor dependency in a shared library reduces integration friction.

---

## 9. Future Considerations

### 9.1 Cross-Granularity Position Aggregation

When a portfolio contains both DA 15-min trades and monthly PPA blocks, the Position Service needs to aggregate across granularities. The granularity cascade (Section 4.5) provides derivation methods (`deriveDaily`, `deriveBaseGranularity`) in `VolumeSeriesService` that create PLAN intervals at finer granularity from CONTRACTUAL intervals. The Position Service orchestrates when derivation occurs (e.g., monthly cron, on-demand) and coordinates the creation of PLAN intervals within the single cascade series. For position aggregation, the Position Service reads PLAN intervals (at base granularity) for near-term periods and CONTRACTUAL intervals (at coarser granularity) for far-dated periods where PLAN intervals have not yet been derived.

### 9.2 Amendment Impact Analysis

When a PPA is amended, the Detail Generation Service should analyze what changed before blindly regenerating all intervals. If only the price formula changed, no interval regeneration is needed (price is external). If the delivery start date shifted, only the affected window needs regeneration. This optimization is not modeled here but should be built into the Detail Generation Service.

### 9.3 Generation-Following Profile

The `GENERATION_FOLLOWING` profile type links to a renewable generation forecast feed via `forecastSourceId`. The actual materialization of these intervals depends on external forecast data arriving, which introduces a dependency on a market data service. The materialization flow for this profile type will differ from baseload/shaped and may require an event-driven trigger when new forecast data arrives.

### 9.4 Multi-Timezone Delivery

Cross-border interconnector trades may have different delivery timezones at each end. The current model supports a single `deliveryTimezone`. If multi-timezone delivery is needed, it would require either two `VolumeSeries` (one per timezone) or extending the model with a secondary timezone field.

---

## 10. Appendix: Quick Reference

### 10.1 Energy Calculation Examples

#### MW_CAPACITY mode (volume = power in MW, energy derived from duration)

| Volume | Duration | Granularity | Intervals | Energy per Interval | Total Energy |
|---|---|---|---|---|---|
| 15 MW | 15 min | MIN_15 | 1 | 3.75 MWh | 3.75 MWh |
| 15 MW | 1 hour | MIN_15 | 4 | 3.75 MWh | 15.00 MWh |
| 15 MW | 1 hour | MIN_30 | 2 | 7.50 MWh | 15.00 MWh |
| 15 MW | 1.5 hours | MIN_30 | 3 | 7.50 MWh | 22.50 MWh |
| 15 MW | 1 year | MIN_15 | ~35,040 | 3.75 MWh | ~131,400 MWh |
| 15 MW | 25h (DST fall-back) | MIN_15 | 100 | 3.75 MWh | 375.00 MWh |
| 15 MW | 23h (DST spring-fwd) | MIN_15 | 92 | 3.75 MWh | 345.00 MWh |

Note: total energy is identical across granularities for the same delivery window — this is the energy conservation invariant.

#### MWH_PER_PERIOD mode (volume = energy in MWh per period, no duration dependency)

| Volume | Duration | Granularity | Intervals | Energy per Interval | Total Energy |
|---|---|---|---|---|---|
| 15 MWh | 15 min | MIN_15 | 1 | 15 MWh | 15 MWh |
| 15 MWh | 1 hour | MIN_15 | 4 | 15 MWh | 60 MWh |
| 15 MWh | 1 hour | MIN_30 | 2 | 15 MWh | 30 MWh |
| 15 MWh | 1.5 hours | MIN_30 | 3 | 15 MWh | 45 MWh |

Note: total energy scales with interval count, NOT with elapsed time. Finer granularity produces more total energy. The cross-granularity energy conservation invariant does NOT apply.

### 10.2 Maven Coordinates

```xml
<dependency>
    <groupId>com.quickysoft.power</groupId>
    <artifactId>power-volume-series</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 10.3 Build & Test

```bash
mvn clean test
```

Requires Java 17+ and Maven 3.8+. No external dependencies beyond JUnit 5.

---

## 11. Performance Requirements

### 11.1 Processing Context and Constraints

Performance requirements are driven by the operational realities of EU physical power markets, where timing windows are non-negotiable and directly tied to gate closure deadlines.

**Day-Ahead Auction (EPEX SPOT DE):**
Gate closure at 12:00 CET. Auction results published ~12:42 CET. All DA trades for the next delivery day (96 intervals at 15-min) must be captured, detail-generated, position-calculated, and available for nomination by 14:30 CET at the latest. This gives the entire async pipeline approximately **108 minutes** from results publication to nomination readiness, but in practice the Detail Generation Service has a fraction of this — the scheduling team needs position data well before the nomination deadline.

**Intraday Continuous (XBID/SIDC):**
Trades execute continuously until 30 minutes before delivery. A trade executed at 16:30 for delivery at 17:00 gives the entire pipeline **30 minutes** end-to-end. Detail generation for a single-interval intraday trade must be near-instantaneous.

**PPA / Long-Tenor:**
No hard gate closure, but the initial M+3 materialization window must complete before the trader's next action (querying position, running risk reports). Acceptable wait time is seconds, not minutes. Full-year background chunk processing can run over minutes but must not block the near-term materialization.

**Batch Scenarios:**
DA auction results arrive as a batch — a single counterparty may receive 50–200 confirmed trades simultaneously. The Detail Generation Service must handle this burst without queue backlog accumulating.

### 11.2 Processing SLAs

#### 11.2.1 Detail Generation Service SLAs

| Scenario | Input | Expected Intervals | Target Latency (p95) | Target Latency (p99) | Notes |
|---|---|---|---|---|---|
| Single intraday interval | 1 trade, 1 interval | 1 | ≤ 5 ms | ≤ 10 ms | Includes Kafka consume + persist |
| DA full day (15-min) | 1 trade, 96 intervals | 96 | ≤ 20 ms | ≤ 50 ms | Single-pass materialization |
| DA full day (hourly) | 1 trade, 24 intervals | 24 | ≤ 10 ms | ≤ 25 ms | Simpler than 15-min |
| DA batch (100 trades) | 100 trades, 9,600 intervals total | 9,600 | ≤ 500 ms | ≤ 1,000 ms | Burst from auction results |
| DA batch (200 trades) | 200 trades, 19,200 intervals total | 19,200 | ≤ 1,000 ms | ≤ 2,000 ms | Large counterparty book |
| PPA M+3 near-term | 1 trade, ~3 months | ~8,640 | ≤ 500 ms | ≤ 1,000 ms | Initial rolling horizon |
| PPA single monthly chunk | 1 chunk, ~2,976 intervals (31-day month) | 2,976 | ≤ 100 ms | ≤ 250 ms | Background chunk job |
| PPA full year (background) | 1 trade, ~35,040 intervals | ~35,040 | ≤ 5 sec | ≤ 10 sec | Full materialization if triggered |
| PPA 10-year (background) | 1 trade, ~350,400 intervals | ~350,400 | ≤ 30 sec | ≤ 60 sec | Worst-case long-tenor |

**Cascade Detail Generation SLAs** (Section 4.5 — multi-granularity cascade):

With the cascade model, the trade capture response path only generates the near-term tier upfront (~288 intervals at base granularity for a 3-day week remainder). This is **constant regardless of PPA tenor**. Medium and long-term tiers are background Kafka jobs.

| Scenario | Operation | Intervals | Target (p95) | Target (p99) | Notes |
|---|---|---|---|---|---|
| Any PPA (6mo–10yr) | `buildCascadeSeries` (upfront, blocking) | ~8,736 near-term | ≤ 50 ms | ≤ 100 ms | Trader wait time; M+M+1+M+2 at base granularity |
| 6-month PPA | Medium-term background | ~91 daily | ≤ 50 ms | ≤ 100 ms | 3 monthly Kafka chunks |
| 6-month PPA | Long-term background | 3 monthly | ≤ 5 ms | ≤ 10 ms | 3 Kafka chunks |
| 1-year PPA | Long-term background | 9 monthly | ≤ 10 ms | ≤ 20 ms | 9 Kafka chunks |
| 3-year PPA | Long-term background | 33 monthly | ≤ 20 ms | ≤ 50 ms | 33 Kafka chunks |
| 6-year PPA | Long-term background | 69 monthly | ≤ 30 ms | ≤ 75 ms | 69 Kafka chunks |
| 10-year PPA | Long-term background | 117 monthly | ≤ 50 ms | ≤ 100 ms | 117 Kafka chunks |
| Per chunk | `materializeMediumTermChunk` | ~28–31 daily | ≤ 1 ms | ≤ 2 ms | Single Kafka message |
| Per chunk | `materializeLongTermChunk` | 1 monthly | ≤ 0.5 ms | ≤ 1 ms | Single Kafka message |
| Monthly cron | `deriveDaily` | 1 monthly → ~30 daily PLAN | ≤ 2 ms | ≤ 5 ms | Rolling derivation |
| Monthly cron | `deriveBaseGranularity` | 30 daily → ~2,880 base PLAN | ≤ 50 ms | ≤ 100 ms | Near-term PLAN creation |

**End-to-End Cascade SLAs** (including Kafka + DB I/O):

| Scenario | Phase | Target (p95) | Target (p99) | Notes |
|---|---|---|---|---|
| Any PPA (6mo–10yr) | Trade capture → near-term position-ready | ≤ 500 ms | ≤ 1 sec | Trader's blocking wait |
| 6-month PPA | Full background completion (all 3 tiers FULL) | ≤ 5 sec | ≤ 10 sec | ~97 total chunks |
| 1-year PPA | Full background completion | ≤ 10 sec | ≤ 20 sec | ~100 total chunks |
| 3-year PPA | Full background completion | ≤ 20 sec | ≤ 40 sec | ~124 total chunks |
| 6-year PPA | Full background completion | ≤ 35 sec | ≤ 60 sec | ~160 total chunks |
| 10-year PPA | Full background completion | ≤ 50 sec | ≤ 90 sec | ~208 total chunks |

#### 11.2.2 Core Domain Operation SLAs

These are the pure computation targets, excluding I/O (Kafka, database). These are what the JMH benchmarks measure.

| Operation | Input Scale | Target (p50) | Target (p95) | Target (p99) |
|---|---|---|---|---|
| `calculateExpectedIntervals()` — sub-daily (any tenor) | O(1) arithmetic | ≤ 100 ns | ≤ 200 ns | ≤ 500 ns |
| `calculateExpectedIntervals()` — DAILY | O(1) ChronoUnit | ≤ 100 ns | ≤ 200 ns | ≤ 500 ns |
| `calculateExpectedIntervals()` — MONTHLY | O(1) ChronoUnit | ≤ 100 ns | ≤ 200 ns | ≤ 500 ns |
| `calculateEnergy()` — single interval | 1 interval | ≤ 200 ns | ≤ 500 ns | ≤ 1 μs |
| `materializeIntervals()` — DA (96) | 96 intervals | ≤ 50 μs | ≤ 100 μs | ≤ 200 μs |
| `materializeIntervals()` — 1 month (2,976) | 2,976 intervals | ≤ 1 ms | ≤ 2 ms | ≤ 5 ms |
| `materializeIntervals()` — 1 year (35,040) | 35,040 intervals | ≤ 15 ms | ≤ 30 ms | ≤ 50 ms |
| `materializeIntervals()` — 10 years (350,400) | 350,400 intervals | ≤ 150 ms | ≤ 300 ms | ≤ 500 ms |
| `getUnmaterializedWindow()` | 1 call | ≤ 100 ns | ≤ 200 ns | ≤ 500 ns |
| `totalEnergy()` — sum across 96 intervals | 96 intervals | ≤ 10 μs | ≤ 20 μs | ≤ 50 μs |
| `totalEnergy()` — sum across 35,040 intervals | 35,040 intervals | ≤ 2 ms | ≤ 5 ms | ≤ 10 ms |
| Contiguity validation — 35,040 intervals | 35,040 intervals | ≤ 2 ms | ≤ 5 ms | ≤ 10 ms |

#### 11.2.3 Serialization SLAs

Volume series are serialized for Kafka event payloads and database persistence.

| Operation | Scale | Target (p95) | Notes |
|---|---|---|---|
| JSON serialize — DA (96 intervals) | ~15 KB | ≤ 1 ms | Jackson ObjectMapper |
| JSON deserialize — DA (96 intervals) | ~15 KB | ≤ 1 ms | Jackson ObjectMapper |
| JSON serialize — 1 month (2,976 intervals) | ~500 KB | ≤ 10 ms | Consider Claim Check pattern |
| JSON serialize — 1 year (35,040 intervals) | ~5.5 MB | ≤ 100 ms | Must use Claim Check pattern |
| JDBC batch insert — 96 intervals | 96 rows | ≤ 20 ms | Batch size 96, single round-trip |
| JDBC batch insert — 2,976 intervals | 2,976 rows | ≤ 200 ms | Batch size 500, 6 round-trips |
| JDBC batch insert — 35,040 intervals | 35,040 rows | ≤ 2 sec | Batch size 1000, 36 round-trips |

### 11.3 Memory Budget

| Scenario | Interval Count | Estimated Heap (intervals only) | Estimated Heap (full aggregate) | Notes |
|---|---|---|---|---|
| DA trade (15-min) | 96 | ~30 KB | ~35 KB | Trivial |
| Monthly chunk | 2,976 | ~950 KB | ~1 MB | Single chunk in flight |
| Full year PPA | 35,040 | ~11 MB | ~12 MB | Acceptable for single trade |
| 10-year PPA | 350,400 | ~112 MB | ~115 MB | Risk: must not hold full set in memory |
| DA batch (200 trades) | 19,200 | ~6 MB | ~7 MB | Concurrent processing |

**Per-interval object estimate:** ~320 bytes (UUID id = 32B, UUID seriesId = 32B, 2× ZonedDateTime = 80B, 2× BigDecimal = 64B, IntervalStatus enum ref = 8B, YearMonth = 16B, object header + padding = ~88B).

**Critical constraint for 10-year PPAs:** The Detail Generation Service must never hold a full 10-year interval list in memory simultaneously. The chunked materialization pattern (monthly chunks of ~3,000 intervals each) keeps the working set under 1 MB per chunk. Each chunk is persisted and released before the next is generated.

### 11.4 Throughput Targets

| Metric | Target | Context |
|---|---|---|
| DA batch throughput | ≥ 200 trades/sec | Auction result burst processing |
| Intraday single-trade throughput | ≥ 1,000 trades/sec | Continuous market peak |
| Monthly chunk throughput | ≥ 50 chunks/sec | Background PPA materialization |
| Interval persistence throughput | ≥ 100,000 intervals/sec | Sustained JDBC batch insert rate |
| Kafka event publish throughput | ≥ 500 events/sec | `trade.details.generated` publish rate |

---

## 12. JMH Microbenchmark Specification

### 12.1 Maven Setup

Add the following to the project POM for JMH benchmark support:

```xml
<dependencies>
    <!-- existing dependencies -->
    <dependency>
        <groupId>org.openjdk.jmh</groupId>
        <artifactId>jmh-core</artifactId>
        <version>1.37</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.openjdk.jmh</groupId>
        <artifactId>jmh-generator-annprocess</artifactId>
        <version>1.37</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 12.2 Benchmark Configuration Standards

All benchmarks must use these common configuration parameters:

```java
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)  // or NANOSECONDS for sub-μs operations
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = {"-Xms512m", "-Xmx512m"})
@State(Scope.Benchmark)
```

Rationale: 2 forks to detect JVM-level variance, 5 warmup iterations to ensure JIT compilation stabilizes, 10 measurement iterations for statistical significance. Fixed heap to prevent GC variance.

### 12.3 Benchmark Classes

#### 12.3.1 `IntervalMaterializationBenchmark`

This is the most critical benchmark — it measures the core interval generation loop that the Detail Generation Service executes.

```
Class: IntervalMaterializationBenchmark
Package: com.quickysoft.power.volume.benchmark
```

**State setup (`@Setup`):**
Pre-construct `ZonedDateTime` start/end pairs and `TimeGranularity` for each scenario. The setup must not include object construction time in the benchmark measurement.

**Benchmark methods:**

| Method | Description | `@Param` |
|---|---|---|
| `materialize_DA_15min` | Full DA day at 15-min granularity | — |
| `materialize_DA_hourly` | Full DA day at hourly granularity | — |
| `materialize_month_15min` | Single month at 15-min | month={28,30,31} days |
| `materialize_year_15min` | Full year at 15-min | — |
| `materialize_decade_15min` | 10 years at 15-min | — |
| `materialize_parameterized` | Parameterized by granularity and tenor | granularity={MIN_5, MIN_15, MIN_30, HOURLY}, tenor={1d, 1m, 1y} |

**What to measure:**
- `avgt` (average time per operation)
- `thrpt` (operations per second)
- Heap allocation via `-prof gc` (GC allocation rate)

**Assertions (in companion JUnit test):**
Each benchmark result is programmatically validated against the SLA targets from Section 11.2.2. The benchmark runner should fail the build if p95 exceeds the target.

#### 12.3.2 `EnergyCalculationBenchmark`

Measures `BigDecimal` arithmetic performance for `calculateEnergy()`.

| Method | Description | Notes |
|---|---|---|
| `calculateEnergy_single` | Single interval energy calc | Measures BigDecimal multiply + divide |
| `calculateEnergy_batch_96` | Sum energy across 96 pre-built intervals | DA scenario |
| `calculateEnergy_batch_35040` | Sum energy across 35,040 pre-built intervals | PPA year scenario |
| `calculateEnergy_DST_fallback` | Energy calc for interval spanning DST transition | Verifies no perf penalty for DST |

**Key concern:** `BigDecimal.divide()` with `RoundingMode.HALF_UP` and scale 6 is the hottest operation. If this shows up as a bottleneck, the mitigation is to pre-compute interval duration hours at materialization time and store it on the interval, avoiding repeated division.

#### 12.3.3 `ExpectedIntervalCountBenchmark`

Measures `calculateExpectedIntervals()` which walks the timeline.

| Method | Description | Notes |
|---|---|---|
| `expectedCount_DA_15min` | 96-interval walk | Should be < 10 μs |
| `expectedCount_year_15min` | 35,040-step walk | Timeline walking is O(n) |
| `expectedCount_decade_15min` | 350,400-step walk | Validate linear scaling |
| `expectedCount_monthly` | Monthly granularity (uses ChronoUnit) | Should be O(1) |

**Optimization signal:** If the year/decade benchmarks exceed targets, `calculateExpectedIntervals()` should be refactored to use arithmetic calculation `(Duration.between(start.toInstant(), end.toInstant()).toMinutes() / granularity.getFixedDuration().toMinutes())` with a DST correction factor, rather than walking the timeline step by step. The current O(n) implementation is correct but may not meet p95 targets at 10-year scale.

#### 12.3.4 `ContiguityValidationBenchmark`

Measures the cost of validating the contiguity invariant (Section 6.2) across the full interval list.

| Method | Description | Notes |
|---|---|---|
| `validateContiguity_96` | 96 intervals | DA scenario |
| `validateContiguity_35040` | 35,040 intervals | PPA year |
| `validateContiguity_350400` | 350,400 intervals | PPA decade |

This matters because contiguity validation runs on every materialization result before persistence. If it becomes a bottleneck, it can be moved to an async validation step or sampled rather than exhaustive.

#### 12.3.5 `SerializationBenchmark`

Measures JSON serialization/deserialization of `VolumeSeries` using Jackson.

| Method | Description | Notes |
|---|---|---|
| `serialize_DA_96` | Serialize 96-interval series to JSON | Measures Jackson ObjectMapper |
| `deserialize_DA_96` | Deserialize 96-interval series from JSON | — |
| `serialize_month_2976` | Serialize 2,976-interval series | Claim Check threshold candidate |
| `serialize_year_35040` | Serialize 35,040-interval series | Must use Claim Check pattern |
| `serialize_payload_size` | Measure serialized byte size | Not a time benchmark — size tracking |

**Claim Check pattern threshold:** If serialized payload exceeds 1 MB, the Kafka event should carry only the series metadata (trade ID, version, interval count, materialization status) and store the full interval data in the database or object store. The Claim Check threshold should be validated by this benchmark.

#### 12.3.6 `MemoryFootprintBenchmark`

Measures actual heap consumption per scenario using `-prof gc`.

| Method | Description | Expected Allocation |
|---|---|---|
| `heap_DA_96` | Allocate 96-interval series | ~35 KB |
| `heap_month_2976` | Allocate 2,976-interval series | ~1 MB |
| `heap_year_35040` | Allocate 35,040-interval series | ~12 MB |
| `heap_decade_350400` | Allocate 350,400-interval series | ~115 MB |

**Use `@Measurement(batchSize = 1)` and `-prof gc:churn`** to measure allocation rate. The decade benchmark validates that the chunked pattern (never hold full set in memory) is necessary.

#### 12.3.7 `DSTBoundaryBenchmark`

Measures whether DST-crossing intervals incur a performance penalty compared to non-DST intervals.

| Method | Description | Notes |
|---|---|---|
| `materialize_normal_day` | Regular 24-hour day, 15-min | Baseline |
| `materialize_fallback_day` | 25-hour DST day, 15-min | Should be ≤ 5% slower than baseline |
| `materialize_springforward_day` | 23-hour DST day, 15-min | Should be ≤ 5% slower than baseline |
| `materialize_year_with_DST` | Full year crossing both transitions | Compare with hypothetical no-DST year |

**Acceptable overhead:** DST-aware `ZonedDateTime.plus()` may be marginally slower than `LocalDateTime.plus()` due to timezone rule lookups. The benchmark validates this overhead stays within 5%. If it exceeds 5%, consider caching the `ZoneRules` instance per materialization batch.

### 12.4 JMH Profiler Configuration

Run benchmarks with multiple profilers to get a complete picture:

```bash
# Standard run with GC profiling
java -jar benchmarks.jar -prof gc

# Stack profiler for hotspot identification
java -jar benchmarks.jar -prof stack

# Linux perf integration (if available)
java -jar benchmarks.jar -prof perf

# Allocation profiler
java -jar benchmarks.jar -prof gc:churn
```

### 12.5 Benchmark Result Validation

Benchmark results must be programmatically compared against the SLA targets. Create a companion JUnit test that parses JMH JSON output and asserts:

```java
@Test
void materializationMeetsP95Target() {
    // Parse JMH result JSON
    // Assert: materialize_DA_15min p95 ≤ 100 μs
    // Assert: materialize_year_15min p95 ≤ 30 ms
    // Assert: materialize_decade_15min p95 ≤ 300 ms
    // Fail build if any SLA breached
}
```

This should run in the CI pipeline on a dedicated benchmark runner (not a shared CI agent — noisy neighbors corrupt benchmark results).

### 12.6 Benchmark Exclusions

The following are explicitly **not** benchmarked at the domain model level because they involve I/O and belong to integration/load test suites:

- Kafka consume/produce latency
- JDBC batch insert performance
- Database connection pool overhead
- Network serialization (Kafka wire format)
- End-to-end pipeline latency (trade capture → detail generation → position)

These are covered by load tests (Section 13).

---

## 13. Load Test Specification

### 13.1 Scope

Load tests validate the end-to-end Detail Generation Service under realistic production conditions, including Kafka consumption, database persistence, and event publishing. They complement the JMH microbenchmarks (which isolate pure domain logic) by adding I/O, concurrency, and resource contention.

### 13.2 Tool

Gatling (Scala/Java DSL) or k6 for HTTP-triggered scenarios. For Kafka-native load testing, use a custom Kafka producer harness that publishes `trade.captured` events at controlled rates.

### 13.3 Load Test Scenarios

#### 13.3.1 DA Auction Burst

Simulates the post-auction burst when 200 DA trades arrive within a 2-second window.

| Parameter | Value |
|---|---|
| Event type | `trade.captured` |
| Trade count | 200 |
| Granularity | MIN_15 |
| Intervals per trade | 96 |
| Total intervals | 19,200 |
| Injection profile | All 200 events within 2 seconds |
| Success criteria | All 200 `trade.details.generated` events emitted within 5 seconds of last `trade.captured` |
| Position readiness | All 200 trades have detail-level positions within 10 seconds |

**Assertions:**

- Zero failed materializations (no DLQ entries)
- p95 individual trade processing time ≤ 500 ms
- Total batch completion ≤ 5 seconds
- Database interval row count = 19,200
- No Kafka consumer lag remaining after completion

#### 13.3.2 Intraday Continuous Steady State

Simulates continuous intraday trading with a sustained arrival rate.

| Parameter | Value |
|---|---|
| Trade arrival rate | 10 trades/sec (sustained) |
| Duration | 5 minutes |
| Granularity | MIN_15 |
| Intervals per trade | 1–4 (randomized) |
| Total trades | 3,000 |
| Success criteria | p99 processing time ≤ 50 ms per trade |

**Assertions:**

- Kafka consumer lag stays below 10 at all times
- No GC pauses > 50 ms during the run
- Heap usage stable (no memory leak signal)
- Error rate < 0.01%

#### 13.3.3 PPA Rolling Horizon Materialization

Simulates capturing a new 10-year PPA and generating the M+3 near-term window plus background chunks.

| Parameter | Value |
|---|---|
| Trade count | 1 |
| Tenor | 10 years |
| Granularity | MIN_15 |
| Near-term window | M+3 (~8,640 intervals) |
| Background chunks | ~117 monthly chunks |
| Total intervals | ~350,400 |
| Success criteria | Near-term materialization ≤ 2 seconds, full background completion ≤ 60 seconds |

**Assertions:**

- `trade.details.generated` (scope=PARTIAL) emitted within 2 seconds
- Near-term position available within 5 seconds
- All monthly chunks completed within 60 seconds
- `materializationStatus` transitions: PENDING → PARTIAL → FULL
- `materializedIntervalCount` reaches `totalExpectedIntervals`
- No duplicate intervals across chunks (uniqueness constraint)
- Memory profile: working set stays below 5 MB during chunked processing

#### 13.3.4 Mixed Workload

Simulates a realistic production mix of trade types arriving concurrently.

| Parameter | Value |
|---|---|
| DA trades | 100 (burst at t=0) |
| Intraday trades | 20/sec (continuous for 60 sec) |
| PPA captures | 3 (staggered at t=0, t=20, t=40) |
| PPA tenors | 3 years, 5 years, 10 years |
| Duration | 60 seconds |
| Total trades | ~1,303 |
| Total intervals | ~800,000+ |

**Assertions:**

- DA burst completes within 5 seconds despite concurrent PPA processing
- Intraday p99 stays below 100 ms despite background chunk generation
- PPA chunk processing does not starve DA/intraday consumer threads
- No Kafka partition rebalancing during the run
- Database connection pool utilization stays below 80%

#### 13.3.5 Amendment Storm

Simulates rapid amendments to existing trades, testing version increment and re-materialization.

| Parameter | Value |
|---|---|
| Initial trades | 50 DA trades (pre-existing) |
| Amendments | 50 amendments (one per trade, volume change) |
| Injection profile | All 50 within 1 second |
| Success criteria | New version materialized and old version intervals untouched |

**Assertions:**

- 50 new `VolumeSeries` created with `tradeVersion = 2`
- Original version 1 intervals remain in database unchanged (immutability)
- New intervals reflect amended volume
- p95 amendment processing time ≤ 500 ms

#### 13.3.6 Failure Recovery

Simulates database outage during chunk processing and validates DLQ + retry behavior.

| Parameter | Value |
|---|---|
| Initial trade | 1 PPA, 5-year |
| Fault injection | Kill database connection after chunk 6 of 60 |
| Recovery | Restore database after 30 seconds |
| Success criteria | Failed chunks land in DLQ, retry succeeds after recovery |

**Assertions:**

- Chunks 1–6 persisted before outage
- Chunks 7+ routed to DLQ with failure status
- `materializationStatus = FAILED` set on the series
- After recovery: DLQ consumer retries failed chunks
- Final state: `materializationStatus = FULL`, all intervals present
- No duplicate intervals from retry (idempotent chunk processing)

### 13.4 Infrastructure Requirements for Load Tests

| Resource | Specification | Notes |
|---|---|---|
| Kafka | 3-broker cluster, 12 partitions for `trade.captured` topic | Match production partition count |
| Database | PostgreSQL 15+, 4 vCPU, 16 GB RAM | Match production instance class |
| Detail Generation Service | 3 pod replicas, 2 vCPU / 4 GB each | Match production deployment |
| Monitoring | Prometheus + Grafana with JVM metrics | GC, heap, thread pool, Kafka lag |
| Network | Same-AZ deployment | Eliminate network variance |

### 13.5 Key Metrics to Capture During Load Tests

| Category | Metric | Alert Threshold |
|---|---|---|
| Latency | Detail generation p50/p95/p99 per trade type | p99 exceeds 2× SLA |
| Throughput | Intervals materialized per second | Below 50,000/sec sustained |
| Kafka | Consumer lag (per partition) | Lag > 100 for > 30 seconds |
| Kafka | Event publish latency | p99 > 100 ms |
| Database | Active connections | > 80% pool utilization |
| Database | Batch insert latency | p99 > 500 ms for 1000-row batch |
| JVM | GC pause duration | Any pause > 200 ms |
| JVM | Heap used after GC | > 80% of max heap |
| JVM | Thread count | Thread pool exhaustion |
| System | CPU utilization | Sustained > 85% |
| Error | DLQ message count | Any entry during normal operation |
| Error | Materialization failure rate | > 0.1% |

### 13.6 Load Test Output Artifacts

Each load test run must produce:

- Gatling/k6 HTML report with latency percentile charts
- Grafana dashboard snapshot covering the test window
- JVM GC log analysis (GCViewer or GCEasy output)
- Kafka consumer lag timeline
- Database slow query log (queries > 100 ms)
- Summary table comparing actual metrics against SLA targets
- Pass/fail verdict per scenario

---

## 14. Performance Optimization Signals

The benchmarks and load tests will surface optimization opportunities. The following are pre-identified candidates based on the domain model design:

### 14.1 `calculateExpectedIntervals()` — O(1) Arithmetic ✅ IMPLEMENTED

**Previous:** O(n) timeline walk stepping through every interval.
**Implemented:** O(1) arithmetic for all granularities:
- Sub-daily (fixed duration): `Duration.between(start.toInstant(), end.toInstant()).getSeconds() / fixedDuration.getSeconds()`. This is exact because the materialization loop uses `Instant.plusSeconds()`, so DST does not affect interval count.
- DAILY: `ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate())`
- MONTHLY: `ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end))`
**Impact:** ~100 ns regardless of tenor (was ~5 ms for 10-year PPA).

### 14.2 `BigDecimal.divide()` in `calculateEnergy()`

**Current:** Division with scale 6 and HALF_UP rounding per interval.
**Optimization:** Pre-compute duration-in-hours as a `BigDecimal` constant per granularity (e.g., `MIN_15` → `0.25`, `MIN_30` → `0.50`) and use multiplication only. Only fall back to `Duration`-based calculation for DST-crossing intervals.
**Trigger:** Benchmark shows `calculateEnergy()` > 500 ns at p95.

### 14.3 UUID Generation in Materialization Loop ✅ IMPLEMENTED

**Previous:** `UUID.randomUUID()` per interval uses `SecureRandom` — acquires a lock, reads system entropy (~1–3 μs per call). For 350,400 intervals this was 350–1,050 ms.
**Implemented:** `FastUUID.generate()` uses `ThreadLocalRandom` — no lock, no system entropy (~50 ns per call). Valid RFC 4122 v4 UUIDs, suitable for domain identifiers where cryptographic randomness is unnecessary.
**Impact:** 20–60× faster UUID generation; total UUID cost for 10-year PPA drops from ~700 ms to ~18 ms.

### 14.4 Object Allocation in Materialization Loop

**Current:** Each interval allocates two `ZonedDateTime` objects (via `cursor.atZone(zone)`), two `BigDecimal` references (shared for fixed-duration), and a `YearMonth` (cached for sub-daily).
**Optimization:** Cache `ZoneOffset` and only recompute on DST transitions (~2 per year). Or switch to a columnar representation (parallel arrays of `long[]` for timestamps, `double[]` for volume/energy) for in-memory processing, materializing to objects only at persistence time.
**Trigger:** GC profiler shows > 500 MB/sec allocation rate during year-scale materialization.

### 14.5 Batch Persistence Strategy

**Current:** JDBC batch insert with configurable batch size.
**Optimization:** Use PostgreSQL `COPY` command for bulk loads > 10,000 rows. For Aurora, use the Data API batch execute with `ARRAY` types for columnar insert.
**Trigger:** Load test shows JDBC batch insert > 2 sec for 35,000 rows.

### 14.6 Kafka Payload Size

**Current:** Full `VolumeSeries` including intervals in Kafka event payload.
**Optimization:** Claim Check pattern — persist intervals to database first, publish only metadata (trade ID, version, interval count, materialization status) in the Kafka event. Consumer fetches intervals from database.
**Trigger:** Serialization benchmark shows payload > 1 MB or serialize time > 50 ms.
