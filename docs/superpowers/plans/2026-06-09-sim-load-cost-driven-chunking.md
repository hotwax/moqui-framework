# Cost-Driven Recursive Chunking for Sim Initial-Load — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace SimInitialLoad's blind generate-time time-slicing with an analyze-first, operator-triggered, **cost-driven** chunk action that splits any analyzed batch into balanced `RANGE` chunks using a single-pass keyset walk over the indexed `last_updated_tx_stamp`.

**Architecture:** Generate now creates one whole-table `FULL` batch (no chunking). After Analyze populates `query_cost` + row estimate, a new `chunk#LoadBatch` service derives a row target from a target optimizer-cost ceiling and keyset-walks the indexed watermark to emit contiguous `RANGE` children (parent → `SKIPPED`). The boundary math is a **pure** function with an injectable probe (unit-testable, mirroring the existing `computeFullChunks` pattern); the DB-touching probe + orchestration are integration-tested. Chunking is recursive — children are themselves `ANALYZED`-then-`chunk`-able, and the boundary walk respects an existing `[from,to)` window.

**Tech Stack:** Groovy (`@CompileStatic`) in `sim-routing` Moqui component; Spock 2.1 (groovy-3.0) on JUnit Platform; Moqui XML services + screen. Source DB is MySQL via the `prod-source` datasource.

**Spec:** `docs/superpowers/specs/2026-06-09-sim-load-cost-driven-chunking-design.md`

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsole.groovy` | engine: config accessors, cost→rows math, pure keyset chunker, `chunkBatch` orchestration, simplified FULL generate | modify (remove `generateFullChunks`/`computeFullChunks`; add `targetChunkCost`/`maxChunks`/`deriveTargetRows`/`BoundaryProbe`/`computeKeysetChunks`/`parseCost`/`chunkBatch`) |
| `runtime/component/sim-routing/service/co/hotwax/order/routing/simulation/SimLoadConsoleServices.xml` | thin service wrappers | modify (add `chunk#LoadBatch`) |
| `runtime/component/sim-routing/screen/SimAdmin/BrokeringSimInitialLoad.xml` | operator UI | modify (add `chunk` transition, a row "Chunk" link, and a "Chunk a batch" override form) |
| `runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsoleSpec.groovy` | unit tests for pure logic | modify (delete the 2 `computeFullChunks` tests; add `deriveTargetRows`, `computeKeysetChunks`, and FULL-generate tests) |
| `runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsoleIntegrationSpec.groovy` | end-to-end DB flow | modify (replace the "FULL auto-chunks" test with generate→analyze→chunk-by-cost) |

**Test commands (run from repo root `/Users/aditipatel/sandbox/brokering-simulation`):**
- Unit: `./gradlew :runtime:component:sim-routing:test --tests "co.hotwax.order.routing.simulation.sync.SimLoadConsoleSpec"`
- Integration: `./gradlew :runtime:component:sim-routing:integrationTest --tests "co.hotwax.order.routing.simulation.sync.SimLoadConsoleIntegrationSpec"`

**Environment caveats (from prior sessions — heed these):**
- **Do NOT run `integrationTest` while a dev server is running** — they share `MoquiDevConf` / the H2 file lock and will collide. Stop the dev server first.
- If a unit/integration run throws `NoSuchMethodError` for a method you just added, a **stale incremental-compiled class** got packaged into the component lib jar. Fix with a component clean: `./gradlew :runtime:component:sim-routing:clean` then re-run.

---

## Task 1: Config accessors + cost→rows derivation (pure)

**Files:**
- Modify: `runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsole.groovy`
- Test: `runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsoleSpec.groovy`

- [ ] **Step 1: Write the failing tests**

Add these methods to `SimLoadConsoleSpec` (after the existing `shouldRunCount` test, before `summarize`):

```groovy
    def "deriveTargetRows converts a cost ceiling into a rows-per-chunk target"() {
        expect:
        SimLoadConsole.deriveTargetRows(queryCost, estRows, ceiling) == expected
        where:
        queryCost | estRows | ceiling || expected
        1312.43d  | 12775L  | 500.0d  || 4867L    // 500 / (1312.43/12775) = 4867.1 -> floor
        1000.0d   | 10000L  | 100.0d  || 1000L    // costPerRow=0.1 -> 100/0.1=1000
    }

    def "deriveTargetRows returns -1 when cost is unusable (caller falls back to a row target)"() {
        expect:
        SimLoadConsole.deriveTargetRows(qc, est, 5000.0d) == -1L
        where:
        qc       | est
        null     | 12775L   // no query_cost
        0.0d     | 12775L   // zero cost
        1312.43d | null     // no estimate
        1312.43d | 0L       // zero estimate
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :runtime:component:sim-routing:test --tests "co.hotwax.order.routing.simulation.sync.SimLoadConsoleSpec"`
Expected: FAIL — `deriveTargetRows` cannot be resolved (compile error / missing method).

- [ ] **Step 3: Implement the config accessors + derivation**

In `SimLoadConsole.groovy`, immediately after the existing `fullChunkRows()` method (line ~73), add:

```groovy
    /** Absolute optimizer-cost ceiling per chunk — the driving input for chunking. */
    double targetChunkCost() {
        return Double.parseDouble(System.getProperty("simulation.load.targetChunkCost", "5000"))
    }

    /** Fat-finger backstop: refuse to create more than this many chunks in one call. */
    int maxChunks() {
        return Integer.parseInt(System.getProperty("simulation.load.maxChunks", "2000"))
    }

    /**
     * Rows-per-chunk implied by a target cost ceiling, from the analyzed whole-table query_cost and row
     * estimate (optimizer cost scales ~linearly with rows scanned). Returns -1 when cost is unusable —
     * the caller then falls back to an explicit row target.
     */
    static long deriveTargetRows(Double queryCost, Long estimatedRows, double targetChunkCost) {
        if (queryCost == null || queryCost <= 0d || estimatedRows == null || estimatedRows <= 0L) return -1L
        double costPerRow = queryCost / (double) estimatedRows
        if (costPerRow <= 0d) return -1L
        long t = (long) Math.floor(targetChunkCost / costPerRow)
        return t < 1L ? 1L : t
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :runtime:component:sim-routing:test --tests "co.hotwax.order.routing.simulation.sync.SimLoadConsoleSpec"`
Expected: PASS (both new tests green; existing tests still pass).

- [ ] **Step 5: Commit**

```bash
git add runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsole.groovy \
        runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsoleSpec.groovy
git commit -m "feat(sim-load): cost-ceiling config + cost->rows derivation"
```

---

## Task 2: Pure keyset boundary computation (replaces computeFullChunks)

**Files:**
- Modify: `runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsole.groovy:113-126` (delete `computeFullChunks`)
- Test: `runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsoleSpec.groovy:15-34` (delete the 2 `computeFullChunks` tests)

- [ ] **Step 1: Delete the obsolete tests**

In `SimLoadConsoleSpec.groovy`, **delete** the two tests:
- `"computeFullChunks tiles [min,max] into contiguous non-overlapping intervals that cover maxTs"` (lines 15-26)
- `"computeFullChunks returns a single covering interval when numChunks is 1"` (lines 28-34)

- [ ] **Step 2: Write the failing tests for the keyset chunker**

Add to `SimLoadConsoleSpec` (a `java.sql.Timestamp` import already exists at the top). Insert near the top of the test methods (where the deleted tests were):

```groovy
    // A pure, in-memory BoundaryProbe over a sorted list of timestamps, optionally upper-bounded by `to`.
    private static SimLoadConsole.BoundaryProbe listProbe(List<Timestamp> sortedAsc, Timestamp to) {
        return [
            at: { Timestamp from, long offset ->
                def eligible = sortedAsc.findAll { it >= from && (to == null || it < to) }
                return offset < eligible.size() ? eligible[(int) offset] : null
            },
            nextDistinct: { Timestamp prev ->
                def after = sortedAsc.findAll { it > prev && (to == null || it < to) }
                return after.isEmpty() ? null : after.min()
            }
        ] as SimLoadConsole.BoundaryProbe
    }

    private static List<Timestamp> tsList(List<Integer> secs) {
        return secs.collect { new Timestamp(it * 1000L) } as List<Timestamp>
    }

    def "computeKeysetChunks makes contiguous ~target-sized chunks for a uniform distribution"() {
        given:
        def rows = tsList((0..9).toList())                 // 10 rows at 0..9s
        def lo = rows[0]; def hi = new Timestamp(rows[-1].time + 1000L)
        when:
        def chunks = SimLoadConsole.computeKeysetChunks(lo, hi, 3L, listProbe(rows, null))
        then:
        chunks.size() == 4                                  // 3+3+3+1
        chunks[0][0] == lo
        chunks[-1][1] == hi
        (0..chunks.size() - 2).every { chunks[it][1] == chunks[it + 1][0] }   // contiguous
    }

    def "computeKeysetChunks puts a dense single-timestamp tail in its own chunk without empty chunks"() {
        given:
        def rows = tsList([0, 1, 2, 100, 100, 100, 100, 100, 100, 100])   // skew: 3 early + 7 at 100
        def lo = rows[0]; def hi = new Timestamp(100 * 1000L + 1000L)
        when:
        def chunks = SimLoadConsole.computeKeysetChunks(lo, hi, 3L, listProbe(rows, null))
        then:
        chunks.size() == 2
        chunks[0][0].time == 0L && chunks[0][1].time == 100 * 1000L        // [0,100): the 3 early rows
        chunks[1][0].time == 100 * 1000L && chunks[1][1] == hi             // [100, max+1s): the 7 tied rows
    }

    def "computeKeysetChunks yields one chunk when a single tx-stamp value holds >= target rows"() {
        given:
        def rows = tsList([50, 50, 50, 50, 50])
        def lo = rows[0]; def hi = new Timestamp(50 * 1000L + 1000L)
        when:
        def chunks = SimLoadConsole.computeKeysetChunks(lo, hi, 2L, listProbe(rows, null))
        then:
        chunks.size() == 1
        chunks[0][0] == lo && chunks[0][1] == hi
    }

    def "computeKeysetChunks yields one chunk when the window has fewer than target rows"() {
        given:
        def rows = tsList([0, 1])
        def lo = rows[0]; def hi = new Timestamp(1 * 1000L + 1000L)
        when:
        def chunks = SimLoadConsole.computeKeysetChunks(lo, hi, 10L, listProbe(rows, null))
        then:
        chunks.size() == 1
        chunks[0][0] == lo && chunks[0][1] == hi
    }

    def "computeKeysetChunks stays inside an explicit [from,to) window"() {
        given:
        def rows = tsList([0, 1, 2, 3, 4, 5])
        def from = new Timestamp(0L); def to = new Timestamp(4 * 1000L)    // exclusive upper bound at 4s
        when:
        def chunks = SimLoadConsole.computeKeysetChunks(from, to, 2L, listProbe(rows, to))
        then:
        chunks.size() == 2
        chunks[0][0] == from && chunks[0][1].time == 2 * 1000L             // [0,2)
        chunks[1][0].time == 2 * 1000L && chunks[1][1] == to              // [2,4)
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :runtime:component:sim-routing:test --tests "co.hotwax.order.routing.simulation.sync.SimLoadConsoleSpec"`
Expected: FAIL — `SimLoadConsole.BoundaryProbe` / `computeKeysetChunks` do not exist (compile error).

- [ ] **Step 4: Replace `computeFullChunks` with the keyset chunker**

In `SimLoadConsole.groovy`, **delete** the `computeFullChunks` method (lines 113-126, including its doc comment on line 113) and **replace** it with:

```groovy
    /** Finds row-position boundaries on the indexed watermark; supplied by chunkBatch (DB) or a test fake. */
    interface BoundaryProbe {
        /** The last_updated_tx_stamp at OFFSET `offset` rows where tx-stamp &ge; from (and &lt; the window's
         *  upper bound, if any), ordered ascending; null when fewer than offset+1 such rows exist. */
        Timestamp at(Timestamp from, long offset)
        /** The smallest last_updated_tx_stamp strictly greater than prev (within the window); null if none. */
        Timestamp nextDistinct(Timestamp prev)
    }

    /**
     * Tile [lo, hiExclusive) into contiguous RANGE windows of ~targetRows each by walking the indexed
     * last_updated_tx_stamp once (each probe scans only targetRows rows from the previous boundary — O(rows)
     * total, NOT O(rows*chunks)). A single tx-stamp value holding &ge; targetRows rows becomes one larger
     * chunk (the documented tie caveat). Contiguous and gap-free; the final chunk ends at hiExclusive.
     */
    static List<Timestamp[]> computeKeysetChunks(Timestamp lo, Timestamp hiExclusive, long targetRows, BoundaryProbe probe) {
        List<Timestamp[]> out = new ArrayList<>()
        if (lo == null || hiExclusive == null) return out
        if (targetRows < 1L) targetRows = 1L
        Timestamp prev = lo
        while (true) {
            Timestamp next = probe.at(prev, targetRows)
            if (next == null) { out.add([prev, hiExclusive] as Timestamp[]); break }
            if (next.equals(prev)) {                       // a single tx-stamp holds >= targetRows rows
                next = probe.nextDistinct(prev)
                if (next == null) { out.add([prev, hiExclusive] as Timestamp[]); break }
            }
            out.add([prev, next] as Timestamp[])
            prev = next
        }
        return out
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :runtime:component:sim-routing:test --tests "co.hotwax.order.routing.simulation.sync.SimLoadConsoleSpec"`
Expected: PASS (all 5 new keyset tests green; no remaining reference to `computeFullChunks`).

- [ ] **Step 6: Commit**

```bash
git add runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsole.groovy \
        runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsoleSpec.groovy
git commit -m "feat(sim-load): pure keyset chunker replaces even-time-division"
```

---

## Task 3: Generate a single whole-table batch for FULL (remove auto-chunk)

**Files:**
- Modify: `runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsole.groovy` (`generateBatches` FULL branch ~lines 27-30; delete `generateFullChunks` ~lines 75-111)
- Test: `runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsoleSpec.groovy`

- [ ] **Step 1: Write the failing tests**

Add to `SimLoadConsoleSpec`:

```groovy
    def "generateBatches FULL makes exactly one whole-table batch (no auto-chunk)"() {
        when:
        List<String> ids = new SimLoadConsole(ec).generateBatches("product_assoc", "FULL", null, null)
        then:
        ids.size() == 1
        def b = ec.entity.find("co.hotwax.order.routing.simulation.BrokeringSimLoadBatch").condition("loadBatchId", ids[0]).one()
        b.getString("mode") == "FULL"
        b.getTimestamp("sliceFrom") == null
        b.getTimestamp("sliceTo") == null
        b.getString("statusId") == "PLANNED"
    }

    def "generateBatches FULL is idempotent — skips an existing non-skipped whole-table batch"() {
        given:
        new SimLoadConsole(ec).generateBatches("product_assoc", "FULL", null, null)
        when:
        List<String> again = new SimLoadConsole(ec).generateBatches("product_assoc", "FULL", null, null)
        then:
        again.isEmpty()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :runtime:component:sim-routing:test --tests "co.hotwax.order.routing.simulation.sync.SimLoadConsoleSpec"`
Expected: FAIL — current FULL path calls `generateFullChunks` (which opens a DB connection via `prodConn()` and is not a single whole-table batch); the test errors or returns the wrong shape.

- [ ] **Step 3: Replace the FULL branch and delete `generateFullChunks`**

In `SimLoadConsole.generateBatches`, replace the FULL branch (currently lines 27-30):

```groovy
        if (mode == "FULL") {
            // FULL = load the WHOLE table, auto-chunked into safe-sized batches (no operator date input).
            return generateFullChunks(tableName)
        }
```

with:

```groovy
        if (mode == "FULL") {
            // FULL = one whole-table batch (predicate=none). Chunking is a separate, analysis-driven
            // operator action (chunk#LoadBatch) — see SimLoadConsole.chunkBatch.
            if (!hasOpenOrDone(tableName, "FULL", null, null)) ids << createPlanned(tableName, "FULL", null, null)
            return ids
        }
```

Then **delete the entire `generateFullChunks` method** (the doc comment + method, currently lines 75-111). `fullChunkRows()` stays (now used only as the rows-fallback in Task 4). The `scalarLong`/`scalarTs` helpers stay (used by Task 4).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :runtime:component:sim-routing:test --tests "co.hotwax.order.routing.simulation.sync.SimLoadConsoleSpec"`
Expected: PASS (both new FULL tests green; the DAY tests at the bottom still pass).

- [ ] **Step 5: Commit**

```bash
git add runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsole.groovy \
        runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsoleSpec.groovy
git commit -m "feat(sim-load): FULL generate yields a single whole-table batch"
```

---

## Task 4: `chunkBatch` orchestration (DB-backed)

**Files:**
- Modify: `runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsole.groovy`

> Note: this is the DB-touching glue (opens `prodConn`, runs the probe SQL, derives the target, creates children, supersedes the parent). It is verified by the integration test in Task 7. No new unit test here — the pure pieces it composes (`deriveTargetRows`, `computeKeysetChunks`) are already unit-tested in Tasks 1-2.

- [ ] **Step 1: Add `parseCost` helper**

In `SimLoadConsole.groovy`, next to the other small `protected static` helpers (near `scalarTs`, ~line 133), add:

```groovy
    /** Parse the persisted queryCost (text-short) into a Double; null when absent/unparseable. */
    protected static Double parseCost(String s) {
        if (s == null || s.isEmpty()) return null
        try { return Double.valueOf(s) } catch (NumberFormatException e) { return null }
    }
```

- [ ] **Step 2: Add the `chunkBatch` method**

In `SimLoadConsole.groovy`, in the "run / approve / abort" region (e.g. just before `approveAndRun`, ~line 337), add:

```groovy
    /**
     * Split an ANALYZED FULL/RANGE batch into contiguous RANGE children sized to a target cost-per-chunk
     * (derived to a row target from the analyzed query_cost), via a single keyset walk over the indexed
     * last_updated_tx_stamp within the batch's window. Recursive: a RANGE child can itself be chunked.
     *   - whole-table query_cost already <= ceiling  -> no children, batch left runnable.
     *   - table has no last_updated_tx_stamp          -> no children (run whole; the 6 routing config tables).
     * Returns the new child loadBatchIds (empty when no split happened). Parent -> SKIPPED only when children
     * are created.
     */
    List<String> chunkBatch(String loadBatchId, Double targetChunkCostIn, Integer targetRowsIn) {
        EntityValue batch = ec.entity.find(ENTITY).condition("loadBatchId", loadBatchId).useCache(false).one()
        if (batch == null) throw new IllegalArgumentException("no batch ${loadBatchId}")
        if (batch.getString("statusId") != "ANALYZED")
            throw new IllegalStateException("batch ${loadBatchId} is ${batch.getString('statusId')}, not ANALYZED")
        String mode = batch.getString("mode")
        if (mode != "FULL" && mode != "RANGE")
            throw new IllegalStateException("only FULL/RANGE batches can be chunked, not ${mode}")
        String tbl = batch.getString("tableName")

        double ceiling = (targetChunkCostIn != null) ? targetChunkCostIn.doubleValue() : targetChunkCost()
        Double cost = parseCost(batch.getString("queryCost"))
        if (cost != null && cost <= ceiling) {
            logger.info("chunkBatch: ${tbl} whole-table cost ${cost} <= target ${ceiling} — no chunking, run as-is")
            return new ArrayList<String>()
        }

        Object estObj = batch.get("estimatedRows")
        Long est = (estObj != null) ? ((Number) estObj).longValue() : null
        long target = deriveTargetRows(cost, est, ceiling)
        if (target < 1L) target = (targetRowsIn != null) ? targetRowsIn.longValue() : (long) fullChunkRows()
        if (est != null && est > 0L && (est / target) > (long) maxChunks())
            throw new IllegalStateException("target ${target} rows/chunk would create > ${maxChunks()} chunks for ~${est} rows; raise the target")

        final Timestamp winTo = batch.getTimestamp("sliceTo")     // null for FULL; the exclusive upper bound for RANGE
        final Timestamp winFrom = batch.getTimestamp("sliceFrom")  // null for FULL
        final Connection my = prodConn()
        try {
            Timestamp lo, hiExclusive
            try {
                lo = (winFrom != null) ? winFrom : scalarTs(my, "SELECT MIN(last_updated_tx_stamp) FROM " + tbl)
                if (winTo != null) {
                    hiExclusive = winTo
                } else {
                    Timestamp maxTs = scalarTs(my, "SELECT MAX(last_updated_tx_stamp) FROM " + tbl)
                    hiExclusive = (maxTs != null) ? new Timestamp(maxTs.getTime() + 1000L) : null
                }
            } catch (Throwable noTxStamp) {
                logger.warn("chunkBatch: ${tbl} has no last_updated_tx_stamp — cannot chunk; run whole")
                return new ArrayList<String>()
            }
            if (lo == null || hiExclusive == null) return new ArrayList<String>()   // empty table

            BoundaryProbe probe = new BoundaryProbe() {
                Timestamp at(Timestamp from, long offset) {
                    String sql = "SELECT last_updated_tx_stamp FROM " + tbl +
                            " WHERE last_updated_tx_stamp >= ?" + (winTo != null ? " AND last_updated_tx_stamp < ?" : "") +
                            " ORDER BY last_updated_tx_stamp LIMIT 1 OFFSET " + offset
                    PreparedStatement ps = null; ResultSet rs = null
                    try {
                        ps = my.prepareStatement(sql)
                        ps.setTimestamp(1, from)
                        if (winTo != null) ps.setTimestamp(2, winTo)
                        rs = ps.executeQuery()
                        return rs.next() ? rs.getTimestamp(1) : null
                    } finally {
                        try { if (rs != null) rs.close() } catch (Throwable i) {}
                        try { if (ps != null) ps.close() } catch (Throwable i) {}
                    }
                }
                Timestamp nextDistinct(Timestamp prev) {
                    String sql = "SELECT MIN(last_updated_tx_stamp) FROM " + tbl +
                            " WHERE last_updated_tx_stamp > ?" + (winTo != null ? " AND last_updated_tx_stamp < ?" : "")
                    PreparedStatement ps = null; ResultSet rs = null
                    try {
                        ps = my.prepareStatement(sql)
                        ps.setTimestamp(1, prev)
                        if (winTo != null) ps.setTimestamp(2, winTo)
                        rs = ps.executeQuery()
                        return rs.next() ? rs.getTimestamp(1) : null
                    } finally {
                        try { if (rs != null) rs.close() } catch (Throwable i) {}
                        try { if (ps != null) ps.close() } catch (Throwable i) {}
                    }
                }
            }

            List<Timestamp[]> ranges = computeKeysetChunks(lo, hiExclusive, target, probe)
            List<String> ids = new ArrayList<>()
            for (Timestamp[] r : ranges) {
                if (!hasOpenOrDone(tbl, "RANGE", r[0], r[1])) ids.add(createPlanned(tbl, "RANGE", r[0], r[1]))
            }
            if (!ids.isEmpty()) batch.setAll([statusId: "SKIPPED"] as Map<String, Object>).update()
            return ids
        } finally { try { my.close() } catch (Throwable ignore) {} }
    }
```

- [ ] **Step 3: Compile-check the component**

Run: `./gradlew :runtime:component:sim-routing:compileGroovy`
Expected: BUILD SUCCESSFUL (no missing-symbol / type errors). `PreparedStatement`, `ResultSet`, `Connection`, `Timestamp` are already imported at the top of the file.

- [ ] **Step 4: Run the unit suite (no regressions)**

Run: `./gradlew :runtime:component:sim-routing:test --tests "co.hotwax.order.routing.simulation.sync.SimLoadConsoleSpec"`
Expected: PASS (unchanged — `chunkBatch` is exercised in Task 7).

- [ ] **Step 5: Commit**

```bash
git add runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsole.groovy
git commit -m "feat(sim-load): chunkBatch — cost-driven keyset chunking of an analyzed batch"
```

---

## Task 5: `chunk#LoadBatch` service

**Files:**
- Modify: `runtime/component/sim-routing/service/co/hotwax/order/routing/simulation/SimLoadConsoleServices.xml`

- [ ] **Step 1: Add the service definition**

In `SimLoadConsoleServices.xml`, after the `analyze#LoadBatch` service (before `run#LoadBatch`), add:

```xml
    <service verb="chunk" noun="LoadBatch" authenticate="true">
        <description>Split an ANALYZED FULL/RANGE batch into RANGE children sized to a target cost-per-chunk
            (derived from the analyzed query_cost). No-op (returns empty) when the whole-table cost is already
            under the ceiling or the table has no last_updated_tx_stamp watermark. Recursive: a RANGE child
            can be chunked again.</description>
        <in-parameters>
            <parameter name="loadBatchId" required="true"/>
            <parameter name="targetChunkCost" type="BigDecimal"><description>Cost ceiling per chunk; defaults to
                simulation.load.targetChunkCost (5000) when blank.</description></parameter>
            <parameter name="targetRows" type="Integer"><description>Rows-per-chunk fallback used only when
                query_cost is unavailable.</description></parameter>
        </in-parameters>
        <out-parameters><parameter name="loadBatchIdList" type="List"/></out-parameters>
        <actions><script><![CDATA[
            def console = new co.hotwax.order.routing.simulation.sync.SimLoadConsole(ec)
            Double cost = (targetChunkCost != null) ? targetChunkCost.doubleValue() : null
            loadBatchIdList = console.chunkBatch(loadBatchId, cost, targetRows)
            if (!loadBatchIdList) ec.message.addMessage("No chunks created — whole-table cost already under target, or table has no tx-stamp watermark. Run the batch as-is.")
            else ec.message.addMessage("Created ${loadBatchIdList.size()} chunk(s).")
        ]]></script></actions>
    </service>
```

- [ ] **Step 2: Validate the service XML parses**

Run: `./gradlew :runtime:component:sim-routing:compileGroovy`
Expected: BUILD SUCCESSFUL. (The service XML is parsed at runtime, but this confirms the component still builds; the service is exercised end-to-end in Task 7.)

- [ ] **Step 3: Commit**

```bash
git add runtime/component/sim-routing/service/co/hotwax/order/routing/simulation/SimLoadConsoleServices.xml
git commit -m "feat(sim-load): chunk#LoadBatch service wrapper"
```

---

## Task 6: Screen — Chunk action + override form

**Files:**
- Modify: `runtime/component/sim-routing/screen/SimAdmin/BrokeringSimInitialLoad.xml`

- [ ] **Step 1: Add the `chunk` transition**

In `BrokeringSimInitialLoad.xml`, after the `analyze` transition (line ~9-12), add:

```xml
    <transition name="chunk">
        <service-call name="co.hotwax.order.routing.simulation.SimLoadConsoleServices.chunk#LoadBatch"/>
        <default-response url="."/>
    </transition>
```

- [ ] **Step 2: Load the ANALYZED batches for the override picker**

In the screen `<actions>` block (currently lines 29-33), add a second find after the existing `batchList` find:

```xml
        <entity-find entity-name="co.hotwax.order.routing.simulation.BrokeringSimLoadBatch" list="analyzedList">
            <econdition field-name="statusId" value="ANALYZED"/>
            <order-by field-name="tableName,sliceFrom"/>
        </entity-find>
```

- [ ] **Step 3: Add a one-click "Chunk" row link (uses the default ceiling)**

In the `BatchQueue` form-list, after the `runLink` field (line ~97-99), add:

```xml
                <field name="chunkLink"><default-field title="Chunk">
                    <link url="chunk" text="Chunk" parameter-map="[loadBatchId:loadBatchId]"
                          condition="statusId == 'ANALYZED' &amp;&amp; (mode == 'FULL' || mode == 'RANGE')"/></default-field></field>
```

- [ ] **Step 4: Add the override form (operator-set cost ceiling)**

After the `Plan a load` container-box (closes at line ~79), and before the `Batch queue` container-box, add:

```xml
        <container-box><box-header title="Chunk a batch (override cost target)"/><box-body>
            <form-single name="ChunkForm" transition="chunk">
                <field name="loadBatchId"><default-field title="Analyzed batch">
                    <drop-down allow-empty="false">
                        <list-options list="analyzedList" key="${loadBatchId}"
                                      text="${tableName} ${mode} [${loadBatchId}]"/>
                    </drop-down>
                </default-field></field>
                <field name="targetChunkCost"><default-field title="Target cost / chunk">
                    <text-line default-value="${System.getProperty('simulation.load.targetChunkCost','5000')}"/>
                </default-field></field>
                <field name="submitButton"><default-field><submit text="Chunk by cost"/></default-field></field>
            </form-single>
        </box-body></container-box>
```

- [ ] **Step 5: Verify the component builds and the screen loads**

Run: `./gradlew :runtime:component:sim-routing:compileGroovy`
Expected: BUILD SUCCESSFUL.

Then (only if no dev server conflict — see env caveats) start the app and open `/qapps/Oms/OrderRouting/SimInitialLoad`; confirm the new "Chunk a batch" box renders and a "Chunk" link appears on `ANALYZED` rows. (Screen XML is validated at runtime, so a manual load is the real check.)

- [ ] **Step 6: Commit**

```bash
git add runtime/component/sim-routing/screen/SimAdmin/BrokeringSimInitialLoad.xml
git commit -m "feat(sim-load): Chunk action + cost-target override form on the console"
```

---

## Task 7: Integration test — generate → analyze → chunk by cost

**Files:**
- Modify: `runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsoleIntegrationSpec.groovy:58-70` (replace the "FULL auto-chunks" test)

- [ ] **Step 1: Replace the obsolete auto-chunk test**

In `SimLoadConsoleIntegrationSpec.groovy`, **delete** the test `"FULL on a big table auto-chunks into multiple contiguous RANGE batches"` (lines 58-70) and **replace** it with:

```groovy
    def "FULL whole-table batch chunks by a low cost ceiling into contiguous RANGE children"() {
        given: "a whole-table FULL product_facility batch, analyzed (populates query_cost + estimate)"
        def console = new SimLoadConsole(ec)
        String fullId = console.generateBatches("product_facility", "FULL", null, null)[0]
        console.analyzeBatch(fullId)
        def full = ec.entity.find("co.hotwax.order.routing.simulation.BrokeringSimLoadBatch").condition("loadBatchId", fullId).useCache(false).one()
        // a cost ceiling well below the whole-table cost forces a multi-chunk split
        double lowCeiling = Math.max(1.0d, (SimLoadConsole.parseCost(full.getString("queryCost")) ?: 1000.0d) / 4.0d)

        when:
        List<String> ids = console.chunkBatch(fullId, lowCeiling as Double, null)

        then: "multiple RANGE children, parent superseded to SKIPPED"
        ids.size() > 1
        def children = ids.collect { ec.entity.find("co.hotwax.order.routing.simulation.BrokeringSimLoadBatch").condition("loadBatchId", it).one() }
        children.every { it.getString("mode") == "RANGE" }
        ec.entity.find("co.hotwax.order.routing.simulation.BrokeringSimLoadBatch").condition("loadBatchId", fullId).one().getString("statusId") == "SKIPPED"

        and: "children are contiguous and cover the whole table (each end == next start, ordered by sliceFrom)"
        def sorted = children.sort { it.getTimestamp("sliceFrom") }
        (0..sorted.size() - 2).every { sorted[it].getTimestamp("sliceTo") == sorted[it + 1].getTimestamp("sliceFrom") }

        cleanup:
        ec.entity.find("co.hotwax.order.routing.simulation.BrokeringSimLoadBatch").condition("loadBatchId", fullId).deleteAll()
        ids?.each { ec.entity.find("co.hotwax.order.routing.simulation.BrokeringSimLoadBatch").condition("loadBatchId", it).deleteAll() }
    }

    def "chunkBatch is a no-op when the whole-table cost is already under the ceiling"() {
        given:
        def console = new SimLoadConsole(ec)
        String fullId = console.generateBatches("product_facility", "FULL", null, null)[0]
        console.analyzeBatch(fullId)

        when: "a generous ceiling (product_facility whole-table cost ~1312) -> no split"
        List<String> ids = console.chunkBatch(fullId, 1000000.0d as Double, null)

        then:
        ids.isEmpty()
        ec.entity.find("co.hotwax.order.routing.simulation.BrokeringSimLoadBatch").condition("loadBatchId", fullId).one().getString("statusId") == "ANALYZED"

        cleanup:
        ec.entity.find("co.hotwax.order.routing.simulation.BrokeringSimLoadBatch").condition("loadBatchId", fullId).deleteAll()
    }
```

- [ ] **Step 2: Run the integration spec**

> Stop any running dev server first (shared `MoquiDevConf` / H2 lock — see env caveats).

Run: `./gradlew :runtime:component:sim-routing:integrationTest --tests "co.hotwax.order.routing.simulation.sync.SimLoadConsoleIntegrationSpec"`
Expected: PASS. The `@Stepwise` order runs the existing DAY end-to-end steps, then the two new chunk tests. If you hit `NoSuchMethodError`, run `./gradlew :runtime:component:sim-routing:clean` and retry (stale lib jar — see env caveats).

- [ ] **Step 3: Commit**

```bash
git add runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/sync/SimLoadConsoleIntegrationSpec.groovy
git commit -m "test(sim-load): integration coverage for cost-driven chunkBatch"
```

---

## Task 8: Final verification

- [ ] **Step 1: Full unit suite for the component**

Run: `./gradlew :runtime:component:sim-routing:test`
Expected: PASS. (Watch for any other spec that referenced `computeFullChunks` or generate-time FULL chunking — there should be none beyond the files changed here; if one fails, update it to the new flow.)

- [ ] **Step 2: Confirm no dangling references to removed methods**

Run: `grep -rn "computeFullChunks\|generateFullChunks" runtime/component/sim-routing`
Expected: no matches (empty output).

- [ ] **Step 3: Confirm the spec's requirements are all covered**

Re-read `docs/superpowers/specs/2026-06-09-sim-load-cost-driven-chunking-design.md` §§3-7 and tick each against the tasks above (workflow change, cost-driven sizing + under-ceiling no-op, windowed keyset walk, recursive chunk on FULL/RANGE, service, screen, guards). Note any gap; if found, add a task.

---

## Self-Review Notes (author)

- **Spec coverage:** §3 workflow → Tasks 3,5,6; §4 cost sizing + under-ceiling no-op → Tasks 1,4,7; §5 windowed keyset walk → Tasks 2,4; §6 service/screen + recursive (FULL *and* RANGE) → Tasks 4,5,6; §7 guards (maxChunks, no-tx-stamp, dedup, one-at-a-time unchanged) → Task 4. Covered.
- **Type consistency:** `BoundaryProbe.at(Timestamp,long)` / `nextDistinct(Timestamp)` used identically in the test fake (Task 2), the chunker (Task 2), and the DB probe (Task 4). `deriveTargetRows(Double,Long,double)→long` and `parseCost(String)→Double` signatures match across Tasks 1/4/7. `chunkBatch(String,Double,Integer)` matches the service call in Task 5.
- **Recursion:** `chunkBatch` accepts `mode in (FULL, RANGE)` and seeds the keyset walk from `sliceFrom`/`sliceTo` when present, so a RANGE child re-chunks within its own window (Task 4) — Task 7's contiguity assertion exercises the FULL case; RANGE-of-RANGE follows the same code path.
- **No placeholders:** every code step shows complete code; every run step shows the command + expected result.
