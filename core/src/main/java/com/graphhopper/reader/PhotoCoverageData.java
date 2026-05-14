package com.graphhopper.reader;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongIntHashMap;

/**
 * H3 cell coverage data loaded from a PCB2 binary file.
 *
 * photoCells : every cell with at least one photo.
 * cells360   : subset where at least one photo is 360°.
 * minDates   : days-since-epoch of earliest photo per cell (0 = unknown → treated as always covered).
 * maxDates   : days-since-epoch of latest photo per cell.
 * hasDates   : true when per-cell date data is available (PCB2 file).
 */
public class PhotoCoverageData {
    public final int h3Resolution;
    public final LongHashSet photoCells;
    public final LongHashSet cells360;
    public final LongIntHashMap minDates;
    public final LongIntHashMap maxDates;
    public final boolean hasDates;

    /** Backward-compat constructor used for PCB1 files (no date data). */
    public PhotoCoverageData(int h3Resolution, LongHashSet photoCells, LongHashSet cells360) {
        this(h3Resolution, photoCells, cells360, null, null);
    }

    public PhotoCoverageData(int h3Resolution, LongHashSet photoCells, LongHashSet cells360,
                              LongIntHashMap minDates, LongIntHashMap maxDates) {
        this.h3Resolution = h3Resolution;
        this.photoCells = photoCells;
        this.cells360 = cells360;
        this.minDates = minDates;
        this.maxDates = maxDates;
        this.hasDates = minDates != null;
    }

    /** Days-since-epoch of earliest photo for this cell, or 1 if unknown (→ treated as always covered). */
    public int getMinDate(long cellId) {
        if (minDates == null) return 1;
        int v = minDates.getOrDefault(cellId, 0);
        return v == 0 ? 1 : v;
    }

    /** Days-since-epoch of latest photo for this cell, or 65534 if unknown (→ treated as always covered). */
    public int getMaxDate(long cellId) {
        if (maxDates == null) return 65534;
        int v = maxDates.getOrDefault(cellId, 0);
        return v == 0 ? 65534 : v;
    }
}
