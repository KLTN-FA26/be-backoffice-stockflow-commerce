package com.stockflow.inventory.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * How one sales-order line of a SKU is packed for load planning: what one carton measures and weighs, how many
 * full cartons there are, and the remainder (SCRUM-287, decisions LP2-2 to LP2-4). Advisory: it is computed from
 * the item's carton unit, not from what a packer will really seal.
 *
 * <p>{@code quantity} is in base units (docs 01 BR-09). With {@code status} {@code RESOLVED} the line is
 * {@code fullCartons} cartons of {@code carton}, plus {@code partialCarton} when the quantity is not a whole number
 * of {@code factor}: that remainder is counted as one carton of the same size (LP2-4), so a solver places
 * {@code totalCartons()} cartons and gives the last one {@code partialCarton.weightKg}.</p>
 *
 * <p>{@code kitParts} is always 1 when resolved: a kit of several boxes per unit came from the item's shipped box, which
 * is gone with parcel shipping (V20261215000100; glossary §8). The field stays so the load estimate's readers keep
 * their shape; taking kits out of load planning is a change of its own.</p>
 *
 * <p>With status {@code MISSING} no carton had a full size and weight (LP2-3): everything but
 * {@code sku}, {@code inventoryItemId}, {@code quantity}, {@code warnings} and {@code missing} is empty. Nothing is
 * invented, the caller blocks the line and points at the item's carton.</p>
 *
 * <p>{@code warnings} lists every level the resolver skipped because its data was incomplete, and other facts the
 * caller may show.</p>
 */
public record PackagingSpec(
        String sku,
        UUID inventoryItemId,
        int quantity,
        Status status,
        RuleLevel level,
        String unitOfMeasure,
        int factor,
        long fullCartons,
        PartialCarton partialCarton,
        CartonSpec carton,
        int kitParts,
        List<SpecWarning> warnings,
        SpecMissing missing
) {

    public enum Status {
        RESOLVED,
        MISSING
    }

    /** One carton: length, width, height in whole millimetres (rounded up) and weight in kilograms. */
    public record CartonSpec(int lengthMm, int widthMm, int heightMm, BigDecimal weightKg, boolean rotatable) {
    }

    /** The remainder of the line: {@code units} base units in one full-size carton weighing {@code weightKg}. */
    public record PartialCarton(int units, BigDecimal weightKg) {
    }

    public enum WarningCode {
        /** The item's carton ({@link RuleLevel#DEFAULT_SHIPPING_UNIT}) lacks a size or weight. */
        SHIPPING_UNIT_INCOMPLETE,
        /** A packing unit lacks a size or weight; a smaller one, or a lower level, was used. */
        PACKING_UNIT_INCOMPLETE
    }

    /** {@code unitOfMeasure} and {@code fields} name what the warning is about; either may be empty. */
    public record SpecWarning(WarningCode code, String unitOfMeasure, List<String> fields, String message) {
    }

    /** Why no level could be used: {@code fields} are the carton's fields that are empty, none when there is no carton. */
    public record SpecMissing(String code, List<String> fields, String message) {
    }

    public boolean resolved() {
        return status == Status.RESOLVED;
    }

    /** Cartons to place: the full ones and the partial one. Zero when missing. */
    public long totalCartons() {
        return resolved() ? fullCartons + (partialCarton == null ? 0 : 1) : 0;
    }
}
