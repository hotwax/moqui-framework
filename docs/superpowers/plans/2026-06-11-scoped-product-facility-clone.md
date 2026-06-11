# Scoped product_facility Clone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clone only the `PRODUCT_FACILITY` rows a brokering run can read (its order-scope products + variants) instead of all ~5.7M rows, behind a `simulation.scopedClone` flag with an automated parity guard, cutting per-job index-rebuild from minutes to seconds.

**Architecture:** `SimSchemaProvisioner` gains a scoped source-SELECT for `PRODUCT_FACILITY` (a product-id `IN (...)` subquery derived from the store's approved order items + their PRODUCT_VARIANT siblings). `SimulationExecutor` resolves the run's `productStoreId` before each clone and passes it plus the flag through. A fixture-based Spock spec runs one group both scoped and unscoped and asserts identical brokering output.

**Tech Stack:** Groovy 3, Moqui framework 3.2, H2 2.3.232 (MVStore), Spock (`@Tag("integration")`), Gradle (build with JDK 11).

---

## File Structure

- `runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/SimulationUtils.groovy` — add `systemPropBool`.
- `.../simulation/SimSchemaProvisioner.groovy` — scoped source-SELECT for `PRODUCT_FACILITY`; new `cloneLiveToSim` / `resetSimToBaseline` signatures `(conn, jobId, productStoreId, scoped)`.
- `.../simulation/SimulationExecutor.groovy` — extract `resolveGroupProductStoreId`; resolve store + read flag at all 3 clone call-sites; variant multi-store guard.
- `.../src/test/groovy/co/hotwax/order/routing/simulation/SimulationUtilsSpec.groovy` — unit test for `systemPropBool` (create if absent).
- `.../src/test/groovy/co/hotwax/order/routing/simulation/SimSchemaProvisionerSpec.groovy` — unit tests for the scoped SQL builder (existing unit spec, `new SimSchemaProvisioner(null)`).
- `.../src/test/groovy/co/hotwax/order/routing/simulation/ScopedCloneParityIntegrationSpec.groovy` — new integration parity spec.

---

## Task 1: `systemPropBool` helper

**Files:**
- Modify: `runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/SimulationUtils.groovy`
- Test: `runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/SimulationUtilsSpec.groovy`

- [ ] **Step 1: Write the failing test** (create the spec if it does not exist; if it exists, add the methods)

```groovy
package co.hotwax.order.routing.simulation

import spock.lang.Specification

class SimulationUtilsSpec extends Specification {

    def "systemPropBool returns default when unset"() {
        expect:
        SimulationUtils.systemPropBool("sim.test.unset.flag.xyz", true)
        !SimulationUtils.systemPropBool("sim.test.unset.flag.xyz", false)
    }

    def "systemPropBool reads true/false from a system property"() {
        when:
        System.setProperty("sim.test.flag.abc", value)
        then:
        SimulationUtils.systemPropBool("sim.test.flag.abc", !expected) == expected
        cleanup:
        System.clearProperty("sim.test.flag.abc")
        where:
        value   | expected
        "true"  | true
        "TRUE"  | true
        "false" | false
        "Y"     | true
        "n"     | false
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runtime:component:sim-routing:test --tests "*SimulationUtilsSpec*"`
Expected: FAIL — `groovy.lang.MissingMethodException: ... systemPropBool`.

- [ ] **Step 3: Add the method** after `systemPropLong` in `SimulationUtils.groovy`

```groovy
    /** Read a boolean system property or env var ("true"/"Y" case-insensitive), default on missing/empty. */
    static boolean systemPropBool(String key, boolean defaultValue) {
        String v = SystemBinding.getPropOrEnv(key)
        if (v == null || v.isEmpty()) return defaultValue
        String t = v.trim()
        return "true".equalsIgnoreCase(t) || "Y".equalsIgnoreCase(t)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :runtime:component:sim-routing:test --tests "*SimulationUtilsSpec*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/SimulationUtils.groovy \
        runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/SimulationUtilsSpec.groovy
git commit -m "feat(sim): add SimulationUtils.systemPropBool"
```

---

## Task 2: Scoped source-SELECT in SimSchemaProvisioner

**Files:**
- Modify: `runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/SimSchemaProvisioner.groovy`
- Test: `runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/SimSchemaProvisionerSpec.groovy`

The scoped predicate keys only on `product_id` (decision: all facilities kept). `productStoreId` is
validated (alphanumerics/underscore/hyphen) and inlined — it comes from trusted config, not user input,
and the provisioner uses `Statement` (not `PreparedStatement`) for DDL.

- [ ] **Step 1: Write the failing unit tests** (append to existing `SimSchemaProvisionerSpec`, which uses `new SimSchemaProvisioner((ExecutionContext) null)`)

```groovy
    def "selectFromLive is plain SELECT * when not scoped"() {
        expect:
        provisioner.selectFromLive("PRODUCT_FACILITY", "SM_STORE", false) ==
            "SELECT * FROM LIVE.PRODUCT_FACILITY"
    }

    def "selectFromLive only scopes PRODUCT_FACILITY, leaves other override tables full"() {
        expect:
        provisioner.selectFromLive("FACILITY", "SM_STORE", true) == "SELECT * FROM LIVE.FACILITY"
    }

    def "selectFromLive scopes PRODUCT_FACILITY by product_id with variant expansion"() {
        when:
        String sql = provisioner.selectFromLive("PRODUCT_FACILITY", "SM_STORE", true)
        then:
        sql.startsWith("SELECT * FROM LIVE.PRODUCT_FACILITY WHERE PRODUCT_ID IN (")
        sql.contains("LIVE.order_item")
        sql.contains("oh.product_store_id='SM_STORE'")
        sql.contains("oh.status_id='ORDER_APPROVED'")
        sql.contains("oi.status_id='ITEM_APPROVED'")
        sql.contains("PRODUCT_VARIANT")               // variant style + siblings expansion
        sql.count("UNION") == 2                        // P0 UNION STYLES UNION SIBLINGS
    }

    def "selectFromLive falls back to full when productStoreId is null"() {
        expect:
        provisioner.selectFromLive("PRODUCT_FACILITY", null, true) ==
            "SELECT * FROM LIVE.PRODUCT_FACILITY"
    }

    def "selectFromLive rejects an unsafe productStoreId"() {
        when:
        provisioner.selectFromLive("PRODUCT_FACILITY", "SM'; DROP", true)
        then:
        thrown(IllegalArgumentException)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :runtime:component:sim-routing:test --tests "*SimSchemaProvisionerSpec*"`
Expected: FAIL — no such method `selectFromLive`.

- [ ] **Step 3: Add the SQL builder and a store-id guard** in `SimSchemaProvisioner.groovy` (place near `requireSafeJobId`)

```groovy
    private static final java.util.regex.Pattern SAFE_STORE_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9_\\-]+")

    private static void requireSafeStoreId(String storeId) {
        if (storeId == null || !SAFE_STORE_ID.matcher(storeId).matches())
            throw new IllegalArgumentException("Unsafe productStoreId for scoped clone: ${storeId}")
    }

    /**
     * Source SELECT used by the CTAS clone / reset INSERT for one override table. PRODUCT_FACILITY is
     * scoped (when {@code scoped} and {@code productStoreId} is set) to the products in the store's
     * approved order items plus their PRODUCT_VARIANT style + siblings — the only product_facility rows
     * the brokering inventory query (InventorySourceSelector) can read. All other tables, and the
     * unscoped/null-store cases, return a full {@code SELECT *}.
     */
    String selectFromLive(String tbl, String productStoreId, boolean scoped) {
        String base = "SELECT * FROM " + LIVE_SCHEMA + "." + tbl
        if (!scoped || !"PRODUCT_FACILITY".equals(tbl) || productStoreId == null) return base
        return base + " WHERE PRODUCT_ID IN (" + neededProductIds(productStoreId) + ")"
    }

    /** product-id set for the scoped clone: P0 (store order-item products) ∪ styles ∪ sibling variants. */
    String neededProductIds(String productStoreId) {
        requireSafeStoreId(productStoreId)
        String variantActive = "pa.product_assoc_type_id='PRODUCT_VARIANT'" +
                " AND (pa.thru_date IS NULL OR pa.thru_date >= CURRENT_TIMESTAMP)"
        String p0 = "SELECT oi.product_id FROM " + LIVE_SCHEMA + ".order_item oi" +
                " JOIN " + LIVE_SCHEMA + ".order_header oh ON oh.order_id=oi.order_id" +
                " WHERE oh.product_store_id='" + productStoreId + "'" +
                " AND oh.order_type_id='SALES_ORDER' AND oh.status_id='ORDER_APPROVED'" +
                " AND oi.status_id='ITEM_APPROVED' AND oi.product_id IS NOT NULL"
        String styles = "SELECT pa.product_id FROM " + LIVE_SCHEMA + ".product_assoc pa" +
                " WHERE " + variantActive + " AND pa.product_id_to IN (" + p0 + ")"
        String siblings = "SELECT pa.product_id_to FROM " + LIVE_SCHEMA + ".product_assoc pa" +
                " WHERE " + variantActive + " AND pa.product_id IN (" + styles + ")"
        return p0 + " UNION " + styles + " UNION " + siblings
    }
```

- [ ] **Step 4: Run to verify the unit tests pass**

Run: `./gradlew :runtime:component:sim-routing:test --tests "*SimSchemaProvisionerSpec*"`
Expected: PASS.

- [ ] **Step 5: Change `cloneLiveToSim` and `resetSimToBaseline` signatures to use the builder**

Replace the body of `cloneLiveToSim`:

```groovy
    /** Create {@code SIM_<jobId>}; clone the 4 override target tables from {@code LIVE} (PRODUCT_FACILITY
     *  scoped when {@code scoped}), then replay PK + indexes. */
    void cloneLiveToSim(Connection conn, String jobId, String productStoreId, boolean scoped) {
        requireSafeJobId(jobId)
        String schema = "SIM_" + jobId
        Statement st = conn.createStatement()
        try {
            st.execute(("CREATE SCHEMA " + schema).toString())
            for (String tbl in OVERRIDE_TABLES) {
                st.execute(("CREATE TABLE " + schema + "." + tbl +
                        " AS " + selectFromLive(tbl, productStoreId, scoped)).toString())
                replayIndexes(conn, schema, tbl)
            }
        } finally {
            st.close()
        }
    }
```

Replace the body of `resetSimToBaseline`:

```groovy
    void resetSimToBaseline(Connection conn, String jobId, String productStoreId, boolean scoped) {
        requireSafeJobId(jobId)
        String schema = "SIM_" + jobId
        Statement st = conn.createStatement()
        try {
            for (String tbl in OVERRIDE_TABLES) {
                st.execute(("TRUNCATE TABLE " + schema + "." + tbl).toString())
                st.execute(("INSERT INTO " + schema + "." + tbl +
                        " " + selectFromLive(tbl, productStoreId, scoped)).toString())
            }
        } finally {
            st.close()
        }
    }
```

- [ ] **Step 6: Update existing direct callers in tests** so the project compiles. Find them:

Run: `grep -rn "cloneLiveToSim\|resetSimToBaseline" runtime/component/sim-routing/src/test`
For each call, add the two new args using full clone (preserves existing behavior): e.g.
`provisioner.cloneLiveToSim(h2Conn, jobId)` → `provisioner.cloneLiveToSim(h2Conn, jobId, null, false)`.

- [ ] **Step 7: Compile to verify signatures**

Run: `./gradlew :runtime:component:sim-routing:compileGroovy :runtime:component:sim-routing:compileTestGroovy`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/SimSchemaProvisioner.groovy \
        runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/SimSchemaProvisionerSpec.groovy
git add -u runtime/component/sim-routing/src/test
git commit -m "feat(sim): scoped PRODUCT_FACILITY source-SELECT in SimSchemaProvisioner"
```

---

## Task 3: Wire productStoreId + flag through SimulationExecutor

**Files:**
- Modify: `runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/SimulationExecutor.groovy`

There are 3 clone call-sites: `runOneGroupRoundH2` (~line 247), `executeGroupRunVariationH2`
(~lines 270 clone, 295 reset), and the what-if path (~lines 485 clone, 498 reset). The
group-store fallback currently inline in `runGroupRoundOnClonedH2` (~line 185) is extracted so all
sites reuse it.

- [ ] **Step 1: Extract the resolver helper.** Add this private method (place just below `runGroupRoundOnClonedH2`):

```groovy
    /** The routing group's own productStoreId (config), used when the request omits one. */
    private String resolveGroupProductStoreId(String routingGroupId) {
        def orgValue = ec.entity.find("co.hotwax.order.routing.OrderRoutingGroup")
                .condition("routingGroupId", routingGroupId).useCache(true).disableAuthz().one()
        return orgValue?.productStoreId
    }
```

- [ ] **Step 2: Replace the inline block in `runGroupRoundOnClonedH2`** (the `if (productStoreId == null) { ... ec.entity.find ... }` block added previously) with a call to the helper:

```groovy
        String productStoreId = deltaConfig?.productStoreId
        if (productStoreId == null) {
            productStoreId = resolveGroupProductStoreId(routingGroupId)
            if (productStoreId != null) {
                ec.logger.info("Group-run ${routingGroupId}: defaulted productStoreId=${productStoreId} " +
                        "from the routing group (none supplied in the request)")
            }
        }
```

- [ ] **Step 3: Single-shot path** — in `runOneGroupRoundH2`, before the `cloneLiveToSim` call:

```groovy
            String storeId = deltaConfig?.productStoreId ?: resolveGroupProductStoreId(routingGroupId)
            boolean scoped = SimulationUtils.systemPropBool("simulation.scopedClone", true)
            h2Provisioner.cloneLiveToSim(h2Conn, jobId, storeId, scoped)
```

(There is no `resetSimToBaseline` in this path; leave the rest unchanged.)

- [ ] **Step 4: Variant path** — in `executeGroupRunVariationH2`, compute the scope store + flag once before the baseline `cloneLiveToSim` (~line 270), with a multi-store safety guard, and reuse for the reset (~line 295):

```groovy
            String groupStore = resolveGroupProductStoreId(routingGroupId)
            // Variants share one SIM schema. If a variant overrides productStoreId to a *different*
            // store, the group-store scope would miss its products → fall back to a full clone.
            boolean scoped = SimulationUtils.systemPropBool("simulation.scopedClone", true)
            if (scoped && variants.any { it.simulationConfig?.productStoreId != null &&
                    it.simulationConfig.productStoreId != groupStore }) {
                ec.logger.warn("Group-run ${routingGroupId}: a variant overrides productStoreId; " +
                        "disabling scoped clone for this run")
                scoped = false
            }
            h2Provisioner.cloneLiveToSim(h2Conn, jobId, groupStore, scoped)
```

And change the reset call (~line 295) to:

```groovy
                    h2Provisioner.resetSimToBaseline(h2Conn, jobId, groupStore, scoped)
```

- [ ] **Step 5: What-if path** — at ~lines 485 / 498, pass `cfg.productStoreId` and the flag:

```groovy
            boolean scopedWhatIf = SimulationUtils.systemPropBool("simulation.scopedClone", true)
            h2Provisioner.cloneLiveToSim(h2Conn, jobId, cfg.productStoreId, scopedWhatIf)
```
```groovy
            h2Provisioner.resetSimToBaseline(h2Conn, jobId, cfg.productStoreId, scopedWhatIf)
```

(Confirm the local variable holding the `SimulationConfig` is named `cfg` in that method; if it differs, use the actual name. Run `grep -n "cloneLiveToSim\|SimulationConfig " ` on the file around lines 460-500 to verify.)

- [ ] **Step 6: Compile**

Run: `./gradlew :runtime:component:sim-routing:compileGroovy`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run the existing group-run integration suite to confirm no regression** (only if no dev server is on :8080 — it shares config; see Sim-routing integration test harness notes)

Run: `./gradlew :runtime:component:sim-routing:integrationTest --tests "*GroupRunH2*" -Pintegration=true`
Expected: PASS (same as baseline).

- [ ] **Step 8: Commit**

```bash
git add runtime/component/sim-routing/src/main/groovy/co/hotwax/order/routing/simulation/SimulationExecutor.groovy
git commit -m "feat(sim): resolve productStoreId + scopedClone flag at all clone call-sites"
```

---

## Task 4: Parity integration test (scoped == full)

**Files:**
- Create: `runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/ScopedCloneParityIntegrationSpec.groovy`

Model on `GroupRunH2ExecutionIntegrationSpec` (same `setupSpec` for `ec`/`h2Conn`, the same fixture
loading, and `executeGroupRun(routingGroupId, cfg, [], opts)`). The test runs the SAME group twice —
once with `simulation.scopedClone=false`, once `true` — and asserts the `GroupRunResult` trees are equal.

- [ ] **Step 1: Write the test.** Copy the `setupSpec`/`newExec`/fixture-loading boilerplate verbatim from `GroupRunH2ExecutionIntegrationSpec.groovy` (open it and reuse its exact `@Shared` fields, `setupSpec`, helper to build `SimulationConfig`/`RunOptions`, and `routingGroupId`). Then add:

```groovy
    def "scoped clone produces identical brokering output to the full clone"() {
        given: "a run config for the fixture group"
        def cfg = newConfig()          // reuse the fixture's SimulationConfig helper
        def opts = newOpts()           // reuse the fixture's RunOptions helper

        when: "run with full clone"
        System.setProperty("simulation.scopedClone", "false")
        def full = newExec().executeGroupRun(routingGroupId, cfg, [], opts).groupRun

        and: "run with scoped clone"
        System.setProperty("simulation.scopedClone", "true")
        def scoped = newExec().executeGroupRun(routingGroupId, cfg, [], opts).groupRun

        then: "top-line counts match"
        scoped.attemptedItemCount == full.attemptedItemCount
        scoped.brokeredItemCount  == full.brokeredItemCount
        scoped.queuedItemCount    == full.queuedItemCount

        and: "per-order outcomes match (finalReason + assignments), order-insensitive"
        traceKey(scoped) == traceKey(full)

        cleanup:
        System.clearProperty("simulation.scopedClone")
    }

    /** Stable multiset key of every order trace: orderId|shipGroup|finalReason|sorted(facility:qty). */
    private static Set<String> traceKey(groupRun) {
        Set<String> keys = [] as Set
        groupRun.routingResults.each { rr ->
            rr.orderTraces.each { t ->
                String asg = (t.finalAssignments ?: []).collect { "${it.facilityId}:${it.quantity}" }.sort().join(",")
                keys << "${t.orderId}|${t.shipGroupSeqId}|${t.finalReason}|${asg}".toString()
            }
        }
        return keys
    }
```

NOTE: verify the property names on `orderTraces` entries (`orderId`, `shipGroupSeqId`, `finalReason`,
`finalAssignments` with `facilityId`/`quantity`) against the result JSON in
`runtime/log/sim/*-variation.json` and the `GroupRunResult`/`OrderTrace` classes; adjust `traceKey`
to the actual field names before running.

- [ ] **Step 2: Run to verify it fails first** (it should fail only if a scope gap exists; if Task 2/3 are correct it may pass immediately — that is the desired guard, not a TDD-red requirement). Confirm it at least executes both paths:

Run: `./gradlew :runtime:component:sim-routing:integrationTest --tests "*ScopedCloneParityIntegrationSpec*" -Pintegration=true`
Expected: PASS (scoped output == full output). If it FAILS, the scope predicate is missing rows — inspect which order's `finalReason` diverged and widen `neededProductIds`.

- [ ] **Step 3: Add a second fixture that exercises broken-style/assortment** if one exists (look for a fixture under `src/test/resources/simulation/fixtures/group-run/` whose `routing-config.sql` enables broken-style, or whose `order-data.sql` includes `product_assoc` PRODUCT_VARIANT rows). Parameterize the test over both fixtures so the variant expansion is covered. If no such fixture exists, note it and rely on the unit test's `PRODUCT_VARIANT` assertion.

- [ ] **Step 4: Commit**

```bash
git add runtime/component/sim-routing/src/test/groovy/co/hotwax/order/routing/simulation/ScopedCloneParityIntegrationSpec.groovy
git commit -m "test(sim): parity guard — scoped clone == full clone brokering output"
```

---

## Task 5: Build (JDK 11) and real-data verification

**Files:** none (build + manual verification).

- [ ] **Step 1: Build both jars with JDK 11** (a plain `./gradlew` reuses a Java-17 daemon → class 61 → runtime crash; force JDK 11 and `--no-daemon`)

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-11.jdk/Contents/Home \
  ./gradlew --no-daemon :runtime:component:sim-routing:jar
```
Verify class version 55: `unzip -p runtime/component/sim-routing/lib/sim-routing-1.0.0.jar co/hotwax/order/routing/simulation/SimSchemaProvisioner.class | od -An -j6 -N2 -tu1 | awk '{print $1*256+$2}'` → `55`.

- [ ] **Step 2: Restart the dev server** with the new jar (boots fast; lazy prod-source):

```bash
# stop the current :8075 listener, then from repo root:
java -Dmoqui.conf=conf/MoquiDevConf.xml -Dmoqui.runtime=runtime \
     -XX:-OmitStackTraceInFastThrow -Dfile.encoding=UTF-8 -jar moqui.war port=8075
```

- [ ] **Step 3: Run a real group both ways and compare time + result.** Submit group `100002` (no productStoreId — auto-resolves SM_STORE) with `simulation.scopedClone=true`, then `false`, and compare `totalDurationMs` and the persisted result. Expected: scoped run is dramatically faster (index rebuild on tens-of-thousands of rows vs 5.7M) with identical `routingResults`/`finalReason`.

```bash
curl -s -u john.doe:moqui -X POST \
  "http://localhost:8075/rest/s1/sim-routing/routingGroups/100002/brokeringSimulation/jobs" \
  -H "Content-Type: application/json" \
  -d '{"variants":[{"label":"scoped","routingDeltas":[]}],"sampleCap":50}'
# poll …/jobs/<jobId>; note totalDurationMs and routingResults
```

- [ ] **Step 4: Confirm SIM schema row count dropped.** While a scoped run holds its schema (or check the log), the cloned `SIM_<jobId>.PRODUCT_FACILITY` should have far fewer than 5.7M rows. Record the before/after run time in the plan's completion notes.

- [ ] **Step 5: Final commit / wrap-up** (jars are build artifacts, not git-tracked; no commit needed unless `.gitignore` says otherwise — verify with `git status`).

---

## Self-Review

- **Spec coverage:** scoped predicate (Task 2 `neededProductIds`), product-only + variants (Task 2), applies to clone + reset (Task 2 Step 5) at all 3 call-sites (Task 3), `simulation.scopedClone` flag default-true (Tasks 1+3), parity test on fixtures incl. variant branch (Task 4), JDK-11 build + perf verification (Task 5), other override tables unchanged (Task 2 `selectFromLive` guard), override-safety (covered by parity test). ✓
- **Placeholders:** none — every code step is concrete. The two NOTE blocks (Task 3 Step 5 variable name; Task 4 field names) are explicit verification instructions, not deferred work.
- **Type consistency:** `selectFromLive(String, String, boolean)`, `neededProductIds(String)`, `cloneLiveToSim(Connection, String, String, boolean)`, `resetSimToBaseline(Connection, String, String, boolean)`, `resolveGroupProductStoreId(String)`, `systemPropBool(String, boolean)` — used identically across tasks. ✓
