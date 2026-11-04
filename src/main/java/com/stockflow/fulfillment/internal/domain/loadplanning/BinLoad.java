package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.List;

/** The earlier solver's {@code (container, placements, used_volume, used_weight)} tuple: one packed vehicle. */
record BinLoad(Bin bin, List<BoxPlacement> placements, double usedVolume) {

    static BinLoad of(Bin bin, List<BoxPlacement> placements) {
        double v = 0.0;
        for (BoxPlacement p : placements) {
            v += p.volume();
        }
        return new BinLoad(bin, List.copyOf(placements), v);
    }

    double usedWeight() {
        double kg = 0.0;
        for (BoxPlacement p : placements) {
            kg += p.weightKg();
        }
        return kg;
    }

    double fill() {
        return usedVolume / bin.volume();
    }
}
