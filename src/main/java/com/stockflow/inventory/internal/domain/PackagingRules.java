package com.stockflow.inventory.internal.domain;

import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpec.CartonSpec;
import com.stockflow.inventory.api.PackagingSpec.PartialCarton;
import com.stockflow.inventory.api.PackagingSpec.SpecMissing;
import com.stockflow.inventory.api.PackagingSpec.SpecWarning;
import com.stockflow.inventory.api.PackagingSpec.Status;
import com.stockflow.inventory.api.PackagingSpec.WarningCode;
import com.stockflow.inventory.api.RuleLevel;
import com.stockflow.inventory.api.UomConversion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The packaging rule chain of SCRUM-287 (decisions LP2-2 to LP2-4), as a pure function of an item's units of measure.
 * No clock, no randomness, no I/O: the same input gives the same answer, which the load estimate relies on.
 * {@link com.stockflow.inventory.api.PackagingSpecResolver} documents the contract.
 *
 * <p><b>The carton is the item's one active unit</b> (01 BR-03, {@code uk_uom_conversions_one_carton}): it is level
 * {@link com.stockflow.inventory.api.RuleLevel#DEFAULT_SHIPPING_UNIT}. The "largest packing unit" level is only reached
 * with several active units, which the database refuses; it is kept so the function stays total.</p>
 *
 * <p><b>Only a carton is measured.</b> The item has no size or weight of its own (V20261215000100, kltn-docs 01 BR-03,
 * C21), and the base unit has no row (V20261226000200). The levels that read the item's box and the
 * item's unit are gone with those columns: a SKU whose carton is not measured is MISSING, as 09 §4 step 129 lists it,
 * and nothing stands in for it.</p>
 *
 * <p><b>Units.</b> Sizes are stored in centimetres and weights in kilograms; the carton is reported in whole
 * millimetres, rounded <i>up</i> so a carton never comes out smaller than it is. Weights keep three decimals.</p>
 *
 * <p><b>The partial carton (LP2-4).</b> With a carton of factor F and a quantity that is not a multiple of F, the
 * remainder r is one carton of the full size weighing the full carton's weight pro rata, {@code cartonWeight * r / F}:
 * with no unit weight on record there is no better figure. Since every carton holds its standard count (01 §4.7) a
 * real order line is a whole number of cartons; the remainder is kept for an estimate asked in units.</p>
 */
public final class PackagingRules {

    private PackagingRules() {
    }

    /**
     * @param units    its packing units, active and retired
     * @param quantity in base units, at least 1
     * @param rotatable the item's flag, carried onto the carton
     */
    public static PackagingSpec resolve(UUID itemId, String sku, List<UomConversion> units, boolean rotatable,
                                        int quantity) {
        List<SpecWarning> warnings = new ArrayList<>();
        List<UomConversion> active = units.stream().filter(UomConversion::active).toList();
        // The item's one active unit is its carton (01 BR-03, uk_uom_conversions_one_carton) and the one it ships in.
        UomConversion shipping = active.size() == 1 ? active.getFirst() : null;

        if (shipping != null) {
            PackagingSpec spec = packingUnit(itemId, sku, shipping, rotatable, quantity,
                    RuleLevel.DEFAULT_SHIPPING_UNIT, WarningCode.SHIPPING_UNIT_INCOMPLETE, warnings);
            if (spec != null) {
                return spec;
            }
        }

        List<UomConversion> packing = active.stream()
                .filter(u -> shipping == null || !u.id().equals(shipping.id()))
                .sorted(Comparator.comparingInt(UomConversion::factor).reversed()
                        .thenComparing(UomConversion::unitOfMeasure))
                .toList();
        for (UomConversion unit : packing) {
            PackagingSpec spec = packingUnit(itemId, sku, unit, rotatable, quantity, RuleLevel.LARGEST_PACKING_UNIT,
                    WarningCode.PACKING_UNIT_INCOMPLETE, warnings);
            if (spec != null) {
                return spec;
            }
        }
        UomConversion carton = shipping != null ? shipping : packing.isEmpty() ? null : packing.getFirst();
        return missingSpec(itemId, sku, quantity, carton, warnings);
    }

    /** MISSING, naming what the carton lacks; with no active carton at all, nothing to name but the carton itself. */
    private static PackagingSpec missingSpec(UUID itemId, String sku, int quantity, UomConversion carton,
                                             List<SpecWarning> warnings) {
        List<String> fields = carton == null ? List.of()
                : missing(carton.lengthCm(), carton.widthCm(), carton.heightCm(), carton.weightKg());
        String message = carton == null
                ? "Item " + sku + " has no active carton unit to pack it in"
                : "The carton " + carton.unitOfMeasure() + " of " + sku + " lacks " + String.join(", ", fields);
        return new PackagingSpec(sku, itemId, quantity, Status.MISSING, null, null, 0, 0, null, null, 0,
                List.copyOf(warnings), new SpecMissing("PACKAGING_MISSING", fields, message));
    }

    private static PackagingSpec packingUnit(UUID itemId, String sku, UomConversion unit, boolean rotatable,
                                             int quantity, RuleLevel level, WarningCode skipped,
                                             List<SpecWarning> warnings) {
        List<String> missing = missing(unit.lengthCm(), unit.widthCm(), unit.heightCm(), unit.weightKg());
        if (!missing.isEmpty()) {
            warnings.add(new SpecWarning(skipped, unit.unitOfMeasure(), missing,
                    "Unit " + unit.unitOfMeasure() + " of " + sku + " lacks " + String.join(", ", missing)
                            + "; the next level was used"));
            return null;
        }
        int factor = unit.factor();
        long full = quantity / factor;
        int remainder = quantity % factor;
        PartialCarton partial = null;
        if (remainder > 0) {
            partial = new PartialCarton(remainder, unit.weightKg().multiply(BigDecimal.valueOf(remainder))
                    .divide(BigDecimal.valueOf(factor), 3, RoundingMode.HALF_UP));
        }
        CartonSpec carton = new CartonSpec(mm(unit.lengthCm()), mm(unit.widthCm()), mm(unit.heightCm()),
                scale(unit.weightKg()), rotatable);
        return new PackagingSpec(sku, itemId, quantity, Status.RESOLVED, level, unit.unitOfMeasure(), factor, full,
                partial, carton, 1, List.copyOf(warnings), null);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** The names of the empty ones among length, width, height, weight, in that order. */
    private static List<String> missing(BigDecimal... values) {
        String[] names = {"lengthCm", "widthCm", "heightCm", "weightKg"};
        List<String> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                out.add(names[i]);
            }
        }
        return out;
    }

    /** Centimetres to whole millimetres, rounded up. */
    public static int mm(BigDecimal cm) {
        return cm.movePointRight(1).setScale(0, RoundingMode.CEILING).intValueExact();
    }

    private static BigDecimal scale(BigDecimal kg) {
        return kg.setScale(3, RoundingMode.HALF_UP);
    }
}
