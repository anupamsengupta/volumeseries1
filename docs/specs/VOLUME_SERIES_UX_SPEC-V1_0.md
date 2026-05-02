# Volume Series — Presentation Layer Specification

**Module:** `power-volume-series-ui` presentation layer
**Domain:** EU Physical Power Trade Capture (CTRM/ETRM)
**Version:** 1.0.0-SNAPSHOT
**Date:** May 2026
**Companion Documents:** VOLUME_SERIES_SPEC-V2.1, VOLUME_SERIES_DATA_ARCHITECTURE-V1.3
**Frontend Stack:** Next.js 14 (per platform standard)

---

## 1. Purpose and Scope

This document specifies the presentation layer for the volume series module — what users see, how they interact with it, and how the data structures defined in the spec and data architecture surface as screens. It is a **design specification**, not a code specification. No JSX, no component code, no CSS class names. The deliverable is screen designs, interaction patterns, and a design language that downstream UI engineers can implement.

### What this covers

- User personas active in an EU power trading firm and how their needs map to specific screens
- Information architecture — the navigation structure that organizes the volume series surface area
- Screen-by-screen mockup specifications, described as text mockups with layout, components, and interaction behavior
- Interaction patterns specific to time-series data (cascade tier visualization, BAV resolution overlays, three-bucket reconciliation)
- Design system foundations (color, typography, spacing, density) appropriate for a regulated financial trading product
- Accessibility, responsiveness, and operational concerns (auth, error states, latency tolerance)

### What this does not cover

- React/Next.js component implementation
- API contract details (covered in the data architecture doc)
- Visual brand identity (logo, color customization for tenant theming) — assumes platform-level design tokens
- Trading-floor display walls or large-format dashboards (those are a different surface)
- Mobile-first responsive design at phone scale — this is a desktop-first product, mobile is read-only escalation views only

---

## 2. Design Principles

These principles govern every screen decision in this document. When in doubt, defer to them.

**P1 — Density without overwhelm.** Energy traders and operators work with dense data. The product should look like a Bloomberg Terminal, not a consumer SaaS app — but unlike Bloomberg, it should be legible to someone who hasn't been trained for six months. This means: small fonts (12-13px body), tight spacing, table-heavy layouts, but with clear typographic hierarchy, generous whitespace around interactive elements, and progressive disclosure for advanced features.

**P2 — Truth at a glance, detail on demand.** Every screen surfaces the most important information without scrolling. Detail expands on click, hover, or drill-down. A user looking at the position dashboard should know within 200ms whether they're in trouble. They should be able to drill from "we are 50 MW short for delivery hour 14:00 today" to the specific trades contributing in two clicks maximum.

**P3 — Time is the primary axis.** This is a time-series product. Almost every meaningful view has a time dimension as one of its axes. Users orient themselves by date and delivery period before anything else. Date pickers and time-range selectors are first-class persistent controls, not buried in modals.

**P4 — Buckets are colors, not text.** The three buckets (CONTRACTUAL, PLAN, ACTUAL) appear constantly. Users should recognize them visually without reading labels. Assign each a distinctive, never-reused color and stick with it everywhere — chart legends, table cell backgrounds, status indicators, badge fills. Same for cascade tiers (NEAR_TERM / MEDIUM_TERM / LONG_TERM).

**P5 — Show the resolution, expose the source.** BAV-resolved values are what users mostly want to see, but they need to know which bucket the value came from when it matters (audit, reconciliation, dispute resolution). Default to showing BAV; provide a one-click toggle or hover overlay to see the underlying buckets.

**P6 — Reflect the immutability in the UI.** CONTRACTUAL data is immutable. The UI should communicate this — these cells/rows look different (slightly muted background, lock icon affordance) and have no edit menu. PLAN cells are editable; ACTUAL cells are read-only-but-correctable (creates a new version, doesn't edit). The UI's affordances must match the data model's invariants.

**P7 — Async operations are first-class citizens.** Materialization can take seconds to minutes. Trade capture for a 15-year PPA returns immediately but background chunks are still being generated. The UI must make this visible without making the user babysit it: progress indicators on series cards, a notification center for completion events, optimistic UI for confirmed operations.

**P8 — Reconciliation, not editing, is the dominant workflow.** Operators don't sit and create data — they monitor what arrived from external systems, reconcile against expectations, and intervene on exceptions. Default views surface variances and gaps, not raw data. Healthy intervals fade into the background; problematic ones are visually loud.

**P9 — Regulatory rigor is invisible until requested.** The platform has to satisfy REMIT, MiFID II, EMIR — but compliance officers only see compliance views when they ask for them. Traders shouldn't see audit trails, version history, or transaction timestamps cluttering their daily work. These are available on a dedicated tab or panel, accessible but not invasive.

**P10 — Let users own their density.** A senior trader wants ten things on screen at once. A new operator wants three. Density is a setting, not a fixed choice. Provide compact / standard / comfortable density toggles per user, persisted. Don't pick the wrong default for everyone.

---

## 3. User Personas

EU power trading firms have specialized roles. The volume series surface is touched by six primary personas. Each has different goals, different information needs, different tolerance for complexity, and different time horizons.

### 3.1 Energy Trader ("Pia")

Trades day-ahead, intraday, and short-term forward products on EPEX SPOT, EEX, Nord Pool. Captures trades, monitors positions, responds to market moves and gate closures. Time horizon: minutes to days.

**Volume Series interactions:**

- Captures a new trade → wants confirmation that volume series materialized correctly for the near-term window
- Watches the position dashboard during operational hours → wants near-term net positions per delivery point at 15-min granularity, BAV-resolved, with deltas vs prior view highlighted
- Spot-checks individual trades → wants to see the full delivery profile of a trade with all three buckets overlaid
- Adjusts plan ahead of nomination cutoffs → wants to edit PLAN volumes for upcoming delivery intervals

**Critical needs:**

- Sub-second response time on near-term position queries
- Clear visual distinction between "what we contracted" (CONTRACTUAL), "what we plan to deliver" (PLAN), "what we delivered" (ACTUAL)
- Alerts when positions approach risk limits or tolerance bands
- Fast trade entry workflow (already covered by the trade capture module; this UI just confirms volume materialization completed)

**Anti-needs (things that would frustrate):**

- Showing 15-year delivery profiles when only the next 48 hours matter
- Forcing them to choose a bucket when they just want to see net position
- Modal popups that interrupt monitoring of live positions

### 3.2 PPA Originator ("Markus")

Structures long-tenor PPAs (Power Purchase Agreements) with renewable generators or large industrial offtakers. Negotiates contractual terms, models cashflow profiles, hands off to operations once signed. Time horizon: months to years.

**Volume Series interactions:**

- Models a candidate PPA → wants to see the projected delivery profile across the full tenor (3-15 years), with seasonal/profile shaping visible
- Reviews an active PPA → wants to see the full cascade structure (near/mid/long-term) and verify it matches the contractual intent
- Compares actuals vs contractual over multi-year periods → wants aggregated views (annual, monthly) with drill-down to delivery period detail

**Critical needs:**

- Long-horizon visualization that doesn't drown in 15-min intervals — sensible auto-aggregation by zoom level
- Clear separation between contractual structure and operational delivery
- Export to Excel for client reports and internal modeling

**Anti-needs:**

- Real-time position widgets — irrelevant for their work
- 15-min granularity views by default for a 10-year contract — overwhelming and meaningless

### 3.3 Scheduling and Nomination Operator ("Anke")

Submits TSO nominations, manages plan-vs-actual reconciliation, handles balancing market interactions. Operational role with strict regulatory and time-sensitive obligations. Time horizon: hours to days.

**Volume Series interactions:**

- Pre-nomination: reviews PLAN volumes for the next delivery day → wants to see derived plan from contractual, adjust where needed
- Post-delivery: reviews ACTUAL meter data vs PLAN → wants to identify imbalances, document explanations
- Handles corrections: when TSO submits revised metering data weeks later → triggers a new ACTUAL version
- Monitors derivation cron status → wants visibility into whether monthly disaggregation completed for all PPAs

**Critical needs:**

- Reconciliation views that show three buckets side-by-side per delivery period
- Tolerance band violations highlighted visually (over/under contracted bounds)
- Bulk edit capabilities for plan adjustments (drag to apply across hours, copy from another day)
- Audit trail visibility — who changed what when

**Anti-needs:**

- Aggregated dashboards — they need raw delivery-period detail
- Modal-heavy workflows — they're processing many intervals quickly

### 3.4 Risk and Credit Officer ("David")

Monitors portfolio risk, exposure to counterparties, credit limits, regulatory capital requirements. Independent from trading; reports to CRO. Time horizon: days to quarters.

**Volume Series interactions:**

- Daily portfolio risk run → wants aggregated net positions across all tenants/delivery points, both near-term and long-term
- Counterparty exposure → wants to see total CONTRACTUAL volume and PLAN volume by counterparty
- Stress scenarios → wants what-if analysis: "what's my exposure if this PPA's actuals come in 20% below plan?"

**Critical needs:**

- Aggregated rollups with consistent definitions
- Time-as-of views for regulatory queries: "what was our position at 10:00 yesterday?"
- Read-only access to most data; cannot accidentally modify trader-owned content
- Confidence in BAV resolution rules — wants to see exactly how positions were computed

**Anti-needs:**

- Real-time market data clutter — they work on closed daily snapshots
- Editable interfaces — they're a control function, not an operational one

### 3.5 Compliance Officer ("Inga")

Regulatory submissions (REMIT, MiFID II), audit responses, transaction reporting. Activates infrequently but with high-stakes outputs. Time horizon: weeks to years.

**Volume Series interactions:**

- Quarterly regulatory submissions → exports trade and volume data in regulator-mandated formats
- Audit responses → reproduces "what did we know on date T about delivery period D" — requires bi-temporal querying
- GDPR data subject requests → identifies and exports/anonymizes data tied to a specific trader or counterparty

**Critical needs:**

- Bi-temporal time travel ("system time" + "valid time")
- Regulatory export workflows with predefined templates
- Full audit trail visibility, including supersession chains for ACTUAL corrections
- Tamper-evident view of CONTRACTUAL immutability

**Anti-needs:**

- Real-time operational widgets
- Editable surfaces — they shouldn't be able to change anything

### 3.6 Platform Administrator ("Theo")

Manages tenant configuration, user provisioning, system health, on-call response. Spans operational and technical concerns. Time horizon: minutes to weeks.

**Volume Series interactions:**

- Materialization queue health → wants to see chunk processing backlog, DLQ depth, retry counts
- Tenant data integrity → reaper job alerts, reconciliation exceptions
- Performance monitoring → query latency dashboards, compression policy execution
- Tenant lifecycle: activation, deactivation, retention purge

**Critical needs:**

- System health dashboards with clear status indicators
- DLQ inspection and replay capabilities
- Tenant-scoped views with the ability to impersonate (with full audit logging) for support

**Anti-needs:**

- Trading-specific UI — they're cross-tenant operational

---

## 4. Information Architecture

The volume series surface is organized into **six top-level domains**, each with sub-areas. Navigation is a left rail (collapsible) plus a top bar for global controls (tenant selector, date selector, search, user menu, notifications).

```
┌─ Trading                     [Pia, Markus]
│   ├─ Trade Capture (out of scope; lives in trade module)
│   ├─ Trade Detail            ← shows volume series for one trade
│   ├─ My Active Trades        ← list view, filterable
│   └─ PPA Workbench           ← long-horizon PPA view, originator-focused
│
├─ Positions                   [Pia, David]
│   ├─ Live Position Board     ← near-term, real-time
│   ├─ Position Explorer       ← any horizon, drill-down
│   └─ Position History        ← time-as-of queries
│
├─ Operations                  [Anke]
│   ├─ Nominations Workbench   ← plan adjustments, TSO submission
│   ├─ Actuals Reconciliation  ← three-bucket comparison
│   ├─ Corrections             ← ACTUAL versioning interface
│   └─ Derivation Status       ← cron job health
│
├─ Risk                        [David]
│   ├─ Portfolio Dashboard
│   ├─ Counterparty Exposure
│   └─ Scenarios
│
├─ Compliance                  [Inga]
│   ├─ Regulatory Reports
│   ├─ Audit Explorer          ← bi-temporal time travel
│   └─ Data Lineage
│
└─ Admin                       [Theo]
    ├─ System Health
    ├─ Materialization Queue
    ├─ Tenant Management
    └─ User Activity
```

The left rail hides items the user lacks permission for (e.g., a trader doesn't see Compliance or Admin). It also surfaces an active-task indicator: a small numeric badge next to "Operations" if there are reconciliation exceptions; on "Admin" if the DLQ is non-empty.

The top bar persistently shows:
- **Tenant selector** (only for users with cross-tenant access; hidden for single-tenant users)
- **Global date/time anchor** — defaults to "now," can be switched to "as of yesterday 18:00" for compliance
- **Search** — finds trades, series, delivery points by ID or fragment
- **Notifications bell** — chunk completion, reconciliation alerts, system events
- **User menu** — profile, density preference, theme, sign out

---

## 5. Design System Foundations

### 5.1 Color

The product uses a restrained, professional palette. Color carries semantic meaning and is reserved for that meaning.

**Bucket colors (the most-used semantic colors):**

- **CONTRACTUAL** — Deep blue (#1E3A8A): conveys "agreed, committed, immutable." Often used as a darker fill or as a left border on cells.
- **PLAN** — Amber (#D97706): conveys "intent, in-progress, may change." Distinct from warning (which is red) but visually attention-getting because plans are where active work happens.
- **ACTUAL** — Forest green (#15803D): conveys "delivered, real, settled." Avoids the typical "success green" by being slightly darker and more muted.

These three colors are used consistently across charts, table cells, badges, and legend swatches. They are never repurposed for unrelated meanings.

**Cascade tier colors (used for tier indicators and time-axis bands):**

- **NEAR_TERM** — Cyan-leaning blue (#0891B2): conveys "now, immediate"
- **MEDIUM_TERM** — Teal (#0D9488): conveys "near-future, planned"
- **LONG_TERM** — Slate gray (#475569): conveys "distant, structural"

**Status colors (for non-bucket signals):**

- **Healthy / Confirmed** — Neutral gray-green (#64748B background, #15803D text for emphasis)
- **Warning** — Amber (#D97706) — same as PLAN intentionally; tolerance band approaches
- **Error / Breach** — Red (#DC2626) — limits exceeded, DLQ entries, validation failures
- **Info** — Blue (#2563EB) — process notifications, async completion

**Neutrals (the bulk of the UI):**

- Background — Off-white (#FAFAFA) for light theme; deep gray (#0F172A) for dark theme
- Surface — White / dark gray panels
- Border — Subtle gray (#E5E7EB light / #334155 dark)
- Body text — Near-black (#111827 light / #F1F5F9 dark)
- Muted text — Gray (#6B7280) for secondary information

**Dark mode is first-class.** Many traders work in low-light environments to reduce screen fatigue. The dark theme is not an afterthought — it has the same visual fidelity as the light theme. Toggle is in the user menu and persists per user.

### 5.2 Typography

A professional financial product uses serif-free, dense, neutral typefaces. The product uses:

- **Body:** Inter (or system equivalent) — geometric sans-serif optimized for screens. Weight 400 for body, 600 for emphasis, 700 for headings.
- **Numerical:** Inter with tabular figures (or JetBrains Mono for tabular contexts) — ensures numbers align in columns. **All numeric data uses tabular figures.** Non-tabular numbers in tables are an instant readability disaster.
- **Code / IDs:** JetBrains Mono — for trade IDs, series UUIDs, raw scalar values when displayed in technical contexts.

Sizes (compact density baseline):
- Display headings (page titles): 24px / 32px line height, weight 700
- Section headings: 18px / 26px, weight 600
- Subsection headings: 14px / 20px, weight 600
- Body / table cells: 13px / 18px, weight 400
- Microcopy (labels, captions): 11px / 14px, weight 500, often uppercase tracking
- Tabular figures: same size as body but `font-feature-settings: 'tnum'`

Comfortable density bumps body to 14px and increases line-height by 2px throughout.

### 5.3 Spacing and Layout

Spacing follows a 4px base unit. The grid is 12 columns at desktop widths (≥1280px) and 8 columns at narrower widths.

Standard panel paddings:
- Compact: 12px
- Standard: 16px
- Comfortable: 20px

Card and panel borders are 1px subtle gray; corners are 6px rounded for a contemporary feel without being playful.

Tables use dense rows by default (28px row height in compact, 32px standard, 36px comfortable). Alternating row backgrounds are very subtle (3% off-white) to preserve readability without zebra-striping fatigue.

### 5.4 Iconography

Icons use a single library (Lucide or Heroicons) at consistent stroke weight (1.5px). Bucket icons are NOT used — buckets are conveyed by color and short labels. Functional icons (filter, export, settings, drill-down arrow) are 16px in dense contexts, 20px in standard.

A small lock icon (🔒) appears next to CONTRACTUAL data values in editable contexts to indicate immutability. ACTUAL rows that have been superseded display a chain link icon (🔗) that opens the version history.

### 5.5 Density modes

Three density modes available per user, persisted to user preferences:

| Mode | Body size | Row height | Panel padding | Use case |
|---|---|---|---|---|
| Compact | 12px | 28px | 12px | Power users, large monitors, reconciliation grids |
| Standard | 13px | 32px | 16px | Default; balanced |
| Comfortable | 14px | 36px | 20px | Lower-density workflows, training, accessibility |

Density does not change information content — every screen shows the same data at every density. It only changes spacing.

### 5.6 Interaction primitives

- **Click** — primary action, drill-down, selection
- **Right-click / context menu** — secondary actions per row (export, copy ID, view audit trail)
- **Hover** — preview, tooltip, soft-highlight related rows
- **Drag** — selection across cells, time range adjustment, reorder
- **Keyboard shortcuts** — power users navigate without mouse; documented in a `?` help overlay
- **Double-click** — quick-edit on editable cells (PLAN volumes); never destructive

Kbd shortcuts are non-trivial in a Bloomberg-style product:
- `g t` go to Trades
- `g p` go to Positions  
- `g o` go to Operations
- `?` show keyboard cheat sheet
- `/` focus search
- `t` jump to today on time-series view
- `[` `]` previous/next delivery period
- `b` toggle bucket view (cycle CONTRACTUAL → PLAN → ACTUAL → BAV)

### 5.7 Loading and async patterns

This is critical given the data architecture has continuous aggregates with a 5-minute refresh window and async chunk materialization:

- **Skeleton loaders** for initial loads, not spinners — they communicate structure
- **Inline progress** for materialization: "Generating chunks: 14/180" with a thin progress bar on the trade card
- **Stale data badges** when showing data that may not yet reflect a recent write: small yellow dot in the corner of a panel with tooltip "Updated 4 min ago"
- **Optimistic UI** for user actions (PLAN edits): the value updates instantly with a subtle pulse; if the write fails, it reverts with a non-modal error banner
- **Notification toast** for async completion: bottom-right, 4-second auto-dismiss, with a "View" link

### 5.8 Error states

Error messages are explicit, actionable, and never blame the user. Three categories:

1. **Validation errors** — inline next to the field, red text, specific (e.g., "PLAN volume cannot exceed contractual ceiling of 50.0 MW for this interval")
2. **Operational errors** — top of screen banner, dismissible (e.g., "Materialization for PPA-2026-001 chunks is delayed; retry in progress")
3. **System errors** — full-page interstitial only when the user cannot proceed (e.g., "Cluster failover in progress, read-only mode active")

Empty states get attention. A trader who has no active trades sees a meaningful empty state with a primary action ("Capture your first trade"), not a blank panel.

---

## 6. Screen-by-Screen Specifications

This section describes each screen as a layout mockup with components, data shown, interactions, and persona mapping. Mockups are described textually using a consistent format: layout, regions, interactions, states.

### 6.1 Trading > Trade Detail

**Primary persona:** Pia (Energy Trader), Markus (PPA Originator)

**Purpose:** Single-trade view showing the full volume series with all three buckets, cascade tier structure, and lifecycle status.

**Layout (desktop, 1280px+):**

```
┌─────────────────────────────────────────────────────────────────────┐
│ Tenant ▾   Date ▾   Search ▾   ⚲ 3   ◐ Theo ▾                       │ ← Top bar
├─┬───────────────────────────────────────────────────────────────────┤
│N│ ◀ Trades / TR-2026-0042: PPA SolarCo DE-LU 50MW 10yr               │ ← Breadcrumb
│a│                                                                   │
│v│ ┌─ Header card ────────────────────────────────────────────────┐ │
│ │ │ TR-2026-0042  ·  Status: Active  ·  Capture: 2026-04-15      │ │
│r│ │ Counterparty: SolarCo Holdings  ·  Direction: BUY            │ │
│a│ │ Delivery: 2026-05-01 → 2036-04-30  ·  DE-LU  ·  15-min base │ │
│i│ │ Materialization: ████████░░ 87%  (next chunk: in 2 min)      │ │
│l│ └──────────────────────────────────────────────────────────────┘ │
│ │                                                                   │
│ │ ┌─ Cascade overview ─────────────────────────────────────────┐   │
│ │ │   May 26 ─── Jul 26 ───── Jul 27 ───────────── Apr 36       │   │
│ │ │   ████NEAR████│██████MID██████│███████LONG████████          │   │
│ │ │   8,640 ints  │ 365 ints      │ 105 ints                    │   │
│ │ │   15-min      │ daily         │ monthly                     │   │
│ │ │   ✓ FULL     │ ✓ FULL        │ ⏳ 87% (158/180 chunks)      │   │
│ │ └────────────────────────────────────────────────────────────┘   │
│ │                                                                   │
│ │ ┌─ Volume profile ──────────────────────────  [BAV ▾] [Export ▾]│
│ │ │                                                                │
│ │ │ [Chart: stacked area / line chart]                             │
│ │ │ X axis: time (resamples by zoom level)                         │
│ │ │ Y axis: MW                                                     │
│ │ │ Lines: CONTRACTUAL (blue), PLAN (amber), ACTUAL (green) when   │
│ │ │ "All buckets" toggled. BAV shown as thicker dark line by       │
│ │ │ default.                                                       │
│ │ │                                                                │
│ │ │ Time range selector below chart: 1D | 1W | 1M | 1Y | All       │
│ │ │ Granularity follows zoom: weekly view = daily resampling, etc. │
│ │ └────────────────────────────────────────────────────────────────┘
│ │                                                                   │
│ │ ┌─ Detail table (delivery periods)  ────  [Filters ▾] [Edit▾]   │
│ │ │ Date/time  │ Granularity│ Tier │ Contracted│ Plan │ Actual    │ │
│ │ │ Aug 1 2026 │ 15-min     │ NEAR │   50.0🔒  │ 50.0 │ 48.3      │ │
│ │ │ Aug 1 2026 │ 15-min     │ NEAR │   50.0🔒  │ 50.0 │ 51.1      │ │
│ │ │ ... 96 rows for the day, virtualized scroll ...                │ │
│ │ └────────────────────────────────────────────────────────────────┘
│ │                                                                   │
│ │ ┌─ Tabs ──────────────────────────────────────────────────────┐ │
│ │ │ [Overview] [Formula] [Audit Trail] [Corrections] [Costs]    │ │
│ │ └──────────────────────────────────────────────────────────────┘ │
└─┴───────────────────────────────────────────────────────────────────┘
```

**Components:**

- **Header card:** Trade ID, status badge, capture date, counterparty, direction, delivery window, base granularity, materialization progress. Materialization progress shows real-time chunk completion via WebSocket; click expands to see per-chunk status.
- **Cascade overview band:** A horizontal time-axis visualization showing the three cascade tiers. Width of each band is proportional to its time span. Each tier shows its interval count, granularity, and FULL/PARTIAL status. Clicking a tier scrolls the chart and table below to that range.
- **Volume profile chart:** The primary visual. Default view shows BAV-resolved volume as a single thick line. A bucket toggle (top-right of chart) cycles through:
  - **BAV** (default): single line, dark gray
  - **All buckets**: three overlapping lines in bucket colors
  - **Contractual only**: blue line
  - **Plan only**: amber line
  - **Actual only**: green line, only spans up to "now"
  - **Tolerance band**: shows tolerance_floor_mw and tolerance_ceiling_mw as a translucent band; volume line goes red when outside
  
  Chart uses time as X-axis. Y-axis is MW. Below the chart is a time range selector (1D / 1W / 1M / 1Y / All) and a brush-style range slider for fine-grained selection.
- **Detail table:** Virtualized scrolling table of delivery periods. Default view shows the BAV-resolved volume, with columns expanding on hover to show per-bucket values. Bucket cells are color-coded with the contractual cell having a lock icon. The table respects the chart's selected time range.
- **Tab strip below table:** Switches the bottom panel to show the formula recipe (Formula tab), audit trail (system events on this series), corrections (ACTUAL version history), or cost breakdown (settlement scalars).

**Interactions:**

- Click a row in the table → highlights the corresponding interval in the chart
- Hover a chart point → tooltip shows date/time, all bucket values, granularity, tier
- Right-click any row → context menu: View audit, Copy ID, Export selected, Compare to prior version
- Drag across chart → selects a time range; table filters to match
- Keyboard `b` → cycles bucket view
- Keyboard `e` → opens edit mode for the selected PLAN cells (only PLAN is editable)

**States:**

- **Loading:** Skeleton cards for header and chart; table shows placeholder rows
- **Materialization in progress:** Progress bar in header; chart shows materialized portion clearly with a "still generating" indicator past the materialized boundary
- **Failed chunks:** Header banner with retry CTA; affected delivery periods marked with a small alert icon in the chart

**Edge cases:**

- A 15-year PPA at 15-min: ~525,000 intervals at base granularity. The detail table virtualizes; the chart resamples by zoom level (showing daily aggregates when zoomed to "All", 15-min only when zoomed to a single day).
- A monthly forward (single granularity, no cascade): cascade overview band shows a single tier filling the whole space; chart is straightforward.
- A trade with no ACTUAL data yet (future delivery only): green ACTUAL line is absent from chart; ACTUAL columns in table show em-dash (—).

---

### 6.2 Trading > PPA Workbench

**Primary persona:** Markus (PPA Originator)

**Purpose:** Long-horizon view optimized for multi-year PPAs. Combines contractual modeling, profile shaping, and lifetime-vs-actuals analysis.

**Layout:**

```
┌──────────────────────────────────────────────────────────────────────┐
│ Tenant ▾   Date ▾   Search ▾   ⚲   ◐                                │
├─┬────────────────────────────────────────────────────────────────────┤
│N│ ◀ Trades / TR-2026-0042 / PPA Workbench                           │
│a│                                                                    │
│v│ ┌─ Selector ─────────────────────────────────────────────────┐    │
│ │ │ PPA: SolarCo DE-LU 50MW 10yr ▾   View: [Profile] [Cashflow]│    │
│r│ │                                  [Variance] [Comparison]    │    │
│a│ └──────────────────────────────────────────────────────────────┘   │
│i│                                                                    │
│l│ ┌─ Annual rollup heatmap ────────────────────────────────────┐    │
│ │ │       Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec       │    │
│ │ │ 2026  ·   ·   ·   ·   ▓   ▓   ▓   ▓   ▓   ▓   ▓   ▓   →    │    │
│ │ │ 2027  ▓   ▓   ▓   ▓   ▓   ▓   ▓   ▓   ▓   ▓   ▓   ▓        │    │
│ │ │ 2028  ▓   ▓   ▓   ▓   ▓   ▓   ▓   ▓   ▓   ▓   ▓   ▓        │    │
│ │ │ ...                                                          │    │
│ │ │ 2036  ▓   ▓   ▓   ▓   ·   ·   ·   ·   ·   ·   ·   ·   ←    │    │
│ │ │                                                              │    │
│ │ │ Cell color: variance vs contractual. Click cell to drill.   │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Profile shape ────────────────────────────────────────────┐    │
│ │ │ [Chart: 24-hour profile, generation-following pattern]      │    │
│ │ │ Hours 0-23 on X axis, MW on Y axis                          │    │
│ │ │ Shows typical day shape; toggle for season/weekday          │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Key metrics ──────────────────────────────────────────────┐    │
│ │ │ Total contracted volume:  4,380 GWh                          │    │
│ │ │ Delivered to date:        832 GWh (19%)                      │    │
│ │ │ Avg variance vs plan:     +1.8%                              │    │
│ │ │ Total contracted value:   €197M (at base + green premium)    │    │
│ │ │ Realized to date:         €37.2M                             │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
└─┴────────────────────────────────────────────────────────────────────┘
```

**Components:**

- **PPA selector and view toggle:** Top-level controls. View switches the rest of the screen between four perspectives: Profile (delivery shape), Cashflow (€ over time), Variance (actual vs contractual deviation), Comparison (multi-PPA side-by-side).
- **Annual rollup heatmap:** Years × months grid. Each cell is shaded by variance from contractual (neutral when on-plan, red when under-delivered, blue when over-delivered). Cells before delivery start or after delivery end are blank with arrow indicators (→ ←). Clicking a cell drills down to the monthly detail view.
- **Profile shape chart:** For Generation-Following profiles (solar/wind), shows the typical 24-hour delivery shape. Toggleable between annual-average, summer/winter, weekday/weekend.
- **Key metrics panel:** Summary numbers — contracted volume, delivered to date, percentage complete, variance, monetary values from contractual scalars (base price × volume + green premium).

**Interactions:**

- Click any heatmap cell → drills to that month's daily-resolution view
- Hover heatmap cell → tooltip with absolute volume, variance, settlement status
- View toggle (Profile/Cashflow/Variance/Comparison) → swaps the chart panel
- "Comparison" view: ability to add up to 4 PPAs as overlays for side-by-side analysis

**States:**

- A newly captured 15-year PPA with only NEAR_TERM materialized: heatmap shows materialized portion crisply, future portion in lighter shade with "Generating" indicator
- An expired PPA: heatmap is fully populated, no future portion, "Closed" badge in header

---

### 6.3 Positions > Live Position Board

**Primary persona:** Pia (Energy Trader)

**Purpose:** Real-time monitoring of net positions per delivery point, primarily for the next 48 hours. Updates as trades and metering data flow in.

**Layout:**

```
┌──────────────────────────────────────────────────────────────────────┐
│ Tenant ▾   Date: 2026-08-01 14:30 ▾   ⚲ 2   ◐                       │
├─┬────────────────────────────────────────────────────────────────────┤
│N│ Live Position Board                       [⏸ Auto-refresh]  [⚙]    │
│a│                                                                    │
│v│ ┌─ Delivery point selector ──────────────────────────────────┐     │
│ │ │ All ▼   DE-LU-001 ✓   DE-LU-002 ✓   FR-001 ☐   AT-001 ☐   │     │
│r│ └──────────────────────────────────────────────────────────────┘    │
│a│                                                                    │
│i│ ┌─ DE-LU-001 ────────────────────────────────────────────────┐    │
│l│ │ Now (14:30) →                                                │    │
│ │ │   Net pos: -12.5 MW   PLAN: -15.0  ACT: -12.5  ⚠ over plan  │    │
│ │ │                                                              │    │
│ │ │ Next 24h heatmap (15-min cells, 96 cells):                  │    │
│ │ │ 14:30 ▓▓▒▒░░░░░░░░░░░░░░░░▒▒▒▒▓▓▓▓▒▒░░░░░░ 14:30+24h        │    │
│ │ │       └── shading: deviation from PLAN, red=under, blue=over │    │
│ │ │                                                              │    │
│ │ │ Click cell to drill → Position Explorer at that interval    │    │
│ │ │                                                              │    │
│ │ │ Mini line chart (24h):                                      │    │
│ │ │ ─── PLAN line                                                │    │
│ │ │ ─── ACTUAL line (up to now)                                 │    │
│ │ │ ─── BAV (current+forecast)                                  │    │
│ │ └──────────────────────────────────────────────────────────────┘    │
│ │                                                                    │
│ │ ┌─ DE-LU-002 ────────────────────────────────────────────────┐    │
│ │ │ ... same structure ...                                       │    │
│ │ └──────────────────────────────────────────────────────────────┘    │
│ │                                                                    │
│ │ Footer: Last update 14:30:02 · Cache hit: 99.2% · Latency: 8ms   │
└─┴────────────────────────────────────────────────────────────────────┘
```

**Components:**

- **Delivery point selector:** Multi-select chips. User picks which delivery points to display. Persistent per user.
- **Per-delivery-point card:** One card per selected delivery point. Each card has:
  - Current state (now): net position, PLAN, ACTUAL, deviation status
  - 24-hour heatmap strip showing deviation per 15-min slot
  - Mini line chart for the same window with PLAN and ACTUAL lines
- **Footer status bar:** Cache hit ratio, end-to-end latency, last update timestamp. Engineers and ops will appreciate this; traders will glance at it for confidence.

**Interactions:**

- Auto-refresh by default (5-second polling via WebSocket position.updated events). Can be paused with the ⏸ control.
- Click a heatmap cell → drills to Position Explorer for that interval
- Hover heatmap cell → tooltip with interval start, exact net MW, PLAN, ACT, deviation
- Right-click a delivery point card → context menu: Show contributing trades, Mute alerts for this delivery point, Open in Explorer
- Settings ⚙ → configure threshold for "deviation alert" coloring (e.g., 5% deviation = warning, 10% = error)

**States:**

- **Quiet state:** All deviations within tolerance, heatmap is mostly neutral. The board looks calm.
- **Active state:** Multiple cards have warning/error indicators. Cards with breaches float to the top of the list automatically.
- **Stale data:** Yellow dot on a card if its last update is >30 seconds old; tooltip "Position cache stale; falling through to PostgreSQL"

**Critical:** The page must respect the principle "truth at a glance." A trader should be able to look at this board peripherally while doing other work and instantly see if something needs attention.

---

### 6.4 Positions > Position Explorer

**Primary persona:** Pia, David, Anke

**Purpose:** Drill down into a specific delivery interval (or range) to see what trades contribute, what the BAV resolution looks like, and how the bucket structure plays out.

**Layout:**

```
┌──────────────────────────────────────────────────────────────────────┐
│ Tenant ▾   Date: 2026-08-01 14:00–14:15 ▾                           │
├─┬────────────────────────────────────────────────────────────────────┤
│N│ Position Explorer                                                  │
│a│                                                                    │
│v│ ┌─ Context band ─────────────────────────────────────────────┐     │
│ │ │ Delivery point: DE-LU-001  ▾                                │    │
│r│ │ Time range: Aug 1, 14:00–14:15  ▾   Granularity: 15-min ▾  │    │
│a│ │ Bucket view: BAV ▾                                          │    │
│i│ └──────────────────────────────────────────────────────────────┘   │
│l│                                                                    │
│ │ ┌─ Net position summary ─────────────────────────────────────┐    │
│ │ │ Net BAV: -12.5 MW (short)                                    │    │
│ │ │ Resolution: ACTUAL (metered post-delivery)                   │    │
│ │ │ ┌──────────────────────────────────────────────────┐        │    │
│ │ │ │ CONTRACTUAL: +37.5 MW   PLAN: +35.0   ACTUAL: +37.5│      │    │
│ │ │ │            └─ 4 trades   └─ 4 trades   └─ 4 trades │      │    │
│ │ │ └──────────────────────────────────────────────────┘        │    │
│ │ │ Sells:                                                      │    │
│ │ │ ┌──────────────────────────────────────────────────┐        │    │
│ │ │ │ CONTRACTUAL: -50.0 MW   PLAN: -50.0   ACTUAL: -50.0│      │    │
│ │ │ │            └─ 1 trade   └─ 1 trade   └─ 1 trade   │      │    │
│ │ │ └──────────────────────────────────────────────────┘        │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Contributing trades ──────────────────────────────────────┐    │
│ │ │ Trade ID  │ Direction│Counterparty│ Contracted│ Plan │Actual│    │
│ │ │ TR-001    │ BUY      │ SolarCo    │   25.0🔒 │ 22.5 │ 25.0 │    │
│ │ │ TR-002    │ BUY      │ WindCo     │   12.5🔒 │ 12.5 │ 12.5 │    │
│ │ │ TR-003    │ SELL     │ Industrial1│  -50.0🔒 │-50.0 │-50.0 │    │
│ │ │ TR-004    │ BUY      │ Battery    │    0.0🔒 │  0.0 │  0.0 │    │
│ │ │ ────────  │ ─        │ ─          │  -12.5    │-15.0 │-12.5 │    │
│ │ │ Net                                                          │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Cost breakdown ───────────────────────────────────────────┐    │
│ │ │ Balancing cost:      +€42                                    │    │
│ │ │ Imbalance penalty:   €0                                      │    │
│ │ │ Profile cost:        €18                                     │    │
│ │ │ Realized P&L impact: -€340 (net short × spot price)         │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
└─┴────────────────────────────────────────────────────────────────────┘
```

**Components:**

- **Context band:** Three persistent selectors for delivery point, time range, and granularity. Time range supports presets (15-min slot, 1 hour, 1 day, custom) plus a date range picker.
- **Net position summary:** Shows the BAV-resolved net position prominently, then breaks down by direction (Buys/Sells) with all three bucket totals visible. The "resolution" line tells the user which bucket the BAV value came from.
- **Contributing trades table:** Lists each trade contributing to this position. Columns show contractual (with lock), plan, actual per trade. Direction is icon-coded. Net row at the bottom matches the summary.
- **Cost breakdown:** Realized cost scalars from ACTUAL bucket. Computed P&L impact based on net position × applicable price.

**Interactions:**

- Click a trade row → opens the trade detail view (Section 6.1)
- Hover a position summary number → tooltip with the COALESCE chain ("This is ACTUAL because actual_volume IS NOT NULL")
- Bucket view selector → toggles which values are shown as primary (BAV by default)
- Time range expansion → expanding the range causes the summary to aggregate (sum of energy across the range)

**States:**

- **Pre-delivery:** ACTUAL columns are em-dash; cost breakdown shows "Pending settlement"
- **Mid-delivery:** ACTUAL appears progressively as metering data flows in
- **Post-settlement:** All values populated; settlement_finalized_at timestamp visible

---

### 6.5 Operations > Nominations Workbench

**Primary persona:** Anke (Scheduling and Nomination Operator)

**Purpose:** Pre-delivery PLAN review and adjustment for the next delivery day, ahead of TSO nomination cutoffs (typically D-1 14:00 for German market).

**Layout:**

```
┌──────────────────────────────────────────────────────────────────────┐
│ Tenant ▾   Delivery day: 2026-08-02 ▾   ⚲ 5   ◐                     │
├─┬────────────────────────────────────────────────────────────────────┤
│N│ Nominations Workbench                                              │
│a│                                                                    │
│v│ ┌─ Status banner ────────────────────────────────────────────┐    │
│ │ │ 🕐 Nomination cutoff: TODAY 14:00 CET (in 47 minutes)        │    │
│r│ │ Status: 7 of 12 delivery points reviewed; 5 require attention│    │
│a│ └──────────────────────────────────────────────────────────────┘    │
│i│                                                                    │
│l│ ┌─ Delivery point list ──────────────────────────────────────┐    │
│ │ │ ● DE-LU-001  Reviewed       ✓                                │    │
│ │ │ ● DE-LU-002  Reviewed       ✓                                │    │
│ │ │ ⚠ DE-LU-003  Plan vs Contract diverges by 8% on hour 16     │    │
│ │ │ ⚠ FR-001     PPA forecast not yet derived for hour 22        │    │
│ │ │ ● AT-001     Reviewed       ✓                                │    │
│ │ │ ... etc                                                      │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Selected: DE-LU-003 — 24-hour grid ──────────────────────┐    │
│ │ │ Hour │ Contracted │ Plan      │ Forecast │ Tolerance      │    │
│ │ │ 00   │  25.0 🔒  │ 25.0      │  25.5    │ 22-28          │    │
│ │ │ 01   │  25.0 🔒  │ 25.0      │  24.8    │ 22-28          │    │
│ │ │ ... 24 rows ...                                              │    │
│ │ │ 14   │  25.0 🔒  │ 25.0      │  25.2    │ 22-28          │    │
│ │ │ 15   │  25.0 🔒  │ 25.0      │  25.0    │ 22-28          │    │
│ │ │ 16   │  25.0 🔒  │ 27.0 ⚠   │  27.5    │ 22-28          │    │
│ │ │ 17   │  25.0 🔒  │ 25.0      │  25.1    │ 22-28          │    │
│ │ │ ...                                                          │    │
│ │ │ 23   │  25.0 🔒  │ 25.0      │  24.9    │ 22-28          │    │
│ │ │                                                              │    │
│ │ │ [Bulk: Apply contract] [Bulk: Apply forecast] [Submit ↗]   │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
└─┴────────────────────────────────────────────────────────────────────┘
```

**Components:**

- **Status banner:** Shows the upcoming nomination deadline with a live countdown. Color shifts from neutral to amber to red as the deadline approaches. Status summary indicates progress.
- **Delivery point list:** All delivery points for the tenant, with status icons (reviewed / requires attention / submitted). Items requiring attention float to the top.
- **24-hour grid:** Hourly grid for the selected delivery point. Columns: contracted (locked, immutable), plan (editable), forecast (from generation profile), tolerance band. Cells with issues are highlighted: PLAN above forecast that's outside tolerance gets an amber warning; values outside tolerance band are red.
- **Bulk action buttons:** Common operations — apply contracted to all hours, apply forecast to all hours, submit to TSO. Selecting a row range first scopes these actions to selected hours.

**Interactions:**

- Double-click a PLAN cell → inline edit; commits on Enter or blur; shows pulse on save
- Drag select across multiple cells → bulk operations apply to selection
- Right-click PLAN cell → context menu: Apply contracted value here, Apply forecast value here, Reset to derived plan, Add note for this hour
- Click "Submit" → confirmation dialog summarizing changes; on confirm, sends to TSO and updates status
- Keyboard `↑↓` to navigate cells; `Enter` to edit; `Esc` to cancel

**States:**

- **Pre-cutoff:** All controls active; banner counts down
- **Cutoff passed:** Editing disabled; banner switches to "Submitted at 13:48"
- **Late submission window:** Some markets allow late submissions with penalty; banner indicates "LATE submission" warning before allowing edits to resume

---

### 6.6 Operations > Actuals Reconciliation

**Primary persona:** Anke

**Purpose:** Post-delivery comparison of PLAN vs ACTUAL, with workflow for documenting variances, dispatching corrections, and identifying systemic issues.

**Layout:**

```
┌──────────────────────────────────────────────────────────────────────┐
│ Tenant ▾   Delivery day: 2026-07-31 ▾   View: Variance ▾            │
├─┬────────────────────────────────────────────────────────────────────┤
│N│ Actuals Reconciliation — 2026-07-31                                │
│a│                                                                    │
│v│ ┌─ Summary ──────────────────────────────────────────────────┐    │
│ │ │ Total intervals: 1,152 (12 dp × 96)                          │    │
│r│ │ Within tolerance: 1,098 (95.3%)                              │    │
│a│ │ Variance >5%: 41                                             │    │
│i│ │ Variance >10%: 13                                            │    │
│l│ │ Missing actuals: 0                                           │    │
│ │ │ Pending corrections: 2 (TSO submitted late)                  │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Heatmap: delivery point × hour ──────────────────────────┐     │
│ │ │           00 01 02 03 04 05 06 ... 22 23                    │    │
│ │ │ DE-LU-001 ░░ ░░ ░░ ░░ ░░ ░░ ░░ ... ▒▒ ▒▒                   │    │
│ │ │ DE-LU-002 ░░ ░░ ░░ ░░ ░░ ░░ ░░ ... ▓▓ ▓▓                   │    │
│ │ │ DE-LU-003 ░░ ▒▒ ░░ ░░ ░░ ░░ ░░ ... ░░ ░░                   │    │
│ │ │ ...                                                          │    │
│ │ │                                                              │    │
│ │ │ Cell color: |actual - plan| / plan, banded                  │    │
│ │ │ ░ <2%  ▒ 2-5%  ▓ 5-10%  ██ >10%                              │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Selected cell: DE-LU-002 hour 23 ────────────────────────┐    │
│ │ │ Plan: 28.0 MW   Actual: 24.8 MW   Variance: -11.4%          │    │
│ │ │ Contracted (BAV ref): 25.0 MW                                │    │
│ │ │ Tolerance: 22-28 MW   Within bounds ✓                       │    │
│ │ │ Trades contributing (4):                                    │    │
│ │ │   TR-2026-1234  +30 MW (BUY, SolarCo)                        │    │
│ │ │   TR-2026-1287  -2 MW  (SELL, Industrial)                    │    │
│ │ │   ...                                                        │    │
│ │ │ Cost impact: imbalance penalty €78                          │    │
│ │ │ [Add explanation note] [View ACTUAL versions]              │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
└─┴────────────────────────────────────────────────────────────────────┘
```

**Components:**

- **Summary panel:** Top-line metrics for the day's reconciliation status. Numbers are clickable to filter the heatmap.
- **Heatmap (delivery point × hour):** A 2D grid where rows are delivery points and columns are hours. Cell color shading represents variance magnitude in banded categories. The user scans for hot zones.
- **Selected cell detail panel:** When a heatmap cell is clicked, this panel populates with full detail for that delivery point + hour combination. Shows plan, actual, contractual reference, tolerance status, contributing trades, and cost impact.

**Interactions:**

- Click heatmap cell → populates detail panel
- Hover heatmap cell → tooltip with delivery point, hour, plan, actual, variance %
- Click any contributing trade in the detail → opens trade detail view
- "Add explanation note" → modal for free-text annotation, persisted to audit trail
- "View ACTUAL versions" → opens a side panel showing the supersession chain for this interval (if corrections have occurred)

**States:**

- **Day in progress:** Heatmap grayscale for hours that haven't completed delivery yet
- **All settled:** Heatmap fully colored, settlement_finalized_at visible
- **Disputed/pending corrections:** Cells marked with a special pattern for intervals awaiting TSO correction

---

### 6.7 Operations > Corrections

**Primary persona:** Anke, with audit visibility for Inga

**Purpose:** Manage ACTUAL corrections — view supersession chains, audit version history, manually trigger BAV refresh.

**Layout:**

```
┌──────────────────────────────────────────────────────────────────────┐
│ Tenant ▾   Date: 2026-07-15 ▾                                        │
├─┬────────────────────────────────────────────────────────────────────┤
│N│ Corrections                                                        │
│a│                                                                    │
│v│ ┌─ Filter ───────────────────────────────────────────────────┐    │
│ │ │ Status: All ▾   Source: All ▾   Delivery date range: ...   │    │
│r│ └──────────────────────────────────────────────────────────────┘   │
│a│                                                                    │
│i│ ┌─ Recent corrections ───────────────────────────────────────┐    │
│l│ │ Delivery date│ Delivery pt │ Versions│ Latest source │ When  │    │
│ │ │ Jul 15 14:00 │ DE-LU-001   │  1→2    │ TSO restated  │ today │    │
│ │ │ Jul 15 14:15 │ DE-LU-001   │  1→2    │ TSO restated  │ today │    │
│ │ │ Jul 03 22:00 │ DE-LU-005   │  1→2→3  │ Manual        │ 3d ago│    │
│ │ │ Jun 28 09:30 │ AT-001      │  1→2    │ TSO restated  │ 5d ago│    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Selected: Jul 15 14:00 DE-LU-001 ─────────────────────────┐    │
│ │ │ Logical key: TR-2026-1234, 2026-07-15 14:00, ACTUAL          │    │
│ │ │                                                              │    │
│ │ │ Version chain:                                               │    │
│ │ │ ┌──────────────────────────────────────────────────────┐    │    │
│ │ │ │ V1  2026-07-16 02:30  Initial meter   24.8 MW         │    │    │
│ │ │ │ ▼   superseded_by V2                                   │    │    │
│ │ │ │ V2  2026-08-01 11:42  TSO restated   25.1 MW (CURRENT)│    │    │
│ │ │ └──────────────────────────────────────────────────────┘    │    │
│ │ │                                                              │    │
│ │ │ Difference: +0.3 MW (+1.2%)                                 │    │
│ │ │ Cost impact: balancing cost adjustment +€8                  │    │
│ │ │ BAV refreshed at: 2026-08-01 11:42:14                       │    │
│ │ │ [View V1 detail] [View V2 detail] [Audit trail ↗]          │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
└─┴────────────────────────────────────────────────────────────────────┘
```

**Components:**

- **Filter panel:** Status, source (TSO / manual / system), date range
- **Recent corrections list:** Each row represents one logical interval that has had corrections applied. Shows version count, latest source, and recency.
- **Selected detail panel:** When a row is selected, shows the full supersession chain as a vertical timeline. Each version row shows version number, timestamp, source/reason, and value. The current version is marked CURRENT. Difference vs previous version is computed.

**Interactions:**

- Click a version row → opens that version's detail in a side panel (read-only; superseded versions are immutable)
- "Audit trail" link → opens compliance audit explorer pre-filtered to this logical key

**States:**

- All corrections visible are read-only at the data level. The UI cannot modify ACTUAL data directly — corrections come from external TSO feeds or manual reconciliation flows initiated elsewhere.

---

### 6.8 Operations > Derivation Status

**Primary persona:** Anke (operationally), Theo (system-wide)

**Purpose:** Visibility into the monthly cron that derives PLAN intervals from CONTRACTUAL across the cascade tiers.

**Layout:**

```
┌──────────────────────────────────────────────────────────────────────┐
│ Tenant ▾                                                             │
├─┬────────────────────────────────────────────────────────────────────┤
│N│ Derivation Status                                                  │
│a│                                                                    │
│v│ ┌─ Last run summary ─────────────────────────────────────────┐    │
│ │ │ Run started: 2026-08-01 02:00                                │    │
│r│ │ Run completed: 2026-08-01 02:14                              │    │
│a│ │ Series processed: 4,012  ✓                                   │    │
│i│ │ deriveDaily ops: 4,012   ✓ all succeeded                     │    │
│l│ │ deriveBaseGranularity ops: 4,012  ✓ all succeeded            │    │
│ │ │ PLAN intervals created: 1.1M                                 │    │
│ │ │ BAV continuous aggregate refreshed: ✓                        │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Per-PPA progress (sample) ────────────────────────────────┐    │
│ │ │ TR-2026-0042  PPA SolarCo 10yr   ✓ derived                  │    │
│ │ │ TR-2026-0067  PPA WindCo 7yr     ✓ derived                  │    │
│ │ │ TR-2026-0099  PPA Industrial 5yr ✓ derived                  │    │
│ │ │ ... 4,009 more                                              │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Schedule ─────────────────────────────────────────────────┐    │
│ │ │ Next run: 2026-09-01 02:00 (in 30 days)                      │    │
│ │ │ Window scope: month of 2026-09 → daily PLAN intervals        │    │
│ │ │                month of 2026-10-01 → 15-min NEAR_TERM PLAN  │    │
│ │ │                                                              │    │
│ │ │ [Run manually] [View history] [Configure]                   │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
└─┴────────────────────────────────────────────────────────────────────┘
```

**Components:**

- Last run summary, per-PPA progress (virtualized list), and schedule for next run with manual trigger.

**States:**

- **Healthy:** Green check on summary, no failed PPAs
- **Failures present:** Red banner on top, list of failed PPAs with retry options
- **Currently running:** Real-time progress bar, list updating live

---

### 6.9 Risk > Portfolio Dashboard

**Primary persona:** David (Risk and Credit Officer)

**Purpose:** Aggregated risk view across all positions, all delivery horizons, with stress and exposure analysis.

**Layout:**

```
┌──────────────────────────────────────────────────────────────────────┐
│ Tenant ▾   As of: 2026-08-01 close ▾                                 │
├─┬────────────────────────────────────────────────────────────────────┤
│N│ Portfolio Dashboard                                                │
│a│                                                                    │
│v│ ┌─ KPI tiles ────────────────────────────────────────────────┐    │
│ │ │ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐        │    │
│r│ │ │ Total    │ │ Net      │ │ Open     │ │ Daily    │        │    │
│a│ │ │ MtM      │ │ Position │ │ PPAs     │ │ VaR (95%)│        │    │
│i│ │ │ €127M    │ │ +850 GWh │ │ 142      │ │ €1.2M    │        │    │
│l│ │ │ ▲ +€2.1M │ │ ▲ +12 GWh│ │          │ │ ▲ €0.1M  │        │    │
│ │ │ └──────────┘ └──────────┘ └──────────┘ └──────────┘        │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Position by horizon ──────────────────────────────────────┐    │
│ │ │ [Stacked bar chart]                                          │    │
│ │ │ X axis: months, next 24 months                              │    │
│ │ │ Y axis: net MWh                                              │    │
│ │ │ Stacks: by counterparty (top 5 + Other)                     │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Top exposures ────────────────────────────────────────────┐    │
│ │ │ Counterparty │ Contract value│ Realized │ Unrealized       │    │
│ │ │ SolarCo      │ €88M          │ €18M     │ €70M             │    │
│ │ │ WindCo       │ €72M          │ €14M     │ €58M             │    │
│ │ │ ...                                                          │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Time-as-of selector ──────────────────────────────────────┐    │
│ │ │ Compare to: Yesterday close ▾                                │    │
│ │ │ All metrics show change vs comparison point.                │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
└─┴────────────────────────────────────────────────────────────────────┘
```

**Components:**

- KPI tiles, horizon-decomposed position chart, top exposures table, time-as-of comparison selector.

**Interactions:**

- All tiles drill down to detail views
- Time-as-of selector enables bi-temporal comparison (vs yesterday, last week, month-end, etc.)
- Right-click row → run scenario, export, view audit history

---

### 6.10 Compliance > Audit Explorer

**Primary persona:** Inga (Compliance Officer)

**Purpose:** Bi-temporal time-travel queries to reproduce "what did we know at time T about delivery period D"

**Layout:**

```
┌──────────────────────────────────────────────────────────────────────┐
│ Tenant ▾                                                             │
├─┬────────────────────────────────────────────────────────────────────┤
│N│ Audit Explorer                                                     │
│a│                                                                    │
│v│ ┌─ Query builder ────────────────────────────────────────────┐    │
│ │ │ Subject: ◉ Delivery period  ○ Trade  ○ Series  ○ Tenant    │    │
│r│ │ Identifier: 2026-07-15 14:00 DE-LU-001 ▾                    │    │
│a│ │                                                              │    │
│i│ │ Bi-temporal axes:                                            │    │
│l│ │   Valid time:        2026-07-15 14:00 (the delivery period) │    │
│ │ │   Transaction time:  As of 2026-07-20 09:00 ▾  (system view) │    │
│ │ │                                                              │    │
│ │ │ [Run query]                                                 │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Results: state at txn time 2026-07-20 09:00 ─────────────┐    │
│ │ │ Volume series state for delivery period 2026-07-15 14:00:   │    │
│ │ │                                                              │    │
│ │ │ CONTRACTUAL (immutable):                                    │    │
│ │ │   TR-2026-1234: 25.0 MW (created 2026-04-15)                │    │
│ │ │   TR-2026-1287: -50.0 MW (created 2026-05-22)               │    │
│ │ │   ...                                                        │    │
│ │ │                                                              │    │
│ │ │ PLAN (as of txn time):                                      │    │
│ │ │   TR-2026-1234: 22.5 MW (derived 2026-07-13 02:00)          │    │
│ │ │   TR-2026-1287: -50.0 MW                                     │    │
│ │ │                                                              │    │
│ │ │ ACTUAL (as of txn time):                                    │    │
│ │ │   V1: 24.8 MW (recorded 2026-07-16 02:30)                   │    │
│ │ │   ⚠ V2 was created on 2026-08-01, AFTER your txn time      │    │
│ │ │     → V2 is NOT visible in this query                       │    │
│ │ │                                                              │    │
│ │ │ Net BAV at this txn time: -25.2 MW                          │    │
│ │ │ Resolution chain: ACTUAL V1 → PLAN → CONTRACTUAL            │    │
│ │ │                                                              │    │
│ │ │ [Export as XML for REMIT] [Generate audit report]           │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
└─┴────────────────────────────────────────────────────────────────────┘
```

**Components:**

- **Query builder:** Specify the subject (interval, trade, series), the valid time, and the transaction time. The bi-temporal interface is explicit about both axes — most data tools hide one or the other.
- **Results panel:** Shows the system state as it existed at the specified transaction time. Critically, anything created after that transaction time is **excluded** — this is the whole point of bi-temporal querying. The UI calls this out explicitly so the auditor knows nothing is hidden.

**Interactions:**

- Run query → shows results
- Each item in results is clickable to drill in (with the same transaction time scope)
- Export options match common regulatory formats (REMIT XML, CSV with bi-temporal metadata, PDF audit report)

**Critical requirement:** The bi-temporal scoping must be visible and verifiable. Auditors and regulators will not accept a system where they cannot prove the query was run as-of a specific point in time. The transaction time is shown prominently and re-confirmed in the export header.

---

### 6.11 Admin > Materialization Queue

**Primary persona:** Theo (Platform Administrator)

**Purpose:** Operational visibility into the chunk processing pipeline, DLQ inspection, retry management.

**Layout:**

```
┌──────────────────────────────────────────────────────────────────────┐
│ All tenants ▾                                                        │
├─┬────────────────────────────────────────────────────────────────────┤
│N│ Materialization Queue                                              │
│a│                                                                    │
│v│ ┌─ Pipeline health ──────────────────────────────────────────┐    │
│ │ │ ┌────────┐    ┌────────┐    ┌────────┐    ┌────────┐         │    │
│r│ │ │ Pending│ →  │ In Prog│ →  │  Done  │    │  DLQ   │         │    │
│a│ │ │  4,012 │    │   180  │    │ 1.2M+  │    │   ⚠ 2  │         │    │
│i│ │ │        │    │        │    │ today  │    │        │         │    │
│l│ │ └────────┘    └────────┘    └────────┘    └────────┘         │    │
│ │ │                                                              │    │
│ │ │ Throughput: 124 chunks/min   Avg duration: 220ms             │    │
│ │ │ Recent errors: 2 (down from 12 yesterday)                   │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ DLQ inspection (2 entries) ───────────────────────────────┐    │
│ │ │ Tenant       │ Trade       │ Chunk      │ Error       │Retry│    │
│ │ │ tenant-A     │ TR-2026-9001│ 2026-12    │ Timeout     │  3  │    │
│ │ │ tenant-B     │ TR-2026-9002│ 2027-01    │ DB conflict │  3  │    │
│ │ │                                                              │    │
│ │ │ [Replay selected] [Mark resolved] [View error detail]      │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
│ │                                                                    │
│ │ ┌─ Live processing (last 30 sec) ────────────────────────────┐    │
│ │ │ [Sparkline chart of chunks/sec]                             │    │
│ │ └──────────────────────────────────────────────────────────────┘   │
└─┴────────────────────────────────────────────────────────────────────┘
```

**Components:**

- **Pipeline health:** Pending → In Progress → Done flow with current counts. DLQ count is highlighted when non-zero.
- **DLQ inspection:** List of failed chunks with error context, retry count, and operator actions.
- **Live throughput chart:** Sparkline of chunks-per-second over the last 30 seconds.

**Interactions:**

- Click DLQ entry → opens detail with full error trace
- Replay selected → confirms and resubmits to chunk processor
- Mark resolved → removes from DLQ (e.g., if resolved manually outside the system)

---

## 7. Cross-cutting Patterns

### 7.1 The Bucket Disclosure Pattern

Every screen that shows volume or scalar data needs to handle the question: "show me the BAV value or show me the buckets?" The answer should not be hidden in a settings menu — it should be a visible, persistent control on every relevant screen.

The pattern:

- **Default to BAV** in most contexts (Position Board, Position Explorer, Risk dashboards)
- **Default to all-buckets** in reconciliation contexts (Actuals Reconciliation, Trade Detail)
- **Provide a one-click toggle** at the top of any data panel that switches between modes
- **In compact tabular contexts**, show BAV as the primary value and the source bucket as a small colored chip in the cell ("48.3 [A]" where [A] is a green chip for ACTUAL)

### 7.2 The Cascade Tier Indicator

Every interval row or chart point can be in one of three cascade tiers. The tier is shown via:

- A colored left border on table rows (cyan for NEAR_TERM, teal for MEDIUM_TERM, slate for LONG_TERM)
- A subtle background tint on chart segments
- A "tier" column in detail tables that uses the same colors

This is enough to convey tier without dedicating header rows or section breaks. Users learn the colors quickly and identify them at a glance.

### 7.3 The Immutability Affordance

CONTRACTUAL values are always shown with:

- A small lock icon (🔒) immediately after the numeric value, in a muted color
- A slightly different cell background (very subtle — 2% opacity blue tint)
- No edit affordance on hover (no pencil icon, no double-click-to-edit)

When a user attempts something forbidden (e.g., right-clicks a CONTRACTUAL cell looking for edit), the context menu shows the options with edit-related items disabled and a tooltip "CONTRACTUAL volumes are immutable per audit policy."

### 7.4 The Time-as-of Selector

The top bar's "Date" selector serves as a global as-of context. By default it shows "Now" with live data. Users can switch it to:

- A specific date (e.g., "2026-07-01")
- A specific date+time (e.g., "2026-07-01 14:30")
- A relative anchor ("Yesterday close", "Last Friday close", "Month-end")

When the selector is anything other than "Now," a subtle banner appears below the top bar: "Viewing system state as of 2026-07-15 09:00. Data may differ from current state." All data on the page reflects the bi-temporal as-of view, not just the most recent.

### 7.5 The Notification Center

Async events surface in a notification center accessible via the bell icon in the top bar:

- Materialization complete (per series)
- Chunk failures (with link to DLQ)
- Reconciliation alerts (variances exceeded threshold)
- Correction events (TSO submitted revised metering)
- System events (compression policy ran, retention purge completed)

Notifications are filterable by type and persist for 7 days. Users can mark items read/unread, archive, or pin.

### 7.6 Drill-down Consistency

Every drill-down follows the pattern: **higher-level summary → middle-level grouping → individual records**. Users can always click "back" or use a breadcrumb to return. The drill chain is preserved as URL state so links can be shared and reproduced.

Examples:
- Position Board card → Position Explorer for that delivery point
- Position Explorer net summary → Contributing Trades list
- Contributing trade → Trade Detail
- Audit Explorer result → individual interval detail

---

## 8. Mobile and Responsive Considerations

This is a desktop-first product. The full surface is designed for desktop-class screens (≥1280px). However:

**Tablet (768-1279px):** The left navigation rail collapses to icons-only by default. Screens reflow from 12-column to 8-column layout. Tables remain horizontally scrollable rather than reducing column count (information loss is worse than scroll). All interactions remain available.

**Mobile (<768px):** The product is **read-only** for mobile users. The use cases are:
- A trader on the train checking their portfolio
- An on-call engineer responding to a DLQ alert
- A risk officer reviewing yesterday's close

Mobile views are simplified, single-column layouts. The Live Position Board condenses to a list view of delivery points with their summary metrics. The Position Explorer is accessible by tapping into a delivery point. Trade Detail shows a vertically-stacked layout with chart at top, table below.

Mobile editing is disabled. Plan adjustments, corrections, and admin actions require desktop. This is an intentional restriction; bulk data manipulation on mobile is error-prone for a regulated product.

---

## 9. Accessibility

The product targets WCAG 2.1 AA compliance.

- **Color contrast:** All text-on-background combinations meet 4.5:1 (normal text) or 3:1 (large text). Bucket colors meet contrast requirements when used as fills with appropriate text color overlays.
- **Color is never the sole signal:** Bucket data uses color + label (CONTRACTUAL/PLAN/ACTUAL appears in headers and tooltips, not just as colored chips). Status uses icons + color + text.
- **Keyboard navigation:** All interactive elements reachable via tab. Focus indicators are visible (2px ring, high-contrast). Power users have keyboard shortcuts for all common actions.
- **Screen readers:** Semantic HTML structure (proper heading hierarchy, table headers with scope attributes, ARIA labels on icon-only buttons). Charts have textual alternatives accessible via "View as table" toggle.
- **Reduced motion:** Animations respect `prefers-reduced-motion`. Chart transitions become instant when motion is reduced. Pulse animations (for optimistic UI feedback) are subtler.
- **Density toggle (Section 5.5)** also serves as accessibility — the comfortable density mode produces larger text and more spacing, helping users with visual impairment.

---

## 10. Authentication, Authorization, and Multi-tenant UI

Authentication is handled by Keycloak (per platform standard). Users authenticate with email/password + MFA for production tenants.

The volume series UI inherits the platform's tenant model:
- **Single-tenant users** (most users) see only their tenant's data; the tenant selector in the top bar is hidden
- **Cross-tenant users** (platform admins, support staff) see a tenant selector; impersonation is logged in audit
- **Roles** map to navigation visibility: a Trader role sees Trading and Positions; a Risk role adds Risk; a Compliance role adds Compliance; an Admin role adds Admin. Roles are additive.

Sensitive operations (e.g., manual correction submission, retention policy override) require step-up authentication — a re-prompt for password even if the session is active. This is inherited platform behavior and shows up in the UI as a confirmation modal with a password field.

---

## 11. Performance Targets for the UI

UI performance targets are derived from the data architecture's SLA targets but are tighter — they include rendering time on top of data retrieval.

| Action | Target (p95) | Notes |
|---|---|---|
| Initial page load (cold) | < 2 sec | Includes auth check, app shell, first data fetch |
| Navigation between pages (warm) | < 300 ms | App shell stays; only the content area re-renders |
| Live Position Board update | < 100 ms | WebSocket push + render |
| Trade Detail load | < 500 ms | Header + chart for first month, lazy-load rest |
| Position Explorer query | < 800 ms | Most queries hit BAV cache or continuous aggregate |
| Audit Explorer bi-temporal query | < 3 sec | Acknowledges this is a heavy query; loading state is acceptable |
| Heatmap (24h × 12 dp) render | < 200 ms | After data arrives |
| Chart re-render on zoom/filter | < 150 ms | Client-side aggregation |
| Search autocomplete | < 100 ms | Local index for common items, server fallback |

These targets assume desktop-class hardware (modern laptop, 16GB+ RAM, decent network). The product is not optimized for low-end devices.

---

## 12. Open UX Questions

These need product/design input before final design lock:

1. **Bucket color choice:** The proposed palette (deep blue / amber / forest green) is functional but should be validated with users (Pia, Markus, Anke) for cultural and habitual associations. Some markets have established conventions (e.g., red for sells in some trading desks) that may conflict.

2. **Density default:** Is "compact" or "standard" the right default for new users? Compact maximizes information; standard is gentler. A tutorial that demonstrates the toggle on first login could let users self-select.

3. **Time zone display:** All delivery times are in the delivery timezone (typically Europe/Berlin). Should the UI also show user's local time, or the delivery timezone exclusively? German traders are unambiguous; users in other time zones may want both.

4. **Heatmap as primary surface vs auxiliary:** The Reconciliation screen relies heavily on a heatmap. Is this the right primary visualization for that workflow, or should it be a sortable/filterable list with status icons? User testing required.

5. **Mobile feature scope:** Read-only mobile is the recommendation, but some users may push for mobile plan adjustment for after-hours scenarios. Risk-vs-utility tradeoff needs explicit decision.

6. **Notification volume:** Active operational hours might generate dozens of notifications per hour for a busy desk. Need rules for batching, deduping, and surfacing. A notification taxonomy and prioritization framework is a separate design exercise.

7. **PPA Workbench depth:** The proposed PPA Workbench is feature-light — it could expand to include cashflow modeling, what-if PPA structuring, and benchmarking against market curves. These are substantial additions; need product roadmap clarity.

8. **Custom scalar UI:** The data model supports `custom_scalars` JSONB. Should the UI surface these as editable columns? Hidden behind a "show advanced" toggle? Tenant-configurable? Currently the spec says these are read-only display only — should be confirmed.

9. **Dark mode adoption:** Light vs dark default? Trading desks often run dark; office workers light. Per-user preference is supported, but the default matters for first impression.

10. **Audit trail granularity:** How much of the system's internal state should be visible to compliance officers? The continuous aggregate refresh logs, the chunk processor traces, the Kafka event log — too much visibility creates noise; too little creates audit gaps.
