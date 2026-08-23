# Avoid Traversed Edges Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `avoid_traversed_edges` routing mode to the GraphHopper fork (penalize edges already used by previous via-route legs, both directions) and a toggle + strength slider in the mmghr UI.

**Architecture:** Server side, `ViaRouting.calcPaths` wraps the `FlexiblePathCalculator`'s weighting with the existing `AvoidEdgesWeighting` (same pattern as `RoundTripRouting.RoundTripCalculator`) and feeds each leg's edge IDs into the shared avoided-edges set. Two request hints (`avoid_traversed_edges`, `traversed_edge_factor`) arrive automatically via `GHRequest`'s `@JsonAnySetter`. Client side, a pill toggle + slider write into `routeState`, and `buildPostRequestBodyWithCustomModel` adds the two fields to the POST body.

**Tech Stack:** Java 17 (built via Docker, no local JDK 17), Maven, JUnit 5 (gh repo); vanilla ES modules, no build step (mmghr repo).

**Spec:** `docs/superpowers/specs/2026-07-10-avoid-traversed-edges-design.md`

## Global Constraints

- Two git repositories are involved. Tasks 1–2 run in `c:\Users\alenoir\Documents\projets\django cookiecutter\gh` (branch `bikelanes_ec`). Tasks 3–5 run in `c:\Users\alenoir\Documents\projets\django cookiecutter\mmghr`. Commit in the repo you modified.
- No JDK 17 or Maven on this machine. Run all Maven commands through Docker (Docker 28 is installed):
  `docker run --rm -v "/c/Users/alenoir/Documents/projets/django cookiecutter/gh:/app" -v gh-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-17 mvn ...`
  (named volume `gh-m2` caches the Maven repo between runs; first run downloads a lot).
- Hint names (exact): `avoid_traversed_edges` (boolean, default false), `traversed_edge_factor` (double in `(0, 1]`, default `0.1`). Internal penalty = `1.0 / factor`.
- Penalty applies to both directions of an edge (edge ID is direction-agnostic — `AvoidEdgesWeighting` already ignores `reverse`).
- Soft penalty only: dead-ends must stay routable.
- mmghr is a static site: no test runner, no build. Verification is manual in the browser (Task 5).
- mmghr state fields (exact names): `routeState.avoidRepeatedRoads` (boolean, default false), `routeState.repeatedRoadsStrength` (number 0–100, default 50). Strength→factor mapping: `0.5 * Math.pow(0.02, s/100)`.

---

### Task 1: Core routing — penalize traversed edges across via-route legs

**Files:**
- Modify: `web-api/src/main/java/com/graphhopper/util/Parameters.java` (inside `Routing` class, right after `PASS_THROUGH`, ~line 122)
- Modify: `core/src/main/java/com/graphhopper/routing/ViaRouting.java`
- Modify: `core/src/main/java/com/graphhopper/routing/Router.java`
- Test: `core/src/test/java/com/graphhopper/routing/AvoidTraversedEdgesRoutingTest.java` (create)

**Interfaces:**
- Consumes: existing `AvoidEdgesWeighting`, `FlexiblePathCalculator.getWeighting()/setWeighting()`, `Path.getEdges()`.
- Produces: `ViaRouting.calcPaths(List<GHPoint>, QueryGraph, List<Snap>, DirectedEdgeFilter, PathCalculator, List<String>, String, List<Double>, boolean passThrough, boolean avoidTraversedEdges, double traversedEdgePenalty)` — new trailing params. `Parameters.Routing.AVOID_TRAVERSED_EDGES`, `Parameters.Routing.TRAVERSED_EDGE_FACTOR` constants. Request hints `avoid_traversed_edges` / `traversed_edge_factor` honored by `Router.routeVia`.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/com/graphhopper/routing/AvoidTraversedEdgesRoutingTest.java`. It is modeled on `HeadingRoutingTest` (same helpers `createRouter` / `calcNodes`):

```java
package com.graphhopper.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.storage.RAMDirectory;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.TranslationMap;
import com.graphhopper.util.details.PathDetail;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import com.graphhopper.util.shapes.GHPoint;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the avoid_traversed_edges via-routing mode: edges used by previous legs are
 * penalized (both directions) so the route loops instead of doubling back at via points.
 */
class AvoidTraversedEdgesRoutingTest {

    // 2---3---4
    // |   |   |
    // 1---8---5     9 is a dead-end antenna hanging off 8
    // |   |   |      \
    // 0---7---6       (edge 8-9)
    //
    // perimeter edges: 100m, spokes 3-8/7-8: 110m, 5-8: 120m, 1-8: 130m, antenna 8-9: 50m
    // (asymmetric spoke lengths break cost ties so each expected path is a unique minimum)

    private BooleanEncodedValue accessEnc;
    private DecimalEncodedValue speedEnc;
    private EncodingManager encodingManager;

    private void setupEncoding() {
        accessEnc = VehicleAccess.create("car");
        speedEnc = VehicleSpeed.create("car", 5, 5, false);
        encodingManager = new EncodingManager.Builder().add(accessEnc).add(speedEnc)
                .add(RoadClass.create())
                .add(RoadClassLink.create())
                .add(RoadEnvironment.create())
                .add(Roundabout.create())
                .add(MaxSpeed.create())
                .add(Subnetwork.create("profile")).build();
    }

    @Test
    public void loopsInsteadOfUTurnAtViaPoint() {
        setupEncoding();
        BaseGraph graph = createGraph();
        Router router = createRouter(graph);

        GHPoint start = new GHPoint(0.002, 0.000); // node 2
        GHPoint via = new GHPoint(0.001, 0.001);   // node 8
        GHPoint end = new GHPoint(0.002, 0.002);   // node 4

        // without the flag: leg2 returns over the already used edge 3-8 (110+100 < 120+100)
        GHRequest req = new GHRequest().
                setPoints(Arrays.asList(start, via, end)).
                setProfile("profile").
                setPathDetails(Collections.singletonList("edge_key"));
        GHResponse response = router.route(req);
        assertFalse(response.hasErrors(), response.getErrors().toString());
        assertArrayEquals(new int[]{2, 3, 8, 8, 3, 4}, calcNodes(graph, response.getBest()));

        // with the flag: edge 3-8 was used by leg1 (forward) -> reusing it backward costs 110*10,
        // so leg2 loops back over the fresh spoke 8-5 instead
        req = new GHRequest().
                setPoints(Arrays.asList(start, via, end)).
                setProfile("profile").
                setPathDetails(Collections.singletonList("edge_key"));
        req.putHint(Parameters.Routing.AVOID_TRAVERSED_EDGES, true);
        req.putHint(Parameters.Routing.TRAVERSED_EDGE_FACTOR, 0.1);
        response = router.route(req);
        assertFalse(response.hasErrors(), response.getErrors().toString());
        assertArrayEquals(new int[]{2, 3, 8, 8, 5, 4}, calcNodes(graph, response.getBest()));
    }

    @Test
    public void defaultFactorAppliesWhenOnlyFlagIsSet() {
        setupEncoding();
        BaseGraph graph = createGraph();
        Router router = createRouter(graph);

        GHRequest req = new GHRequest().
                setPoints(Arrays.asList(new GHPoint(0.002, 0.000), new GHPoint(0.001, 0.001), new GHPoint(0.002, 0.002))).
                setProfile("profile").
                setPathDetails(Collections.singletonList("edge_key"));
        req.putHint(Parameters.Routing.AVOID_TRAVERSED_EDGES, true);
        GHResponse response = router.route(req);
        assertFalse(response.hasErrors(), response.getErrors().toString());
        // default factor 0.1 -> same loop as the explicit-factor test
        assertArrayEquals(new int[]{2, 3, 8, 8, 5, 4}, calcNodes(graph, response.getBest()));
    }

    @Test
    public void deadEndStaysRoutable() {
        setupEncoding();
        BaseGraph graph = createGraph();
        Router router = createRouter(graph);

        GHPoint start = new GHPoint(0.002, 0.000);  // node 2
        GHPoint via = new GHPoint(0.0013, 0.0013);  // node 9 (dead end)
        GHPoint end = new GHPoint(0.002, 0.002);    // node 4

        GHRequest req = new GHRequest().
                setPoints(Arrays.asList(start, via, end)).
                setProfile("profile").
                setPathDetails(Collections.singletonList("edge_key"));
        req.putHint(Parameters.Routing.AVOID_TRAVERSED_EDGES, true);
        req.putHint(Parameters.Routing.TRAVERSED_EDGE_FACTOR, 0.1);
        GHResponse response = router.route(req);
        assertFalse(response.hasErrors(), response.getErrors().toString());
        // edge 8-9 must be reused (only way out of the dead end), but the rest loops via 5
        assertArrayEquals(new int[]{2, 3, 8, 9, 9, 8, 5, 4}, calcNodes(graph, response.getBest()));
    }

    @Test
    public void invalidFactorIsRejected() {
        setupEncoding();
        BaseGraph graph = createGraph();
        Router router = createRouter(graph);

        for (double invalid : new double[]{0.0, -0.5, 1.5}) {
            GHRequest req = new GHRequest().
                    setPoints(Arrays.asList(new GHPoint(0.002, 0.000), new GHPoint(0.002, 0.002))).
                    setProfile("profile");
            req.putHint(Parameters.Routing.AVOID_TRAVERSED_EDGES, true);
            req.putHint(Parameters.Routing.TRAVERSED_EDGE_FACTOR, invalid);
            GHResponse response = router.route(req);
            assertTrue(response.hasErrors(), "factor " + invalid + " should be rejected");
            assertTrue(response.getErrors().get(0).getMessage().contains(Parameters.Routing.TRAVERSED_EDGE_FACTOR),
                    response.getErrors().toString());
        }
    }

    private Router createRouter(BaseGraph graph) {
        LocationIndexTree locationIndex = new LocationIndexTree(graph, new RAMDirectory());
        locationIndex.prepareIndex();
        Map<String, Profile> profilesByName = new HashMap<>();
        profilesByName.put("profile", TestProfiles.accessAndSpeed("profile", "car"));
        return new Router(graph.getBaseGraph(), encodingManager, locationIndex, profilesByName,
                new PathDetailsBuilderFactory(), new TranslationMap().doImport(), new RouterConfig(),
                new DefaultWeightingFactory(graph.getBaseGraph(), encodingManager), Collections.emptyMap(), Collections.emptyMap());
    }

    private BaseGraph createGraph() {
        BaseGraph g = new BaseGraph.Builder(encodingManager).create();
        NodeAccess na = g.getNodeAccess();
        na.setNode(0, 0.000, 0.000);
        na.setNode(1, 0.001, 0.000);
        na.setNode(2, 0.002, 0.000);
        na.setNode(3, 0.002, 0.001);
        na.setNode(4, 0.002, 0.002);
        na.setNode(5, 0.001, 0.002);
        na.setNode(6, 0.000, 0.002);
        na.setNode(7, 0.000, 0.001);
        na.setNode(8, 0.001, 0.001);
        na.setNode(9, 0.0013, 0.0013);

        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(0, 1).setDistance(100));
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(1, 2).setDistance(100));
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(2, 3).setDistance(100));
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(3, 4).setDistance(100));
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(4, 5).setDistance(100));
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(5, 6).setDistance(100));
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(6, 7).setDistance(100));
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(7, 0).setDistance(100));

        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(1, 8).setDistance(130));
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(3, 8).setDistance(110));
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(5, 8).setDistance(120));
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(7, 8).setDistance(110));
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, g.edge(8, 9).setDistance(50));

        return g;
    }

    private int[] calcNodes(Graph graph, ResponsePath responsePath) {
        List<PathDetail> edgeKeys = responsePath.getPathDetails().get("edge_key");
        int[] result = new int[edgeKeys.size() + 1];
        for (int i = 0; i < edgeKeys.size(); i++) {
            EdgeIteratorState edgeIteratorState = graph.getEdgeIteratorStateForKey((int) edgeKeys.get(i).getValue());
            result[i] = edgeIteratorState.getBaseNode();
            if (i == edgeKeys.size() - 1) result[edgeKeys.size()] = edgeIteratorState.getAdjNode();
        }
        return result;
    }
}
```

Expected costs backing the assertions (weights are proportional to distance, uniform speed, penalty ×10 at factor 0.1):
- Leg 2→8: `2-3-8` = 210 < `2-1-8` = 230 → leg 1 is always `[2,3,8]`.
- Leg 8→4 without flag: `8-3-4` = 210 < `8-5-4` = 220 → U-turn route `[...,8,3,4]`.
- Leg 8→4 with flag (edges 2-3 and 3-8 penalized): `8-3-4` = 1200, `8-1-2-3-4` = 1330 > `8-5-4` = 220 → loop `[...,8,5,4]`.
- Dead-end leg 9→4 with flag: edge 8-9 has no alternative (reused despite penalty), then `8-5-4` as above.

- [ ] **Step 2: Run the test to verify it fails**

```bash
docker run --rm -v "/c/Users/alenoir/Documents/projets/django cookiecutter/gh:/app" -v gh-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-17 mvn -q -pl core -am -DskipTests=false -Dtest=AvoidTraversedEdgesRoutingTest test
```

Expected: COMPILATION ERROR — `AVOID_TRAVERSED_EDGES` and `TRAVERSED_EDGE_FACTOR` do not exist yet. (`-am` builds the `web-api` dependency where `Parameters` lives.)

- [ ] **Step 3: Add the two constants to Parameters.Routing**

In `web-api/src/main/java/com/graphhopper/util/Parameters.java`, directly after the `PASS_THROUGH` constant (~line 122):

```java
        /**
         * true or false. If true, edges already traversed by previous legs of a via-route are
         * penalized so the route avoids passing the same road twice (in either direction).
         * Only for flexible mode (ch.disable=true). See TRAVERSED_EDGE_FACTOR.
         */
        public static final String AVOID_TRAVERSED_EDGES = "avoid_traversed_edges";
        /**
         * Priority factor in (0, 1] applied to already traversed edges when
         * avoid_traversed_edges=true. 1.0 disables the penalty, small values strongly avoid
         * repeated edges. The edge weight is multiplied by 1/factor. Default: 0.1
         */
        public static final String TRAVERSED_EDGE_FACTOR = "traversed_edge_factor";
```

- [ ] **Step 4: Extend ViaRouting.calcPaths**

In `core/src/main/java/com/graphhopper/routing/ViaRouting.java`:

Add imports:

```java
import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntCursor;
import com.graphhopper.routing.weighting.AvoidEdgesWeighting;
```

Change the `calcPaths` signature (add two trailing parameters):

```java
    public static Result calcPaths(List<GHPoint> points, QueryGraph queryGraph, List<Snap> snaps,
                                   DirectedEdgeFilter directedEdgeFilter, PathCalculator pathCalculator,
                                   List<String> curbsides, String curbsideStrictness, List<Double> headings,
                                   boolean passThrough, boolean avoidTraversedEdges, double traversedEdgePenalty) {
```

After the existing argument checks and before `final int legs = snaps.size() - 1;`, install the weighting wrapper (same pattern as `RoundTripRouting.RoundTripCalculator`):

```java
        // avoid_traversed_edges: penalize edges already used by previous legs (both directions)
        // so the route loops back instead of doubling over the same road
        IntSet traversedEdges = new IntHashSet();
        if (avoidTraversedEdges) {
            if (!(pathCalculator instanceof FlexiblePathCalculator))
                throw new IllegalArgumentException("The '" + Parameters.Routing.AVOID_TRAVERSED_EDGES +
                        "' parameter requires flexible routing, set ch.disable=true");
            FlexiblePathCalculator flexPathCalculator = (FlexiblePathCalculator) pathCalculator;
            AvoidEdgesWeighting avoidTraversedWeighting = new AvoidEdgesWeighting(flexPathCalculator.getWeighting())
                    .setEdgePenaltyFactor(traversedEdgePenalty);
            avoidTraversedWeighting.setAvoidedEdges(traversedEdges);
            flexPathCalculator.setWeighting(avoidTraversedWeighting);
        }
```

(`Parameters` needs an import: `import com.graphhopper.util.Parameters;` — check it is not already imported via the static imports at the top.)

Inside the per-leg loop, right after the `for (int i = 0; i < paths.size(); i++) { ... }` block that adds paths to the result (still inside the `for (int leg...)` loop), record the leg's edges:

```java
            if (avoidTraversedEdges)
                for (Path path : paths)
                    for (IntCursor c : path.getEdges())
                        traversedEdges.add(c.value);
```

- [ ] **Step 5: Plumb the hints through Router**

In `core/src/main/java/com/graphhopper/routing/Router.java`:

In `routeVia` (~line 289), replace the `ViaRouting.calcPaths(...)` call with:

```java
        boolean avoidTraversedEdges = getAvoidTraversedEdges(request.getHints());
        double traversedEdgePenalty = getTraversedEdgePenalty(request.getHints());
        ViaRouting.Result result = ViaRouting.calcPaths(request.getPoints(), queryGraph, snaps, directedEdgeFilter,
                pathCalculator, request.getCurbsides(), curbsideStrictness, request.getHeadings(), passThrough,
                avoidTraversedEdges, traversedEdgePenalty);
```

In `routeAlt` (~line 259), forbid the combination (mirrors the `pass_through` guard just above) and pass neutral values:

```java
        if (getAvoidTraversedEdges(request.getHints()))
            throw new IllegalArgumentException("Alternative paths and " + AVOID_TRAVERSED_EDGES + " at the same time is currently not supported");
```

and change its `ViaRouting.calcPaths(...)` call to append `, false, 1.0`.

Next to `getPassThrough` (~line 346), add:

```java
    private static boolean getAvoidTraversedEdges(PMap hints) {
        return hints.getBool(AVOID_TRAVERSED_EDGES, false);
    }

    private static double getTraversedEdgePenalty(PMap hints) {
        double factor = hints.getDouble(TRAVERSED_EDGE_FACTOR, 0.1);
        if (factor <= 0 || factor > 1)
            throw new IllegalArgumentException(TRAVERSED_EDGE_FACTOR + " must be in (0, 1], but was: " + factor);
        return 1.0 / factor;
    }
```

(`AVOID_TRAVERSED_EDGES` and `TRAVERSED_EDGE_FACTOR` are available through the existing `import static com.graphhopper.util.Parameters.Routing.*;`.)

In the `CHSolver.checkRequest()` override (~line 456), after the `pass_through` check, add:

```java
            if (request.getHints().getBool(Parameters.Routing.AVOID_TRAVERSED_EDGES, false))
                throw new IllegalArgumentException("The '" + Parameters.Routing.AVOID_TRAVERSED_EDGES + "' parameter is currently not supported for speed mode, you need to disable speed mode with `ch.disable=true`.");
```

- [ ] **Step 6: Run the new test to verify it passes**

```bash
docker run --rm -v "/c/Users/alenoir/Documents/projets/django cookiecutter/gh:/app" -v gh-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-17 mvn -q -pl core -am -Dtest=AvoidTraversedEdgesRoutingTest test
```

Expected: `Tests run: 4, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 7: Run the surrounding regression tests (signature change touched routeAlt/routeVia)**

```bash
docker run --rm -v "/c/Users/alenoir/Documents/projets/django cookiecutter/gh:/app" -v gh-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-17 mvn -q -pl core -am -Dtest='HeadingRoutingTest,RouterTest*' test
```

Expected: PASS (if `RouterTest*` matches nothing, Maven may fail with "no tests matching" — rerun with just `-Dtest=HeadingRoutingTest`).

- [ ] **Step 8: Commit (gh repo)**

```bash
cd "/c/Users/alenoir/Documents/projets/django cookiecutter/gh"
git add web-api/src/main/java/com/graphhopper/util/Parameters.java \
        core/src/main/java/com/graphhopper/routing/ViaRouting.java \
        core/src/main/java/com/graphhopper/routing/Router.java \
        core/src/test/java/com/graphhopper/routing/AvoidTraversedEdgesRoutingTest.java
git commit -m "feat: add avoid_traversed_edges via-routing mode

Penalizes edges already used by previous legs (both directions) so the
route loops back instead of doubling over the same road. Controlled by
the avoid_traversed_edges / traversed_edge_factor request hints.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: API documentation for the new parameters

**Files:**
- Modify: `../mmghr/doc_routeur_custom.md` (the fork's API doc used by the UI project)

**Interfaces:**
- Consumes: hint names and semantics from Task 1.
- Produces: documentation only.

- [ ] **Step 1: Document the parameters**

In `c:\Users\alenoir\Documents\projets\django cookiecutter\mmghr\doc_routeur_custom.md`, add a new section before "## Notes importantes":

```markdown
---

## Mode « couverture maximale » — ne pas repasser deux fois

Deux paramètres optionnels du POST `/route` pénalisent les edges déjà empruntés par les
tronçons précédents du trajet (waypoints intermédiaires), dans **les deux sens**. Au lieu
d'un aller-retour vers un waypoint, la route revient par une boucle.

| Champ | Type | Défaut | Description |
|---|---|---|---|
| `avoid_traversed_edges` | `boolean` | `false` | Active le mode. Nécessite `ch.disable: true`. |
| `traversed_edge_factor` | `number` | `0.1` | Facteur de priorité dans `(0, 1]`. `1.0` = neutre, proche de `0` = quasi-interdit. Le poids d'un edge déjà emprunté est multiplié par `1/facteur`. |

```json
{
  "points": [[1.9, 47.9], [1.95, 47.88], [2.0, 47.85]],
  "profile": "bike",
  "ch.disable": true,
  "avoid_traversed_edges": true,
  "traversed_edge_factor": 0.1
}
```

- Sans waypoint intermédiaire (2 points), le mode est sans effet : un plus court chemin
  ne repasse jamais deux fois sur le même edge à l'intérieur d'un tronçon.
- La pénalité est **souple** : une impasse menant à un waypoint reste franchissable,
  le routeur ne réutilise un edge qu'en dernier recours.
- Incompatible avec `algorithm=alternative_route` et avec le mode CH (speed mode).
```

- [ ] **Step 2: Commit (mmghr repo)**

```bash
cd "/c/Users/alenoir/Documents/projets/django cookiecutter/mmghr"
git add doc_routeur_custom.md
git commit -m "docs: document avoid_traversed_edges routing parameters

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: mmghr state + request body

**Files:**
- Modify: `js/routing/routeState.js` (mmghr repo — all Task 3–5 paths are relative to the mmghr repo root)
- Modify: `js/routing/customModel.js`
- Modify: `js/routing/routing.js` (two call sites of `buildPostRequestBodyWithCustomModel`, ~line 77 and ~line 695)

**Interfaces:**
- Consumes: hint names from Task 1.
- Produces: `routeState.avoidRepeatedRoads` (boolean), `routeState.repeatedRoadsStrength` (number 0–100), `traversedStrengthToFactor(strength) → number` exported from `customModel.js`, `buildPostRequestBodyWithCustomModel(points, profile, customModel, options = {})` with `options.avoidTraversedEdges` / `options.traversedEdgeFactor`. Tasks 4–5 rely on these exact names.

- [ ] **Step 1: Add state fields**

In `js/routing/routeState.js`, after the `photoCoverageStrength: 50,` block (~line 50):

```js
  // Avoid repeated roads: penalize edges already used by previous route legs
  // (custom GraphHopper fork, avoid_traversed_edges). Loops back instead of U-turns at waypoints.
  avoidRepeatedRoads: false,
  repeatedRoadsStrength: 50, // 0 (weak) to 100 (strong), same exponential scale as photoCoverageStrength
```

- [ ] **Step 2: Extend the request body builder**

In `js/routing/customModel.js`, replace `buildPostRequestBodyWithCustomModel` with:

```js
export function buildPostRequestBodyWithCustomModel(points, profile, customModel, options = {}) {
  const graphHopperProfile = getGraphHopperProfile(profile);
  const requestBody = {
    points: points,
    profile: graphHopperProfile,
    points_encoded: false,
    elevation: true,
    details: ['photo_coverage', 'photo_coverage_only360', 'road_class'],
    custom_model: customModel
  };

  // ch.disable is required for custom model routing on all profiles
  requestBody['ch.disable'] = true;

  // Avoid repeated roads (custom fork): penalize edges already traversed by previous legs
  if (options.avoidTraversedEdges) {
    requestBody['avoid_traversed_edges'] = true;
    requestBody['traversed_edge_factor'] = options.traversedEdgeFactor ?? 0.1;
  }

  return requestBody;
}
```

And add next to it (same exponential mapping as `getPhotoCoverageMultipliers` in routingUI.js):

```js
/**
 * Map the 0-100 repeated-roads strength slider to a traversed_edge_factor in (0, 1].
 * 0.5 at s=0, ~0.07 at s=50, 0.01 at s=100.
 */
export function traversedStrengthToFactor(strength) {
  const t = Math.max(0, Math.min(100, strength ?? 50)) / 100;
  return 0.5 * Math.pow(0.02, t);
}
```

- [ ] **Step 3: Pass the options at both call sites**

In `js/routing/routing.js`, add `traversedStrengthToFactor` to the existing import from `./customModel.js`, then update BOTH `buildPostRequestBodyWithCustomModel(...)` calls (comparison route ~line 77, main route ~line 695) to:

```js
    const requestBody = buildPostRequestBodyWithCustomModel(
      allPoints,
      routeState.selectedProfile,
      /* keep the existing 3rd argument of each call site unchanged */,
      {
        avoidTraversedEdges: routeState.avoidRepeatedRoads,
        traversedEdgeFactor: traversedStrengthToFactor(routeState.repeatedRoadsStrength)
      }
    );
```

(The comparison-route call keeps `comparisonCustomModel`, the main call keeps `routeState.customModel`.)

- [ ] **Step 4: Syntax check**

```bash
cd "/c/Users/alenoir/Documents/projets/django cookiecutter/mmghr"
node --check js/routing/routeState.js && node --check js/routing/customModel.js && node --check js/routing/routing.js && echo OK
```

Expected: `OK`.

- [ ] **Step 5: Commit (mmghr repo)**

```bash
cd "/c/Users/alenoir/Documents/projets/django cookiecutter/mmghr"
git add js/routing/routeState.js js/routing/customModel.js js/routing/routing.js
git commit -m "feat: send avoid_traversed_edges params with routing requests

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: mmghr toggle pill, strength slider, i18n

**Files:**
- Modify: `index.html` (after the off-road pill block that ends ~line 135)
- Modify: `js/routing/routingUI.js`
- Modify: `js/i18n/fr.json`, `js/i18n/en.json`, `js/i18n/de.json`

**Interfaces:**
- Consumes: `routeState.avoidRepeatedRoads`, `routeState.repeatedRoadsStrength` (Task 3).
- Produces: DOM ids `ppill-norepeat`, `norepeat-options`, `repeated-roads-strength`; exported `updateNoRepeatUI()` from `routingUI.js` (used by Task 5).

- [ ] **Step 1: Add the markup**

In `index.html`, immediately after the closing `</div>` of the off-road `provider-pills` block (~line 135), insert:

```html
      <!-- Avoid repeated roads toggle (max photo coverage) -->
      <div class="provider-pills" style="margin-bottom: 8px;">
        <div class="ppill" id="ppill-norepeat" data-tooltip="Agit avec au moins un point de passage intermédiaire">
          <div class="ppill-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M17 2l4 4-4 4"/>
              <path d="M3 11v-1a4 4 0 0 1 4-4h14"/>
              <path d="M7 22l-4-4 4-4"/>
              <path d="M21 13v1a4 4 0 0 1-4 4H3"/>
            </svg>
          </div>
          <div class="ppill-text">
            <div class="ppill-name" data-i18n="routing.noRepeat">Ne pas repasser deux fois</div>
            <div class="ppill-sub" data-i18n="routing.noRepeatSub">Boucle plutôt que demi-tour</div>
          </div>
          <div class="ppill-dot"></div>
        </div>
      </div>
      <div id="norepeat-options" style="display:none; margin-bottom: 8px;">
        <div class="opt-section-title" data-i18n="routing.noRepeatStrength">Intensité</div>
        <div class="opt-strength-row">
          <span class="opt-strength-ends" data-i18n="photoCoverage.weak">Faible</span>
          <input type="range" id="repeated-roads-strength" min="0" max="100" value="50" class="opt-slider">
          <span class="opt-strength-ends" data-i18n="photoCoverage.strong">Fort</span>
        </div>
      </div>
```

- [ ] **Step 2: Add i18n keys**

In each of `js/i18n/fr.json`, `js/i18n/en.json`, `js/i18n/de.json`, add inside the existing `"routing"` object:

fr.json:
```json
    "noRepeat": "Ne pas repasser deux fois",
    "noRepeatSub": "Boucle plutôt que demi-tour",
    "noRepeatStrength": "Intensité"
```

en.json:
```json
    "noRepeat": "Don't pass twice",
    "noRepeatSub": "Loop instead of U-turn",
    "noRepeatStrength": "Strength"
```

de.json:
```json
    "noRepeat": "Nicht zweimal befahren",
    "noRepeatSub": "Schleife statt Wendemanöver",
    "noRepeatStrength": "Intensität"
```

(Validate each file stays valid JSON: `python -c "import json;[json.load(open(f'js/i18n/{l}.json',encoding='utf-8')) for l in ('fr','en','de')];print('OK')"`.)

- [ ] **Step 3: Wire the handlers**

In `js/routing/routingUI.js`:

Add an exported UI-sync helper near `updateStrengthRowVisibility` (~line 149):

```js
export function updateNoRepeatUI() {
  const pill = document.getElementById('ppill-norepeat');
  if (pill) pill.classList.toggle('active', !!routeState.avoidRepeatedRoads);
  const options = document.getElementById('norepeat-options');
  if (options) options.style.display = routeState.avoidRepeatedRoads ? '' : 'none';
  const slider = document.getElementById('repeated-roads-strength');
  if (slider) {
    slider.value = routeState.repeatedRoadsStrength ?? 50;
    updateOptSliderBg(slider);
  }
}
```

In `setupUIHandlers`, after the `ppill-offroad` handler block (~line 429), add:

```js
  // Avoid repeated roads pill (avoid_traversed_edges fork parameter)
  const ppillNoRepeat = document.getElementById('ppill-norepeat');
  if (ppillNoRepeat) {
    ppillNoRepeat.addEventListener('click', () => {
      routeState.avoidRepeatedRoads = !routeState.avoidRepeatedRoads;
      trackEvent('Route', routeState.avoidRepeatedRoads ? 'AvoidRepeatedRoads' : 'AllowRepeatedRoads');
      updateNoRepeatUI();
      recalculateRouteIfReady();
    });
  }

  const repeatedRoadsSlider = document.getElementById('repeated-roads-strength');
  if (repeatedRoadsSlider) {
    repeatedRoadsSlider.value = routeState.repeatedRoadsStrength ?? 50;
    updateOptSliderBg(repeatedRoadsSlider);
    let repeatedRoadsDebounceTimer = null;
    repeatedRoadsSlider.addEventListener('input', (e) => {
      routeState.repeatedRoadsStrength = parseFloat(e.target.value);
      updateOptSliderBg(e.target);
      clearTimeout(repeatedRoadsDebounceTimer);
      repeatedRoadsDebounceTimer = setTimeout(() => {
        if (routeState.avoidRepeatedRoads) recalculateRouteIfReady();
      }, 350);
    });
  }
```

- [ ] **Step 4: Syntax check**

```bash
cd "/c/Users/alenoir/Documents/projets/django cookiecutter/mmghr"
node --check js/routing/routingUI.js && python -c "import json;[json.load(open(f'js/i18n/{l}.json',encoding='utf-8')) for l in ('fr','en','de')];print('OK')"
```

Expected: `OK`.

- [ ] **Step 5: Commit (mmghr repo)**

```bash
cd "/c/Users/alenoir/Documents/projets/django cookiecutter/mmghr"
git add index.html js/routing/routingUI.js js/i18n/fr.json js/i18n/en.json js/i18n/de.json
git commit -m "feat: add 'don't pass twice' toggle and strength slider

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Permalink persistence

**Files:**
- Modify: `js/utils/permalink.js`

**Interfaces:**
- Consumes: `routeState.avoidRepeatedRoads`, `routeState.repeatedRoadsStrength` (Task 3), `updateNoRepeatUI` (Task 4).
- Produces: URL params `no_repeat=1`, `no_repeat_strength=<0-100>`.

- [ ] **Step 1: Snapshot + URL serialization**

In `js/utils/permalink.js`:

In `getRouteStateSnapshot()` (~line 104), after `photoCoverageStrength: routeState.photoCoverageStrength,` add:

```js
      avoidRepeatedRoads: routeState.avoidRepeatedRoads,
      repeatedRoadsStrength: routeState.repeatedRoadsStrength,
```

In `buildParamParts()` (~line 388), after the `date_max` line, add:

```js
    // Avoid repeated roads
    if (routeState.avoidRepeatedRoads) {
      paramParts.push('no_repeat=1');
      paramParts.push(`no_repeat_strength=${routeState.repeatedRoadsStrength ?? 50}`);
    }
```

- [ ] **Step 2: URL parsing**

In `loadFromURL()`, in the synchronous section next to the photo-coverage parsing (~line 531, after `if (dateMaxParam) routeState.photoDateMax = dateMaxParam;`), add:

```js
    // Avoid repeated roads settings
    routeState.avoidRepeatedRoads = params.get('no_repeat') === '1';
    const noRepeatStrengthParam = params.get('no_repeat_strength');
    if (noRepeatStrengthParam !== null) {
      routeState.repeatedRoadsStrength = parseFloat(noRepeatStrengthParam);
    }
    updateNoRepeatUI();
```

Add `updateNoRepeatUI` to the existing import from `../routing/routingUI.js` at the top of the file (the file already imports `applyPhotoCoverageSettings` and `updateStrengthRowVisibility` from there).

- [ ] **Step 3: Syntax check**

```bash
cd "/c/Users/alenoir/Documents/projets/django cookiecutter/mmghr"
node --check js/utils/permalink.js && echo OK
```

Expected: `OK`.

- [ ] **Step 4: Commit (mmghr repo)**

```bash
cd "/c/Users/alenoir/Documents/projets/django cookiecutter/mmghr"
git add js/utils/permalink.js
git commit -m "feat: persist avoid-repeated-roads toggle in permalink

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: End-to-end verification (manual, real server + UI)

**Files:** none (verification only).

**Interfaces:**
- Consumes: everything above, plus the user's local GraphHopper data (`gh/myconfig/config_25-12-14.yml`, `gh/myconfig/graph-cache/`, `gh/myconfig/data/`).

- [ ] **Step 1: Build the server jar in Docker**

```bash
docker run --rm -v "/c/Users/alenoir/Documents/projets/django cookiecutter/gh:/app" -v gh-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-17 mvn -q -DskipTests package
ls "/c/Users/alenoir/Documents/projets/django cookiecutter/gh/web/target/" | grep graphhopper-web
```

Expected: a `graphhopper-web-*.jar` fat jar.

- [ ] **Step 2: Start the server against the existing graph cache**

```bash
docker run --rm -d --name gh-test -p 8989:8989 \
  -v "/c/Users/alenoir/Documents/projets/django cookiecutter/gh:/app" -w /app/myconfig \
  eclipse-temurin:17-jre \
  java -Xmx4g -jar ../web/target/<the-fat-jar-name>.jar server config_25-12-14.yml
# wait for startup, then:
curl -s http://localhost:8989/info | head -c 300
```

Expected: JSON info response. **Caveat:** the existing `graph-cache` was built by the user's v11 custom jar; if the newly built jar refuses to load it (version/flags mismatch), do NOT delete or rebuild the user's cache — report back and fall back to asserting behavior with the unit tests plus a UI check against the user's already-running server if one exists.

- [ ] **Step 3: Verify the API behavior**

Pick three points in the imported area (check bbox in `/info`) where the middle point sits on a short side street off a main road, then:

```bash
# without the flag
curl -s -X POST http://localhost:8989/route -H "Content-Type: application/json" -d '{
  "points": [[LNG1,LAT1],[LNG2,LAT2],[LNG3,LAT3]],
  "profile": "bike", "ch.disable": true, "points_encoded": false
}' > /tmp/without.json

# with the flag
curl -s -X POST http://localhost:8989/route -H "Content-Type: application/json" -d '{
  "points": [[LNG1,LAT1],[LNG2,LAT2],[LNG3,LAT3]],
  "profile": "bike", "ch.disable": true, "points_encoded": false,
  "avoid_traversed_edges": true, "traversed_edge_factor": 0.1
}' > /tmp/with.json
```

Expected: both return `paths[0]`; the flagged route is longer or equal, and its coordinate list does not double back over the same street where a loop exists. Also verify `traversed_edge_factor: 2` returns an HTTP 400 with a message naming `traversed_edge_factor`.

- [ ] **Step 4: Verify the UI flow**

```bash
cd "/c/Users/alenoir/Documents/projets/django cookiecutter/mmghr"
python -m http.server 8080
```

In the browser at `http://localhost:8080` (with the UI pointed at the local router — check `js/config/envConfig.js` for how `GRAPHHOPPER_URL` is resolved and override if needed):
1. Set start/end + one waypoint just off the main road. Toggle « Ne pas repasser deux fois » ON → route recalculates and returns by a loop; network tab shows `avoid_traversed_edges: true` and `traversed_edge_factor` in the POST body.
2. Move the strength slider → debounced recalculation, factor changes in the request.
3. Reload the page with the permalink URL → toggle state and slider position restored.
4. Toggle OFF → params disappear from the request body.

- [ ] **Step 5: Stop the test server and report**

```bash
docker stop gh-test
```

Report results (screenshots of the loop vs U-turn route if possible).
