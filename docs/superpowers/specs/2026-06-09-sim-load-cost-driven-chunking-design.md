# Sim Initial-Load — Cost-Driven, Recursive Chunking — Design Spec

> Replaces the initial-load console's **blind up-front time-slicing** of large tables with an
> **analyze-first, operator-triggered, cost-driven** chunk action. The operator analyzes the whole-table
> SQL (plan + `query_cost` + row estimate), then asks to split it into chunks sized by a **target cost
> per chunk**. Chunking is **recursive** — any analyzed batch (whole-table or an existing chunk) can be
> chunked further. Boundaries come from the **actual row distribution** of the indexed
> `last_updated_tx_stamp`, computed with a single-pass keyset walk that does not overwhelm the source DB.
>
> Status: **design pending approval 2026-06-09**.
> Supersedes the FULL-mode auto-chunk behavior in `SimLoadConsole.generateFullChunks` /
> `computeFullChunks`.
> Related: `2026-06-09-sim-initial-load-console-design.md` (the console this extends),
> `2026-06-08-watermark-tx-stamp-switch.md` (the tx-stamp watermark + the 6 tables that lack it).

## 1. Problem

`SimLoadConsole.generateFullChunks` currently chunks a large FULL-mode table at **generation time**,
before any analysis: it counts rows, computes `numChunks = ceil(count / target)`, then
`computeFullChunks` divides the `[min, max]` span of `last_updated_tx_stamp` into **equal-width *time*
intervals**. The number of chunks is row-based but the boundaries are time-based, so the two only agree
when rows are uniformly distributed over time.

For a **bulk-loaded** table (e.g. `product_facility`), most rows share a near-identical
`last_updated_tx_stamp`. Equal time-slicing then collapses almost every row into **one fat chunk** while
the rest come out empty — N batch records, useless chunking. This is the reported symptom.

Two further problems with the current model:
- Chunking happens **before** the operator sees the cost, so it can't be informed by the analysis the
  console exists to produce.
- There is **no way to chunk further** once a slice is found still too expensive.

## 2. Requirements

- **Analyze the whole table first.** `EXPLAIN` + `query_cost` + guarded `COUNT` run on the entire SQL
  (`SELECT … FROM <t>`, no window) before any chunking decision.
- **Operator-triggered chunking driven by cost.** After analysis the operator chooses a **target cost
  per chunk**; the system derives the row target from the measured cost and splits accordingly.
- **Recursive.** Any `ANALYZED` batch — the whole-table batch *or* an already-chunked `RANGE` child —
  can be chunked again, as deep as needed.
- **Balanced chunks from real distribution.** Each chunk holds ≈ the derived row target regardless of
  how `last_updated_tx_stamp` is distributed.
- **Don't overwhelm the source DB.** Boundary computation scans the indexed column **once** total; runs
  stay one-at-a-time (unchanged).
- **Analysis NEVER executes the query.** The Analyze step issues only `EXPLAIN` and
  `EXPLAIN FORMAT=JSON` — never the data `SELECT`, never `COUNT(*)`, never `EXPLAIN ANALYZE`. EXPLAIN
  asks the optimizer for the plan/cost (a few index-dive page reads) and returns; it does not run the
  query or touch the matched rows. Cost sizing uses the EXPLAIN **row estimate**, not an exact count.
  (Only the Chunk step runs lightweight *indexed* boundary probes; only the Run step executes the data
  `SELECT` + `MERGE`, and only after approval.)

### Non-goals
- Steady-state delta sync (unchanged).
- Automatic, unattended chunking — chunking is always an explicit operator action.
- Chunking tables that have no `last_updated_tx_stamp` watermark (the 6 routing config tables; small —
  run whole).

## 3. Workflow

```
Generate ─▶ ONE whole-table batch        (mode=FULL, sliceFrom/To=null, predicate=none)
Analyze  ─▶ EXPLAIN + query_cost + guarded COUNT on the entire SQL   → ANALYZED
Chunk    ─▶ operator enters target cost/chunk                         → N RANGE children (PLANNED)
            parent batch                                              → SKIPPED
  (each child:)
Analyze  ─▶ … per-child plan + cost                                  → ANALYZED
Chunk    ─▶ still too costly? split this child further (recursive)    → grandchildren; child → SKIPPED
Approve & Run each leaf child                                        → APPROVED → RUNNING → DONE
```

The up-front auto-chunk in `generateFullChunks` is **removed**: generating a FULL table now yields a
single whole-table batch. `DAY` / `RANGE` generation with operator-supplied `from`/`to` is unchanged.

## 4. Cost-driven sizing

The driving input is an **absolute target-cost ceiling per chunk** — "no chunk should cost more than
`targetChunkCost`" — *not* a ratio of the whole-table cost. The DB cares about the absolute cost of each
query it runs, so an absolute ceiling makes small tables stay one batch and only splits genuinely
expensive tables, as much as their cost demands. (A relative `cost / N` default was rejected: it always
yields ~N chunks regardless of table size — count-driven in disguise.)

Analysis persists `query_cost` (whole-table optimizer cost) and `estimatedRows` (EXPLAIN estimate) — both
from `EXPLAIN` (the guarded `COUNT(*)` is **removed**; Analyze is EXPLAIN-only per §2/§7, so `exactCount`
is left null). Because optimizer cost scales ~linearly with rows scanned:

```
if query_cost <= targetChunkCost:  → single chunk, no split (already under the ceiling)
cost_per_row ≈ query_cost / estimatedRows
target_rows  ≈ targetChunkCost / cost_per_row
             = estimatedRows × (targetChunkCost / query_cost)
numChunks    ≈ query_cost / targetChunkCost               (informational / cap check)
```

- **`targetChunkCost` is the operator's primary input** on the Chunk action, pre-filled from
  `simulation.load.targetChunkCost` (**default 5000**, configurable — likely re-tuned once we see a real
  large table's cost). The derived `target_rows` is what the keyset walk actually uses.
- **Already under the ceiling:** if the analyzed `query_cost <= targetChunkCost` (e.g. `product_facility`
  at 1312 vs a 5000 ceiling), `chunkBatch` makes **no children** and reports "whole-table cost already
  under target — run as-is." The whole-table batch stays runnable (not SKIPPED).
- **Fallbacks:** if `query_cost` is null (the JSON-cost probe is best-effort) or `estimatedRows` is 0,
  cost→rows conversion is impossible; the action falls back to a **target rows-per-chunk** input
  (pre-filled from `simulation.load.fullChunkRows`) and says why.
- The cost→rows estimate only sets the *target*; the keyset walk is self-terminating, so an inaccurate
  optimizer estimate makes chunks somewhat larger/smaller than intended but never produces gaps,
  overlaps, or a wrong total. Cost per chunk is a target, not a guarantee.

**Worked example (`product_facility`, measured 2026-06-09):** whole-table `query_cost` = 1312.43,
`estimatedRows` = 12,775 (exact 12,847). At the default ceiling 5000, 1312 ≤ 5000 → **one batch, no
chunking** (correct — it's a small table). If the ceiling were lowered to 500: `target_rows ≈
500 / (1312.43/12775) ≈ 4,867` → ~3 chunks of ~4,300 rows, each costing ≈ 440.

## 5. Boundary algorithm — windowed keyset walk

New method on `SimLoadConsole`, **replacing** `computeFullChunks` (equal-time-division is deleted). It
takes the batch's existing window `[from, to)` (null/null for a whole-table FULL batch) and a
`target_rows`, and walks the indexed `last_updated_tx_stamp` once:

```
lo  = from ?: SELECT MIN(last_updated_tx_stamp) FROM <t> [WHERE within window]
hiExclusive = (to ?: SELECT MAX(...)+1s)
prev = lo
loop:
  next = SELECT last_updated_tx_stamp FROM <t>
         WHERE last_updated_tx_stamp >= :prev [AND < :to]
         ORDER BY last_updated_tx_stamp
         LIMIT 1 OFFSET :target_rows
  if next == null:                 // < target rows remain → final chunk
      emit [prev, hiExclusive); break
  if next == prev:                 // one tx-stamp holds ≥ target rows (bulk-load tie)
      next = SELECT MIN(last_updated_tx_stamp)
             WHERE last_updated_tx_stamp > :prev [AND < :to]
      if next == null: emit [prev, hiExclusive); break
  emit [prev, next); prev = next
```

- **Single-pass cost:** each probe scans only `target_rows` indexed rows starting at the previous
  boundary, so the whole walk scans the indexed column **once** (O(rows)) — *not* O(rows × chunks) as a
  deep `OFFSET` from the start would. This is the "don't overwhelm the source DB" guarantee.
- **Windowed:** for a `RANGE` child, every probe is bounded by `< to` and seeded by `from`, so chunking
  a child only ever subdivides that child's window.
- **Contiguous & total:** emitted ranges are `[prev, next)` back-to-back, last one ending at the window's
  exclusive upper bound — no gaps, no overlaps, full coverage.
- **Tie caveat** (accepted per design): a single `last_updated_tx_stamp` value holding more than
  `target_rows` rows becomes one larger-than-target chunk; it cannot be split finer without abandoning
  the watermark column. This is the irreducible floor of tx-stamp chunking.

Each emitted range becomes a child via the existing `createPlanned(tbl, "RANGE", from, to)`; its run-time
predicate comes from the existing `SimLoadBatchQuery.slice` RANGE form
(`last_updated_tx_stamp >= ? AND < ?`), so children stay index range scans and remain aligned to the
delta-sync watermark.

## 6. Service & screen

- **New service `chunk#LoadBatch`** (`SimLoadConsoleServices.xml`)
  - in: `loadBatchId` (required); `targetChunkCost` (Number, default from `simulation.load.targetChunkCost`);
    `targetRows` (Integer, fallback)
  - out: `loadBatchIdList`
  - delegates to `SimLoadConsole.chunkBatch(loadBatchId, targetChunkCost, targetRows)`.
  - **Validates:** batch exists; `statusId == ANALYZED`; `mode in (FULL, RANGE)`. If the analyzed
    `query_cost <= targetChunkCost` → makes **no children**, leaves the batch runnable, returns empty list
    with the "already under target" message (§4). Otherwise computes `target_rows` from cost (§4) or uses
    the rows fallback; keyset-walks the window (§5); creates `RANGE` children (dedup via `hasOpenOrDone`);
    sets the parent `statusId = SKIPPED`. Returns child ids.
  - **Refuses / reports** when the table has no `last_updated_tx_stamp` (MIN throws) → no children,
    message "no tx-stamp watermark; run as a single whole-table load."
- **`generate#LoadBatches`** — FULL path now creates a **single whole-table batch** (drop the auto-chunk
  call). DAY/RANGE unchanged.
- **Screen `BrokeringSimInitialLoad.xml`** — add a **Chunk** transition and a row action shown when
  `statusId == 'ANALYZED'` (both FULL and RANGE), with a `targetChunkCost` input pre-filled from
  `simulation.load.targetChunkCost` (and a `targetRows` fallback field). Existing Analyze / Approve&Run /
  Skip / Abort actions unchanged.

## 7. Guards

- **Analysis is EXPLAIN-only** — `analyzeBatch` issues only `EXPLAIN` + `EXPLAIN FORMAT=JSON`; the
  guarded `COUNT(*)` is removed. No data `SELECT`, no `COUNT`, no `EXPLAIN ANALYZE` ever runs during
  analysis. This is a hard invariant (see §2) — the cost figures come from the optimizer, not from
  executing anything.
- **Prod source is opened READ-ONLY.** Every `prod-source` connection is acquired through the single
  `prodConn()` chokepoint, which sets `Connection.setReadOnly(true)`. Whatever runs against prod —
  an EXPLAIN, a chunk boundary probe, or the run-time fetch — is on a read-only connection, so writing
  to production is **structurally impossible at the JDBC layer, independent of the SQL text**. The
  run-time fetch additionally uses a forward-only, read-only cursor. (Defense in depth, outside this
  code: the `prod-source` DB account should hold SELECT-only grants.)
- **All writes target H2 only.** The MERGE writes exclusively to the `simulation` (H2 `LIVE.*`)
  datasource; the `prod-source` connection is never written.
- **Table names are manifest-validated.** Before any table name is interpolated into a prod SQL string,
  `assertKnownTable` rejects anything not in `SourceTableManifest` — no arbitrary or typo'd identifier
  can reach a production query.
- **Max-chunk backstop** — `simulation.load.maxChunks` (default `2000`): if the derived target would
  imply more chunks than the cap (checked against `estimatedRows / target_rows`), `chunkBatch` refuses
  and tells the operator to raise the target. This is a fat-finger backstop only; it never *drives* the
  chunk count (rejected as a sizing basis — the count follows the cost/row target, §4).
- **One `RUNNING` at a time** — unchanged in `runBatch`.
- **Idempotent re-chunk** — `hasOpenOrDone` skips child slices that already exist non-SKIPPED.

## 8. Config (system properties)

- `simulation.load.targetChunkCost` (default `5000`) — **the driving input**: absolute optimizer-cost
  ceiling per chunk. Pre-fills the Chunk action; whole-table cost at or under it → no split. Expected to
  be re-tuned once a real large table's cost is observed.
- `simulation.load.fullChunkRows` (default `100000`) — now only the **rows-fallback** pre-fill when cost
  is unavailable (no longer the generate-time auto-chunk target).
- `simulation.load.maxChunks` (default `2000`) — new fat-finger backstop (§7).
- `simulation.load.countThresholdRows` (default `500000`) — unchanged (analyze COUNT guard).

## 9. Testing

- **Unit (Spock, mocked conn)** for the windowed keyset walk:
  - uniform distribution → ≈ even, ≈ `target_rows`-sized chunks;
  - heavy skew (90% of rows at the max tx-stamp) → correct contiguous boundaries, the dense tail in its
    own chunk(s), no empty-chunk collapse;
  - single-value tie with > `target_rows` rows at one stamp → progresses past it, that chunk holds the
    whole value, coverage still total;
  - window smaller than `target_rows` → exactly one chunk;
  - bounded window (`RANGE` child) → boundaries stay inside `[from, to)`.
  - cost→rows derivation: given `query_cost`/`estimatedRows`, asserts the derived `target_rows`; null
    cost → rows fallback; `query_cost <= targetChunkCost` → no children, parent left runnable
    (the `product_facility` 1312-vs-5000 case).
- **Replace** the `SimLoadConsoleSpec` tests that assert the deleted `computeFullChunks` even-division.
- **Integration (`product_facility`, real `prod-source`):** generate whole-table → analyze (cost shown)
  → chunk by a target cost → assert N contiguous children covering the table, each analyzing as an index
  range scan; re-chunk one child and assert it subdivides only that child's window.

## 10. Out of scope / follow-ups

- Switching the watermark/chunk column per-table for the 6 tx-stamp-less tables (they're small; run
  whole). Tracked separately in `2026-06-08-watermark-tx-stamp-switch.md`.
- Any change to steady-state delta sync.
