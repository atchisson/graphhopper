package com.graphhopper.reader;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a PCB2 (or legacy PCB1) binary coverage file produced by tools/panoramax_preprocess.py.
 *
 * PCB2 format (big-endian):
 *   4 bytes  magic "PCB2"
 *   4 bytes  h3_resolution (int32)
 *   8 bytes  n_cells (int64)
 *   n_cells * 24 bytes:
 *     8 bytes  cell_id       (int64)
 *     4 bytes  min_date      (int32, days since 1000-01-01; 0 = unknown)
 *     4 bytes  max_date      (int32, days since 1000-01-01; 0 = unknown)
 *     4 bytes  photo_count   (int32)
 *     4 bytes  pano360_count (int32)
 */
public class PhotoCoverageLoader {
    private static final Logger LOG = LoggerFactory.getLogger(PhotoCoverageLoader.class);
    private static final int MAGIC_PCB1 = 0x50434231; // "PCB1"
    private static final int MAGIC_PCB2 = 0x50434232; // "PCB2"

    public static PhotoCoverageData load(Path file) {
        if (file == null || !Files.exists(file)) {
            LOG.info("Photo coverage file not found, skipping: {}", file);
            return null;
        }
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = dis.readInt();
            if (magic == MAGIC_PCB2)
                return loadPCB2(dis, file);
            if (magic == MAGIC_PCB1)
                return loadPCB1(dis, file);
            throw new IllegalArgumentException("Not a PCB1 or PCB2 coverage file: " + file);
        } catch (IOException e) {
            LOG.warn("Failed to read photo coverage file {}, skipping", file, e);
            return null;
        }
    }

    private static PhotoCoverageData loadPCB1(DataInputStream dis, Path file) throws IOException {
        LOG.warn("PCB1 file detected — date filtering unavailable. Re-run panoramax_preprocess.py to generate PCB2.");
        int h3Res = dis.readInt();
        long nPhoto = dis.readLong();
        long n360 = dis.readLong();

        LongHashSet photoSet = new LongHashSet((int) nPhoto);
        for (long i = 0; i < nPhoto; i++) photoSet.add(dis.readLong());

        LongHashSet set360 = new LongHashSet((int) n360);
        for (long i = 0; i < n360; i++) set360.add(dis.readLong());

        LOG.info("Loaded PCB1 photo coverage: {} photo cells, {} 360° cells (H3 res {})", nPhoto, n360, h3Res);
        return new PhotoCoverageData(h3Res, photoSet, set360);
    }

    private static PhotoCoverageData loadPCB2(DataInputStream dis, Path file) throws IOException {
        int h3Res = dis.readInt();
        long nCells = dis.readLong();

        LongHashSet photoSet = new LongHashSet((int) nCells);
        LongHashSet set360 = new LongHashSet();
        LongIntHashMap minDates = new LongIntHashMap((int) nCells);
        LongIntHashMap maxDates = new LongIntHashMap((int) nCells);

        for (long i = 0; i < nCells; i++) {
            long cellId = dis.readLong();
            int minDate = dis.readInt();
            int maxDate = dis.readInt();
            int photoCount = dis.readInt();
            int pano360Count = dis.readInt();

            if (photoCount > 0) {
                photoSet.add(cellId);
                minDates.put(cellId, minDate);
                maxDates.put(cellId, maxDate);
            }
            if (pano360Count > 0)
                set360.add(cellId);
        }

        LOG.info("Loaded PCB2 photo coverage: {} cells with dates (H3 res {})", nCells, h3Res);
        return new PhotoCoverageData(h3Res, photoSet, set360, minDates, maxDates);
    }
}
