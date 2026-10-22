package com.stockflow.inventory.internal.domain;

import com.stockflow.inventory.api.ItemLogistics;
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
 * The packaging rule chain of SCRUM-287 (decisions LP2-2 to LP2-5), as a pure function of an item's logistics and
 * its units of measure. No clock, no randomness, no I/O: the same input gives the same answer, which the load
 * estimate relies on. {@link com.stockflow.inventory.api.PackagingSpecResolver} documents the contract.
 *
 * <p><b>Units.</b> Sizes are stored in centimetres and weights in kilograms; the carton is reported in whole
 * millimetres, rounded <i>up</i> so a carton never comes out smaller than it is. Weights keep three decimals.</p>
 *
 * <p><b>The item's box (level 3).</b> {@code package*} on the item describe the shipped package, one box;
 * {@code packageCount} boxes make one unit. A box weighs {@code packageWeightKg} when the item declares it,
 * otherwise the unit weight shared across the boxes ({@code weightKg / packageCount}). Each unit is then a "carton"
 * of {@code packageCount} identical boxes (LP2-5), so there is never a remainder at this level.</p>
 *
 * <p><b>The partial carton (LP2-4).</b> With a packing unit of factor F and a quantity that is not a multiple of F,
 * the remainder r is one carton of the full size. Its weight is the units' own weight plus the carton's packaging:
 * {@code r * unitWeight + max(0, cartonWeight - F * unitWeight)} where unitWeight is the item's unit weight. The
 * packaging weight is not a field of the data model: it is what the full carton weighs beyond its F units. It is
 * never above the full carton's weight. Without a unit weight the carton's weight is shared pro rata
 * ({@code cartonWeight * r / F}) and the line carries a {@code UNIT_WEIGHT_UNKNOWN} warning.</p>
 */
public final class PackagingRules {

    private static final int MM_PER_CM = 10;

    private PackagingRules() {
    }

    /**
     * @param item   the item's id, SKU and logistics
     * @param units  its units of measure, base unit included (the base row's own size and weight are ignored: they
     *               are the item's logistics)
     * @param quantity in base units, at least 1
     */
    public static PackagingSpec resolve(UUID itemId, String sku, ItemLogistics item, List<UomConversion> units,
                                        int quantity) {
        List<SpecWarning> warnings = new ArrayList<>();
        UomConversion shipping = units.stream().filter(u -> u.defaultShipping() && u.active()).findFirst()
                .orElse(null);

        if (shipping != null) {
            PackagingSpec spec;
            if (shipping.base()) {
                // "Ship loose": the item's own box, or its unit when no box is declared.
                List<SpecWarning> own = new ArrayList<>(warnings);
                spec = itemBox(itemId, sku, item, quantity, RuleLevel.DEFAULT_SHIPPING_UNIT, own);
                if (spec == null) {
                    spec = itemUnit(itemId, sku, item, quantity, RuleLevel.DEFAULT_SHIPPING_UNIT, own);
                }
                if (spec == null) {
                    warnings.add(new SpecWarning(WarningCode.SHIPPING_UNIT_INCOMPLETE, shipping.unitOfMeasure(),
                            missingUnitFields(item), "The default shipping unit " + shipping.unitOfMeasure()
                            + " (the item's own box or unit) has no full size and weight; the next level was used"));
                }
            } else {
                spec = packingUnit(itemId, sku, item, shipping, quantity, RuleLevel.DEFAULT_SHIPPING_UNIT,
                        WarningCode.SHIPPING_UNIT_INCOMPLETE, warnings);
            }
            if (spec != null) {
                return spec;
            }
        }

        List<UomConversion> packing = units.stream()
                .filter(u -> !u.base() && u.active() && (shipping == null || !u.id().equals(shipping.id())))
                .sorted(Comparator.comparingInt(UomConversion::factor).reversed()
                        .thenComparing(UomConversion::unitOfMeasure))
                .toList();
        for (UomConversion unit : packing) {
            PackagingSpec spec = packingUnit(itemId, sku, item, unit, quantity, RuleLevel.LARGEST_PACKING_UNIT,
                    WarningCode.PACKING_UNIT_INCOMPLETE, warnings);
            if (spec != null) {
                return spec;
            }
        }

        PackagingSpec box = itemBox(itemId, sku, item, quantity, RuleLevel.ITEM_BOX, warnings);
        if (box != null) {
            return box;
        }
        PackagingSpec unit = itemUnit(itemId, sku, item, quantity, RuleLevel.ITEM_UNIT, warnings);
        if (unit != null) {
            return unit;
        }
        List<String> fields = missingUnitFields(item);
        return new PackagingSpec(sku, itemId, quantity, Status.MISSING, null, null, 0, 0, null, null, 0,
                List.copyOf(warnings),
                new SpecMissing("PACKAGING_MISSING", fields,
                        "Item " + sku + " has no size and weight for any packaging level; missing on the item's unit: "
                                + String.join(", ", fields)));
    }

    // ---- levels 1 and 2: a packing unit --------------------------------------------------------------------------

    private static PackagingSpec packingUnit(UUID itemId, String sku, ItemLogistics item, UomConversion unit,
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
            partial = new PartialCarton(remainder, partialWeight(item, unit, remainder, warnings));
        }
        CartonSpec carton = carton(unit.lengthCm(), unit.widthCm(), unit.heightCm(), scale(unit.weightKg()),
                item.rotatable());
        return new PackagingSpec(sku, itemId, quantity, Status.RESOLVED, level, unit.unitOfMeasure(), factor, full,
                partial, carton, 1, List.copyOf(warnings), null);
    }

    private static BigDecimal partialWeight(ItemLogistics item, UomConversion unit, int remainder,
                                            List<SpecWarning> warnings) {
        BigDecimal cartonWeight = unit.weightKg();
        BigDecimal weight;
        if (item.weightKg() == null) {
            warnings.add(new SpecWarning(WarningCode.UNIT_WEIGHT_UNKNOWN, unit.unitOfMeasure(), List.of("weightKg"),
                    "The item has no unit weight; the partial carton weighs the full carton pro rata"));
            weight = cartonWeight.multiply(BigDecimal.valueOf(remainder))
                    .divide(BigDecimal.valueOf(unit.factor()), 3, RoundingMode.HALF_UP);
        } else {
            BigDecimal packaging = cartonWeight.subtract(item.weightKg().multiply(BigDecimal.valueOf(unit.factor())))
                    .max(BigDecimal.ZERO);
            weight = item.weightKg().multiply(BigDecimal.valueOf(remainder)).add(packaging);
        }
        return scale(weight.min(cartonWeight));
    }

    // ---- level 3: the item's box ---------------------------------------------------------------------------------

    private static PackagingSpec itemBox(UUID itemId, String sku, ItemLogistics item, int quantity, RuleLevel level,
                                         List<SpecWarning> warnings) {
        BigDecimal weight = boxWeight(item);
        List<String> missing = new ArrayList<>(missing(item.packageLengthCm(), item.packageWidthCm(),
                item.packageHeightCm()).stream().map(f -> "package" + capitalise(f)).toList());
        boolean declared = missing.size() < 3;
        if (weight == null) {
            missing.add("packageWeightKg");
        }
        if (!missing.isEmpty()) {
            // A box nobody declared is not news; a half-declared one is a data problem worth showing.
            if (declared) {
                warnings.add(new SpecWarning(WarningCode.ITEM_BOX_INCOMPLETE, item.unitOfMeasure(), missing,
                        "The item's box lacks " + String.join(", ", missing) + "; the next level was used"));
            }
            return null;
        }
        CartonSpec carton = carton(item.packageLengthCm(), item.packageWidthCm(), item.packageHeightCm(), weight,
                item.rotatable());
        return new PackagingSpec(sku, itemId, quantity, Status.RESOLVED, level, item.unitOfMeasure(), 1, quantity,
                null, carton, item.packageCount(), List.copyOf(warnings), null);
    }

    private static BigDecimal boxWeight(ItemLogistics item) {
        if (item.packageWeightKg() != null) {
            return scale(item.packageWeightKg());
        }
        if (item.weightKg() != null) {
            return item.weightKg().divide(BigDecimal.valueOf(item.packageCount()), 3, RoundingMode.HALF_UP);
        }
        return null;
    }

    // ---- level 4: the item's unit --------------------------------------------------------------------------------

    private static PackagingSpec itemUnit(UUID itemId, String sku, ItemLogistics item, int quantity, RuleLevel level,
                                          List<SpecWarning> warnings) {
        if (!missingUnitFields(item).isEmpty()) {
            return null;
        }
        List<SpecWarning> all = new ArrayList<>(warnings);
        if (item.packageCount() > 1) {
            all.add(new SpecWarning(WarningCode.PACKAGE_COUNT_IGNORED, item.unitOfMeasure(),
                    List.of("packageCount"), "The item ships as " + item.packageCount()
                    + " boxes but its box size is unknown; its unit size was used for the whole unit"));
        }
        CartonSpec carton = carton(item.lengthCm(), item.widthCm(), item.heightCm(), scale(item.weightKg()),
                item.rotatable());
        return new PackagingSpec(sku, itemId, quantity, Status.RESOLVED, level, item.unitOfMeasure(), 1, quantity,
                null, carton, 1, List.copyOf(all), null);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static List<String> missingUnitFields(ItemLogistics item) {
        return missing(item.lengthCm(), item.widthCm(), item.heightCm(), item.weightKg());
    }

    /** The names of the empty ones among length, width, height[, weight], in that order. */
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

    private static CartonSpec carton(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightKg,
                                     boolean rotatable) {
        return new CartonSpec(mm(lengthCm), mm(widthCm), mm(heightCm), weightKg, rotatable);
    }

    /** Centimetres to whole millimetres, rounded up. */
    static int mm(BigDecimal cm) {
        return cm.movePointRight(1).setScale(0, RoundingMode.CEILING).intValueExact();
    }

    private static BigDecimal scale(BigDecimal kg) {
        return kg.setScale(3, RoundingMode.HALF_UP);
    }

    private static String capitalise(String field) {
        return Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }
}
