package com.stockflow.inventory.internal.domain;

import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpec.WarningCode;
import com.stockflow.inventory.api.RuleLevel;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.inventory.api.UomConversion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule chain of SCRUM-287 (LP2-2 to LP2-5) as a table: which level wins for which data, the remainder carton,
 * kits, the missing result, determinism. Pure: no Spring, no database.
 */
class PackagingRulesTest {

    private static final UUID ITEM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // ---- builders ----------------------------------------------------------------------------------------------

    private static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    /** An item whose unit is 10 x 8 x 6 cm and 0.5 kg; unit 12 x 9 x 7 cm box is added by {@link #withBox}. */
    private static ItemLogistics item(String weight, String l, String w, String h, String pWeight, String pl,
                                      String pw, String ph, int packageCount, boolean rotatable) {
        return new ItemLogistics("EACH", null, d(weight), d(l), d(w), d(h), d(pWeight), d(pl), d(pw), d(ph),
                packageCount, 1, StorageClass.NORMAL, false, null, false, rotatable);
    }

    private static ItemLogistics unitOnly() {
        return item("0.5", "10", "8", "6", null, null, null, null, 1, true);
    }

    private static ItemLogistics withBox() {
        return item("0.5", "10", "8", "6", "0.7", "12", "9", "7", 1, true);
    }

    private static UomConversion base() {
        return new UomConversion(UUID.randomUUID(), "SKU", "EACH", 1, true, null, null, null, null, true, true, 0,
                false);
    }

    private static UomConversion baseShipping() {
        return new UomConversion(UUID.randomUUID(), "SKU", "EACH", 1, true, null, null, null, null, true, true, 0,
                true);
    }

    private static UomConversion pack(String code, int factor, String weight, String l, String w, String h,
                                      boolean active, boolean shipping) {
        return new UomConversion(UUID.nameUUIDFromBytes(code.getBytes()), "SKU", code, factor, false, d(weight), d(l),
                d(w), d(h), false, active, 0, shipping);
    }

    private static UomConversion carton(String code, int factor) {
        return pack(code, factor, "10.0", "50", "40", "30", true, false);
    }

    private static PackagingSpec resolve(ItemLogistics item, int qty, UomConversion... units) {
        List<UomConversion> all = new java.util.ArrayList<>();
        all.add(base());
        all.addAll(List.of(units));
        return PackagingRules.resolve(ITEM, "SKU", item, all, qty);
    }

    // ---- level 1: the default shipping unit ----------------------------------------------------------------------

    @Test
    @DisplayName("level 1: the default shipping unit wins over a larger packing unit")
    void defaultShippingWins() {
        PackagingSpec spec = resolve(withBox(), 100, pack("PALLET", 100, "55", "120", "100", "150", true, false),
                pack("BOX", 10, "5.5", "30", "20", "20", true, true));
        assertThat(spec.level()).isEqualTo(RuleLevel.DEFAULT_SHIPPING_UNIT);
        assertThat(spec.unitOfMeasure()).isEqualTo("BOX");
        assertThat(spec.factor()).isEqualTo(10);
        assertThat(spec.fullCartons()).isEqualTo(10);
        assertThat(spec.partialCarton()).isNull();
        assertThat(spec.carton().lengthMm()).isEqualTo(300);
        assertThat(spec.carton().weightKg()).isEqualByComparingTo("5.5");
        assertThat(spec.warnings()).isEmpty();
    }

    @Test
    @DisplayName("level 1 incomplete: skipped with a warning, level 2 is used")
    void incompleteShippingUnitFallsThrough() {
        PackagingSpec spec = resolve(withBox(), 30, pack("BOX", 10, null, "30", "20", "20", true, true),
                carton("CTN", 6));
        assertThat(spec.level()).isEqualTo(RuleLevel.LARGEST_PACKING_UNIT);
        assertThat(spec.unitOfMeasure()).isEqualTo("CTN");
        assertThat(spec.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(WarningCode.SHIPPING_UNIT_INCOMPLETE);
            assertThat(w.unitOfMeasure()).isEqualTo("BOX");
            assertThat(w.fields()).containsExactly("weightKg");
        });
    }

    @Test
    @DisplayName("a retired unit marked in old data is not the default shipping unit")
    void retiredShippingUnitIsIgnored() {
        PackagingSpec spec = resolve(withBox(), 10, pack("OLD", 10, "5", "30", "20", "20", false, true));
        assertThat(spec.level()).isEqualTo(RuleLevel.ITEM_BOX);
        assertThat(spec.warnings()).isEmpty();
    }

    @Test
    @DisplayName("level 1 on the base unit: ship loose in the item's box, ahead of a larger packing unit")
    void baseUnitAsDefaultShipping() {
        List<UomConversion> units = List.of(baseShipping(),
                pack("PALLET", 100, "55", "120", "100", "150", true, false));
        PackagingSpec spec = PackagingRules.resolve(ITEM, "SKU", withBox(), units, 7);
        assertThat(spec.level()).isEqualTo(RuleLevel.DEFAULT_SHIPPING_UNIT);
        assertThat(spec.unitOfMeasure()).isEqualTo("EACH");
        assertThat(spec.fullCartons()).isEqualTo(7);
        assertThat(spec.carton().lengthMm()).isEqualTo(120);
    }

    @Test
    @DisplayName("level 1 on the base unit without any size: skipped with a warning")
    void baseUnitAsDefaultShippingWithoutData() {
        List<UomConversion> units = List.of(baseShipping(), carton("CTN", 10));
        PackagingSpec spec = PackagingRules.resolve(ITEM, "SKU",
                item("0.5", null, null, null, null, null, null, null, 1, true), units, 25);
        assertThat(spec.level()).isEqualTo(RuleLevel.LARGEST_PACKING_UNIT);
        assertThat(spec.warnings()).extracting(PackagingSpec.SpecWarning::code)
                .containsExactly(WarningCode.SHIPPING_UNIT_INCOMPLETE);
    }

    // ---- level 2: the largest packing unit with a full size ------------------------------------------------------

    @Test
    @DisplayName("level 2: the unit with the largest factor, whatever the order it is declared in")
    void largestPackingUnit() {
        PackagingSpec spec = resolve(withBox(), 1000, carton("SMALL", 10), carton("HUGE", 500), carton("MID", 100));
        assertThat(spec.level()).isEqualTo(RuleLevel.LARGEST_PACKING_UNIT);
        assertThat(spec.unitOfMeasure()).isEqualTo("HUGE");
        assertThat(spec.fullCartons()).isEqualTo(2);
    }

    @Test
    @DisplayName("level 2: a larger unit without a size is skipped (warned), the next largest is used; retired ones never")
    void incompletePackingUnitSkipped() {
        PackagingSpec spec = resolve(withBox(), 100,
                pack("HUGE", 500, "30", null, "40", "30", true, false),
                pack("RETIRED", 400, "30", "50", "40", "30", false, false),
                carton("MID", 100), carton("SMALL", 10));
        assertThat(spec.unitOfMeasure()).isEqualTo("MID");
        assertThat(spec.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(WarningCode.PACKING_UNIT_INCOMPLETE);
            assertThat(w.unitOfMeasure()).isEqualTo("HUGE");
            assertThat(w.fields()).containsExactly("lengthCm");
        });
    }

    @Test
    @DisplayName("level 2: equal factors are ordered by code, so the answer is stable")
    void equalFactorsTieBreakByCode() {
        assertThat(resolve(withBox(), 10, carton("B", 10), carton("A", 10)).unitOfMeasure()).isEqualTo("A");
        assertThat(resolve(withBox(), 10, carton("A", 10), carton("B", 10)).unitOfMeasure()).isEqualTo("A");
    }

    // ---- the remainder carton (LP2-4) ---------------------------------------------------------------------------

    @Test
    @DisplayName("cartons = ceil(qty / factor): the remainder is one full-size carton, weighed pro rata plus packaging")
    void remainderCarton() {
        // Item unit 0.5 kg; a carton of 10 weighs 6.0 kg: 5.0 for the units, 1.0 for the packaging.
        PackagingSpec spec = resolve(withBox(), 27, pack("CTN", 10, "6.0", "50", "40", "30", true, false));
        assertThat(spec.fullCartons()).isEqualTo(2);
        assertThat(spec.partialCarton().units()).isEqualTo(7);
        assertThat(spec.partialCarton().weightKg()).isEqualByComparingTo("4.500");   // 7 * 0.5 + 1.0
        assertThat(spec.totalCartons()).isEqualTo(3);
        assertThat(spec.carton().weightKg()).isEqualByComparingTo("6.000");
    }

    @Test
    @DisplayName("an exact multiple has no partial carton; fewer units than one carton is a single partial one")
    void exactAndShort() {
        assertThat(resolve(withBox(), 20, carton("CTN", 10)).partialCarton()).isNull();
        PackagingSpec few = resolve(withBox(), 3, carton("CTN", 10));
        assertThat(few.fullCartons()).isZero();
        assertThat(few.partialCarton().units()).isEqualTo(3);
        assertThat(few.totalCartons()).isEqualTo(1);
    }

    @Test
    @DisplayName("without a unit weight the partial carton is the carton's weight pro rata, with a warning")
    void remainderWithoutUnitWeight() {
        PackagingSpec spec = resolve(item(null, "10", "8", "6", "0.7", "12", "9", "7", 1, true), 5,
                pack("CTN", 8, "4.0", "50", "40", "30", true, false));
        assertThat(spec.partialCarton().weightKg()).isEqualByComparingTo("2.500");   // 4.0 * 5 / 8
        assertThat(spec.warnings()).extracting(PackagingSpec.SpecWarning::code)
                .containsExactly(WarningCode.UNIT_WEIGHT_UNKNOWN);
    }

    @Test
    @DisplayName("a carton lighter than its units adds no packaging and the partial never outweighs a full carton")
    void remainderNeverAboveFullCarton() {
        // 10 units of 0.5 kg = 5.0 kg but the carton is declared 4.0 kg.
        PackagingSpec spec = resolve(withBox(), 19, pack("CTN", 10, "4.0", "50", "40", "30", true, false));
        assertThat(spec.partialCarton().weightKg()).isEqualByComparingTo("4.000");
    }

    // ---- level 3: the item's box and kits (LP2-5) ----------------------------------------------------------------

    @Test
    @DisplayName("level 3: the item's box, one carton per unit, millimetres rounded up")
    void itemBox() {
        PackagingSpec spec = resolve(item("0.5", "10", "8", "6", "0.7", "12.34", "9", "7", 1, true), 12);
        assertThat(spec.level()).isEqualTo(RuleLevel.ITEM_BOX);
        assertThat(spec.unitOfMeasure()).isEqualTo("EACH");
        assertThat(spec.factor()).isEqualTo(1);
        assertThat(spec.fullCartons()).isEqualTo(12);
        assertThat(spec.partialCarton()).isNull();
        assertThat(spec.kitParts()).isEqualTo(1);
        assertThat(spec.carton().lengthMm()).isEqualTo(124);    // 123.4 up
        assertThat(spec.carton().widthMm()).isEqualTo(90);
        assertThat(spec.carton().heightMm()).isEqualTo(70);
        assertThat(spec.carton().weightKg()).isEqualByComparingTo("0.700");
        assertThat(spec.carton().rotatable()).isTrue();
    }

    @Test
    @DisplayName("level 3 with package_count > 1: a kit of N same-size boxes per unit")
    void kit() {
        PackagingSpec spec = resolve(item("3.0", "60", "40", "30", "1.5", "62", "42", "32", 3, false), 4);
        assertThat(spec.level()).isEqualTo(RuleLevel.ITEM_BOX);
        assertThat(spec.kitParts()).isEqualTo(3);
        assertThat(spec.fullCartons()).isEqualTo(4);
        assertThat(spec.carton().weightKg()).isEqualByComparingTo("1.500");
        assertThat(spec.carton().rotatable()).isFalse();
    }

    @Test
    @DisplayName("a kit box without its own weight takes the unit weight shared across the boxes")
    void kitBoxWeightFromUnitWeight() {
        PackagingSpec spec = resolve(item("3.0", "60", "40", "30", null, "62", "42", "32", 3, true), 1);
        assertThat(spec.level()).isEqualTo(RuleLevel.ITEM_BOX);
        assertThat(spec.carton().weightKg()).isEqualByComparingTo("1.000");
    }

    @Test
    @DisplayName("a half-declared box is skipped with a warning; a box nobody declared is skipped silently")
    void incompleteBox() {
        PackagingSpec half = resolve(item("0.5", "10", "8", "6", "0.7", "12", null, "7", 1, true), 2);
        assertThat(half.level()).isEqualTo(RuleLevel.ITEM_UNIT);
        assertThat(half.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(WarningCode.ITEM_BOX_INCOMPLETE);
            assertThat(w.fields()).containsExactly("packageWidthCm");
        });
        assertThat(resolve(unitOnly(), 2).warnings()).isEmpty();
    }

    // ---- level 4: the item's unit ------------------------------------------------------------------------------------

    @Test
    @DisplayName("level 4: the item's own unit size and weight")
    void itemUnit() {
        PackagingSpec spec = resolve(unitOnly(), 9);
        assertThat(spec.level()).isEqualTo(RuleLevel.ITEM_UNIT);
        assertThat(spec.fullCartons()).isEqualTo(9);
        assertThat(spec.carton().lengthMm()).isEqualTo(100);
        assertThat(spec.carton().weightKg()).isEqualByComparingTo("0.500");
        assertThat(spec.kitParts()).isEqualTo(1);
    }

    @Test
    @DisplayName("level 4 with package_count > 1 says the count was not applied")
    void unitLevelIgnoresPackageCount() {
        PackagingSpec spec = resolve(item("0.5", "10", "8", "6", null, null, null, null, 2, true), 1);
        assertThat(spec.level()).isEqualTo(RuleLevel.ITEM_UNIT);
        assertThat(spec.kitParts()).isEqualTo(1);
        assertThat(spec.warnings()).extracting(PackagingSpec.SpecWarning::code)
                .containsExactly(WarningCode.PACKAGE_COUNT_IGNORED);
    }

    // ---- missing (LP2-3) ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("nothing complete at any level: MISSING with the empty fields, no invented defaults")
    void missing() {
        PackagingSpec spec = resolve(item("0.5", "10", null, "6", null, null, null, null, 1, true), 5,
                pack("CTN", 10, null, null, null, null, true, false));
        assertThat(spec.status()).isEqualTo(PackagingSpec.Status.MISSING);
        assertThat(spec.resolved()).isFalse();
        assertThat(spec.level()).isNull();
        assertThat(spec.carton()).isNull();
        assertThat(spec.totalCartons()).isZero();
        assertThat(spec.missing().code()).isEqualTo("PACKAGING_MISSING");
        assertThat(spec.missing().fields()).containsExactly("widthCm");
        assertThat(spec.sku()).isEqualTo("SKU");
        assertThat(spec.inventoryItemId()).isEqualTo(ITEM);
        assertThat(spec.quantity()).isEqualTo(5);
        assertThat(spec.warnings()).extracting(PackagingSpec.SpecWarning::code)
                .containsExactly(WarningCode.PACKING_UNIT_INCOMPLETE);
    }

    @Test
    @DisplayName("an item with no data at all is MISSING on all four fields")
    void missingEverything() {
        PackagingSpec spec = resolve(item(null, null, null, null, null, null, null, null, 1, true), 1);
        assertThat(spec.status()).isEqualTo(PackagingSpec.Status.MISSING);
        assertThat(spec.missing().fields()).containsExactly("lengthCm", "widthCm", "heightCm", "weightKg");
    }

    // ---- determinism -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the same input gives the same answer, whatever order the units arrive in")
    void deterministic() {
        UomConversion a = carton("A", 10);
        UomConversion b = pack("B", 25, "9", "40", "40", "40", true, false);
        UomConversion c = pack("C", 25, null, "40", "40", "40", true, false);
        PackagingSpec first = resolve(withBox(), 63, a, b, c);
        assertThat(resolve(withBox(), 63, a, b, c)).isEqualTo(first);
        assertThat(resolve(withBox(), 63, c, b, a)).isEqualTo(first);
        assertThat(resolve(withBox(), 63, b, c, a)).isEqualTo(first);
        assertThat(first.unitOfMeasure()).isEqualTo("B");
    }
}
