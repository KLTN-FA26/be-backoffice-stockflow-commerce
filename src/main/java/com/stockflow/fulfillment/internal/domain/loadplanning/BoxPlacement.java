package com.stockflow.fulfillment.internal.domain.loadplanning;

/**
 * The earlier solver's {@code BoxPlacement}: one carton of a type at a corner, with the sizes it occupies. Compared by
 * identity, as the original compared placements with {@code id()}.
 */
final class BoxPlacement {

    final double x;
    final double y;
    final double z;
    final double w;
    final double h;
    final double d;
    /** Null for a reserved corner block, which is never cargo. */
    final ItemType type;
    /** Lower-priority cargo that only tops up the space the main cargo left (docs "hàng chèn"). */
    final boolean fill;
    /** Scratch mark of {@link SupportGrid#support}: the last query that counted this placement. */
    long seenBy;

    BoxPlacement(double x, double y, double z, double w, double h, double d, ItemType type, boolean fill) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
        this.h = h;
        this.d = d;
        this.type = type;
        this.fill = fill;
    }

    double volume() {
        return w * h * d;
    }

    double weightKg() {
        return type == null ? 0.0 : type.weightKg;
    }

    String partName() {
        return type == null ? "" : type.itemKey;
    }

    double[] region() {
        return new double[] {x, y, z, w, h, d};
    }
}
