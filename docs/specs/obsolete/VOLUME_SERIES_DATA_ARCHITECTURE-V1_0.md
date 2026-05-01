# Volume Series — Data Architecture

**Module:** `power-volume-series` data persistence layer
**Domain:** EU Physical Power Trade Capture (CTRM/ETRM)
**Version:** 1.0.0-SNAPSHOT
**Date:** May 2026
**Companion Spec:** VOLUME_SERIES_SPEC-V2.1

---

## 1. Scope

This document defines the data persistence architecture for the `volume-series` domain as specified in VOLUME_SERIES_SPEC-V2.1. It covers physical schema design, DDL, indexing, partitioning, compression, retention, connection pooling, multitenancy enforcement, caching, SLA targets, and operational concerns.

Out of scope: trade master entity (assumed to exist in the broader CTRM codebase), counterparty master, delivery point master, trading calendar — these are referenced via foreign keys but their full schemas are owned by other modules.

## 2. Architecture Principles

The data architecture is governed by the following principles, in order of precedence:

**P1 — Multitenancy via discriminator column.** Every persisted row carries a `tenant_id`. Application-layer enforcement plus row-level security policies prevent cross-tenant data access. This pattern was established at the platform level and is non-negotiable.

**P2 — Contractual immutability.** CONTRACTUAL bucket rows are never updated or deleted by application code. The only path that removes them is the regulatory retention policy after settlement+7yr+6mo. This is enforced both by application convention and by a database-level update trigger on the `bucket_type='CONTRACTUAL'` predicate.

**P3 — Write path uses native sequences.** Per the platform mandate, IDs use PostgreSQL native sequences with `allocationSize=50` for Hibernate batch insert efficiency. `IDENTITY` strategy is prohibited because it disables Hibernate batch inserts.

**P4 — Read/write split via dual HikariCP pools.** The Aurora reader endpoint serves analytical queries (position aggregation, regulatory reports, audit). The writer endpoint serves Kafka consumer writes and CRUD operations. The split is enforced at the DataSource bean level, not via Spring `@Transactional(readOnly=true)` (which routes to writer in current Spring Data JPA behavior).

**P5 — Cascade invariants enforced at DB level where feasible.** Where a check constraint can express an invariant cheaply (e.g., `bucket_type IN (...)`, `interval_end > interval_start`), it is added. Complex invariants (energy conservation, scalar derivation correctness) are enforced at the application layer with test coverage.

**P6 — Compression and retention are time-bound automated policies.** No human-driven archival. TimescaleDB compression and retention policies run on schedule. The compression boundary is delivery-period-based (not chunk-creation-time-based).

**P7 — Hot path is separated from cold path.** Position calculation reads only NEAR_TERM hot intervals via Redis cache. PostgreSQL is consulted only on cache miss or for non-position queries. The cold path (regulatory reporting, historical analysis) uses the reader endpoint with no Redis involvement.

## 3. Database Topology

### 3.1 Cluster Configuration

| Component | Specification | Notes |
|---|---|---|
| Engine | Aurora PostgreSQL 16 | Per platform standard |
| Extension | TimescaleDB 2.15+ | Required for hypertables on `volume_interval` |
| Writer | 1 instance, db.r7g.2xlarge (8 vCPU / 64 GB RAM) | Initial sizing; revisit at 100 active tenants |
| Readers | 2 instances, db.r7g.2xlarge | Reader endpoint load-balanced across both |
| Storage | Aurora I/O-optimized | Justifies cost at the IOPS this workload generates |
| Backup | 7-day point-in-time recovery + 35-day automated snapshots | Aurora native |

### 3.2 Schema Organization

Two PostgreSQL schemas:

- `volume_series` — domain tables for this module (the focus of this document)
- `volume_audit` — bi-temporal audit trail (separate schema for retention boundary clarity)

Cross-schema FKs are not used. References to `trade.trade` and `tenant.tenant` are by ID without enforced FK (deferred constraints break our async write pattern). Application layer ensures referential integrity.

### 3.3 Connection Pool Configuration

Two HikariCP pools per service instance, configured as Spring `@Bean`s with primary/qualifier annotations:

| Pool | Endpoint | Max Pool Size | Min Idle | Connection Timeout | Idle Timeout | Max Lifetime |
|---|---|---|---|---|---|---|
| `writeDataSource` | Aurora writer | 20 | 5 | 3000ms | 600000ms | 1800000ms |
| `readDataSource` | Aurora reader (load-balanced) | 30 | 10 | 3000ms | 600000ms | 1800000ms |

Reader pool is sized larger because analytical queries are longer-running and read traffic dominates. The `connectionTimeout=3000ms` enforces fast-fail behavior; if a thread can't get a connection in 3 seconds, the request fails rather than queueing indefinitely.

`leakDetectionThreshold` is set to 60000ms to catch unreturned connections — important given Kafka consumer poll loops and the risk of forgetting to close a connection in error paths.

## 4. Schema Structure

### 4.1 Entity-Relationship Overview

```
tenant (external)
  ↓
trade (external) ─── 1:N ─── trade_leg (external)
                                ↓
                              1:1
                                ↓
                          volume_series ─── 1:1 ─── volume_formula
                                ↓                      ↓
                              1:N                    1:N
                                ↓                      ↓
                          volume_interval        shaping_entry
                          (HYPERTABLE)           seasonal_adjustment

  materialization_chunk_status (1:N child of volume_series)
```

### 4.2 Table Inventory

| Table | Purpose | Row Estimate (steady state, 200 tenants) | Hypertable |
|---|---|---|---|
| `volume_series` | Root aggregate, one per trade leg version | ~80,000 | No |
| `volume_formula` | Contractual recipe, 1:1 with series | ~80,000 | No |
| `shaping_entry` | Time-of-use blocks within formula | ~400,000 | No |
| `seasonal_adjustment` | Year/season modifiers within formula | ~100,000 | No |
| `volume_interval` | Materialized intervals (CONTRACTUAL + PLAN + ACTUAL) | ~10 billion (over 7-year retention) | **Yes** |
| `materialization_chunk_status` | Per-chunk tracking for async generation | ~2,000,000 | No |
| `volume_audit.series_history` | Bi-temporal audit of series-level changes | ~500,000 | No |

## 5. DDL — Domain Tables

All DDL is Flyway-versioned. Migration files are numbered sequentially per platform convention.

### 5.1 Sequences

```sql
-- V001__volume_series_sequences.sql
CREATE SEQUENCE volume_series.volume_series_seq START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.volume_formula_seq START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.shaping_entry_seq START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.seasonal_adjustment_seq START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.volume_interval_seq START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.chunk_status_seq START 1 INCREMENT 50;
```

`INCREMENT 50` aligns with Hibernate's `allocationSize=50` for batch insert efficiency. Per the platform learning, `IDENTITY` strategy silently disables Hibernate batch inserts and is prohibited.

### 5.2 Enum Types

PostgreSQL native enums are used rather than CHECK constraints because they integrate cleanly with Hibernate's `@Enumerated(EnumType.STRING)` and produce clearer pg_dump output.

```sql
-- V002__volume_series_enums.sql
CREATE TYPE volume_series.bucket_type AS ENUM (
    'CONTRACTUAL', 'PLAN', 'ACTUAL'
);

CREATE TYPE volume_series.cascade_tier AS ENUM (
    'NEAR_TERM', 'MEDIUM_TERM', 'LONG_TERM'
);

CREATE TYPE volume_series.time_granularity AS ENUM (
    'MIN_5', 'MIN_15', 'MIN_30', 'HOURLY', 'DAILY', 'MONTHLY'
);

CREATE TYPE volume_series.profile_type AS ENUM (
    'BASELOAD', 'PEAKLOAD', 'OFFPEAK', 'SHAPED', 'BLOCK', 'GENERATION_FOLLOWING'
);

CREATE TYPE volume_series.materialization_status AS ENUM (
    'PENDING', 'PARTIAL', 'FULL', 'FAILED'
);

CREATE TYPE volume_series.interval_status AS ENUM (
    'CONFIRMED', 'ESTIMATED', 'PROVISIONAL', 'CANCELLED'
);

CREATE TYPE volume_series.volume_unit AS ENUM (
    'MW_CAPACITY', 'MWH_PER_PERIOD'
);

CREATE TYPE volume_series.chunk_status AS ENUM (
    'PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'DLQ'
);
```

### 5.3 volume_series

```sql
-- V003__volume_series_table.sql
CREATE TABLE volume_series.volume_series (
    id                          BIGINT PRIMARY KEY DEFAULT nextval('volume_series.volume_series_seq'),
    series_uuid                 UUID NOT NULL UNIQUE,
    tenant_id                   UUID NOT NULL,
    trade_id                    VARCHAR(64) NOT NULL,
    trade_leg_id                VARCHAR(64) NOT NULL,
    trade_version               INTEGER NOT NULL,
    volume_unit                 volume_series.volume_unit NOT NULL,
    delivery_start              TIMESTAMPTZ NOT NULL,
    delivery_end                TIMESTAMPTZ NOT NULL,
    delivery_timezone           VARCHAR(64) NOT NULL,
    base_granularity            volume_series.time_granularity NOT NULL,
    profile_type                volume_series.profile_type NOT NULL,
    materialization_status      volume_series.materialization_status NOT NULL,
    materialized_through        DATE,
    total_expected_intervals    INTEGER NOT NULL,
    materialized_interval_count INTEGER NOT NULL DEFAULT 0,
    transaction_time            TIMESTAMPTZ NOT NULL,
    valid_time                  TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    CONSTRAINT chk_delivery_window CHECK (delivery_end > delivery_start),
    CONSTRAINT chk_interval_count CHECK (materialized_interval_count <= total_expected_intervals),
    CONSTRAINT uq_trade_leg_version UNIQUE (tenant_id, trade_id, trade_leg_id, trade_version)
);

CREATE INDEX idx_vs_tenant_trade ON volume_series.volume_series (tenant_id, trade_id);
CREATE INDEX idx_vs_tenant_status ON volume_series.volume_series (tenant_id, materialization_status) 
    WHERE materialization_status IN ('PENDING', 'PARTIAL', 'FAILED');
CREATE INDEX idx_vs_delivery_window ON volume_series.volume_series (tenant_id, delivery_start, delivery_end);

ALTER TABLE volume_series.volume_series ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.volume_series
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

Notes:
- `id` is BIGINT for compactness (FK references) while `series_uuid` is the public identifier exposed in events and APIs.
- `materialized_through` is `DATE` (not `YearMonth`) because PostgreSQL has no native `YearMonth` type; convention is to store the first day of the month.
- The partial index on incomplete materialization status keeps the index small (only non-FULL series are interesting for the chunk processor).
- Row-level security uses a session variable set by the application's `@TenantAware` aspect.

### 5.4 volume_formula

```sql
-- V004__volume_formula_table.sql
CREATE TABLE volume_series.volume_formula (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('volume_series.volume_formula_seq'),
    formula_uuid        UUID NOT NULL UNIQUE,
    series_id           BIGINT NOT NULL UNIQUE,  -- 1:1 enforced by UNIQUE
    tenant_id           UUID NOT NULL,
    base_volume         NUMERIC(15, 6),
    min_volume          NUMERIC(15, 6),
    max_volume          NUMERIC(15, 6),
    forecast_source_id  VARCHAR(128),
    forecast_multiplier NUMERIC(8, 6),
    calendar_id         VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    CONSTRAINT fk_formula_series FOREIGN KEY (series_id) 
        REFERENCES volume_series.volume_series(id) ON DELETE CASCADE,
    CONSTRAINT chk_tolerance_band CHECK (
        (min_volume IS NULL AND max_volume IS NULL) OR
        (min_volume IS NOT NULL AND max_volume IS NOT NULL AND min_volume <= max_volume)
    )
);

CREATE INDEX idx_vf_tenant ON volume_series.volume_formula (tenant_id);

ALTER TABLE volume_series.volume_formula ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.volume_formula
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

### 5.5 shaping_entry

```sql
-- V005__shaping_entry_table.sql
CREATE TABLE volume_series.shaping_entry (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('volume_series.shaping_entry_seq'),
    formula_id          BIGINT NOT NULL,
    tenant_id           UUID NOT NULL,
    applicable_days     SMALLINT NOT NULL,  -- bitmask: Mon=1, Tue=2, Wed=4, ... Sun=64
    block_start         TIME NOT NULL,
    block_end           TIME NOT NULL,
    volume              NUMERIC(15, 6) NOT NULL,
    applies_to_holidays BOOLEAN NOT NULL DEFAULT false,
    valid_from_month    SMALLINT,  -- 1..12 or null for all months
    valid_to_month      SMALLINT,
    
    CONSTRAINT fk_shaping_formula FOREIGN KEY (formula_id) 
        REFERENCES volume_series.volume_formula(id) ON DELETE CASCADE,
    CONSTRAINT chk_block_window CHECK (block_end > block_start),
    CONSTRAINT chk_month_range CHECK (
        (valid_from_month IS NULL AND valid_to_month IS NULL) OR
        (valid_from_month BETWEEN 1 AND 12 AND valid_to_month BETWEEN 1 AND 12)
    ),
    CONSTRAINT chk_applicable_days CHECK (applicable_days BETWEEN 0 AND 127)
);

CREATE INDEX idx_se_formula ON volume_series.shaping_entry (formula_id);
```

Day-of-week as a bitmask (`SMALLINT`) is more compact than a separate join table for `Set<DayOfWeek>` and the cardinality is fixed at 7.

### 5.6 seasonal_adjustment

```sql
-- V006__seasonal_adjustment_table.sql
CREATE TABLE volume_series.seasonal_adjustment (
    id              BIGINT PRIMARY KEY DEFAULT nextval('volume_series.seasonal_adjustment_seq'),
    formula_id      BIGINT NOT NULL,
    tenant_id       UUID NOT NULL,
    from_month      SMALLINT NOT NULL,
    to_month        SMALLINT NOT NULL,
    from_year       INTEGER,
    to_year         INTEGER,
    multiplier      NUMERIC(8, 6),
    absolute_adj    NUMERIC(15, 6),
    
    CONSTRAINT fk_sa_formula FOREIGN KEY (formula_id) 
        REFERENCES volume_series.volume_formula(id) ON DELETE CASCADE,
    CONSTRAINT chk_sa_months CHECK (from_month BETWEEN 1 AND 12 AND to_month BETWEEN 1 AND 12),
    CONSTRAINT chk_sa_years CHECK (
        (from_year IS NULL AND to_year IS NULL) OR
        (from_year IS NOT NULL AND to_year IS NOT NULL AND from_year <= to_year)
    ),
    CONSTRAINT chk_sa_has_adjustment CHECK (multiplier IS NOT NULL OR absolute_adj IS NOT NULL)
);

CREATE INDEX idx_sa_formula ON volume_series.seasonal_adjustment (formula_id);
```

### 5.7 volume_interval (HYPERTABLE — wide table with bucket dimension)

This is the central table. It uses TimescaleDB hypertable partitioning by `interval_start`.

```sql
-- V007__volume_interval_table.sql
CREATE TABLE volume_series.volume_interval (
    id                          BIGINT NOT NULL DEFAULT nextval('volume_series.volume_interval_seq'),
    interval_uuid               UUID NOT NULL,
    series_id                   BIGINT NOT NULL,
    tenant_id                   UUID NOT NULL,
    trade_id                    VARCHAR(64) NOT NULL,
    
    -- Bucket and cascade dimensions
    bucket_type                 volume_series.bucket_type NOT NULL,
    cascade_tier                volume_series.cascade_tier,
    effective_granularity       volume_series.time_granularity NOT NULL,
    
    -- Time and core volume
    interval_start              TIMESTAMPTZ NOT NULL,
    interval_end                TIMESTAMPTZ NOT NULL,
    volume                      NUMERIC(15, 6),
    energy                      NUMERIC(18, 6),
    status                      volume_series.interval_status NOT NULL,
    chunk_month                 DATE,
    
    -- Wide-table RATE scalars
    contracted_mw               NUMERIC(15, 6),
    plan_mw                     NUMERIC(15, 6),
    actual_mw                   NUMERIC(15, 6),
    nominated_mw                NUMERIC(15, 6),
    base_price_eur_mwh          NUMERIC(12, 4),
    green_premium_eur_mwh       NUMERIC(12, 4),
    tolerance_floor_mw          NUMERIC(15, 6),
    tolerance_ceiling_mw        NUMERIC(15, 6),
    forecast_mw                 NUMERIC(15, 6),
    
    -- Wide-table ABSOLUTE scalars
    balancing_cost_eur          NUMERIC(15, 4),
    imbalance_penalty_eur       NUMERIC(15, 4),
    congestion_rent_eur         NUMERIC(15, 4),
    profile_cost_eur            NUMERIC(15, 4),
    
    -- Dynamic overflow
    custom_scalars              JSONB,
    
    -- Audit
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    settlement_finalized_at     TIMESTAMPTZ,  -- Set when post-settlement; drives retention clock
    
    CONSTRAINT chk_vi_window CHECK (interval_end > interval_start),
    CONSTRAINT chk_vi_bucket_granularity CHECK (
        -- ACTUAL must be at base granularity (operationally always 15-min in EU)
        bucket_type != 'ACTUAL' OR effective_granularity IN ('MIN_5', 'MIN_15', 'MIN_30', 'HOURLY')
    ),
    PRIMARY KEY (id, interval_start)
) PARTITION BY RANGE (interval_start);

-- Convert to TimescaleDB hypertable
SELECT create_hypertable(
    'volume_series.volume_interval',
    'interval_start',
    chunk_time_interval => INTERVAL '1 month',
    if_not_exists => TRUE
);

-- Set space partitioning by tenant_id for parallel query/write isolation
SELECT add_dimension(
    'volume_series.volume_interval',
    'tenant_id',
    number_partitions => 8
);
```

Notes:
- Primary key is `(id, interval_start)` because TimescaleDB requires the partitioning column to be in the primary key.
- The wide table has 14 named scalar columns plus `custom_scalars` JSONB. NULL columns compress essentially to zero overhead post-compression.
- The `(bucket_type, cascade_tier, effective_granularity)` triple is the new key dimensional metadata. Indexes below address the dominant query patterns.
- The space partitioning by `tenant_id` (8 partitions) gives parallel write isolation across tenants. Combined with the time partitioning (monthly chunks), each tenant's writes for a given month land in a distinct chunk. This keeps write contention low even with 200 tenants writing concurrently.

### 5.8 Indexes on volume_interval

```sql
-- V008__volume_interval_indexes.sql

-- Primary access pattern: tenant + delivery range + bucket
-- Used by position calculation, settlement, regulatory reports
CREATE INDEX idx_vi_tenant_time_bucket 
    ON volume_series.volume_interval (tenant_id, interval_start DESC, bucket_type);

-- Series-level queries: getContractualIntervals, getPlanIntervals, getActualIntervals
-- Used by domain service methods filtering by series + bucket
CREATE INDEX idx_vi_series_bucket 
    ON volume_series.volume_interval (series_id, bucket_type, interval_start);

-- Trade-level reconciliation: all buckets for a specific trade
CREATE INDEX idx_vi_trade_time 
    ON volume_series.volume_interval (tenant_id, trade_id, interval_start);

-- Chunk processor lookup: finds intervals by chunk month for retry/reconciliation
CREATE INDEX idx_vi_chunk_month 
    ON volume_series.volume_interval (series_id, chunk_month, bucket_type)
    WHERE chunk_month IS NOT NULL;

-- Cascade tier analytical queries: "show me all LONG_TERM contractuals"
-- Partial index keeps it small (only non-NULL cascade tiers)
CREATE INDEX idx_vi_cascade_tier
    ON volume_series.volume_interval (tenant_id, cascade_tier, bucket_type)
    WHERE cascade_tier IS NOT NULL;

-- Settlement reconciliation: actuals for a delivery period across all trades
-- Used by settlement reconciliation jobs
CREATE INDEX idx_vi_actual_settlement
    ON volume_series.volume_interval (tenant_id, interval_start, settlement_finalized_at)
    WHERE bucket_type = 'ACTUAL';

ALTER TABLE volume_series.volume_interval ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.volume_interval
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

Index strategy rationale:
- The lead column is always `tenant_id` for multitenancy filter pushdown.
- Each index targets one named query pattern; we resist the urge to "cover everything with one mega-index."
- Partial indexes (`WHERE` clauses) keep index size proportional to the relevant subset.
- The compound index ordering `(tenant_id, interval_start DESC, bucket_type)` matches the most common query: "get me everything for tenant X around delivery time T, possibly filtered by bucket."

### 5.9 materialization_chunk_status

Tracks per-chunk state for async generation. This is the table the chunk processor reads to find pending work and the DLQ retry job reads to find failures.

```sql
-- V009__materialization_chunk_status_table.sql
CREATE TABLE volume_series.materialization_chunk_status (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('volume_series.chunk_status_seq'),
    series_id           BIGINT NOT NULL,
    tenant_id           UUID NOT NULL,
    chunk_month         DATE NOT NULL,
    cascade_tier        volume_series.cascade_tier NOT NULL,
    status              volume_series.chunk_status NOT NULL,
    expected_intervals  INTEGER NOT NULL,
    actual_intervals    INTEGER NOT NULL DEFAULT 0,
    retry_count         INTEGER NOT NULL DEFAULT 0,
    last_error          TEXT,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    CONSTRAINT fk_chunk_series FOREIGN KEY (series_id) 
        REFERENCES volume_series.volume_series(id) ON DELETE CASCADE,
    CONSTRAINT uq_chunk_series_month_tier UNIQUE (series_id, chunk_month, cascade_tier)
);

CREATE INDEX idx_mcs_pending 
    ON volume_series.materialization_chunk_status (tenant_id, status, chunk_month)
    WHERE status IN ('PENDING', 'IN_PROGRESS');

CREATE INDEX idx_mcs_dlq
    ON volume_series.materialization_chunk_status (tenant_id, retry_count, updated_at)
    WHERE status = 'DLQ';
```

### 5.10 Trigger: enforce CONTRACTUAL immutability

```sql
-- V010__contractual_immutability_trigger.sql
CREATE OR REPLACE FUNCTION volume_series.prevent_contractual_update()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.bucket_type = 'CONTRACTUAL' AND TG_OP = 'UPDATE' THEN
        -- Allow only updates to settlement_finalized_at and updated_at (administrative)
        IF OLD.volume IS DISTINCT FROM NEW.volume
           OR OLD.energy IS DISTINCT FROM NEW.energy
           OR OLD.contracted_mw IS DISTINCT FROM NEW.contracted_mw
           OR OLD.base_price_eur_mwh IS DISTINCT FROM NEW.base_price_eur_mwh
           OR OLD.tolerance_floor_mw IS DISTINCT FROM NEW.tolerance_floor_mw
           OR OLD.tolerance_ceiling_mw IS DISTINCT FROM NEW.tolerance_ceiling_mw
           OR OLD.cascade_tier IS DISTINCT FROM NEW.cascade_tier
           OR OLD.effective_granularity IS DISTINCT FROM NEW.effective_granularity
           OR OLD.interval_start IS DISTINCT FROM NEW.interval_start
           OR OLD.interval_end IS DISTINCT FROM NEW.interval_end
        THEN
            RAISE EXCEPTION 'CONTRACTUAL intervals are immutable (interval id=%)', OLD.id
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF OLD.bucket_type = 'CONTRACTUAL' AND TG_OP = 'DELETE' THEN
        -- DELETE only permitted if invoked via retention policy session variable
        IF current_setting('app.retention_purge_active', true) IS DISTINCT FROM 'true' THEN
            RAISE EXCEPTION 'CONTRACTUAL intervals can only be deleted by the retention purge job (interval id=%)', OLD.id
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_contractual_immutability
    BEFORE UPDATE OR DELETE ON volume_series.volume_interval
    FOR EACH ROW EXECUTE FUNCTION volume_series.prevent_contractual_update();
```

This is a database-level enforcement of invariant 6.9 from the spec. Application-level enforcement is also present (it's faster to fail at the service layer), but the DB trigger is the safety net for direct SQL access, broken application code, or migration mistakes. The retention purge job sets `app.retention_purge_active='true'` for its session to bypass the trigger legitimately.

## 6. Compression Strategy

### 6.1 Policy Definition

```sql
-- V011__compression_policy.sql
ALTER TABLE volume_series.volume_interval SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id, trade_id, bucket_type',
    timescaledb.compress_orderby = 'interval_start, series_id'
);

-- Compress chunks where the latest interval is more than 6 months old
-- This corresponds to settled delivery periods
SELECT add_compression_policy(
    'volume_series.volume_interval',
    compress_after => INTERVAL '6 months'
);
```

### 6.2 Why these compression parameters

`compress_segmentby = 'tenant_id, trade_id, bucket_type'` is the key choice. After compression, queries that filter by these columns are highly efficient because they hit a single segment per tenant/trade/bucket combination. Within each segment, the values are stored columnar (excellent compression on repeated tenant_ids and trade_ids) and the scalar columns compress at 90%+ ratios because they're highly repetitive across intervals (same `contracted_mw=50` for an entire month of 15-min intervals).

`compress_orderby = 'interval_start, series_id'` keeps intervals in time order within each segment, which enables fast time-range scans on compressed chunks.

### 6.3 Compression Performance Expectations

Based on TimescaleDB benchmarks for similar workloads:

| Scenario | Uncompressed | Compressed | Ratio |
|---|---|---|---|
| 1 month of 15-min CONTRACTUAL for one PPA (~2,976 rows) | ~3 MB | ~250 KB | ~12x |
| 1 month of all three buckets for one PPA (~8,960 rows) | ~9 MB | ~700 KB | ~13x |
| Full year for 4,000 PPAs across 200 tenants | ~430 GB | ~33 GB | ~13x |

Compressed chunks are read-only at the row level (writes require decompression). This is acceptable because settled CONTRACTUAL data shouldn't change. ACTUAL corrections do happen but they create new versioned rows rather than updating existing ones.

### 6.4 Decompression Path for Corrections

When a settled ACTUAL needs correction (rare, but happens — TSO submits corrected metering data weeks later), the application must:

1. Set session variable: `SET timescaledb.enable_chunk_skipping = false;` (only for the connection performing the correction)
2. Decompress the affected chunk: `SELECT decompress_chunk(chunk_name);`
3. Insert the correction as a new row (do not update the original — append-only semantics for ACTUAL)
4. Re-compress: `SELECT compress_chunk(chunk_name);`

This is operationally intensive and should be batched (collect all corrections for a chunk, decompress once, apply all, re-compress). A nightly correction batch job is recommended over real-time correction processing.

## 7. Retention Strategy

### 7.1 Policy Definition

```sql
-- V012__retention_policy.sql
SELECT add_retention_policy(
    'volume_series.volume_interval',
    drop_after => INTERVAL '7 years 6 months',
    schedule_interval => INTERVAL '1 day'
);
```

### 7.2 Retention Clock Anchoring

The naive policy above keys retention off `interval_start`. This is incorrect for our domain because:

- A delivery interval on 2026-08-01 might not have its settlement finalized until 2026-10-15 (typical 45-90 day settlement cycle plus dispute window)
- MiFID II's 7-year clock starts from settlement, not from delivery

So we need a custom retention job, not the default policy. Replace the `add_retention_policy` call with a scheduled procedure:

```sql
-- V012__retention_policy.sql (corrected)
CREATE OR REPLACE PROCEDURE volume_series.purge_expired_intervals(job_id INTEGER, config JSONB)
LANGUAGE plpgsql AS $$
DECLARE
    cutoff TIMESTAMPTZ;
BEGIN
    -- Set the bypass flag for the immutability trigger
    PERFORM set_config('app.retention_purge_active', 'true', true);
    
    -- Compute cutoff: 7 years + 6 months buffer past settlement finalization
    cutoff := now() - INTERVAL '7 years 6 months';
    
    -- Delete intervals where settlement was finalized before the cutoff
    DELETE FROM volume_series.volume_interval
    WHERE settlement_finalized_at IS NOT NULL
      AND settlement_finalized_at < cutoff;
    
    -- For unsettled-but-old intervals (rare), apply a separate ceiling at 10 years
    -- post delivery to prevent indefinite retention
    DELETE FROM volume_series.volume_interval
    WHERE settlement_finalized_at IS NULL
      AND interval_start < (now() - INTERVAL '10 years');
    
    -- Reset the bypass flag (defense in depth — also resets at session end)
    PERFORM set_config('app.retention_purge_active', 'false', true);
END;
$$;

SELECT add_job(
    'volume_series.purge_expired_intervals',
    schedule_interval => INTERVAL '1 day',
    initial_start => CURRENT_DATE + TIME '03:00:00'
);
```

This runs nightly at 03:00 UTC and applies the correct two-track retention rule.

### 7.3 GDPR Anonymization

The `volume_interval` table itself contains no personal data (volumes, prices, costs — all financial/operational). Personal data (trader names, counterparty contact persons) lives on the `trade` and `counterparty` tables, which are owned by other modules.

This module is responsible for ensuring that volume series records can be **dissociated** from personal data when the parent trade's personal fields are anonymized. Since we reference `trade_id` as a string (no FK), no cascade is needed — the volume series rows simply become unlinkable to the original natural persons after the trade module anonymizes its own personal fields.

## 8. Bi-Temporal Audit

The spec requires (invariant 6.5) that `transaction_time` and `valid_time` are captured for REMIT regulatory reporting ("what did we know at point in time T").

### 8.1 series_history table

```sql
-- V013__series_history_table.sql
CREATE TABLE volume_audit.series_history (
    id                          BIGSERIAL PRIMARY KEY,
    series_id                   BIGINT NOT NULL,
    tenant_id                   UUID NOT NULL,
    trade_id                    VARCHAR(64) NOT NULL,
    trade_version               INTEGER NOT NULL,
    operation                   VARCHAR(16) NOT NULL,  -- 'INSERT', 'UPDATE', 'DELETE'
    transaction_time            TIMESTAMPTZ NOT NULL,
    valid_time                  TIMESTAMPTZ NOT NULL,
    series_state                JSONB NOT NULL,  -- snapshot of the series row at this point
    actor                       VARCHAR(128),    -- user or system that made the change
    
    CONSTRAINT chk_operation CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE'))
);

CREATE INDEX idx_sh_series_time 
    ON volume_audit.series_history (series_id, transaction_time DESC);
CREATE INDEX idx_sh_tenant_trade 
    ON volume_audit.series_history (tenant_id, trade_id, transaction_time DESC);
```

### 8.2 What we do NOT audit

We do not maintain a full audit trail of every interval change because:

- CONTRACTUAL intervals are immutable (no audit needed; the original row IS the audit)
- ACTUAL intervals are append-only with versioning (corrections create new rows, not updates)
- PLAN intervals change frequently and their history is not regulatory-required

For PLAN history, if needed for operational forensics, we rely on Kafka topic retention (`trade.details.generated` events for 7 days) plus the chunk status table for trace reconstruction.

## 9. Multitenancy Enforcement

### 9.1 Three Layers of Defense

**Layer 1 — Application:** `@TenantAware` aspect intercepts every repository call, sets `app.tenant_id` session variable, and clears it after the call. Service-layer code cannot accidentally bypass it.

**Layer 2 — Row-Level Security:** Every domain table has RLS enabled with a policy filtering on `current_setting('app.tenant_id', true)`. If the application aspect fails, RLS returns zero rows rather than cross-tenant data.

**Layer 3 — Index lead column:** Every multi-column index has `tenant_id` as the lead column. This means even a forced cross-tenant query (e.g., a malicious admin running raw SQL) is bounded by the row-level security filter and the query plan still uses the index efficiently.

### 9.2 The Aurora Reader Concern

RLS session variables don't propagate to read replicas automatically. Each connection to a reader must independently set `app.tenant_id`. The `@TenantAware` aspect handles this on both pools (`writeDataSource` and `readDataSource`) by intercepting at the JDBC connection acquisition.

## 10. Cache Strategy (Redis)

The Position Service maintains a Redis cache of net positions per delivery interval. The cache is populated on write (Kafka consumer side-effect) and consulted on read (Position Service queries).

### 10.1 Cache Key Structure

```
position:{tenant_id}:{delivery_point_id}:{interval_start_iso}
```

Example: `position:550e8400-e29b-41d4-a716-446655440000:DE-LU-001:2026-08-01T00:00:00Z`

### 10.2 What's Cached

The cache holds the **net PLAN position** (sum of all PLAN interval volumes across all trades for a given tenant + delivery point + delivery interval). It is a derived view, not a copy of the volume_interval table.

CONTRACTUAL and ACTUAL data are never cached — they're queried from PostgreSQL directly when needed. CONTRACTUAL is queried rarely (audit, regulatory). ACTUAL is queried for settlement reconciliation, also infrequently.

### 10.3 Atomicity

The Kafka consumer pattern is:
1. Begin database transaction
2. Insert PLAN intervals into volume_interval
3. Compute new net position for affected delivery intervals
4. Inside a Redis MULTI/EXEC block: update position keys
5. Commit database transaction
6. Publish `position.updated` event to Kafka

If the Redis update fails, we roll back the database transaction. The MULTI/EXEC block ensures Redis-side atomicity (multi-key consistency). PostgreSQL is the source of truth; Redis is rebuildable from a full table scan in case of corruption.

### 10.4 TTL and Eviction

Position keys have a 7-day TTL. Beyond that window, queries fall through to PostgreSQL. This keeps Redis memory bounded — at 200 tenants × 100 delivery points × 96 intervals/day × 7 days = ~13.4M keys, well within a single ElastiCache instance.

## 11. SLA Targets

These targets reflect what the data layer must deliver to the application services. They are derived from:
- EU power market operational deadlines (gate closures, nomination windows)
- Customer contract commitments (subscription tier SLAs)
- Industry expectations for ETRM platforms in this segment

**SLA is load-independent.** Targets below are absolute response times the system must meet under stated capacity conditions, not a function of test load. The single exception is connection pool wait time, which is inherently load-dependent.

### 11.1 Write SLAs

| Operation | Scope | p95 | p99 | Capacity Assumption |
|---|---|---|---|---|
| Single interval insert (VolumeInterval) | 1 row | 5 ms | 15 ms | Up to 200 trades/sec sustained |
| Batch insert (DA day, 96 intervals) | 96 rows | 30 ms | 80 ms | Within DA auction burst |
| Batch insert (monthly chunk, ~2,976 intervals) | 2,976 rows | 300 ms | 800 ms | Background chunk processor |
| VolumeSeries upsert | 1 row + cascade | 15 ms | 40 ms | Trade capture path |
| Materialization chunk status update | 1 row | 5 ms | 15 ms | Per-chunk lifecycle event |
| ACTUAL append | 1 row | 8 ms | 25 ms | Settlement feed ingestion |

### 11.2 Read SLAs

| Operation | Scope | p95 | p99 | Capacity Assumption |
|---|---|---|---|---|
| Get series by ID | 1 row | 3 ms | 10 ms | Trade detail view |
| Get all CONTRACTUAL for series | ~200 rows (10yr PPA) | 50 ms | 150 ms | Audit/regulatory query |
| Get all PLAN for delivery period | ~96 rows | 20 ms | 60 ms | Position calculation cache miss |
| Get all 3 buckets for delivery period | ~290 rows | 80 ms | 200 ms | Reconciliation view |
| Position aggregation across tenant (Redis hit) | 1 key | 1 ms | 5 ms | Hot path |
| Position aggregation (Redis miss → PG) | ~1,000 rows | 100 ms | 300 ms | Cold path fallback |
| Cross-PPA settlement query (tenant scope, 1 month) | ~3M rows scanned | 2 sec | 5 sec | Monthly settlement run |
| Regulatory report extract (REMIT, 1 month) | ~3M rows | 10 sec | 30 sec | Monthly regulatory submission |

### 11.3 Latency Budget Allocation

For the critical path (Kafka consumer → DB write → Redis update → Kafka publish), the 250ms p95 end-to-end target decomposes as:

| Stage | Allocated p95 | Source |
|---|---|---|
| Kafka consume + deserialize | 5 ms | Application |
| Domain logic (interval materialization) | 30 ms | Application (per VOLUME_SERIES_SPEC §11) |
| DB write (batch insert + series update) | 50 ms | This document |
| Redis MULTI/EXEC update | 10 ms | This document |
| Transaction commit | 10 ms | DB |
| Kafka produce | 5 ms | Application |
| Buffer | 140 ms | Reserve for spikes |

### 11.4 Connection Pool Wait SLAs

This is the one load-dependent SLA. Targets:

| Pool Utilization | Acceptable Wait Time (p95) |
|---|---|
| < 50% | < 1 ms |
| 50–80% | < 10 ms |
| 80–95% | < 100 ms |
| > 95% | Alert; investigate query/transaction patterns |

Sustained pool utilization above 80% is a leading indicator of either (a) query inefficiency, (b) transaction scope too large, or (c) under-provisioned pool size. None of those should be papered over by enlarging the pool.

### 11.5 Replication Lag SLAs

| Metric | Target |
|---|---|
| Aurora reader lag from writer | < 100 ms p95, < 500 ms p99 |
| Detection threshold (alert) | > 1 sec sustained for 30 sec |
| Consistent read fallback | If lag > 500 ms, route the specific query to writer |

The fallback logic lives in the data access layer: `@ConsistentRead` annotation on repository methods that cannot tolerate stale reads (e.g., post-write read of a just-written interval). Default is to hit the reader.

## 12. Backup and Disaster Recovery

### 12.1 RPO and RTO

| Scenario | RPO | RTO |
|---|---|---|
| Single AZ failure (Aurora multi-AZ failover) | 0 (sync replication) | 30–60 sec |
| Region failure (cross-region replica promotion) | < 1 sec (async) | 15 min |
| Logical corruption (bad migration, bad data) | Up to 5 min (PITR granularity) | 1 hour (restore) |
| Catastrophic loss (rare) | 24 hours (daily snapshot) | 4 hours |

### 12.2 What's Backed Up

Aurora's automated backups cover everything. Additionally:
- Daily logical dumps of the `volume_audit.series_history` table to S3 (long-term cold storage; this table accumulates and isn't part of the 7-year purge)
- Weekly schema-only dumps for migration-rollback capability

### 12.3 What's NOT Backed Up

- Redis position cache (rebuildable from PostgreSQL)
- Kafka topic data (this is a transport, not a system of record)
- Application logs (separate observability pipeline)

## 13. Migration Strategy

### 13.1 Flyway Configuration

Migrations live in `src/main/resources/db/migration` and are versioned `V{YYYYMMDD}{HHMM}__{description}.sql` (timestamp-based to avoid merge conflicts in branches).

The order of the migrations defined in this document (V001–V013) maps to a single deployable unit. They must apply in sequence on a fresh database.

### 13.2 Online Schema Change Patterns

For schema changes on the live system, use the standard expand/contract pattern:
1. Add new column nullable
2. Backfill in batches (chunked DELETE/INSERT or UPDATE WHERE row_id BETWEEN X AND Y)
3. Add NOT NULL constraint after backfill complete
4. Remove old column in a later release

For changes to `volume_interval` (the hypertable), additional caveats:
- ALTER TABLE on a hypertable propagates to all chunks; large hypertables can take hours
- Compressed chunks must be decompressed before structural changes (test the migration on a representative compressed dataset)
- For new indexes, use `CREATE INDEX CONCURRENTLY` plus `add_dimension` carefully — TimescaleDB has specific patterns for hypertable index management

### 13.3 Backward Compatibility Window

Application versions must support the database schema for at least one major version backward. This means a new column must remain nullable across at least one full deploy cycle before being made required.

## 14. Observability

The data layer exposes the following metrics to Prometheus:

| Metric | Type | Description |
|---|---|---|
| `volume_interval_inserts_total{bucket_type, cascade_tier}` | Counter | Inserts by bucket and tier |
| `volume_interval_query_duration_seconds{operation}` | Histogram | Query latency per operation type |
| `chunk_processing_duration_seconds{tier}` | Histogram | Chunk materialization time |
| `chunk_status_total{status}` | Gauge | Current count of chunks in each status |
| `db_connection_pool_active{pool}` | Gauge | Active connections (writer/reader) |
| `db_connection_pool_wait_duration_seconds{pool}` | Histogram | Pool acquisition wait time |
| `redis_position_cache_hit_ratio` | Gauge | Cache effectiveness |
| `compression_chunk_size_bytes{state}` | Gauge | Compressed vs uncompressed chunk sizes |
| `retention_purge_rows_deleted_total` | Counter | Rows purged by retention job |

Critical alerts:
- Chunk DLQ > 0 for > 5 minutes
- Connection pool wait > 100 ms p95 for > 5 minutes
- Replication lag > 1 sec for > 30 sec
- Disk usage on Aurora storage > 80%
- Compression policy not running (no compression activity for > 24 hours)
- CONTRACTUAL immutability trigger fired (any occurrence — should never happen in normal operation)

## 15. Things You Might Have Missed

These are aspects that often get overlooked in data architecture for systems like this. Calling them out so they don't bite later.

### 15.1 Hibernate batch insert configuration

The platform-wide `allocationSize=50` covers ID generation, but Hibernate's batch insert requires additional config:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc.batch_size: 1000
        order_inserts: true
        order_updates: true
        jdbc.batch_versioned_data: true
```

Without `order_inserts=true`, batches are flushed per entity type causing many small JDBC roundtrips instead of one batch per type. This single config knob is worth ~5x throughput on the chunk processor.

### 15.2 Deserialization mismatch (open issue from earlier work)

The Kafka consumer-position-engine has an unresolved ByteArraySerializer vs JsonDeserializer mismatch. If volume_interval messages are produced with one serializer and consumed with another, deserialization fails silently or produces corrupt records. Resolve before this data layer goes live.

### 15.3 Time zone handling at the database boundary

Aurora stores `TIMESTAMPTZ` as UTC internally. The application stores delivery times in `Europe/Berlin`. The conversion happens at the JDBC driver level. Two specific risks:

- The PostgreSQL JDBC driver respects the server's `timezone` setting unless overridden. Set `?stringtype=unspecified&assumeMinServerVersion=10` and ensure the connection's `SET TIME ZONE 'UTC'` runs at acquisition.
- DST-crossing intervals stored as TIMESTAMPTZ round-trip correctly, but DAILY intervals stored as DATE lose timezone info. This is intentional (DAILY is a calendar concept, not a wall-clock concept), but be careful that the application never converts DATE → TIMESTAMPTZ without explicit zone.

### 15.4 Foreign key behavior across modules

References to `trade_id` and `trade_leg_id` are strings without enforced FKs. This is intentional (per arch principle on async write patterns) but means:

- Orphaned volume series can exist if the parent trade is deleted
- A reaper job should periodically check for orphans (`volume_series` where `trade_id` doesn't exist in `trade.trade`) and either nullify them or alert
- The reaper should NOT delete orphans without human approval — this is regulatory data

### 15.5 Statistics on the hypertable

PostgreSQL auto-vacuum runs per-chunk on hypertables, but the planner statistics quality matters for query plans. Set:

```sql
ALTER TABLE volume_series.volume_interval ALTER COLUMN tenant_id SET STATISTICS 1000;
ALTER TABLE volume_series.volume_interval ALTER COLUMN bucket_type SET STATISTICS 1000;
ALTER TABLE volume_series.volume_interval ALTER COLUMN interval_start SET STATISTICS 1000;
ANALYZE volume_series.volume_interval;
```

This produces better cardinality estimates and prevents the planner from choosing a sequential scan when an index scan is correct.

### 15.6 The PostgreSQL `work_mem` trap

Default `work_mem` (4 MB) is too small for analytical queries against this data. A regulatory report aggregating across tenants will spill to disk and run 5-10x slower than necessary. Set per-session for analytical queries:

```sql
SET work_mem = '256MB';
```

Do this in the application's analytical query path, not as a global config (a global increase causes memory pressure during write bursts).

### 15.7 Schema evolution and event compatibility

If the `volume_interval` table gets a new scalar column, the corresponding Kafka event schema (`trade.details.generated`) must be updated compatibly. Use Avro or Protobuf with a schema registry — JSON without a schema registry has bitten this team historically.

### 15.8 Integer overflow on row counts

`materialized_interval_count` is `INTEGER` (max ~2.1 billion). For a single VolumeSeries, this is way more than enough (10-year PPA at 15-min = ~350K). But if anyone ever decides to put all PPAs across all tenants into a single series for some reason, the column would overflow. Should be `INTEGER` per current design, but flag if requirements change.

### 15.9 The "no FK to trade" position is absolute

Several engineers will, with good intentions, propose adding a foreign key from `volume_series.trade_id` to `trade.trade(trade_id)`. Resist this. The reasons:

- Async write order can violate referential integrity temporarily
- Cross-schema FKs limit independent deployment of trade and volume modules
- FK validation overhead on every insert at the throughput we're targeting matters

If integrity is a concern, the reaper job (15.4) is the right answer.

### 15.10 Read replica routing and CONSISTENT_READ semantics

A `@ConsistentRead` JPA repository annotation that routes to the writer is straightforward, but engineers must understand when to use it. Examples:
- A trade is captured, then immediately the user views the trade detail. The view reads CONTRACTUAL intervals — this MUST be consistent (use writer)
- A user opens the position dashboard which is updated continuously. Sub-100ms staleness is acceptable (use reader)

Document this clearly in the codebase. A bad default (always reader, or always writer) creates either correctness bugs or load problems.

### 15.11 The "what if a tenant churns" cleanup

When a tenant stops paying and contracts terminate, do we delete their data immediately? No. Regulatory retention obligations apply for 7 years even after the customer leaves. The architecture must support:

- Tenant deactivation (no new writes, queries return empty per RLS)
- Data preservation through the 7-year window
- Final purge after retention expires

This means the `tenant` table needs a `status` column (`ACTIVE`, `DEACTIVATED`, `PURGED`) and the volume_interval rows survive deactivation. Worth verifying this is wired up correctly.

## 16. Open Questions for Operations

1. Aurora reader instance count: 2 is a starting point. At what tenant count or query volume do we add a third? Define the scaling trigger.
2. Cross-region replica: does the platform need DR to a second EU region (e.g., for sovereignty requirements from a German customer)? Currently single-region.
3. Compression scheduling: should compression run continuously or only during off-peak hours? Continuous is simpler; off-peak avoids potential CPU contention with batch jobs.
4. Retention policy testing: how do we validate the retention policy works correctly without waiting 7 years? Need a synthetic dataset with backdated `settlement_finalized_at` for retention rehearsal.
5. RLS performance at scale: row-level security adds query overhead. At what tenant count does this become significant enough to warrant moving to schema-per-tenant or database-per-tenant? Likely fine through 500 tenants but worth measuring.

