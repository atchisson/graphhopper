package com.graphhopper.reader;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValue;
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
    private final IntEncodedValue dateMin;
    private final IntEncodedValue dateMax;

    /** Constructor without date EVs — used when loading PCB1 or when date EVs are unavailable. */
    public PhotoCoverageApplier(PhotoCoverageData data, BooleanEncodedValue hasPhoto, BooleanEncodedValue has360) {
        this(data, hasPhoto, has360, null, null);
    }

    public PhotoCoverageApplier(PhotoCoverageData data, BooleanEncodedValue hasPhoto, BooleanEncodedValue has360,
                                 IntEncodedValue dateMin, IntEncodedValue dateMax) {
        this.data = data;
        this.hasPhoto = hasPhoto;
        this.has360 = has360;
        this.dateMin = dateMin;
        this.dateMax = dateMax;
    }

    public void apply(BaseGraph graph) {
        H3Core h3;
        try {
            h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize H3Core", e);
        }

        int h3Res = data.h3Resolution;
        boolean writeDates = dateMin != null && dateMax != null && data.hasDates;
        long edgesWithPhoto = 0;
        var edges = graph.getAllEdges();
        while (edges.next()) {
            PointList pl = edges.fetchWayGeometry(FetchMode.ALL);
            if (pl.isEmpty()) continue;

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
                if (writeDates) {
                    edges.set(dateMin, data.getMinDate(cell));
                    edges.set(dateMax, data.getMaxDate(cell));
                }
                edgesWithPhoto++;
            }
        }
        LOG.info("Photo coverage applied: {} edges flagged (dates: {})", edgesWithPhoto, writeDates);
    }
}
