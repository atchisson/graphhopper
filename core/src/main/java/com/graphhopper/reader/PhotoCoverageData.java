package com.graphhopper.reader;

import com.carrotsearch.hppc.LongHashSet;

/**
 * Holds the two sets of H3 cell IDs loaded from a PCB1 binary coverage file.
 * photoCells: every cell that has at least one photo.
 * cells360:   subset of photoCells where all photos are 360°.
 */
public class PhotoCoverageData {
    public final int h3Resolution;
    public final LongHashSet photoCells;
    public final LongHashSet cells360;

    public PhotoCoverageData(int h3Resolution, LongHashSet photoCells, LongHashSet cells360) {
        this.h3Resolution = h3Resolution;
        this.photoCells = photoCells;
        this.cells360 = cells360;
    }
}
