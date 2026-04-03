package com.graphhopper.reader;

import com.graphhopper.routing.util.CustomArea;
import com.graphhopper.util.JsonFeature;
import com.graphhopper.util.JsonFeatureCollection;
import com.graphhopper.jackson.Jackson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class PhotoCoverageLoader {
    private static final Logger LOG = LoggerFactory.getLogger(PhotoCoverageLoader.class);
    private static final ObjectMapper MAPPER = Jackson.newObjectMapper();

    public static List<CustomArea> load(Path file) {
        if (file == null || !Files.exists(file)) {
            LOG.info("Photo coverage file not found, skipping: {}", file);
            return List.of();
        }
        try {
            try (var reader = Files.newBufferedReader(file)) {
                JsonFeatureCollection fc = MAPPER.readValue(reader, JsonFeatureCollection.class);
                return fc.getFeatures().stream()
                        .map(PhotoCoverageLoader::toCustomAreaSafe)
                        .filter(a -> a != null)
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            LOG.warn("Failed to read photo coverage file {}, skipping", file, e);
            return List.of();
        }
    }

    private static CustomArea toCustomAreaSafe(JsonFeature f) {
        try {
            return CustomArea.fromJsonFeature(f);
        } catch (Exception e) {
            LOG.warn("Failed to parse coverage feature {}, skipping", f.getId(), e);
            return null;
        }
    }
}
