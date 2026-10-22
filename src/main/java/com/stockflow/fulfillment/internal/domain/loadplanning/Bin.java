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

    Bin(VehicleSpec spec) {
        this.spec = spec;
        this.w = spec.innerWidthMm();
        this.h = spec.innerHeightMm();
        this.d = spec.innerLengthMm();
        this.payloadKg = spec.maxPayloadKg().doubleValue();
        this.gussetMm = spec.gussetMm();
    }

    double volume() {
        return w * h * d;
    }

    boolean sameShape(Bin other) {
        return w == other.w && h == other.h && d == other.d && payloadKg == other.payloadKg
                && gussetMm == other.gussetMm;
    }
}
