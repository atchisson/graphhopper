package com.graphhopper.routing.ev;

public class PhotoCoverage {
    public static final String KEY_HAS_PHOTO = "photo_coverage";
    public static final String KEY_HAS_360 = "photo_coverage_only360";
    /** Days since 1000-01-01 of earliest photo in H3 cell; 0 = unknown. */
    public static final String KEY_DATE_MIN = "photo_date_min";
    /** Days since 1000-01-01 of latest photo in H3 cell; 0 = unknown. */
    public static final String KEY_DATE_MAX = "photo_date_max";

    public static BooleanEncodedValue createHasPhoto() {
        return new SimpleBooleanEncodedValue(KEY_HAS_PHOTO, false);
    }

    public static BooleanEncodedValue createHas360() {
        return new SimpleBooleanEncodedValue(KEY_HAS_360, false);
    }

    /** 20-bit unsigned, covers days 0–1048575 (epoch 1000-01-01, upper limit ~3870). */
    public static IntEncodedValue createDateMin() {
        return new IntEncodedValueImpl(KEY_DATE_MIN, 20, false);
    }

    public static IntEncodedValue createDateMax() {
        return new IntEncodedValueImpl(KEY_DATE_MAX, 20, false);
    }
}
