package com.graphhopper.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.*;
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
        // via point is a tower node so there is no duplicated virtual node in the merged path
        assertArrayEquals(new int[]{2, 3, 8, 3, 4}, calcNodes(graph, response.getBest()));

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
        assertArrayEquals(new int[]{2, 3, 8, 5, 4}, calcNodes(graph, response.getBest()));
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
        assertArrayEquals(new int[]{2, 3, 8, 5, 4}, calcNodes(graph, response.getBest()));
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
        assertArrayEquals(new int[]{2, 3, 8, 9, 8, 5, 4}, calcNodes(graph, response.getBest()));
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
        LocationIndexTree locationIndex = new LocationIndexTree(graph, new GHDirectory("", DAType.RAM));
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
