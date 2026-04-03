package com.graphhopper.reader;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.util.AreaIndex;
import com.graphhopper.routing.util.CustomArea;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import java.util.List;

public class PhotoCoverageApplier {
    private final AreaIndex<CustomArea> areaIndex;
    private final BooleanEncodedValue hasPhoto;
    private final BooleanEncodedValue only360;
    private final GeometryFactory gf = new GeometryFactory();

    public PhotoCoverageApplier(AreaIndex<CustomArea> areaIndex, BooleanEncodedValue hasPhoto, BooleanEncodedValue only360) {
        this.areaIndex = areaIndex;
        this.hasPhoto = hasPhoto;
        this.only360 = only360;
    }

    public void apply(BaseGraph graph) {
        if (areaIndex == null || hasPhoto == null || only360 == null) return;

        var edges = graph.getAllEdges();
        while (edges.next()) {
            int edgeId = edges.getEdge();
            PointList pl = edges.fetchWayGeometry(com.graphhopper.util.FetchMode.ALL);
            Point mid = midpoint(pl);
            if (mid == null) continue;
            List<CustomArea> matches = areaIndex.query(mid.getY(), mid.getX());
            boolean photo = false;
            boolean panoOnly = false;
            for (CustomArea ca : matches) {
                Object hasPhotoProp = ca.getProperties().getOrDefault("has_photo", true);
                Object only360Prop = ca.getProperties().getOrDefault("has_only_360", false);
                photo |= Boolean.parseBoolean(hasPhotoProp.toString());
                panoOnly |= Boolean.parseBoolean(only360Prop.toString());
            }
            if (photo) {
                edges.set(hasPhoto, true);
                edges.set(only360, panoOnly);
            }
        }
    }

    private Point midpoint(PointList pl) {
        if (pl.isEmpty()) return null;
        double lat = 0, lon = 0;
        int n = pl.size();
        for (int i = 0; i < n; i++) {
            lat += pl.getLat(i);
            lon += pl.getLon(i);
        }
        return gf.createPoint(new org.locationtech.jts.geom.Coordinate(lon / n, lat / n));
    }
}
