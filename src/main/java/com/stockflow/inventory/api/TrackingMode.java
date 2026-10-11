package com.stockflow.inventory.api;

public enum TrackingMode {
    NONE,
    LOT,
    SERIAL,
    LOT_SERIAL;

    public boolean lotTracked() {
        return this == LOT || this == LOT_SERIAL;
    }

    public boolean serialTracked() {
        return this == SERIAL || this == LOT_SERIAL;
    }

    public static TrackingMode of(boolean lot, boolean serial) {
        return lot ? (serial ? LOT_SERIAL : LOT) : (serial ? SERIAL : NONE);
    }
}
