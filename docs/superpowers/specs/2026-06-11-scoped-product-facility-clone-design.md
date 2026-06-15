# Scoped `product_facility` clone for brokering simulation

Date: 2026-06-11
Component: `runtime/component/sim-routing`
Status: Design — approved, pending spec review

## Problem

Each brokering-simulation job provisions a private `SIM_<jobId>` schema by CTAS-cloning 4
"override target" tables from the `LIVE` mirror, then replaying their PK + indexes
(`SimSchemaProvisioner.cloneLiveToSim` / `resetSimToBaseline`). The dominant cost is
`PRODUCT_FACILITY` at **~5.7M rows**: the CTAS itself is cheap (~15s) but rebuilding the
index on 5.7M rows — read out of a bloated MVStore — takes minutes. Profiling (`jstack`)
showed the sim thread parked in `org.h2.mvstore.db.MVTable.rebuildIndexBlockMerge` and in a
large `GROUP BY` aggregation, both dominated by `FileStore.readPage`. A non-empty group run
takes ~10 min, virtually all of it in provisioning/index work, not brokering.

## Goal

Clone only the `PRODUCT_FACILITY` rows the brokering pipeline can actually read for a given
run, shrinking the cloned table from ~5.7M rows to the products in the run's order scope
(plus their style/variant siblings) — a ~50–200× reduction — so the per-job index rebuild
drops from minutes to seconds. **Brokering results must be identical** to the full-clone path.

### Non-goals
- DB compaction / reclaiming the 9.75GB bloat (separate work item).
- Scoping the other 3 override tables (`FACILITY`, `FACILITY_ORDER_COUNT`,
  `FACILITY_GROUP_MEMBER`) — they are small (183 / 163K / 3.6K rows) and stay full-cloned.
- Changing `replayIndexes` — it is unchanged; it simply operates on a smaller table.

## The scoped predicate (product-only, all facilities)

Derived from `LIVE` for the run's `productStoreId`. The "needed" product set:

```
P0      = DISTINCT oi.product_id of the store's brokerable approved order items
STYLES  = product_assoc.product_id   where product_id_to ∈ P0   (PRODUCT_VARIANT, active)
SIBLINGS= product_assoc.product_id_to where product_id   ∈ STYLES (PRODUCT_VARIANT, active)
NEEDED  = P0 ∪ STYLES ∪ SIBLINGS
```

P0 query:
```sql
SELECT DISTINCT oi.product_id
FROM LIVE.order_item oi
JOIN LIVE.order_header oh ON oh.order_id = oi.order_id
WHERE oh.product_store_id = ?
  AND oh.order_type_id = 'SALES_ORDER'
  AND oh.status_id     = 'ORDER_APPROVED'
  AND oi.status_id     = 'ITEM_APPROVED'
  AND oi.product_id IS NOT NULL
```
STYLES / SIBLINGS use `LIVE.product_assoc` with `product_assoc_type_id='PRODUCT_VARIANT'`
and `(thru_date IS NULL OR thru_date >= CURRENT_TIMESTAMP)`.

Clone (H2 CTE; same shape for the `resetSimToBaseline` INSERT):
```sql
CREATE TABLE SIM_<jobId>.PRODUCT_FACILITY AS
SELECT * FROM LIVE.PRODUCT_FACILITY
WHERE PRODUCT_ID IN ( <NEEDED> )
```

Facilities are **not** filtered (per decision) — keep every facility row for an in-scope product.

### Why product-only is correct
`InventorySourceSelector.sql.ftl` joins `product_facility` exclusively on
`product_id = ordered item's product` (aliases `pf`, `pf1`, `pf2`, `PFI`) and, in the
broken-style/assortment branch, on the item's **style siblings** via `product_assoc`
(alias `pf3`) — which is why STYLES+SIBLINGS are included. No brokering path reads
`product_facility` for a product outside NEEDED. Facility filtering happens downstream via
`product_store_facility`; rows at irrelevant facilities are never selected, so keeping them
is harmless (just slightly larger).

### Why it's safe for data-change overrides
The sim's overrides mutate `product_facility`, but brokering only *reads* rows for ordered
products (+variants). An override on an out-of-scope product is a no-op in **both** the full
and scoped clone, so results are unaffected. The parity test is the guard against any gap.

## Code touchpoints

- **`SimulationExecutor`**: resolve `productStoreId` **once, before cloning** (hoist the
  group-record fallback currently in `runGroupRoundOnClonedH2` ~line 185 up to the group-run
  entry — `executeGroupRunVariationH2` / `runOneGroupRoundH2`) and pass it into
  `cloneLiveToSim` / `resetSimToBaseline`. Covers baseline, variants, and single-shot.
- **`SimSchemaProvisioner`**: `cloneLiveToSim(conn, jobId, productStoreId, scoped)` and
  `resetSimToBaseline(conn, jobId, productStoreId, scoped)` apply the scoped predicate to
  `PRODUCT_FACILITY` only; other override tables unchanged.
- **Flag**: `simulation.scopedClone` system property (default `true`); `false` → current
  full-clone behaviour verbatim.

## Feature flag & rollback

`simulation.scopedClone` defaults to `true`. Setting it `false` (in `MoquiConf` /
`MoquiDevConf` / `-D`) restores the full clone with no code change — instant rollback.

## Parity test

Fixture-based test under `src/test/...` using the isolated `MoquiSimLoadTestConf` (NOT the
shared `MoquiDevConf`, which collides with a running dev server). It runs one group fixture
both ways (`scopedClone=true` and `false`) and asserts **identical brokering output**:
per-order `finalReason`, `finalAssignments`, and brokered/queued/attempted counts. A
fixture that exercises the broken-style/assortment branch is included so the variant
expansion is covered.

## Expected impact

`PRODUCT_FACILITY` clone shrinks from ~5.7M rows to the store's order-scope products
(+variants) — empirically a small fraction. Index rebuild drops from minutes to seconds;
non-empty group run wall-clock should fall from ~10 min toward ~1–2 min. (Independent of the
DB-bloat issue, which compounds page-read cost and is tracked separately.)

## Risks / open questions

- **Scope completeness**: if any brokering path reads `product_facility` for a product not in
  NEEDED, scoped runs would diverge. Mitigated by the parity test; the flag allows instant
  fallback. New brokering features that read `product_facility` differently must keep NEEDED a
  superset.
- **CTE support**: relies on H2 `WITH ... CREATE TABLE AS SELECT`; if awkward, fall back to a
  transient helper table of NEEDED product ids per job.
