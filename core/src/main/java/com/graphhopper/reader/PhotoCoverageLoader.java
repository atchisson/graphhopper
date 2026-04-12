package com.graphhopper.reader;

import com.carrotsearch.hppc.LongHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a PCB1 binary coverage file produced by tools/panoramax_preprocess.py.
 *
 * Format (big-endian):
 *   4 bytes  magic "PCB1"
 *   4 bytes  h3_resolution (int32)
 *   8 bytes  n_photo (int64)
 *   8 bytes  n_360   (int64)
 *   n_photo * 8 bytes  photo cell IDs (int64)
 *   n_360   * 8 bytes  360° cell IDs  (int64)
 */
public class PhotoCoverageLoader {
    private static final Logger LOG = LoggerFactory.getLogger(PhotoCoverageLoader.class);
    private static final int MAGIC = 0x50434231; // "PCB1"

    public static PhotoCoverageData load(Path file) {
        if (file == null || !Files.exists(file)) {
            LOG.info("Photo coverage file not found, skipping: {}", file);
            return null;
        }
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = dis.readInt();
            if (magic != MAGIC)
                throw new IllegalArgumentException("Not a PCB1 coverage file: " + file);

            int h3Res = dis.readInt();
            long nPhoto = dis.readLong();
            long n360 = dis.readLong();

            LongHashSet photoSet = new LongHashSet((int) nPhoto);
            for (long i = 0; i < nPhoto; i++)
                photoSet.add(dis.readLong());

            LongHashSet set360 = new LongHashSet((int) n360);
            for (long i = 0; i < n360; i++)
                set360.add(dis.readLong());

            LOG.info("Loaded photo coverage: {} photo cells, {} 360° cells (H3 res {})",
                    nPhoto, n360, h3Res);
            return new PhotoCoverageData(h3Res, photoSet, set360);
        } catch (IOException e) {
            LOG.warn("Failed to read photo coverage file {}, skipping", file, e);
            return null;
        }
    }
}
