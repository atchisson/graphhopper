package com.graphhopper.routing.ev;

public class PhotoCoverage {
    public static final String KEY_HAS_PHOTO = "photo_coverage";
    public static final String KEY_HAS_360 = "photo_coverage_only360";

    public static BooleanEncodedValue createHasPhoto() {
        return new SimpleBooleanEncodedValue(KEY_HAS_PHOTO, false);
    }

    public static BooleanEncodedValue createHas360() {
        return new SimpleBooleanEncodedValue(KEY_HAS_360, false);
    }
}
