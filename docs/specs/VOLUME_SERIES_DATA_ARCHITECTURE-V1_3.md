# Volume Series — Data Architecture

**Module:** `power-volume-series` data persistence layer
**Domain:** EU Physical Power Trade Capture (CTRM/ETRM)
**Version:** 1.3.0-SNAPSHOT
**Date:** May 2026
**Companion Spec:** VOLUME_SERIES_SPEC-V2.1

### Change Log (V1.3 from V1.2)

| Section | Change | Rationale |
|---|---|---|
| 5.7 | Replaced `superseded_at` / `superseded_by_id` (back-link, requires updating old row) with `supersedes_id` (forward-link, set only on the new row at insert time) | The old row is now truly never touched; the volume_interval hypertable becomes genuinely write-once. Avoids any updates on compressed chunks |
| 5.10 | ACTUAL append-only trigger now blocks ALL updates on ACTUAL rows (no exception for supersession metadata, since that no longer exists) | Reflects true append-only semantics |
| 6.4 | Removed implication of "segment-level decompression handled by TimescaleDB"; correction flow is pure insert with no decompression at all | Earlier wording was misleading about whether old rows get touched |
| 11 | BAV continuous aggregate redesigned: instead of `WHERE superseded_at IS NULL` filter, uses `last(value, version)` per bucket to select the latest version (forward-link supersession model). A separate view `bav_resolved` applies cross-bucket priority via COALESCE | Forward-link supersession means no flag column to filter on; latest version per bucket = current version |
| New index | `idx_vi_supersedes` on `supersedes_id WHERE supersedes_id IS NOT NULL` to make the anti-join efficient | Performance for the "is this row current" lookup |

### Change Log (V1.2 from V1.1)

| Section | Change | Rationale |
|---|---|---|
| 10 | Cache scope reduced to near-term only (M+M+1+M+2 at 15-min). Mid-term and long-term cache layers removed | Original 180M+ key estimate was a sign of overuse; mid/long horizon queries are infrequent enough to hit PostgreSQL directly |
| 11 | BAV materialization redesigned: single authoritative continuous aggregate in PostgreSQL; Redis becomes pure read-through cache populated from the materialized result | Eliminates split-brain risk; BAV is computed once, never independently |
| 5.7, 5.10 | Append-only ACTUAL with `version` and `superseded_at` columns; no decompression workflow for corrections | Corrections are inserts on hot chunks, not updates on compressed chunks |
| 6.4 | Decompression workflow removed; replaced with append-only correction pattern | Operationally simpler and safer |
| 3.4 | Cross-cluster integrity hardened: outbox pattern, idempotent consumers with deduplication keys, daily reconciliation job, saga pattern for multi-step trade-volume operations, extended Kafka retention | "Reaper job" alone was detective control; preventive controls added |
| 5.7 (notes), 16.12 (NEW) | Performance contract for `custom_scalars` JSONB documented; promotion path for high-traffic custom scalars to named columns | Makes the dual storage model deliberate and operable |
| 18 (NEW) | Position persistence: same RDS+TimescaleDB cluster, position modeled as continuous aggregate derived from BAV | Avoids creating a third cluster with the same cross-cluster integrity concerns |
| 11.2 SLA | BAV query SLAs revised for materialized access (no runtime ROW_NUMBER() window function) | Materialized BAV is O(rows-returned), not O(rows-scanned-with-window) |

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

Per principle P0, the volume series service is persistence-isolated from the transactional services that own trade master data. Coordination across this boundary follows specific patterns. Cross-cluster integrity in distributed systems is never absolute; the strategy here is to layer multiple complementary controls (preventive + detective + recovery) rather than rely on any single mechanism.

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

**Layered integrity controls:**

**Preventive control 1 — Outbox pattern on the trade side.**

The trade service writes trade rows and outbox events in a single ACID transaction in Aurora. A relay process polls the outbox table and publishes to Kafka with at-least-once delivery, marking events as published only after Kafka broker acknowledgment.

```sql
-- In Aurora (trade service schema):
CREATE TABLE trade.outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    VARCHAR(64) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_unpublished 
    ON trade.outbox (created_at) 
    WHERE published_at IS NULL;
```

If the relay crashes between database commit and Kafka publish, the next relay tick picks up the unpublished row and retries. There is no scenario where a trade is created in Aurora but its event is permanently lost — the outbox row exists until acknowledged.

**Preventive control 2 — Idempotent consumers with deduplication.**

The volume series consumer uses `(trade_id, trade_version)` as a natural idempotency key. The consumer first checks if a VolumeSeries with that key already exists; if so, it logs and skips. Duplicate events from broker retries or relay re-publishes are safe.

```java
public void handleTradeCaptured(TradeCapturedEvent event) {
    if (volumeSeriesRepository.existsByTradeIdAndTradeVersion(
            event.getTradeId(), event.getTradeVersion())) {
        log.info("Idempotent skip: VolumeSeries for {} v{} already exists",
                event.getTradeId(), event.getTradeVersion());
        return;
    }
    // ... create VolumeSeries ...
}
```

**Preventive control 3 — Saga pattern for multi-step operations.**

When trade capture must result in a downstream VolumeSeries, a saga state machine tracks progress in the trade service's database. The saga has three states: `STARTED`, `VOLUME_SERIES_CREATED`, `FAILED_REQUIRES_REVIEW`. The trade service's saga coordinator listens for `volume.series.materialized` confirmation events and updates the saga state. Sagas stuck in `STARTED` for longer than a timeout (e.g., 30 minutes) are surfaced to operations.

```sql
-- In Aurora (trade service schema):
CREATE TABLE trade.saga_state (
    saga_id         UUID PRIMARY KEY,
    trade_id        VARCHAR(64) NOT NULL,
    saga_type       VARCHAR(64) NOT NULL,  -- e.g., 'TRADE_CAPTURE'
    state           VARCHAR(32) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    last_updated_at TIMESTAMPTZ NOT NULL,
    error_context   JSONB
);
```

This means a trade cannot quietly exist in Aurora without a corresponding VolumeSeries — if the volume series creation fails, the saga makes that visible.

**Preventive control 4 — Extended Kafka retention.**

Trade event topics use 7-day retention (vs the platform default of 1-3 days). This means a 5-day volume series consumer outage can be recovered by event replay rather than data backfill. This is the key insurance against extended consumer downtime — combined with the outbox pattern, it covers most realistic failure modes.

**Detective control — Daily reconciliation job.**

A daily reconciliation job compares the trade event log (Aurora outbox + published events) against the VolumeSeries record set. It detects three classes of inconsistency:

1. **Orphan VolumeSeries** — A VolumeSeries exists for a trade_id that no longer exists in Aurora. Most likely cause: late-arriving cancellation that wasn't processed correctly.
2. **Missing VolumeSeries** — A trade exists in Aurora with `saga_state = VOLUME_SERIES_CREATED` but no VolumeSeries in RDS. Indicates data loss; alert immediately.
3. **Version mismatch** — Latest trade_version in Aurora doesn't match latest in RDS. Indicates a missed amendment event.

Reconciliation runs at 03:00 UTC, after the retention purge. Mismatches are written to a `reconciliation_exceptions` table and alerted via the observability stack.

**Recovery control — Event replay capability.**

The volume series consumer can be reset to a Kafka offset for a given partition and replay all events in order. Combined with idempotent processing, this gives us recovery from extended outages without data loss.

**Coordination patterns:**

- **No distributed transactions.** Trade insert in Aurora and VolumeSeries creation in RDS are separate transactions, coordinated via the outbox pattern.
- **No synchronous cross-cluster reads.** If volume series logic needs trade context, that context arrives in the Kafka event payload. If new context is needed later (rare), the trade service publishes an event with that field; volume series captures it in its own state.
- **Cross-cluster joins are impossible at the database level.** Reports requiring `trade × volume_interval` joins are served by a denormalized read model populated from both event streams (separate concern; not part of this volume series service).

**What this gives us in failure scenarios:**

| Failure | Mitigation | Result |
|---|---|---|
| Volume series consumer crashes mid-event | Idempotent processing on restart | No data loss; duplicate event safely re-applied |
| Kafka broker outage (< 7 days) | Outbox preserves unpublished events; consumer replays from offset on recovery | No data loss |
| Volume series RDS outage | Trade service continues; outbox accumulates; consumer drains backlog on RDS recovery | Eventual consistency restored within minutes of RDS availability |
| Permanent event loss (rare) | Daily reconciliation detects and alerts; saga state surfaces stuck workflows | Detected within 24 hours; manual reconciliation possible |
| Partial event delivery (some consumers got it, others didn't) | Each consumer is idempotent; reconciliation verifies cross-system consistency | Detected and corrected |

This is stronger than V1.1's "reaper job alone" — we now have preventive controls (outbox, sagas, idempotency, retention) plus detective controls (reconciliation) plus recovery controls (replay).

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
    
    -- Append-only versioning for ACTUAL corrections (Section 5.10)
    -- Forward-link model: the new (correcting) row points back to the row it supersedes.
    -- The superseded row is NEVER updated — it remains exactly as originally written.
    -- A row is "current" iff no other row references it via supersedes_id.
    version                     INTEGER NOT NULL DEFAULT 1,
    supersedes_id               BIGINT,  -- ID of the row this row supersedes; NULL for original rows
    
    CONSTRAINT chk_vi_window CHECK (interval_end > interval_start),
    CONSTRAINT chk_vi_bucket_granularity CHECK (
        -- ACTUAL must be at base granularity (operationally always 15-min in EU)
        bucket_type != 'ACTUAL' OR effective_granularity IN ('MIN_5', 'MIN_15', 'MIN_30', 'HOURLY')
    ),
    CONSTRAINT chk_vi_supersedes_versioning CHECK (
        -- A row that supersedes another must have version > 1
        (supersedes_id IS NULL AND version = 1) OR
        (supersedes_id IS NOT NULL AND version > 1)
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

-- Append-only ACTUAL: supersession anti-join lookup
-- Used to determine "is this row current?" — a row is current iff no other row's 
-- supersedes_id points to it. This index makes the anti-join efficient.
CREATE INDEX idx_vi_supersedes
    ON volume_series.volume_interval (supersedes_id)
    WHERE supersedes_id IS NOT NULL;

-- Composite for finding the latest version of a logical interval directly
-- "Latest version for (series, interval, bucket)" = MAX(version) row
CREATE INDEX idx_vi_logical_latest
    ON volume_series.volume_interval (series_id, interval_start, bucket_type, version DESC);

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

### 5.10 Triggers: append-only ACTUAL and CONTRACTUAL immutability

Two database-level invariants are enforced via triggers: CONTRACTUAL is fully immutable, and ACTUAL is **truly** append-only — corrections create new rows that point back to the row being superseded; the superseded row is never modified in any way.

**ACTUAL append-only via forward-link versioning:**

When new ACTUAL data arrives for a delivery interval that already has an ACTUAL row:
1. The application inserts a new row with `version = previous.version + 1`, `supersedes_id = previous.id`, and the corrected values
2. The previous row is left **completely untouched** — no UPDATE, no metadata change, no anything
3. The new row lands in the current (hot, uncompressed) chunk regardless of the delivery period

This means corrections never trigger any modification — not even at the segment level — on compressed chunks. The volume_interval hypertable becomes genuinely write-once at the row level.

**Determining "is this row current":**

A row is the current version of its logical interval if and only if no other row's `supersedes_id` points to it:

```sql
-- Current row for (series_id, interval_start, bucket_type):
SELECT vi.* FROM volume_series.volume_interval vi
WHERE vi.series_id = ?
  AND vi.interval_start = ?
  AND vi.bucket_type = ?
  AND NOT EXISTS (
      SELECT 1 FROM volume_series.volume_interval vi2
      WHERE vi2.supersedes_id = vi.id
  );
```

The `idx_vi_supersedes` index (Section 5.8) makes the NOT EXISTS subquery efficient — it's an index-only scan with a small index size (only superseding rows have non-NULL `supersedes_id`).

**Trigger DDL:**

```sql
-- V010__immutability_and_versioning_triggers.sql

-- Trigger for CONTRACTUAL immutability (unchanged from V1.2)
CREATE OR REPLACE FUNCTION volume_series.prevent_contractual_update()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.bucket_type = 'CONTRACTUAL' AND TG_OP = 'UPDATE' THEN
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

-- Trigger for ACTUAL: NO updates allowed at all (true append-only)
-- Corrections are handled by inserting a new row with supersedes_id set
CREATE OR REPLACE FUNCTION volume_series.prevent_actual_update()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.bucket_type = 'ACTUAL' AND TG_OP = 'UPDATE' THEN
        -- Allow ONLY administrative changes to settlement_finalized_at
        -- (which marks when settlement clock starts; never changes interval data)
        IF OLD.volume IS DISTINCT FROM NEW.volume
           OR OLD.energy IS DISTINCT FROM NEW.energy
           OR OLD.actual_mw IS DISTINCT FROM NEW.actual_mw
           OR OLD.balancing_cost_eur IS DISTINCT FROM NEW.balancing_cost_eur
           OR OLD.imbalance_penalty_eur IS DISTINCT FROM NEW.imbalance_penalty_eur
           OR OLD.congestion_rent_eur IS DISTINCT FROM NEW.congestion_rent_eur
           OR OLD.profile_cost_eur IS DISTINCT FROM NEW.profile_cost_eur
           OR OLD.interval_start IS DISTINCT FROM NEW.interval_start
           OR OLD.interval_end IS DISTINCT FROM NEW.interval_end
           OR OLD.version IS DISTINCT FROM NEW.version
           OR OLD.supersedes_id IS DISTINCT FROM NEW.supersedes_id
        THEN
            RAISE EXCEPTION 'ACTUAL intervals are append-only; corrections must create a new row with supersedes_id set, not update existing rows (interval id=%)', OLD.id
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_actual_append_only
    BEFORE UPDATE ON volume_series.volume_interval
    FOR EACH ROW EXECUTE FUNCTION volume_series.prevent_actual_update();
```

**Application-side correction flow:**

```java
@Transactional
public void recordActualCorrection(UUID seriesId, ZonedDateTime intervalStart, 
                                    BigDecimal correctedVolume, ScalarUpdate scalars) {
    VolumeInterval previous = repository.findCurrentByActualKey(seriesId, intervalStart);
    
    VolumeInterval newVersion = previous.toBuilder()
        .id(null)  // new ID assigned by sequence
        .version(previous.getVersion() + 1)
        .supersedesId(previous.getId())  // forward link to the row being corrected
        .volume(correctedVolume)
        .actualMw(scalars.getActualMw())
        // ... other scalar updates ...
        .createdAt(Instant.now())
        .build();
    
    repository.save(newVersion);
    // No update to `previous` — it stays exactly as it was, untouched
}
```

This pattern means:
- New corrections always land in the current (hot, uncompressed) chunk via INSERT only
- Old versions stay in their compressed chunks, **truly never touched** — not even segment-level decompression
- No update workflow on compressed data of any kind
- Full audit trail preserved automatically (the supersedes_id chain provides full version history)
- BAV materialization (Section 11) reads only rows where no superseder exists

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

### 6.4 Corrections: Pure Insert, Zero Updates on Compressed Data

When a settled ACTUAL needs correction (TSO submits revised metering data weeks later), the data layer performs a **pure insert** on the current hot chunk. No row in any compressed chunk is touched in any way — not at row level, not at segment level, not at chunk level.

**The correction flow:**

1. Application receives corrected metering data for a past delivery period
2. Application looks up the current ACTUAL row for that interval (via `idx_vi_logical_latest` index, fast lookup that uses `MAX(version)` per logical key)
3. Application inserts a new row with:
   - `version = previous.version + 1`
   - `supersedes_id = previous.id`
   - The corrected volume, energy, and scalar values
4. The new row lands in the current (hot) chunk regardless of when the original delivery occurred
5. The previous row is **never read for write purposes and never modified** — it stays exactly as originally written
6. Application triggers BAV materialization refresh for the affected interval (Section 11.4)

**What this avoids:**

- No `decompress_chunk()` calls
- No segment-level decompression (which TimescaleDB technically supports for compressed-chunk updates, but we don't need)
- No bulk re-compression
- No long transactions on compressed data
- No risk of partial-state corruption during decompress/recompress cycles
- No fragmentation of compressed segments over time

**What this requires:**

- BAV materialization (Section 11) uses a self-anti-join pattern: a row is current iff no other row references it via `supersedes_id`
- Audit queries that need historical versions can walk the supersession chain forward via `supersedes_id` index
- Storage cost: corrected intervals carry both the original and corrected rows. In practice corrections are rare (single-digit percentage of delivered intervals), so the storage overhead is negligible
- The `idx_vi_supersedes` index keeps the "is this row current" lookup fast (small index — only superseding rows have non-NULL `supersedes_id`)

**Bulk correction batches:**

Some TSOs deliver corrections in monthly batches. The application processes them with a batch size that fits comfortably in a single transaction (typically 1,000–5,000 rows per batch), all hitting the current hot chunk as INSERT-only operations. This is fast and safe.

**Why forward-link supersession (instead of back-link with `superseded_at`):**

A back-link model (older designs we considered) sets a `superseded_at` timestamp on the old row when it gets superseded. That requires updating a row that may be in a compressed chunk — even though TimescaleDB supports such updates via segment-level decompression, it's not zero-cost:
- The segment containing the old row gets decompressed in memory
- The updated row stays uncompressed within the chunk
- A periodic recompression job is needed to consolidate
- Compression efficiency degrades over time as more "holes" accumulate

The forward-link model (`supersedes_id` on the new row) avoids all of this. The cost is a slightly more complex query for "current row" — a `NOT EXISTS` subquery against an indexed column — but this is fast and bounded. The architectural payoff is clean: the volume_interval hypertable is genuinely write-once at the row level.

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
-- V014__series_history_table.sql
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

The Position Service maintains a Redis read-through cache of net positions for **near-term delivery intervals only**. The cache is populated from the BAV materialized view (Section 11) and serves the high-frequency operational query path. Mid-term and long-term position queries hit PostgreSQL directly.

### 10.1 Why Near-Term Only

Position queries have radically different access patterns by horizon:

| Horizon | Query Frequency | Latency Tolerance | Cache Decision |
|---|---|---|---|
| Near-term (M+M+1+M+2) | High (continuous during operational hours; gate closure decisions) | < 10 ms | Cache |
| Mid-term (3 months – 1 year) | Low (hedging decisions, daily/weekly) | < 500 ms | No cache; PostgreSQL with materialized BAV |
| Long-term (1 year – delivery end) | Very low (risk reports, scheduled) | < 5 sec | No cache; PostgreSQL with materialized BAV |

The BAV materialized view (Section 11) is fast enough at PostgreSQL level for mid/long-term queries. Caching them adds memory pressure, key management complexity, and consistency burden without meaningful latency benefit.

The earlier V1.1 design that cached all three horizons produced a key estimate of 180M+ keys — a sign the design was wrong, not that we needed a bigger Redis. The corrected approach scopes the cache narrowly.

### 10.2 Cache Key Structure

```
position:{tenant_id}:{delivery_point_id}:{interval_start_iso}
```

Example: `position:550e8400-e29b-41d4-a716-446655440000:DE-LU-001:2026-08-01T00:00:00Z`

No horizon suffix — only near-term keys exist in the cache. If a query references a delivery interval outside the near-term window, the cache miss falls through to PostgreSQL.

### 10.3 Cache Sizing

Realistic dimensioning for 200 tenants:

| Dimension | Value | Notes |
|---|---|---|
| Tenants | 200 | Steady state |
| Delivery points per tenant | 15 (avg) | Small/mid energy traders |
| Intervals per day at 15-min | 96 | EU power standard |
| Days in near-term window | 90 (M+M+1+M+2) | Worst case |
| **Total keys** | **~26M** | |
| Bytes per key (key + value) | ~150 | Position scalar + metadata |
| **Total memory** | **~4 GB** | |

Fits comfortably in `cache.r7g.large` (13 GB usable) with headroom for growth. No upsize required.

### 10.4 Read-Through Pattern (Single Source of Truth)

Critically, Redis is **not** an independent computation layer. Position data is computed and materialized in PostgreSQL via the BAV continuous aggregate (Section 11). Redis holds copies of authoritative rows from PostgreSQL — it never resolves BAV independently.

The flow on a Kafka consumer write:

1. Begin database transaction
2. Insert/update intervals in volume_interval (CONTRACTUAL, PLAN, or ACTUAL with versioning)
3. Commit database transaction
4. (After commit succeeds) BAV continuous aggregate refresh policy picks up the change in its next scheduled refresh (incremental, sub-second)
5. (After commit succeeds) Cache invalidation: DELETE the affected position keys from Redis
6. Publish `position.updated` event to Kafka

The next read for an invalidated key triggers a cache miss → query the BAV materialized view → populate the Redis key with the result → return to caller.

**Key properties of this pattern:**

- Redis cannot become a source of truth — it's invalidated, then repopulated from PostgreSQL on next read
- No split-brain BAV computation — there's exactly one BAV computation, in PostgreSQL
- Cache is eventually consistent with PostgreSQL (sub-second after commit + continuous aggregate refresh)
- A Redis outage degrades latency but not correctness — queries fall through to PostgreSQL

### 10.5 Atomicity Concerns

The Kafka consumer pattern is:

1. Begin database transaction
2. Insert intervals in volume_interval
3. Commit database transaction
4. (Best-effort, post-commit) Invalidate affected Redis keys
5. Publish `position.updated` event to Kafka (also post-commit, via outbox-like pattern)

Steps 4 and 5 happen after database commit — they cannot roll back the database. Failure modes:

- If Redis invalidation fails: keys remain populated with stale data until TTL. Acceptable because next read also re-checks TTL; staleness window is bounded.
- If Kafka publish fails: position.updated event is lost. Mitigated by the same outbox pattern as cross-cluster events (Section 3.4).

The database is the source of truth. Redis and Kafka are downstream notifications, not part of the consistency boundary.

### 10.6 TTL and Eviction

Position keys have a 24-hour TTL. Beyond TTL, the key is evicted; next read repopulates from PostgreSQL. The TTL exists primarily to bound the impact of any missed invalidations (defense in depth) and to age out keys for delivery intervals that have moved out of the near-term window.

Memory pressure is managed by:
- TTL-based eviction (24h)
- Active eviction of keys outside the rolling near-term window (cron job)
- Redis maxmemory-policy: `allkeys-lru` as a safety net

### 10.7 Strong Consistency Reads

A small set of operations require strong consistency (read-after-write within the same business transaction):
- Trade confirmation flow that reads back the trade's just-materialized intervals
- Settlement reconciliation that must see the latest ACTUAL corrections

These bypass Redis via a `read_consistent=true` flag on the repository method. Queries go directly to the PostgreSQL writer endpoint (not the read replicas, to avoid replication lag).

## 11. BAV (Best Available Volume) Materialization

### 11.1 Why BAV Is Needed

For position calculation, risk computation, and operational dashboards, the question "what volume should we use for delivery interval X?" has a layered answer based on bucket type:

1. **If ACTUAL exists** for the interval: use it (this is what was metered)
2. **Else if PLAN exists** for the interval: use it (this is what we intend to deliver)
3. **Else fall back to CONTRACTUAL**: at whatever granularity it was materialized

This resolution rule is called BAV — Best Available Volume. The same logic applies to scalar columns where multiple buckets carry values.

### 11.2 Single Authoritative Materialization

BAV is computed in **exactly one place**: a TimescaleDB continuous aggregate on the volume_interval hypertable. Redis does not compute BAV independently. The PostgreSQL view from V1.1 (which used a runtime ROW_NUMBER window function) is removed because it does not scale to large analytical queries.

The continuous aggregate refreshes incrementally as new intervals are written, making it both fast for reads and current with the source data.

**Selecting the current version:** Because the volume_interval table uses forward-link supersession (Section 5.10) — old rows are never modified — the continuous aggregate cannot use a simple WHERE filter to find current versions. Instead, we use the fact that version numbers are monotonically increasing on the supersession chain: the latest version for any (series_id, interval_start, bucket_type) is by definition the current version. The continuous aggregate uses TimescaleDB's `last(value, ordering)` aggregate to pick the highest-version row per logical key, then ranks across bucket types by priority.

```sql
-- V015__bav_continuous_aggregate.sql

-- Helper function: priority rank for bucket types (lower = higher priority)
CREATE OR REPLACE FUNCTION volume_series.bucket_priority(bt volume_series.bucket_type)
RETURNS INTEGER AS $$
BEGIN
    RETURN CASE bt
        WHEN 'ACTUAL' THEN 1
        WHEN 'PLAN' THEN 2
        WHEN 'CONTRACTUAL' THEN 3
    END;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- BAV continuous aggregate: one row per (tenant, series, interval),
-- holding the highest-priority current-version row's resolved values.
--
-- "Current version" semantics: in the forward-link supersession model
-- (Section 5.10), the row with MAX(version) for a given logical key is 
-- always the current row. last(value, version) picks that row's value.
--
-- For BAV resolution across buckets: we use last(value, bucket_priority)
-- — but FILTERED to only the latest version per bucket. The two-step
-- selection happens within the continuous aggregate.
CREATE MATERIALIZED VIEW volume_series.bav_interval
WITH (timescaledb.continuous) AS
SELECT 
    time_bucket('15 minutes', interval_start) AS bucket_start,
    tenant_id, series_id, trade_id,
    
    -- For each bucket_type, find the latest-version row's volume.
    -- Then across buckets, take the highest-priority bucket's value.
    -- We materialize per-bucket latest-version values, then COALESCE across.
    --
    -- Latest version per bucket (using last() ordered by version):
    last(volume, version) FILTER (WHERE bucket_type = 'ACTUAL')      AS actual_volume,
    last(volume, version) FILTER (WHERE bucket_type = 'PLAN')        AS plan_volume,
    last(volume, version) FILTER (WHERE bucket_type = 'CONTRACTUAL') AS contractual_volume,
    
    last(energy, version) FILTER (WHERE bucket_type = 'ACTUAL')      AS actual_energy,
    last(energy, version) FILTER (WHERE bucket_type = 'PLAN')        AS plan_energy,
    last(energy, version) FILTER (WHERE bucket_type = 'CONTRACTUAL') AS contractual_energy,
    
    last(effective_granularity, version) FILTER (WHERE bucket_type = 'ACTUAL')      AS actual_granularity,
    last(effective_granularity, version) FILTER (WHERE bucket_type = 'PLAN')        AS plan_granularity,
    last(effective_granularity, version) FILTER (WHERE bucket_type = 'CONTRACTUAL') AS contractual_granularity,
    
    -- Per-bucket scalar columns (latest version per bucket)
    last(contracted_mw, version) FILTER (WHERE bucket_type = 'CONTRACTUAL') AS contracted_mw,
    last(plan_mw, version)       FILTER (WHERE bucket_type = 'PLAN')        AS plan_mw,
    last(actual_mw, version)     FILTER (WHERE bucket_type = 'ACTUAL')      AS actual_mw,
    last(nominated_mw, version)  FILTER (WHERE bucket_type = 'PLAN')        AS nominated_mw,
    
    -- Price scalars: PLAN > CONTRACTUAL (resolved at query time via view, see 11.2.1)
    last(base_price_eur_mwh, version)    FILTER (WHERE bucket_type = 'PLAN')        AS plan_base_price_eur_mwh,
    last(base_price_eur_mwh, version)    FILTER (WHERE bucket_type = 'CONTRACTUAL') AS contractual_base_price_eur_mwh,
    last(green_premium_eur_mwh, version) FILTER (WHERE bucket_type = 'PLAN')        AS plan_green_premium_eur_mwh,
    last(green_premium_eur_mwh, version) FILTER (WHERE bucket_type = 'CONTRACTUAL') AS contractual_green_premium_eur_mwh,
    
    -- ACTUAL-only cost scalars
    last(balancing_cost_eur, version)    FILTER (WHERE bucket_type = 'ACTUAL') AS balancing_cost_eur,
    last(imbalance_penalty_eur, version) FILTER (WHERE bucket_type = 'ACTUAL') AS imbalance_penalty_eur,
    last(congestion_rent_eur, version)   FILTER (WHERE bucket_type = 'ACTUAL') AS congestion_rent_eur,
    
    -- profile_cost: ACTUAL > PLAN (resolved at query time via view)
    last(profile_cost_eur, version) FILTER (WHERE bucket_type = 'ACTUAL') AS actual_profile_cost_eur,
    last(profile_cost_eur, version) FILTER (WHERE bucket_type = 'PLAN')   AS plan_profile_cost_eur
    
FROM volume_series.volume_interval
GROUP BY bucket_start, tenant_id, series_id, trade_id;

-- Refresh policy: refresh recent data every 5 minutes
SELECT add_continuous_aggregate_policy('volume_series.bav_interval',
    start_offset => INTERVAL '7 days',
    end_offset => INTERVAL '5 minutes',
    schedule_interval => INTERVAL '5 minutes');

CREATE INDEX idx_bav_tenant_time ON volume_series.bav_interval (tenant_id, bucket_start DESC);
CREATE INDEX idx_bav_series ON volume_series.bav_interval (series_id, bucket_start);
```

### 11.2.1 BAV Resolved View

The continuous aggregate stores per-bucket latest-version values. A regular view on top applies the BAV resolution rule (ACTUAL > PLAN > CONTRACTUAL) to produce the final consumer-facing rows. This view is fast because each underlying value is already a materialized column lookup, no row scanning required.

```sql
-- V016__bav_resolved_view.sql

CREATE OR REPLACE VIEW volume_series.bav_resolved AS
SELECT 
    bucket_start, tenant_id, series_id, trade_id,
    
    -- Resolve volume: ACTUAL > PLAN > CONTRACTUAL
    COALESCE(actual_volume, plan_volume, contractual_volume) AS bav_volume,
    COALESCE(actual_energy, plan_energy, contractual_energy) AS bav_energy,
    
    -- Which bucket the volume came from
    CASE 
        WHEN actual_volume IS NOT NULL      THEN 'ACTUAL'::volume_series.bucket_type
        WHEN plan_volume IS NOT NULL        THEN 'PLAN'::volume_series.bucket_type
        WHEN contractual_volume IS NOT NULL THEN 'CONTRACTUAL'::volume_series.bucket_type
    END AS resolved_from_bucket,
    
    COALESCE(actual_granularity, plan_granularity, contractual_granularity) AS resolved_granularity,
    
    -- Per-bucket scalars (passed through from CA)
    contracted_mw, plan_mw, actual_mw, nominated_mw,
    
    -- Cross-bucket scalars: PLAN > CONTRACTUAL
    COALESCE(plan_base_price_eur_mwh, contractual_base_price_eur_mwh) AS base_price_eur_mwh,
    COALESCE(plan_green_premium_eur_mwh, contractual_green_premium_eur_mwh) AS green_premium_eur_mwh,
    
    -- ACTUAL-only
    balancing_cost_eur, imbalance_penalty_eur, congestion_rent_eur,
    
    -- ACTUAL > PLAN
    COALESCE(actual_profile_cost_eur, plan_profile_cost_eur) AS profile_cost_eur
    
FROM volume_series.bav_interval;
```

Application code queries `bav_resolved` for final BAV values. The two-layer design (continuous aggregate stores per-bucket latest values; view resolves across buckets) lets us put the materialized work in the continuous aggregate (where TimescaleDB's incremental refresh handles it) while keeping the cross-bucket COALESCE logic in a regular SQL view (where it's cheap and easy to read).

### 11.3 How Continuous Aggregates Work

TimescaleDB continuous aggregates store the materialized result in their own hypertable. They refresh incrementally — only chunks that have new or changed data are recomputed, not the entire view.

For the volume series workload:
- Hot chunks (recent intervals) refresh every 5 minutes via the policy
- Older chunks refresh on demand when ACTUAL corrections supersede previous versions (the application calls `refresh_continuous_aggregate` for the affected window)
- Compressed chunks remain compressed; the continuous aggregate refresh reads them efficiently

This means BAV queries hit the materialized rows in `bav_interval` directly via the `bav_resolved` view — no ROW_NUMBER, no window function, no filtering across multiple bucket types at query time. A query like "give me BAV for tenant X for delivery month Y" runs against `bav_resolved`, which translates to a simple range scan on `bav_interval` with the `idx_bav_tenant_time` index, plus a few cheap COALESCE operations per row.

### 11.4 Triggering Refresh on Corrections

When an ACTUAL correction creates a new version row, the application explicitly refreshes the continuous aggregate for the affected window:

```java
@Transactional
public void recordActualCorrection(/* ... */) {
    // ... insert new version, mark previous as superseded ...
    
    // Outside the transaction (after commit), trigger CA refresh for affected window
    afterCommit(() -> {
        refreshBavAggregate(intervalStart.minus(1, HOUR), intervalStart.plus(1, HOUR));
    });
}

private void refreshBavAggregate(Instant from, Instant to) {
    jdbcTemplate.execute(String.format(
        "CALL refresh_continuous_aggregate('volume_series.bav_interval', '%s', '%s')",
        from.toString(), to.toString()));
}
```

This is a sub-second operation for a 2-hour window — much faster than recomputing the whole BAV.

### 11.5 BAV Query Performance

With materialization in place, query performance is now O(rows-returned), not O(rows-scanned-with-window):

| Query | Before (V1.1 view) | After (V1.3 continuous aggregate + resolved view) |
|---|---|---|
| BAV for one delivery interval | 5–15 ms (window function on small set) | 1–3 ms (single row index lookup) |
| BAV for one day (96 intervals) | 30–100 ms | 5–20 ms |
| BAV for one tenant for one month (~3M source rows) | 2–5 sec (full window pass) | 50–150 ms (range scan on aggregate) |
| BAV for cross-tenant settlement run | 30–60 sec | 1–3 sec |

The ~10–20× improvement on large queries is what makes this design correct for analytical workloads. Without it, monthly settlement runs and quarterly regulatory reports would be impractical.

### 11.6 BAV Scalar Resolution Rules

The two-layer design (continuous aggregate + resolved view) encodes the resolution rules:

| Scalar | Resolution Rule | Where Implemented |
|---|---|---|
| `bav_volume` (volume) | ACTUAL > PLAN > CONTRACTUAL | CA stores `actual_volume`, `plan_volume`, `contractual_volume` per bucket; view applies `COALESCE(actual, plan, contractual)` |
| `contracted_mw` | CONTRACTUAL only (latest version) | CA: `last(contracted_mw, version) FILTER (WHERE bucket_type = 'CONTRACTUAL')` |
| `plan_mw` | PLAN only (latest version) | CA: `last(plan_mw, version) FILTER (WHERE bucket_type = 'PLAN')` |
| `actual_mw` | ACTUAL only (latest version) | CA: `last(actual_mw, version) FILTER (WHERE bucket_type = 'ACTUAL')` |
| `base_price_eur_mwh` | PLAN if present, else CONTRACTUAL | CA stores both `plan_base_price` and `contractual_base_price`; view applies `COALESCE` |
| `balancing_cost_eur`, `imbalance_penalty_eur`, `congestion_rent_eur` | ACTUAL only (latest version) | CA: `last(value, version) FILTER (WHERE bucket_type = 'ACTUAL')` |
| `profile_cost_eur` | ACTUAL > PLAN | CA stores both; view applies `COALESCE(actual_profile_cost, plan_profile_cost)` |

`last(value, version)` is used as the aggregation function because version numbers are strictly increasing on the supersession chain — the latest version is always the current row. The continuous aggregate uses this as a per-bucket selector, then the resolved view (Section 11.2.1) applies the cross-bucket BAV priority via COALESCE.

### 11.7 BAV Is the Sole Source for Position

Position calculations always read from `bav_resolved` (which queries `bav_interval` underneath), never from `volume_interval` directly. This guarantees:
- No client code computes BAV independently
- No drift between hot path (Redis) and cold path (analytical queries)
- All consumers see the same authoritative resolution

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

BAV queries hit the materialized continuous aggregate (Section 11), not the raw `volume_interval` table. SLA targets reflect this — they are O(rows-returned), not O(rows-scanned-with-window).

| Operation | Scope | p95 | p99 | Capacity Assumption |
|---|---|---|---|---|
| Get series by ID | 1 row | 3 ms | 10 ms | Trade detail view |
| Get all CONTRACTUAL for 10yr PPA series | ~9,110 rows | 150 ms | 400 ms | Audit/regulatory query; full life of trade |
| Get CONTRACTUAL for one delivery month (any tier) | ~30–100 rows | 20 ms | 50 ms | Targeted regulatory query |
| Get all PLAN for delivery period (1 day) | ~96 rows | 20 ms | 60 ms | Position calculation cache miss |
| Get all 3 buckets for delivery period (1 day, raw) | ~290 rows | 80 ms | 200 ms | Reconciliation view; raw access |
| BAV query for one delivery interval (materialized) | 1 row | 1 ms | 5 ms | Single row index lookup |
| BAV query for 1 day (96 intervals, materialized) | 96 rows | 5 ms | 20 ms | Range scan on aggregate |
| BAV query for 1 tenant for 1 month (materialized) | ~10K rows | 50 ms | 150 ms | Range scan on aggregate |
| BAV query for cross-tenant settlement run (materialized) | ~3M rows | 1 sec | 3 sec | Monthly settlement |
| Position aggregation, near-term (Redis hit) | 1 key | 1 ms | 5 ms | Hot path |
| Position aggregation, near-term (Redis miss → BAV) | ~96 rows | 10 ms | 30 ms | Cold path fallback |
| Position aggregation, mid-term (PostgreSQL via BAV) | ~365 rows | 50 ms | 150 ms | Hedging decisions |
| Position aggregation, long-term (PostgreSQL via BAV) | ~120 rows | 50 ms | 150 ms | Risk/credit reports |
| Cross-PPA settlement query (1 month, via BAV) | ~3M rows | 1 sec | 3 sec | Monthly settlement run |
| Regulatory report extract (REMIT, 1 month) | ~3M rows | 5 sec | 15 sec | Monthly regulatory submission |

### 12.3 Latency Budget Allocation

For the critical path (Kafka consumer → DB write → BAV refresh → cache invalidation → Kafka publish), the 250ms p95 end-to-end target decomposes as:

| Stage | Allocated p95 | Source |
|---|---|---|
| Kafka consume + deserialize | 5 ms | Application |
| Domain logic (interval materialization) | 30 ms | Application (per VOLUME_SERIES_SPEC §11) |
| DB write (batch insert + series update) | 50 ms | This document |
| Transaction commit | 10 ms | DB |
| Continuous aggregate refresh trigger (async, post-commit) | 5 ms | DB; actual refresh happens in background |
| Redis key invalidation (post-commit) | 5 ms | This document |
| Kafka produce | 5 ms | Application |
| Buffer | 140 ms | Reserve for spikes |

The continuous aggregate refresh runs asynchronously on its 5-minute schedule, not in the critical path. The commit returns as soon as PostgreSQL confirms the write — readers see consistent BAV within the next refresh cycle (typically < 5 minutes).

For paths requiring strong consistency immediately after write (e.g., trade confirmation), the application explicitly calls `refresh_continuous_aggregate` for the affected window after commit, adding ~50–200ms but guaranteeing the next read sees the new data.

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

- Redis position cache (rebuildable from PostgreSQL via the `bav_interval` continuous aggregate)
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

### 16.12 Performance contract for custom_scalars JSONB

The wide-table model has 14 named scalar columns (CONTRACTUAL/PLAN/ACTUAL specific scalars) plus a `custom_scalars` JSONB column for tenant-specific overflow. This dual model is deliberate, not inconsistent — but the performance contract must be explicit so engineers don't misuse it.

**Named columns:**
- Full SLA targets apply (per Section 12)
- Indexed where needed
- Type-safe at Java/JPA level
- Suitable for high-volume aggregation, settlement calculations, regulatory queries

**custom_scalars JSONB:**
- For storage and identification, not high-volume aggregation
- Queries via JSON path operators (`->`, `->>`, `@>`) are slower than column queries
- No general-purpose index; specific JSONB GIN indexes can be added per access pattern at a cost
- Suitable for: tenant-specific cost components that show up on dashboards but aren't aggregated across tenants; reference data; ad-hoc fields used by a single tenant's reporting
- Not suitable for: settlement calculations, position aggregation, regulatory reports requiring scans across many tenants

**Promotion path:**

If a tenant's custom scalar becomes a high-traffic field — appears in cross-tenant aggregations, drives a regulatory report, or shows up frequently in performance hotspots — the architecture team can promote it to a named column. This is a schema migration with backfill (move data from `custom_scalars->>'X'` into a new column `X_eur`, drop the JSONB key after backfill confirmed). Promoted scalars get full SLA treatment.

The promotion path makes the dual model self-correcting over time: as a custom scalar becomes important, it earns its way into the structured schema. As a structured scalar becomes obsolete, it can be deprecated. The schema evolves with usage rather than being frozen at design time.

Document this contract in the API docs and tenant onboarding so customers know what their custom scalars are and aren't optimized for.

## 17. Open Questions for Operations

1. RDS read replica count: 2 is a starting point. At what tenant count or query volume do we add a third? Define the scaling trigger.
2. Cross-region replica: does the platform need DR to a second EU region (e.g., for sovereignty requirements from a German customer)? Currently single-region.
3. Compression scheduling: should compression run continuously or only during off-peak hours? Continuous is simpler; off-peak avoids potential CPU contention with batch jobs.
4. Retention policy testing: how do we validate the retention policy works correctly without waiting 7 years? Need a synthetic dataset with backdated `settlement_finalized_at` for retention rehearsal.
5. RLS performance at scale: row-level security adds query overhead. At what tenant count does this become significant enough to warrant moving to schema-per-tenant or database-per-tenant? Likely fine through 500 tenants but worth measuring.


## 18. Position Persistence

The position service computes and serves net positions: aggregations of BAV-resolved volume across all trades for a given tenant, delivery point, and delivery interval. This section addresses where position data should live and how it should be modeled.

### 18.1 Same Cluster, Same Pattern

Position data is structurally identical to volume_interval data:
- Time-series shape (one row per delivery interval)
- Same access patterns (range scans by delivery time, tenant-filtered)
- Same compression and retention requirements
- Same regulatory retention boundary
- Same operational integrity requirements

There is no architectural reason to put it in a separate cluster. Doing so would replicate the cross-cluster integrity concerns from Section 3.4 without offering any benefit. Position lives in the **same RDS PostgreSQL + TimescaleDB cluster** as volume_interval, in the `volume_series` schema (or a sibling `position` schema for namespacing — both work).

### 18.2 Position as a Continuous Aggregate

Net position is not a separately maintained table — it is a **continuous aggregate derived from `bav_interval`** (TimescaleDB supports hierarchical continuous aggregates as of 2.10+). The composition makes the relationship explicit: position = aggregation of BAV across all series for the same delivery context.

Because the underlying `bav_interval` continuous aggregate stores per-bucket latest-version values rather than pre-resolved BAV (Section 11.2), the net_position aggregate applies the BAV resolution rule inline via COALESCE.

```sql
-- V017__net_position_continuous_aggregate.sql

CREATE MATERIALIZED VIEW volume_series.net_position
WITH (timescaledb.continuous) AS
SELECT 
    bucket_start,
    bav.tenant_id,
    vs.delivery_point_id,
    
    -- Net delivered volume = sum of (signed BAV-resolved volume across all trades)
    -- BAV resolution: ACTUAL > PLAN > CONTRACTUAL (applied via COALESCE)
    -- Direction sign from the trade leg (BUY positive, SELL negative)
    SUM(
        CASE vs.trade_direction
            WHEN 'BUY' THEN COALESCE(bav.actual_volume, bav.plan_volume, bav.contractual_volume, 0)
            WHEN 'SELL' THEN -COALESCE(bav.actual_volume, bav.plan_volume, bav.contractual_volume, 0)
        END
    ) AS net_volume_mw,
    
    SUM(
        CASE vs.trade_direction
            WHEN 'BUY' THEN COALESCE(bav.actual_energy, bav.plan_energy, bav.contractual_energy, 0)
            WHEN 'SELL' THEN -COALESCE(bav.actual_energy, bav.plan_energy, bav.contractual_energy, 0)
        END
    ) AS net_energy_mwh,
    
    -- Realized cost components: sum across all trades (ACTUAL only)
    SUM(COALESCE(bav.balancing_cost_eur, 0))    AS total_balancing_cost_eur,
    SUM(COALESCE(bav.imbalance_penalty_eur, 0)) AS total_imbalance_penalty_eur,
    
    -- profile_cost: ACTUAL > PLAN
    SUM(COALESCE(bav.actual_profile_cost_eur, bav.plan_profile_cost_eur, 0)) AS total_profile_cost_eur,
    
    COUNT(DISTINCT bav.trade_id) AS contributing_trade_count
    
FROM volume_series.bav_interval bav
JOIN volume_series.volume_series vs ON bav.series_id = vs.id
GROUP BY bucket_start, bav.tenant_id, vs.delivery_point_id;

-- Refresh policy: every 5 minutes for recent data
SELECT add_continuous_aggregate_policy('volume_series.net_position',
    start_offset => INTERVAL '7 days',
    end_offset => INTERVAL '5 minutes',
    schedule_interval => INTERVAL '5 minutes');

CREATE INDEX idx_np_tenant_dp_time
    ON volume_series.net_position (tenant_id, delivery_point_id, bucket_start DESC);

CREATE INDEX idx_np_tenant_time
    ON volume_series.net_position (tenant_id, bucket_start DESC);
```

Note: `volume_series.trade_direction` is a column we'd add to the volume_series table to capture BUY/SELL at series creation time (sourced from the trade event payload).

### 18.3 Why This Is Better Than a Separate Position Table

Two camps could be argued:

**Camp A — net_position as continuous aggregate (chosen):** Position is derived from BAV. Single source of truth. No separate ingestion path. No synchronization concerns. Refreshes incrementally with the source data.

**Camp B — net_position as a separately maintained table updated by application code:** More flexibility for complex position logic (e.g., portfolio-level netting rules). Separate ownership boundary. But: requires synchronization with BAV, risks drift, adds Kafka consumer surface area.

Camp A wins for the basic case (delivery-point-level netting). If portfolio-level netting becomes complex enough to require business logic beyond SUM aggregation, the application layer can compute that on top of `net_position` rather than replacing it.

### 18.4 Position Cache Pattern

Same pattern as volume cache (Section 10): Redis caches near-term net positions only, populated read-through from `net_position`, invalidated on writes that affect the underlying volume_interval rows.

The cache invalidation logic is more complex for position than for individual intervals — a single PLAN row insert affects the position for that delivery point and interval, but doesn't necessarily affect other delivery points. The application layer computes the affected position keys based on the trade's delivery point.

### 18.5 Position SLAs

| Operation | Scope | p95 | p99 |
|---|---|---|---|
| Get net position for one delivery interval (Redis hit) | 1 key | 1 ms | 5 ms |
| Get net position for one delivery interval (Redis miss → CA) | 1 row | 3 ms | 10 ms |
| Get net positions for one day (96 intervals) | 96 rows | 10 ms | 30 ms |
| Get net positions across all delivery points for tenant for 1 day | ~1,500 rows | 30 ms | 100 ms |
| Get net positions for tenant for 1 month | ~45,000 rows | 200 ms | 600 ms |
| Get net positions for tenant for 1 year | ~525,000 rows | 1.5 sec | 4 sec |

These SLAs assume the continuous aggregate is up to date (sub-5-minute lag from source data). For freshness-critical reads, the caller can trigger an explicit refresh of the affected window.

### 18.6 Position Storage Volume

Net position has roughly 10x fewer rows than volume_interval (no per-trade granularity, only aggregated):

| Period | Rows |
|---|---|
| One delivery year per tenant per delivery point | ~35,000 |
| Steady state (200 tenants × 15 dp × 365d × 96 intervals) | ~105M |
| 7-year retention | ~735M |

After TimescaleDB compression, 7-year position storage is ~5-7 GB. Negligible relative to the underlying volume_interval storage.

### 18.7 What This Document Does NOT Cover

The position service has its own concerns beyond persistence:
- Real-time computation logic (handled by Position Service application code)
- Risk metrics derived from positions (VaR, exposure, concentration)
- Integration with credit limit checks
- Position dashboards and visualizations

These are out of scope for the data architecture document. The persistence layer described here provides the materialized position data; the position service builds the rest on top.
