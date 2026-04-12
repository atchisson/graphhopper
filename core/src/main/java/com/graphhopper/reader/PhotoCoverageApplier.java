package com.graphhopper.reader;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import com.uber.h3core.H3Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class PhotoCoverageApplier {
    private static final Logger LOG = LoggerFactory.getLogger(PhotoCoverageApplier.class);

    private final PhotoCoverageData data;
    private final BooleanEncodedValue hasPhoto;
    private final BooleanEncodedValue has360;

    public PhotoCoverageApplier(PhotoCoverageData data, BooleanEncodedValue hasPhoto, BooleanEncodedValue has360) {
        this.data = data;
        this.hasPhoto = hasPhoto;
        this.has360 = has360;
    }

    public void apply(BaseGraph graph) {
        H3Core h3;
        try {
            h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize H3Core", e);
        }

        int h3Res = data.h3Resolution;
        long edgesWithPhoto = 0;
        var edges = graph.getAllEdges();
        while (edges.next()) {
            PointList pl = edges.fetchWayGeometry(FetchMode.ALL);
            if (pl.isEmpty()) continue;

            // Average of all waypoints — identical to the previous midpoint logic
            double lat = 0, lon = 0;
            int n = pl.size();
            for (int i = 0; i < n; i++) {
                lat += pl.getLat(i);
                lon += pl.getLon(i);
            }
            lat /= n;
            lon /= n;

            long cell = h3.latLngToCell(lat, lon, h3Res);
            if (data.photoCells.contains(cell)) {
                edges.set(hasPhoto, true);
                edges.set(has360, data.cells360.contains(cell));
                edgesWithPhoto++;
            }
        }
        LOG.info("Photo coverage applied: {} edges flagged", edgesWithPhoto);
    }
}
