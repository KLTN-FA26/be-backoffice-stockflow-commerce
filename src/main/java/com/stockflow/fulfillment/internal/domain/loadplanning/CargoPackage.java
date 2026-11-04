package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A carton to be loaded (kltn-docs 09 §3, "Danh sách kiện cần xếp"): a sealed parcel, or a carton estimated from a
 * sales order's packaging before it is packed.
 *
 * <p>The three sizes are as measured; which of them ends up along which axis is the {@link Orientation} the planner
 * chooses. A carton without its sizes or weight never reaches the planner - the docs block the calculation and send
 * it back to be measured - so every size and the weight must be positive.</p>
 *
 * @param ref       unique per carton within a request and opaque to the planner, handed back unchanged on its
 *                  placement or unplaced entry: a parcel's package id, or {@code <lineId>#<n>} for the n-th carton
 *                  estimated from a line
 * @param sourceRef what the carton comes from: the package id again for a parcel, the estimate line id for an
 *                  estimated carton. Null means the same as {@code ref}
 * @param lengthMm  along the vehicle's length ({@code z}) when laid as measured
 * @param widthMm   across the vehicle ({@code x}) when laid as measured
 * @param heightMm  vertical ({@code y}) when laid as measured
 * @param rotatable may lie in any of the six orientations; otherwise it keeps its placing orientation (BR-10)
 * @param partCode  what it is (a SKU, or a kit part such as {@code SKU/2}), or null. A {@link KitSpec} names its
 *                  members by it
 * @param kitGroup  the physical set it was sealed in (a package's {@code kit_group_id}), or null; carried for the
 *                  caller
 * @param groupKey  the order, shipment or transfer it belongs to, or null. Carried for the caller; the planner does
 *                  not keep a group in one vehicle, since a shipment may span vehicles (LP-3, design v2 Q7)
 * @param fill      lower-priority cargo that only tops up the space the main cargo leaves and never needs a vehicle
 *                  of its own (docs 09 §4.2 "Ưu tiên + hàng chèn")
 */
public record CargoPackage(String ref, String sourceRef, int lengthMm, int widthMm, int heightMm, BigDecimal weightKg,
                           boolean rotatable, String partCode, String kitGroup, String groupKey, boolean fill) {

    public CargoPackage {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(weightKg, "weightKg");
        sourceRef = sourceRef == null ? ref : sourceRef;
        if (lengthMm <= 0 || widthMm <= 0 || heightMm <= 0) {
            throw new IllegalArgumentException("carton " + ref + " has a size that is not positive");
        }
        if (weightKg.signum() <= 0) {
            throw new IllegalArgumentException("carton " + ref + " has a weight that is not positive");
        }
    }

    /** A main-cargo parcel with no part code, set or group: the common case in tests. */
    public static CargoPackage of(String ref, int lengthMm, int widthMm, int heightMm, BigDecimal weightKg,
                                  boolean rotatable) {
        return new CargoPackage(ref, null, lengthMm, widthMm, heightMm, weightKg, rotatable, null, null, null, false);
    }
}
