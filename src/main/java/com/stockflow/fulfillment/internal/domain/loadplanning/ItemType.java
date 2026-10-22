package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Cartons that are interchangeable for loading: same part code, sizes, rotatable flag and priority. The solver works
 * on these with a quantity (the earlier solver's demand line), which is what lets the block filler stack identical
 * cartons as one block; the planner hands the concrete cartons back out only when the plan is final.
 */
final class ItemType {

    final int index;
    /** The earlier solver's {@code part_name}: what a kit names its members by. Empty when the carton names none. */
    final String itemKey;
    /** The heaviest of its cartons: the search checks the payload against it (see {@link LoadPlanner}). */
    final double weightKg;
    final boolean rotatable;
    /** Sizes along x, y and z when laid as measured (orientation 0). */
    final double w;
    final double h;
    final double d;
    /** The cartons of this type, as indexes into the request, in request order. */
    final List<Integer> cartons = new ArrayList<>();

    ItemType(int index, String itemKey, double weightKg, boolean rotatable, double w, double h,
             double d) {
        this.index = index;
        this.itemKey = Objects.requireNonNullElse(itemKey, "");
        this.weightKg = weightKg;
        this.rotatable = rotatable;
        this.w = w;
        this.h = h;
        this.d = d;
    }

    Box box(int quantity) {
        return new Box(this, w, h, d, quantity);
    }

    @Override
    public String toString() {
        return "type" + index + "(" + itemKey + " " + w + "x" + h + "x" + d + ")";
    }
}
