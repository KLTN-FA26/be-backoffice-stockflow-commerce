package com.stockflow.inventory.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * How one sales-order line of a SKU is packed for load planning: what one carton measures and weighs, how many
 * full cartons there are, and the remainder (SCRUM-287, decisions LP2-2 to LP2-5). Advisory: it is computed from
 * the item master data, not from what a packer will really seal.
 *
 * <p>{@code quantity} is in base units (docs 01 BR-09). With {@code status} {@code RESOLVED} the line is
 * {@code fullCartons} cartons of {@code carton}, plus {@code partialCarton} when the quantity is not a whole number
 * of {@code factor}: that remainder is counted as one carton of the same size (LP2-4), so a solver places
 * {@code totalCartons()} cartons and gives the last one {@code partialCarton.weightKg}.</p>
 *
 * <p>When {@code kitParts} is above 1 (level {@link RuleLevel#ITEM_BOX} with {@code packageCount} above 1) a
 * "carton" is one unit shipped as a kit of {@code kitParts} boxes, each measuring and weighing what {@code carton}
 * says; the line is then {@code totalCartons() * kitParts} boxes, loaded together (LP2-5).</p>
 *
 * <p>With status {@code MISSING} no level had a full size and weight, even the item's own (LP2-3): everything but
 * {@code sku}, {@code inventoryItemId}, {@code quantity}, {@code warnings} and {@code missing} is empty. Nothing is
 * invented, the caller blocks the line and points at the item.</p>
 *
 * <p>{@code warnings} lists every level the resolver skipped because its data was incomplete, and other facts the
 * caller may show (an unknown unit weight).</p>
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
        /** The default shipping unit lacks a size or weight; the next level was used. */
        SHIPPING_UNIT_INCOMPLETE,
        /** A packing unit lacks a size or weight; a smaller one, or a lower level, was used. */
        PACKING_UNIT_INCOMPLETE,
        /** The item declares part of its box but not all of it; the box was not used. */
        ITEM_BOX_INCOMPLETE,
        /** The item has no unit weight, so the partial carton's weight is the carton's pro rata. */
        UNIT_WEIGHT_UNKNOWN,
        /** The unit is declared lighter than the units it holds; the partial carton was capped at its weight. */
        CARTON_WEIGHT_BELOW_CONTENTS,
        /** The item's unit size was used although it ships as several boxes; they were counted as one. */
        PACKAGE_COUNT_IGNORED
    }

    /** {@code unitOfMeasure} and {@code fields} name what the warning is about; either may be empty. */
    public record SpecWarning(WarningCode code, String unitOfMeasure, List<String> fields, String message) {
    }

    /** Why no level could be used: {@code fields} are the item's unit fields that are empty. */
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
