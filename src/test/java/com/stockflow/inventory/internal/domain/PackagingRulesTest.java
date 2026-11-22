package com.stockflow.inventory.internal.domain;

import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpec.WarningCode;
import com.stockflow.inventory.api.RuleLevel;
import com.stockflow.inventory.api.UomConversion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule chain of SCRUM-287 (LP2-2 to LP2-4) as a table: which carton wins for which data, the remainder carton,
 * the missing result, determinism. Only a carton is measured: the item has no size or weight to fall back on
 * (V20261215000100, kltn-docs 01 BR-03). Pure: no Spring, no database.
 */
class PackagingRulesTest {

    private static final UUID ITEM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // ---- builders ----------------------------------------------------------------------------------------------

    private static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static UomConversion base() {
        return new UomConversion(UUID.randomUUID(), "SKU", "EACH", 1, true, null, null, null, null, true, 0,
                false);
    }

    private static UomConversion baseShipping() {
        return new UomConversion(UUID.randomUUID(), "SKU", "EACH", 1, true, null, null, null, null, true, 0,
                true);
    }

    private static UomConversion pack(String code, int factor, String weight, String l, String w, String h,
                                      boolean active, boolean shipping) {
        return new UomConversion(UUID.nameUUIDFromBytes(code.getBytes()), "SKU", code, factor, false, d(weight), d(l),
                d(w), d(h), active, 0, shipping);
    }

    private static UomConversion carton(String code, int factor) {
        return pack(code, factor, "10.0", "50", "40", "30", true, false);
    }

    private static PackagingSpec resolve(int qty, UomConversion... units) {
        List<UomConversion> all = new java.util.ArrayList<>();
        all.add(base());
        all.addAll(List.of(units));
        return PackagingRules.resolve(ITEM, "SKU", all, true, qty);
    }

    // ---- level 1: the default shipping unit ----------------------------------------------------------------------

    @Test
    @DisplayName("level 1: the default shipping unit wins over a larger packing unit")
    void defaultShippingWins() {
        PackagingSpec spec = resolve(100, pack("PALLET", 100, "55", "120", "100", "150", true, false),
                pack("BOX", 10, "5.5", "30", "20", "20", true, true));
        assertThat(spec.level()).isEqualTo(RuleLevel.DEFAULT_SHIPPING_UNIT);
        assertThat(spec.unitOfMeasure()).isEqualTo("BOX");
        assertThat(spec.factor()).isEqualTo(10);
        assertThat(spec.fullCartons()).isEqualTo(10);
        assertThat(spec.partialCarton()).isNull();
        assertThat(spec.kitParts()).isEqualTo(1);
        assertThat(spec.carton().lengthMm()).isEqualTo(300);
        assertThat(spec.carton().weightKg()).isEqualByComparingTo("5.5");
        assertThat(spec.warnings()).isEmpty();
    }

    @Test
    @DisplayName("level 1 incomplete: skipped with a warning, level 2 is used")
    void incompleteShippingUnitFallsThrough() {
        PackagingSpec spec = resolve(30, pack("BOX", 10, null, "30", "20", "20", true, true), carton("CTN", 6));
        assertThat(spec.level()).isEqualTo(RuleLevel.LARGEST_PACKING_UNIT);
        assertThat(spec.unitOfMeasure()).isEqualTo("CTN");
        assertThat(spec.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(WarningCode.SHIPPING_UNIT_INCOMPLETE);
            assertThat(w.unitOfMeasure()).isEqualTo("BOX");
            assertThat(w.fields()).containsExactly("weightKg");
        });
    }

    @Test
    @DisplayName("a retired unit marked in old data is not the default shipping unit, nor a carton at all")
    void retiredShippingUnitIsIgnored() {
        PackagingSpec spec = resolve(10, pack("OLD", 10, "5", "30", "20", "20", false, true));
        assertThat(spec.status()).isEqualTo(PackagingSpec.Status.MISSING);
        assertThat(spec.missing().fields()).isEmpty();
        assertThat(spec.missing().message()).contains("no active carton unit");
        assertThat(spec.warnings()).isEmpty();
    }

    @Test
    @DisplayName("level 1 on the base unit: nothing ships loose any more, so the carton is used, with a warning")
    void baseUnitAsDefaultShipping() {
        List<UomConversion> units = List.of(baseShipping(), carton("CTN", 10));
        PackagingSpec spec = PackagingRules.resolve(ITEM, "SKU", units, true, 25);
        assertThat(spec.level()).isEqualTo(RuleLevel.LARGEST_PACKING_UNIT);
        assertThat(spec.unitOfMeasure()).isEqualTo("CTN");
        assertThat(spec.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(WarningCode.SHIPPING_UNIT_INCOMPLETE);
            assertThat(w.unitOfMeasure()).isEqualTo("EACH");
        });
    }

    // ---- level 2: the largest packing unit with a full size ------------------------------------------------------

    @Test
    @DisplayName("level 2: the unit with the largest factor, whatever the order it is declared in")
    void largestPackingUnit() {
        PackagingSpec spec = resolve(1000, carton("SMALL", 10), carton("HUGE", 500), carton("MID", 100));
        assertThat(spec.level()).isEqualTo(RuleLevel.LARGEST_PACKING_UNIT);
        assertThat(spec.unitOfMeasure()).isEqualTo("HUGE");
        assertThat(spec.fullCartons()).isEqualTo(2);
    }

    @Test
    @DisplayName("level 2: a larger unit without a size is skipped (warned), the next largest is used; retired ones never")
    void incompletePackingUnitSkipped() {
        PackagingSpec spec = resolve(100,
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
        assertThat(resolve(10, carton("B", 10), carton("A", 10)).unitOfMeasure()).isEqualTo("A");
        assertThat(resolve(10, carton("A", 10), carton("B", 10)).unitOfMeasure()).isEqualTo("A");
    }

    // ---- the carton ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the carton in millimetres rounded up, three-decimal weight, the item's rotatable flag")
    void cartonMeasures() {
        List<UomConversion> units = List.of(base(), pack("CTN", 10, "6.25", "12.34", "9", "7", true, true));
        PackagingSpec spec = PackagingRules.resolve(ITEM, "SKU", units, false, 10);
        assertThat(spec.carton().lengthMm()).isEqualTo(124);    // 123.4 up
        assertThat(spec.carton().widthMm()).isEqualTo(90);
        assertThat(spec.carton().heightMm()).isEqualTo(70);
        assertThat(spec.carton().weightKg()).isEqualByComparingTo("6.250");
        assertThat(spec.carton().rotatable()).isFalse();
    }

    // ---- the remainder carton (LP2-4) ---------------------------------------------------------------------------

    @Test
    @DisplayName("cartons = ceil(qty / factor): the remainder is one full-size carton weighing the carton pro rata")
    void remainderCarton() {
        PackagingSpec spec = resolve(27, pack("CTN", 10, "8.0", "50", "40", "30", true, false));
        assertThat(spec.fullCartons()).isEqualTo(2);
        assertThat(spec.partialCarton().units()).isEqualTo(7);
        assertThat(spec.partialCarton().weightKg()).isEqualByComparingTo("5.600");   // 8.0 * 7 / 10
        assertThat(spec.totalCartons()).isEqualTo(3);
        assertThat(spec.carton().weightKg()).isEqualByComparingTo("8.000");
        assertThat(spec.warnings()).isEmpty();
    }

    @Test
    @DisplayName("an exact multiple has no partial carton; fewer units than one carton is a single partial one")
    void exactAndShort() {
        assertThat(resolve(20, carton("CTN", 10)).partialCarton()).isNull();
        PackagingSpec few = resolve(3, carton("CTN", 10));
        assertThat(few.fullCartons()).isZero();
        assertThat(few.partialCarton().units()).isEqualTo(3);
        assertThat(few.partialCarton().weightKg()).isEqualByComparingTo("3.000");   // 10.0 * 3 / 10
        assertThat(few.totalCartons()).isEqualTo(1);
    }

    // ---- missing (LP2-3) ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a carton missing part of its size: MISSING naming what it lacks, no invented defaults")
    void missing() {
        PackagingSpec spec = resolve(5, pack("CTN", 10, "6", "50", null, "30", true, false));
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
    @DisplayName("a carton with no measure at all is MISSING on all four fields")
    void missingEverything() {
        PackagingSpec spec = resolve(1, pack("CTN", 10, null, null, null, null, true, true));
        assertThat(spec.status()).isEqualTo(PackagingSpec.Status.MISSING);
        assertThat(spec.missing().fields()).containsExactly("lengthCm", "widthCm", "heightCm", "weightKg");
    }

    @Test
    @DisplayName("no carton unit at all: MISSING, nothing to name")
    void noCarton() {
        PackagingSpec spec = resolve(1);
        assertThat(spec.status()).isEqualTo(PackagingSpec.Status.MISSING);
        assertThat(spec.missing().fields()).isEmpty();
        assertThat(spec.warnings()).isEmpty();
    }

    // ---- determinism -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the same input gives the same answer, whatever order the units arrive in")
    void deterministic() {
        UomConversion a = carton("A", 10);
        UomConversion b = pack("B", 25, "9", "40", "40", "40", true, false);
        UomConversion c = pack("C", 25, null, "40", "40", "40", true, false);
        PackagingSpec first = resolve(63, a, b, c);
        assertThat(resolve(63, a, b, c)).isEqualTo(first);
        assertThat(resolve(63, c, b, a)).isEqualTo(first);
        assertThat(resolve(63, b, c, a)).isEqualTo(first);
        assertThat(first.unitOfMeasure()).isEqualTo("B");
    }
}
