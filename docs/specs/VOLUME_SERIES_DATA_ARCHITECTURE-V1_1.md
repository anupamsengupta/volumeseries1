# Volume Series — Data Architecture

**Module:** `power-volume-series` data persistence layer
**Domain:** EU Physical Power Trade Capture (CTRM/ETRM)
**Version:** 1.1.0-SNAPSHOT
**Date:** May 2026
**Companion Spec:** VOLUME_SERIES_SPEC-V2.1

### Change Log (V1.1 from V1.0)

| Section | Change | Rationale |
|---|---|---|
| 2 (P0 NEW, P7 rewritten) | New principle: time-series persistence separate from transactional persistence. P7 rewritten to clarify hot vs cold path is access-frequency, not delivery-horizon based | Aurora cannot host TimescaleDB; hybrid persistence is the correct architecture |
| 3.1 | Engine changed from Aurora PostgreSQL to RDS PostgreSQL 16 with pg_timescaledb | Aurora does not support the TimescaleDB extension |
| 3.1.1 | Cost recalculated for RDS pricing model (instance + provisioned storage + IOPS) | Pricing model differs from Aurora I/O-Optimized |
| 3.4 (NEW) | Cross-cluster integration patterns | Boundary contract between time-series service and transactional services |
| 5.7, 5.8 | DDL restored to TimescaleDB hypertable (was correct in spirit; now actually deployable) | Now that we're on RDS, hypertable is available |
| 6 | Compression strategy is TimescaleDB native (no custom partition rebuild needed) | RDS supports TimescaleDB compression natively |
| 7 | Retention rewritten to use TimescaleDB chunk drop, not row-by-row DELETE | Original DELETE pattern would not scale to billions of rows |
| 10 (NEW) | BAV (Best Available Volume) materialization design | Required for position/risk queries spanning all bucket types |
| 11.2 | Row count and SLA corrected for "Get all CONTRACTUAL for series" | Previous figure (~200 rows) was off by ~45x |
| 12 | Hot vs cold path clarified across all delivery horizons | Aligns with corrected P7 |

---

## 1. Scope

This document defines the data persistence architecture for the `volume-series` domain as specified in VOLUME_SERIES_SPEC-V2.1. It covers physical schema design, DDL, indexing, partitioning, compression, retention, connection pooling, multitenancy enforcement, caching, SLA targets, and operational concerns.

Out of scope: trade master entity (assumed to exist in the broader CTRM codebase), counterparty master, delivery point master, trading calendar — these are referenced via foreign keys but their full schemas are owned by other modules.

## 2. Architecture Principles

The data architecture is governed by the following principles, in order of precedence:

**P0 — Time-series persistence is separate from transactional persistence.** The volume series module is a specialized time-series service. Its persistence layer is a dedicated RDS PostgreSQL cluster with the pg_timescaledb extension. Transactional entities (trades, counterparties, delivery points, users, tenants) live in a separate Aurora cluster owned by other services. The boundary is enforced architecturally — there are no foreign keys, no cross-cluster transactions, and no synchronous queries across the boundary. All coordination is event-driven via Kafka. This separation is non-negotiable; future "let's just put it all in one database for convenience" proposals must be resisted because the workloads (time-series append-heavy vs OLTP transactional) have fundamentally different optimization profiles.

**P1 — Multitenancy via discriminator column.** Every persisted row carries a `tenant_id`. Application-layer enforcement plus row-level security policies prevent cross-tenant data access. This pattern was established at the platform level and is non-negotiable.

**P2 — Contractual immutability.** CONTRACTUAL bucket rows are never updated or deleted by application code. The only path that removes them is the regulatory retention policy after settlement+7yr+6mo. This is enforced both by application convention and by a database-level update trigger on the `bucket_type='CONTRACTUAL'` predicate.

**P3 — Write path uses native sequences.** Per the platform mandate, IDs use PostgreSQL native sequences with `allocationSize=50` for Hibernate batch insert efficiency. `IDENTITY` strategy is prohibited because it disables Hibernate batch inserts.

**P4 — Read/write split via dual HikariCP pools.** The RDS reader endpoint serves analytical queries (position aggregation, regulatory reports, audit). The writer endpoint serves Kafka consumer writes and CRUD operations. The split is enforced at the DataSource bean level, not via Spring `@Transactional(readOnly=true)` (which routes to writer in current Spring Data JPA behavior).

**P5 — Cascade invariants enforced at DB level where feasible.** Where a check constraint can express an invariant cheaply (e.g., `bucket_type IN (...)`, `interval_end > interval_start`), it is added. Complex invariants (energy conservation, scalar derivation correctness) are enforced at the application layer with test coverage.

**P6 — Compression and retention are time-bound automated policies.** No human-driven archival. TimescaleDB compression and retention policies run on schedule. The compression boundary is delivery-period-based (not chunk-creation-time-based).

**P7 — Hot path serves frequent queries; cold path serves analytical and regulatory queries.** The distinction is access frequency, not delivery horizon. Position and risk calculations span all delivery horizons (operational scheduling needs near-term; hedging needs mid-term; portfolio risk and credit exposure need long-term). The hot path therefore caches near-term positions at full granularity (15-min) and aggregated longer-horizon positions at coarser granularity (daily/monthly), all in Redis. PostgreSQL is consulted on cache miss or for queries that don't fit the cache pattern (regulatory reports, full-resolution historical analysis, ad-hoc risk simulations). The cold path uses the reader endpoint with no Redis involvement.

## 3. Database Topology

### 3.1 Cluster Configuration

The volume series module uses a dedicated RDS PostgreSQL cluster, separate from the Aurora cluster used by transactional services. This is the time-series specialized service per principle P0.

| Component | Specification | Notes |
|---|---|---|
| Engine | RDS PostgreSQL 16 | TimescaleDB requires RDS; Aurora does not support the extension |
| Extension | pg_timescaledb 2.15+ | Available via RDS parameter group; enables hypertable, compression, retention |
| Writer | 1 instance, db.r7g.2xlarge (8 vCPU / 64 GB RAM) | Multi-AZ deployment |
| Read replicas | 2 instances, db.r7g.2xlarge | Application routes via custom endpoint with weighted distribution |
| Storage | gp3 SSD, 500 GB initial provisioned with autoscaling enabled | gp3 baseline 3,000 IOPS / 125 MB/s; provisioned IOPS available if needed |
| Storage autoscaling | Enabled, max 4 TB | Headroom for ramp; compression keeps actual usage well below |
| Backup | 35-day automated backups + manual snapshots before major changes | RDS native |
| Maintenance | Auto minor version upgrades during defined window | Major upgrades manual; coordinate with TimescaleDB compatibility |

**Why RDS PostgreSQL instead of Aurora for this workload:**

- TimescaleDB is the right tool for the volume_interval table (compression, retention policies, chunk-based partitioning, continuous aggregates). Aurora does not support the extension.
- The time-series workload is append-heavy with batch inserts and range scans — patterns that TimescaleDB on RDS handles excellently. Aurora's strengths (instant reader provisioning, near-zero replication lag, shared storage) are more valuable for OLTP than for time-series.
- Compression is critical at our scale. Without TimescaleDB compression, year-7 storage would be ~5.5 TB instead of ~430 GB. The compression mechanism is unavailable on Aurora.

**RDS-specific operational characteristics to plan for:**

- Failover takes 60–120 seconds (vs Aurora's 30–60). Plan circuit-breaker behavior in Kafka consumers accordingly.
- Read replica replication lag is typically 50–200 ms (vs Aurora's near-zero). Application code that performs read-after-write must use the writer endpoint or an explicit consistency hint (Section 11.5).
- Storage is provisioned upfront with autoscaling option. Aurora's auto-scaling shared cluster volume is more elastic but unavailable here.
- Read replicas are independent instances, not shared-storage readers. Each replica consumes its own WAL stream.

### 3.1.1 Estimated Monthly Cost of Operation

Pricing reflects on-demand list rates as of May 2026 for Frankfurt (`eu-central-1`). All prices in USD, excluding VAT. Hours-per-month assumes 730.

**Assumptions for the costing baseline:**
- 200 active tenants in steady state
- 1 writer + 2 read replicas (per Section 3.1)
- gp3 storage with autoscaling enabled
- Storage profile (with TimescaleDB compression): ~50 GB year-1 → ~200 GB year-3 → ~430 GB year-7
- ElastiCache Redis with primary + 1 replica for HA

**RDS PostgreSQL compute (db.r7g.2xlarge, Multi-AZ for writer, single-AZ for readers, Frankfurt):**

The RDS PostgreSQL on-demand price for `db.r7g.2xlarge` in Frankfurt is approximately $0.78/hour single-AZ, with Multi-AZ doubling that for the writer (~$1.56/hour). Readers are single-AZ each.

| Pricing Model | Writer (Multi-AZ) | 2 Readers (single-AZ) | Total/Month |
|---|---|---|---|
| On-Demand | $1.56/hr → ~$1,139 | $0.78/hr × 2 → ~$1,139 | ~$2,278 |
| 1-year RI, No Upfront | ~$1.10/hr → ~$803 | $0.55/hr × 2 → ~$803 | ~$1,606 |
| 1-year RI, All Upfront | ~$1.02/hr → ~$745 | $0.51/hr × 2 → ~$745 | ~$1,490 |
| 3-year RI, All Upfront | ~$0.62/hr → ~$453 | $0.31/hr × 2 → ~$453 | ~$906 |

**RDS storage (gp3, ~$0.137/GB-month in Frankfurt):**

| Storage Lifecycle Stage | Approximate GB | Monthly Cost |
|---|---|---|
| Year 1 average (ramp-up) | ~50 GB | ~$7 |
| Year 3 steady state | ~200 GB | ~$27 |
| Year 7 full retention | ~430 GB | ~$59 |

These figures already reflect TimescaleDB's ~13x compression on warm/cold chunks. Without compression, year-7 would be ~5.5 TB and ~$754/month for storage alone.

gp3 baseline IOPS (3,000) and throughput (125 MB/s) are sufficient for the Kafka consumer workload at projected throughput. If load testing reveals IOPS pressure, provisioned IOPS can be added at ~$0.10/IOPS-month. Budget headroom: $50–100/month if needed; not in baseline.

**ElastiCache Redis (cache.r7g.large, primary + replica, Frankfurt) — unchanged from V1.0:**

| Pricing Model | Per Node/Hour | 2 Nodes/Month |
|---|---|---|
| On-Demand | ~$0.241 | ~$352 |
| 1-year RI, No Upfront | ~$0.165 | ~$240 |
| 3-year RI, No Upfront | ~$0.126 | ~$185 |

**RDS backups:**
- First copy of provisioned storage size: free (RDS native policy)
- Backups beyond storage size: ~$0.095/GB-month
- 35-day retention typical cost: ~$3–10/month at steady state

**Other infrastructure (unchanged from V1.0):**

| Item | Monthly Cost | Notes |
|---|---|---|
| VPC endpoints (3: S3, KMS, SecretsManager) | ~$22 | $7.30 per endpoint |
| CloudWatch metrics + logs | ~$75 | Application + RDS Performance Insights |
| KMS keys (encryption at rest) | ~$3 | $1/key for 3 keys |
| Data transfer (egress for reports) | ~$15 | Minimal; mostly intra-VPC |
| S3 (audit table cold dumps) | ~$2 | ~50 GB at $0.023/GB-month |
| **Subtotal** | **~$117** | |

**Totals across deployment scenarios (volume series module only):**

| Scenario | RDS Compute | RDS Storage | ElastiCache | Other | **Total/Month** |
|---|---|---|---|---|---|
| Year 1, On-Demand | $2,278 | $7 | $352 | $117 | **~$2,754** |
| Year 1, 1-yr RI (No Upfront) | $1,606 | $7 | $240 | $117 | **~$1,970** |
| Year 3 steady state, 3-yr RI | $906 | $27 | $185 | $117 | **~$1,235** |
| Year 7 full retention, On-Demand | $2,278 | $59 | $352 | $117 | **~$2,806** |
| Year 7 full retention, 3-yr RI | $906 | $59 | $185 | $117 | **~$1,267** |

**Comparison to V1.0 (Aurora-only assumption):**

| Scenario | V1.0 (Aurora) | V1.1 (RDS+TimescaleDB) | Savings |
|---|---|---|---|
| Year 1, On-Demand | ~$3,385 | ~$2,754 | ~$631/month (~19%) |
| Year 3 steady state, 3-yr RI | ~$1,512 | ~$1,235 | ~$277/month (~18%) |
| Year 7 full retention, 3-yr RI | ~$1,568 | ~$1,267 | ~$301/month (~19%) |

The RDS+TimescaleDB approach is roughly 18–19% cheaper than the Aurora-only baseline, despite running a separate cluster. The savings come from (a) RDS instance pricing being lower than Aurora I/O-Optimized pricing and (b) TimescaleDB's compression eliminating most of the storage cost over time.

**Per-tenant unit economics:**

At year-3 steady state with reserved instances (~$1,235/month for the volume series module), infrastructure cost per tenant works out to ~$6.18/month or ~$74/year. Against a subscription price point in the tens of thousands EUR/year, this is well under 1% of revenue.

**Cost levers to consider:**

1. **3-year RI**: ~60% savings on compute is the single biggest lever. RDS RIs are size-flexible within the same family. Worth committing once tenant count and traffic patterns are stable.
2. **Read replica count**: 2 readers is conservative. At 200 tenants, monitoring will reveal whether one suffices for read traffic. Removing one saves ~$453/month on-demand.
3. **Multi-AZ scope**: We have Multi-AZ on the writer (essential) but single-AZ on readers (acceptable since they're read-replaceable). Multi-AZ on readers would double their cost without proportional benefit.
4. **Compression scheduling**: Already factored in. Without it, year-7 storage cost would be ~13x higher.
5. **Region choice**: Ireland (`eu-west-1`) is ~5–8% cheaper than Frankfurt. Frankfurt is preferred for German customer data residency requirements; Ireland is fine if no customers explicitly require Germany.

**Whole-platform cost note:**

This document covers only the volume series persistence layer (RDS PostgreSQL + TimescaleDB + Redis). The full platform additionally requires an Aurora cluster for transactional services (trades, counterparties, etc.), Kafka (MSK or self-managed), application servers (ECS/EKS), Keycloak, observability stack, and frontend hosting. A reasonable estimate for the rest of the platform at year-3 steady state is $5,000–$8,000/month with reserved instances, putting whole-platform infrastructure at roughly $6,500–$9,500/month for 200 tenants. Per-tenant whole-platform cost: ~$32–$47/month, still comfortable against subscription pricing.

### 3.2 Schema Organization

Two PostgreSQL schemas:

- `volume_series` — domain tables for this module (the focus of this document)
- `volume_audit` — bi-temporal audit trail (separate schema for retention boundary clarity)

Cross-schema FKs are not used. References to `trade.trade` and `tenant.tenant` are by ID without enforced FK (these tables live in a separate Aurora cluster — see P0 and Section 3.4). Application layer ensures referential integrity via the reaper job (Section 15.4).

### 3.3 Connection Pool Configuration

Two HikariCP pools per service instance, configured as Spring `@Bean`s with primary/qualifier annotations. These pools target the RDS volume series cluster only — services that also need to talk to the transactional Aurora cluster maintain a separate pair of pools (out of scope for this document).

| Pool | Endpoint | Max Pool Size | Min Idle | Connection Timeout | Idle Timeout | Max Lifetime |
|---|---|---|---|---|---|---|
| `writeDataSource` | RDS writer endpoint | 20 | 5 | 3000ms | 600000ms | 1800000ms |
| `readDataSource` | RDS reader (custom endpoint with weighted distribution across replicas) | 30 | 10 | 3000ms | 600000ms | 1800000ms |

Reader pool is sized larger because analytical queries are longer-running and read traffic dominates. The `connectionTimeout=3000ms` enforces fast-fail behavior; if a thread can't get a connection in 3 seconds, the request fails rather than queueing indefinitely.

`leakDetectionThreshold` is set to 60000ms to catch unreturned connections — important given Kafka consumer poll loops and the risk of forgetting to close a connection in error paths.

**Note on RDS reader endpoint behavior:** Unlike Aurora's load-balanced reader endpoint, RDS does not provide a single endpoint that distributes across replicas. Application code must either (a) use a custom DNS or proxy layer (RDS Proxy supports this), (b) connect to specific replica endpoints with client-side load balancing, or (c) use a connection-routing library. RDS Proxy is the recommended approach for this platform — it adds modest cost (~$0.015/vCPU-hour) but simplifies connection management and provides connection multiplexing benefits for the Kafka consumer fleet.

### 3.4 Cross-Cluster Integration Patterns

Per principle P0, the volume series service is persistence-isolated from the transactional services that own trade master data. Coordination across this boundary follows specific patterns:

**Inbound — trade lifecycle events drive volume series operations:**

The volume series service consumes Kafka events from the trade capture service:

| Event | Trigger | Volume Series Action |
|---|---|---|
| `trade.captured` | New trade created | Build VolumeSeries with cascade tiers, materialize NEAR_TERM CONTRACTUAL intervals |
| `trade.amended` | Trade fields changed | Create new VolumeSeries version with bumped `trade_version`; old version retained for audit |
| `trade.cancelled` | Trade cancelled | Mark VolumeSeries as cancelled (status field, not deletion); retain for regulatory record |
| `trade.settlement_finalized` | Settlement completed | Update `settlement_finalized_at` on all CONTRACTUAL intervals for that trade; starts retention clock |

Events carry the full trade payload needed for VolumeSeries construction. The volume series service does not query back to the trade service for additional context.

**Outbound — volume series events drive downstream consumers:**

| Event | Trigger | Downstream Consumers |
|---|---|---|
| `volume.series.materialized` | VolumeSeries created or amended | Position service, risk service |
| `volume.interval.materialized` | Chunk processor completes a chunk | Position service (for batch position recompute) |
| `volume.position.updated` | Net position changes for a delivery interval | Risk service, dashboard, Kafka audit log |
| `volume.derivation.completed` | Monthly cron creates PLAN intervals | Scheduling service, settlement service |

**Coordination patterns:**

- **No distributed transactions.** Trade insert in Aurora and VolumeSeries creation in RDS are separate transactions, coordinated via Kafka. The trade service uses an outbox pattern: trade insert and outbox event are in one transaction; a relay process publishes from outbox to Kafka.
- **No synchronous cross-cluster reads.** If volume series logic needs trade context, that context arrives in the Kafka event payload. If new context is needed later (rare), the trade service publishes an event with that field; volume series captures it in its own state.
- **Cross-cluster joins are impossible.** Reports requiring `trade × volume_interval` joins are served by a denormalized read model populated from both event streams (separate concern; not part of this volume series service).

**Failure handling:**

- Volume series Kafka consumer applies idempotent writes (upsert by `series_uuid`); duplicate events from broker retries are safe.
- Failed event processing goes to a DLQ topic with the original event payload, error context, and a retry counter.
- Reaper job (Section 15.4) detects volume series whose parent trade no longer exists in the trade service and flags for human review.

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

### 7.1 Why Row-Level DELETE Is Wrong at This Scale

A naive retention implementation using `DELETE FROM volume_interval WHERE settlement_finalized_at < cutoff` would fail at our scale:

- At ~10 billion rows over 7 years, a DELETE matching even 1% of rows touches ~100 million rows
- DELETE generates massive WAL volume and triggers replication lag spikes on read replicas
- Marked-for-deletion rows are not reclaimed until VACUUM runs; until then, the table bloats and sequential scans become slower
- Long-running DELETE holds row locks that conflict with concurrent writes

The correct pattern leverages TimescaleDB's chunk-based partitioning: drop entire chunks as metadata operations, no row-level work.

### 7.2 Chunk Drop Pattern

TimescaleDB's `drop_chunks` function detaches and drops chunks whose time range is entirely older than a cutoff. This is an O(1) operation per chunk regardless of row count — the chunk's metadata is removed and its underlying storage is freed.

```sql
-- V012__retention_policy.sql

-- TimescaleDB native retention policy: drops chunks where
-- ALL intervals in the chunk are older than the cutoff
SELECT add_retention_policy(
    'volume_series.volume_interval',
    drop_after => INTERVAL '8 years',
    schedule_interval => INTERVAL '1 day'
);
```

The default policy keys retention off `interval_start` (the partitioning column). At our chunk_time_interval of 1 month, this means: a chunk for delivery month 2018-08 is eligible for drop only when the entire chunk falls outside the retention window (i.e., when `now() > 2018-08-01 + 8 years = 2026-08-01`).

### 7.3 Why 8 Years and Not 7

The regulatory requirement is settlement+7 years. We use a delivery-based 8-year retention because:

- Settlement typically occurs within 90 days of delivery (most often within 30)
- Adding 1 year of buffer accommodates: settlement disputes (up to 12 months), late corrections, and the gap between settlement-finalization clock and delivery clock
- TimescaleDB native retention works on the partitioning column (`interval_start`), not arbitrary columns. Using `settlement_finalized_at` would require custom logic outside the native policy.

For most chunks, this is conservative-safe — they're well past settlement by the time delivery+8 years arrives. For the rare chunk with unsettled disputes near the boundary, the buffer absorbs it.

### 7.4 Custom Procedure for Edge Cases

For the rare cases where a chunk is past delivery+8 years but contains intervals with `settlement_finalized_at IS NULL` (genuinely unsettled long-dispute trades), a defensive check runs before the retention policy:

```sql
-- V013__pre_retention_check.sql

CREATE OR REPLACE PROCEDURE volume_series.pre_retention_audit(job_id INTEGER, config JSONB)
LANGUAGE plpgsql AS $$
DECLARE
    chunk_record RECORD;
    unsettled_count INTEGER;
BEGIN
    -- Find chunks that the retention policy will drop on its next run
    FOR chunk_record IN
        SELECT chunk_schema, chunk_name, range_start, range_end
        FROM timescaledb_information.chunks
        WHERE hypertable_name = 'volume_interval'
          AND range_end < (now() - INTERVAL '8 years')
    LOOP
        -- Count unsettled intervals in the chunk
        EXECUTE format(
            'SELECT COUNT(*) FROM %I.%I WHERE settlement_finalized_at IS NULL',
            chunk_record.chunk_schema, chunk_record.chunk_name
        ) INTO unsettled_count;
        
        IF unsettled_count > 0 THEN
            -- Log to audit table; alert for human review; do NOT drop
            INSERT INTO volume_series.retention_exceptions(
                chunk_name, range_start, range_end, unsettled_count, detected_at
            ) VALUES (
                chunk_record.chunk_name, chunk_record.range_start, 
                chunk_record.range_end, unsettled_count, now()
            );
            
            RAISE WARNING 'Chunk % has % unsettled intervals; flagged for review',
                chunk_record.chunk_name, unsettled_count;
        END IF;
    END LOOP;
END;
$$;

-- Audit table for retention exceptions
CREATE TABLE volume_series.retention_exceptions (
    id              BIGSERIAL PRIMARY KEY,
    chunk_name      TEXT NOT NULL,
    range_start     TIMESTAMPTZ NOT NULL,
    range_end       TIMESTAMPTZ NOT NULL,
    unsettled_count INTEGER NOT NULL,
    detected_at     TIMESTAMPTZ NOT NULL,
    resolved_at     TIMESTAMPTZ,
    resolution_note TEXT
);

-- Run the audit job 1 hour before the retention policy
SELECT add_job(
    'volume_series.pre_retention_audit',
    schedule_interval => INTERVAL '1 day',
    initial_start => CURRENT_DATE + TIME '02:00:00'
);
```

The audit job runs at 02:00 UTC, the retention policy runs at 03:00 UTC by default. If the audit detects unsettled intervals in a chunk that's about to be dropped, it logs to the exceptions table. An alerting hook on this table notifies operators to either (a) finalize settlement before the next retention cycle or (b) explicitly approve the chunk drop.

This is rare enough that human review is appropriate. For typical operation, no exceptions are detected and the retention policy runs unchallenged.

### 7.5 Operational Behavior of Chunk Drops

When `drop_chunks` runs:
- The chunk's tables and indexes are removed (DROP TABLE under the hood)
- Disk space is reclaimed immediately (no VACUUM needed)
- The metadata in `_timescaledb_catalog` is updated
- Total runtime: typically sub-second per chunk regardless of size

For a typical retention run, 1 month's worth of chunks (1 chunk if not space-partitioned, or N chunks if space-partitioned by tenant) drop in seconds. This is the correct pattern for time-series retention at this scale.

### 7.6 Compressed Chunks Are Dropped Identically

The retention policy operates on chunks regardless of compression state. A compressed chunk is dropped just as efficiently as an uncompressed one. No decompression occurs during the drop.

### 7.7 GDPR Anonymization

The `volume_interval` table itself contains no personal data (volumes, prices, costs — all financial/operational). Personal data (trader names, counterparty contact persons) lives on the `trade` and `counterparty` tables, which are owned by other modules (and live in the Aurora cluster per P0).

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

### 9.2 The RDS Read Replica Concern

RLS session variables don't propagate to read replicas automatically. Each connection to a reader must independently set `app.tenant_id`. The `@TenantAware` aspect handles this on both pools (`writeDataSource` and `readDataSource`) by intercepting at the JDBC connection acquisition.

Note: RDS PostgreSQL read replicas are independent instances replicating WAL from the writer; they have their own connection state. RDS Proxy (recommended in Section 3.3) does not change this — RLS variables remain per-connection.

## 10. Cache Strategy (Redis)

The Position Service maintains a Redis cache of net positions per delivery interval. The cache is populated on write (Kafka consumer side-effect) and consulted on read (Position Service queries).

### 10.1 Cache Key Structure

```
position:{tenant_id}:{delivery_point_id}:{interval_start_iso}:{horizon}
```

Where `horizon` is one of `near` (15-min granularity), `mid` (daily aggregate), or `long` (monthly aggregate). Examples:
- Near-term: `position:550e8400-...:DE-LU-001:2026-08-01T00:00:00Z:near`
- Mid-term aggregate: `position:550e8400-...:DE-LU-001:2026-09-01:mid`
- Long-term aggregate: `position:550e8400-...:DE-LU-001:2027-03:long`

### 10.2 What's Cached

The cache holds **net BAV-resolved positions** (sum of BAV-merged volumes across all trades) per `(tenant, delivery_point, interval, horizon)` combination. It is a derived view, not a copy of the volume_interval table.

What's cached at each horizon:

| Horizon | Granularity | Window | Cache Population Trigger |
|---|---|---|---|
| `near` | 15-min | Rolling M+M+1+M+2 | Every Kafka consumer write affecting near-term intervals |
| `mid` | Daily aggregate | M+2 to +1 year | Daily roll-up cron + on-demand for any in-window writes |
| `long` | Monthly aggregate | +1 year to delivery end | Monthly roll-up cron + on-demand for amendments |

CONTRACTUAL and ACTUAL data are not separately cached — they're inputs to the BAV computation that produces what's cached. The BAV resolution rule (ACTUAL > PLAN > CONTRACTUAL) is applied at cache population time, so cache reads return the BAV-resolved value directly without a runtime COALESCE.

This is consistent with corrected principle P7: hot path serves all delivery horizons via Redis (at appropriate granularity per horizon); cold path serves PostgreSQL for full-resolution analytical and regulatory queries.

### 10.3 Atomicity

The Kafka consumer pattern is:
1. Begin database transaction
2. Insert/update intervals in volume_interval (CONTRACTUAL, PLAN, or ACTUAL as applicable)
3. Compute new BAV-resolved net position for affected delivery intervals at all relevant horizons
4. Inside a Redis MULTI/EXEC block: update position keys at all horizons
5. Commit database transaction
6. Publish `position.updated` event to Kafka

If the Redis update fails, we roll back the database transaction. The MULTI/EXEC block ensures Redis-side atomicity (multi-key consistency). PostgreSQL is the source of truth; Redis is rebuildable from a full table scan in case of corruption.

### 10.4 TTL and Eviction

| Horizon | TTL | Rationale |
|---|---|---|
| `near` | 7 days | Operational query window; longer than near-term boundary |
| `mid` | 30 days | Refreshed by monthly roll-up cron |
| `long` | 90 days | Refreshed by monthly roll-up cron; longest lifetime since least frequently amended |

Beyond TTL, queries fall through to PostgreSQL. Memory footprint estimate:
- Near-term: 200 tenants × 100 delivery points × 96 intervals/day × 90 days (M+M+1+M+2) ≈ 173M keys at peak
- Mid-term: 200 × 100 × 365 days ≈ 7.3M keys
- Long-term: 200 × 100 × 9 years × 12 months ≈ 2.2M keys

Total ~183M keys, average ~150 bytes per key (key + small value), ~27 GB. The single `cache.r7g.large` (13 GB) is undersized — we need to upgrade to `cache.r7g.xlarge` (~26 GB usable) or partition the cache. **Cost adjustment:** moving from `cache.r7g.large` to `cache.r7g.xlarge` adds ~$340/month on-demand. Worth budgeting.

Alternative: cache only near-term at 15-min and mid-term at weekly aggregate (not daily), which cuts mid-term keys by 7x and brings total back under 13 GB. This is a tradeoff — coarser mid-term cache means more PostgreSQL fall-throughs for queries needing daily resolution. Decision: start with coarser mid-term cache; revise if PostgreSQL load warrants.

## 11. BAV (Best Available Volume) Materialization

### 11.1 Why BAV Is Needed

For position calculation, risk computation, and operational dashboards, the question "what volume should we use for delivery interval X?" has a layered answer based on bucket type:

1. **If ACTUAL exists** for the interval: use it (this is what was metered)
2. **Else if PLAN exists** for the interval: use it (this is what we intend to deliver)
3. **Else fall back to CONTRACTUAL**: at whatever granularity it was materialized

This resolution rule is called BAV — Best Available Volume. The same logic applies to scalar columns where multiple buckets carry values (e.g., `effective_price_eur_mwh` resolves from ACTUAL → PLAN → CONTRACTUAL).

### 11.2 The Naive Approach Doesn't Scale

A naive runtime BAV computation would be:

```sql
SELECT COALESCE(
  (SELECT volume FROM volume_interval WHERE bucket_type='ACTUAL' AND ...),
  (SELECT volume FROM volume_interval WHERE bucket_type='PLAN' AND ...),
  (SELECT volume FROM volume_interval WHERE bucket_type='CONTRACTUAL' AND ...)
) AS bav_volume;
```

For a single 15-min slot, that's 3 index lookups. For a position calculation across 96 intervals × 200 trades, that's ~57,600 lookups. This works but it's wasteful — we're doing the same resolution computation many times for data that changes only on bucket-update events.

### 11.3 BAV View (Source of Truth for Cold Queries)

A PostgreSQL view encapsulates the resolution rule, used by all cold-path queries (regulatory, reconciliation, ad-hoc analysis):

```sql
-- V014__bav_view.sql

CREATE OR REPLACE VIEW volume_series.v_bav_intervals AS
WITH ranked AS (
    SELECT
        tenant_id,
        trade_id,
        series_id,
        interval_start,
        interval_end,
        bucket_type,
        cascade_tier,
        effective_granularity,
        volume,
        energy,
        contracted_mw,
        plan_mw,
        actual_mw,
        nominated_mw,
        base_price_eur_mwh,
        green_premium_eur_mwh,
        balancing_cost_eur,
        imbalance_penalty_eur,
        congestion_rent_eur,
        profile_cost_eur,
        custom_scalars,
        ROW_NUMBER() OVER (
            PARTITION BY tenant_id, series_id, interval_start
            ORDER BY 
                CASE bucket_type
                    WHEN 'ACTUAL' THEN 1
                    WHEN 'PLAN' THEN 2
                    WHEN 'CONTRACTUAL' THEN 3
                END
        ) AS bav_rank
    FROM volume_series.volume_interval
)
SELECT
    tenant_id,
    trade_id,
    series_id,
    interval_start,
    interval_end,
    bucket_type AS resolved_from_bucket,
    cascade_tier,
    effective_granularity,
    volume,
    energy,
    -- BAV-resolved scalars: take from highest-priority bucket where present
    -- Note: rate scalars like contracted_mw appear in CONTRACTUAL only, so they
    -- pass through naturally; price scalars in PLAN override CONTRACTUAL
    COALESCE(actual_mw, plan_mw, contracted_mw) AS bav_mw,
    COALESCE(plan_mw, contracted_mw) AS plan_or_contracted_mw,
    base_price_eur_mwh,
    green_premium_eur_mwh,
    balancing_cost_eur,
    imbalance_penalty_eur,
    congestion_rent_eur,
    profile_cost_eur,
    custom_scalars
FROM ranked
WHERE bav_rank = 1;
```

The view returns the highest-priority bucket row per (tenant, series, interval), with derived BAV scalar columns that present the resolved view. The `bav_rank` window function does the priority resolution; the `WHERE bav_rank = 1` filters to just the winning bucket.

**View limitations:**
- The view is a query-time computation, not a materialized table. PostgreSQL recomputes it for each query. For analytical queries on bounded ranges (one delivery month, one tenant), it's fine. For scans across all tenants and years, it's slow.
- The window function PARTITION BY can be expensive on hot intervals; ensure the underlying indexes support efficient access by `(tenant_id, series_id, interval_start)`.

### 11.4 BAV Aggregates in Redis (Hot Path)

For position queries (the hot path), BAV is precomputed and stored in Redis as described in Section 10.2. The cache key holds the BAV-resolved net position, not the raw bucket data. When the Kafka consumer writes a new ACTUAL or PLAN row, it:

1. Reads the current BAV state for affected intervals (existing position from cache or PostgreSQL)
2. Computes the new BAV-resolved volume considering the new bucket data
3. Updates the Redis position key

This means cache reads are O(1) — they return the BAV-resolved net position directly, no resolution logic at read time.

### 11.5 BAV for Scalar Columns

The volume column has a clear BAV resolution: ACTUAL > PLAN > CONTRACTUAL. Scalar columns are more nuanced:

| Scalar | BAV Rule | Rationale |
|---|---|---|
| `contracted_mw` | CONTRACTUAL only | Definitionally a contractual property |
| `plan_mw` | PLAN only | Definitionally a plan property |
| `actual_mw` | ACTUAL only | Definitionally an actual property |
| `bav_mw` (derived) | COALESCE(actual, plan, contracted) | Net delivered or expected volume |
| `base_price_eur_mwh` | PLAN if present, else CONTRACTUAL | Plan price overrides contractual (e.g., renegotiation); CONTRACTUAL price is the fallback |
| `green_premium_eur_mwh` | Same as base price | Same logic |
| `balancing_cost_eur` | ACTUAL only | Only realized post-delivery |
| `imbalance_penalty_eur` | ACTUAL only | Only realized post-delivery |
| `congestion_rent_eur` | ACTUAL only | Only realized post-delivery |
| `profile_cost_eur` | ACTUAL > PLAN | ACTUAL is the realized cost; PLAN is the expected |

The BAV view applies these rules in its SELECT clause. The materialized cache also applies them when computing aggregates.

### 11.6 BAV Computation Cost

For the hot path (Redis cache), BAV computation happens once per write event, not once per read. The cost is amortized:

| Event | BAV Recomputation Scope |
|---|---|
| New CONTRACTUAL interval (rare, only at trade capture or amendment) | All intervals in the new series |
| New PLAN interval (monthly cron, on-demand) | Only the affected intervals |
| New ACTUAL interval (post-delivery feed) | Only the affected interval |

This is much cheaper than recomputing BAV for every position query. The pattern aligns with the spec's principle of "store the recipe, not the meal" — BAV is precomputed and stored, not recomputed at read time.

## 12. SLA Targets

These targets reflect what the data layer must deliver to the application services. They are derived from:
- EU power market operational deadlines (gate closures, nomination windows)
- Customer contract commitments (subscription tier SLAs)
- Industry expectations for ETRM platforms in this segment

**SLA is load-independent.** Targets below are absolute response times the system must meet under stated capacity conditions, not a function of test load. The single exception is connection pool wait time, which is inherently load-dependent.

### 12.1 Write SLAs

| Operation | Scope | p95 | p99 | Capacity Assumption |
|---|---|---|---|---|
| Single interval insert (VolumeInterval) | 1 row | 5 ms | 15 ms | Up to 200 trades/sec sustained |
| Batch insert (DA day, 96 intervals) | 96 rows | 30 ms | 80 ms | Within DA auction burst |
| Batch insert (monthly chunk, ~2,976 intervals) | 2,976 rows | 300 ms | 800 ms | Background chunk processor |
| VolumeSeries upsert | 1 row + cascade | 15 ms | 40 ms | Trade capture path |
| Materialization chunk status update | 1 row | 5 ms | 15 ms | Per-chunk lifecycle event |
| ACTUAL append | 1 row | 8 ms | 25 ms | Settlement feed ingestion |

### 12.2 Read SLAs

Row count assumptions corrected for cascade tier structure: a 10-year PPA with cascade has approximately 8,640 NEAR_TERM CONTRACTUAL (3 months × 30 days × 96 intervals) + 365 MEDIUM_TERM CONTRACTUAL (12 months at daily) + 105 LONG_TERM CONTRACTUAL (~8.75 years at monthly) = **~9,110 CONTRACTUAL rows** at trade capture, immutable for the life of the trade.

| Operation | Scope | p95 | p99 | Capacity Assumption |
|---|---|---|---|---|
| Get series by ID | 1 row | 3 ms | 10 ms | Trade detail view |
| Get all CONTRACTUAL for 10yr PPA series | ~9,110 rows | 150 ms | 400 ms | Audit/regulatory query; full life of trade |
| Get CONTRACTUAL for one delivery month (any tier) | ~30–100 rows | 20 ms | 50 ms | Targeted regulatory query |
| Get all PLAN for delivery period (1 day) | ~96 rows | 20 ms | 60 ms | Position calculation cache miss |
| Get all 3 buckets for delivery period (1 day) | ~290 rows | 80 ms | 200 ms | Reconciliation view via BAV view |
| BAV query for one delivery interval (1 slot) | 1 row | 5 ms | 15 ms | View access via index |
| BAV query for 1 day (96 slots) | 96 rows | 30 ms | 100 ms | Daily position summary |
| Position aggregation across tenant (Redis hit, near horizon) | 1 key | 1 ms | 5 ms | Hot path |
| Position aggregation across tenant (Redis hit, mid/long horizon) | 1 key | 1 ms | 5 ms | Hot path for hedging/risk |
| Position aggregation (Redis miss → PG via BAV view) | ~1,000 rows | 150 ms | 400 ms | Cold path fallback |
| Cross-PPA settlement query (tenant scope, 1 month) | ~3M rows scanned | 2 sec | 5 sec | Monthly settlement run |
| Regulatory report extract (REMIT, 1 month) | ~3M rows | 10 sec | 30 sec | Monthly regulatory submission |

### 12.3 Latency Budget Allocation

For the critical path (Kafka consumer → DB write → Redis update → Kafka publish), the 250ms p95 end-to-end target decomposes as:

| Stage | Allocated p95 | Source |
|---|---|---|
| Kafka consume + deserialize | 5 ms | Application |
| Domain logic (interval materialization) | 30 ms | Application (per VOLUME_SERIES_SPEC §11) |
| BAV recomputation for affected intervals | 15 ms | Application |
| DB write (batch insert + series update) | 50 ms | This document |
| Redis MULTI/EXEC update (multi-horizon) | 15 ms | This document |
| Transaction commit | 10 ms | DB |
| Kafka produce | 5 ms | Application |
| Buffer | 120 ms | Reserve for spikes |

### 12.4 Connection Pool Wait SLAs

This is the one load-dependent SLA. Targets:

| Pool Utilization | Acceptable Wait Time (p95) |
|---|---|
| < 50% | < 1 ms |
| 50–80% | < 10 ms |
| 80–95% | < 100 ms |
| > 95% | Alert; investigate query/transaction patterns |

Sustained pool utilization above 80% is a leading indicator of either (a) query inefficiency, (b) transaction scope too large, or (c) under-provisioned pool size. None of those should be papered over by enlarging the pool.

### 12.5 Replication Lag SLAs

| Metric | Target |
|---|---|
| RDS read replica lag from writer | < 200 ms p95, < 1 sec p99 |
| Detection threshold (alert) | > 2 sec sustained for 30 sec |
| Consistent read fallback | If lag > 500 ms, route the specific query to writer |

Note: RDS read replica lag is inherently higher than Aurora's near-zero. The targets above accept this characteristic. The fallback logic lives in the data access layer: `@ConsistentRead` annotation on repository methods that cannot tolerate stale reads (e.g., post-write read of a just-written interval). Default is to hit the reader.

## 13. Backup and Disaster Recovery

### 13.1 RPO and RTO

| Scenario | RPO | RTO |
|---|---|---|
| Single AZ failure (RDS Multi-AZ failover) | 0 (sync replication on writer) | 60–120 sec |
| Region failure (cross-region read replica promotion) | < 5 sec (async replication) | 15–30 min |
| Logical corruption (bad migration, bad data) | Up to 5 min (PITR granularity) | 1–2 hours (restore from snapshot + replay) |
| Catastrophic loss (rare) | 24 hours (daily snapshot) | 4 hours |

### 13.2 What's Backed Up

RDS automated backups cover the database. Additionally:
- Daily logical dumps of the `volume_audit.series_history` table to S3 (long-term cold storage; this table accumulates and isn't part of the 8-year purge)
- Weekly schema-only dumps for migration-rollback capability
- Manual snapshots before any major version upgrade or significant schema change

### 13.3 What's NOT Backed Up

- Redis position cache (rebuildable from PostgreSQL via BAV view scan)
- Kafka topic data (this is a transport, not a system of record)
- Application logs (separate observability pipeline)

## 14. Migration Strategy

### 14.1 Flyway Configuration

Migrations live in `src/main/resources/db/migration` and are versioned `V{YYYYMMDD}{HHMM}__{description}.sql` (timestamp-based to avoid merge conflicts in branches).

The order of the migrations defined in this document (V001–V013) maps to a single deployable unit. They must apply in sequence on a fresh database.

### 14.2 Online Schema Change Patterns

For schema changes on the live system, use the standard expand/contract pattern:
1. Add new column nullable
2. Backfill in batches (chunked DELETE/INSERT or UPDATE WHERE row_id BETWEEN X AND Y)
3. Add NOT NULL constraint after backfill complete
4. Remove old column in a later release

For changes to `volume_interval` (the hypertable), additional caveats:
- ALTER TABLE on a hypertable propagates to all chunks; large hypertables can take hours
- Compressed chunks must be decompressed before structural changes (test the migration on a representative compressed dataset)
- For new indexes, use `CREATE INDEX CONCURRENTLY` plus `add_dimension` carefully — TimescaleDB has specific patterns for hypertable index management

### 14.3 Backward Compatibility Window

Application versions must support the database schema for at least one major version backward. This means a new column must remain nullable across at least one full deploy cycle before being made required.

## 15. Observability

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
- Disk usage on RDS storage > 80%
- Compression policy not running (no compression activity for > 24 hours)
- CONTRACTUAL immutability trigger fired (any occurrence — should never happen in normal operation)

## 16. Things You Might Have Missed

These are aspects that often get overlooked in data architecture for systems like this. Calling them out so they don't bite later.

### 16.1 Hibernate batch insert configuration

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

### 16.2 Deserialization mismatch (open issue from earlier work)

The Kafka consumer-position-engine has an unresolved ByteArraySerializer vs JsonDeserializer mismatch. If volume_interval messages are produced with one serializer and consumed with another, deserialization fails silently or produces corrupt records. Resolve before this data layer goes live.

### 16.3 Time zone handling at the database boundary

PostgreSQL stores `TIMESTAMPTZ` as UTC internally. The application stores delivery times in `Europe/Berlin`. The conversion happens at the JDBC driver level. Two specific risks:

- The PostgreSQL JDBC driver respects the server's `timezone` setting unless overridden. Set `?stringtype=unspecified&assumeMinServerVersion=10` and ensure the connection's `SET TIME ZONE 'UTC'` runs at acquisition.
- DST-crossing intervals stored as TIMESTAMPTZ round-trip correctly, but DAILY intervals stored as DATE lose timezone info. This is intentional (DAILY is a calendar concept, not a wall-clock concept), but be careful that the application never converts DATE → TIMESTAMPTZ without explicit zone.

### 16.4 Foreign key behavior across modules

References to `trade_id` and `trade_leg_id` are strings without enforced FKs. This is intentional (per arch principle on async write patterns) but means:

- Orphaned volume series can exist if the parent trade is deleted
- A reaper job should periodically check for orphans (`volume_series` where `trade_id` doesn't exist in `trade.trade`) and either nullify them or alert
- The reaper should NOT delete orphans without human approval — this is regulatory data

### 16.5 Statistics on the hypertable

PostgreSQL auto-vacuum runs per-chunk on hypertables, but the planner statistics quality matters for query plans. Set:

```sql
ALTER TABLE volume_series.volume_interval ALTER COLUMN tenant_id SET STATISTICS 1000;
ALTER TABLE volume_series.volume_interval ALTER COLUMN bucket_type SET STATISTICS 1000;
ALTER TABLE volume_series.volume_interval ALTER COLUMN interval_start SET STATISTICS 1000;
ANALYZE volume_series.volume_interval;
```

This produces better cardinality estimates and prevents the planner from choosing a sequential scan when an index scan is correct.

### 16.6 The PostgreSQL `work_mem` trap

Default `work_mem` (4 MB) is too small for analytical queries against this data. A regulatory report aggregating across tenants will spill to disk and run 5-10x slower than necessary. Set per-session for analytical queries:

```sql
SET work_mem = '256MB';
```

Do this in the application's analytical query path, not as a global config (a global increase causes memory pressure during write bursts).

### 16.7 Schema evolution and event compatibility

If the `volume_interval` table gets a new scalar column, the corresponding Kafka event schema (`trade.details.generated`) must be updated compatibly. Use Avro or Protobuf with a schema registry — JSON without a schema registry has bitten this team historically.

### 16.8 Integer overflow on row counts

`materialized_interval_count` is `INTEGER` (max ~2.1 billion). For a single VolumeSeries, this is way more than enough (10-year PPA at 15-min = ~350K). But if anyone ever decides to put all PPAs across all tenants into a single series for some reason, the column would overflow. Should be `INTEGER` per current design, but flag if requirements change.

### 16.9 The "no FK to trade" position is absolute

Several engineers will, with good intentions, propose adding a foreign key from `volume_series.trade_id` to `trade.trade(trade_id)`. Resist this. The reasons:

- Async write order can violate referential integrity temporarily
- Cross-schema FKs limit independent deployment of trade and volume modules
- FK validation overhead on every insert at the throughput we're targeting matters

If integrity is a concern, the reaper job (15.4) is the right answer.

### 16.10 Read replica routing and CONSISTENT_READ semantics

A `@ConsistentRead` JPA repository annotation that routes to the writer is straightforward, but engineers must understand when to use it. Examples:
- A trade is captured, then immediately the user views the trade detail. The view reads CONTRACTUAL intervals — this MUST be consistent (use writer)
- A user opens the position dashboard which is updated continuously. Sub-100ms staleness is acceptable (use reader)

Document this clearly in the codebase. A bad default (always reader, or always writer) creates either correctness bugs or load problems.

### 16.11 The "what if a tenant churns" cleanup

When a tenant stops paying and contracts terminate, do we delete their data immediately? No. Regulatory retention obligations apply for 7 years even after the customer leaves. The architecture must support:

- Tenant deactivation (no new writes, queries return empty per RLS)
- Data preservation through the 7-year window
- Final purge after retention expires

This means the `tenant` table needs a `status` column (`ACTIVE`, `DEACTIVATED`, `PURGED`) and the volume_interval rows survive deactivation. Worth verifying this is wired up correctly.

## 17. Open Questions for Operations

1. RDS read replica count: 2 is a starting point. At what tenant count or query volume do we add a third? Define the scaling trigger.
2. Cross-region replica: does the platform need DR to a second EU region (e.g., for sovereignty requirements from a German customer)? Currently single-region.
3. Compression scheduling: should compression run continuously or only during off-peak hours? Continuous is simpler; off-peak avoids potential CPU contention with batch jobs.
4. Retention policy testing: how do we validate the retention policy works correctly without waiting 7 years? Need a synthetic dataset with backdated `settlement_finalized_at` for retention rehearsal.
5. RLS performance at scale: row-level security adds query overhead. At what tenant count does this become significant enough to warrant moving to schema-per-tenant or database-per-tenant? Likely fine through 500 tenants but worth measuring.

