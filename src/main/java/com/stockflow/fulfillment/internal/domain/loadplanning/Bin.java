package com.stockflow.fulfillment.internal.domain.loadplanning;

/**
 * The earlier solver's {@code Container}: one vehicle's cargo box. {@code w} is across ({@code x}), {@code h} up
 * ({@code y}), {@code d} along the length toward the door ({@code z}).
 *
 * <p>Unlike the original, the payload is always a real cap (it read 0 as "no limit") and the count is always finite
 * (it read null as "as many as needed"): BR-10 and BR-12 make both hard.</p>
 */
final class Bin {

    final VehicleSpec spec;
    final double w;
    final double h;
    final double d;
    final double payloadKg;
    final double gussetMm;
    /** The door opening, x by y; infinite where the type sets no limit. */
    final double doorW;
    final double doorH;

    Bin(VehicleSpec spec) {
        this.spec = spec;
        this.w = spec.innerWidthMm();
        this.h = spec.innerHeightMm();
        this.d = spec.innerLengthMm();
        this.payloadKg = spec.maxPayloadKg().doubleValue();
        this.gussetMm = spec.gussetMm();
        this.doorW = spec.doorWidthMm() == null ? Double.POSITIVE_INFINITY : spec.doorWidthMm();
        this.doorH = spec.doorHeightMm() == null ? Double.POSITIVE_INFINITY : spec.doorHeightMm();
    }

    /** Whether a carton laid with these placed sizes passes the door (it enters along z). */
    boolean passesDoor(double w, double h) {
        return w <= doorW + BlockFiller.TOL && h <= doorH + BlockFiller.TOL;
    }

    double volume() {
        return w * h * d;
    }

    boolean sameShape(Bin other) {
        return w == other.w && h == other.h && d == other.d && payloadKg == other.payloadKg
                && gussetMm == other.gussetMm && spec.kind() == other.spec.kind() && doorW == other.doorW
                && doorH == other.doorH;
    }
}
