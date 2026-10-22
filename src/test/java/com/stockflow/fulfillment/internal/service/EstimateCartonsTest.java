package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.KitSpec;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.RuleLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The planner's cartons from resolved order lines (design v2 §2-§3; LP2-4 remainder, LP2-5 kits). */
class EstimateCartonsTest {

    private static final UUID PALLET_LINE = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID KIT_LINE = UUID.fromString("00000000-0000-7000-8000-000000000002");

    /** 25 units on a 24-carton pallet (the seeded LYG-1L-12OZ): one full pallet and one of 1 unit, 31.5 kg. */
    private static PackagingSpec pallet() {
        return new PackagingSpec("LYG-1L-12OZ", UUID.randomUUID(), 25, PackagingSpec.Status.RESOLVED,
                RuleLevel.DEFAULT_SHIPPING_UNIT, "PALLET", 24, 1, new PackagingSpec.PartialCarton(1,
                new BigDecimal("31.500")), new PackagingSpec.CartonSpec(1200, 1000, 1500, new BigDecimal("181.000"),
                false), 1, List.of(), null);
    }

    /** 3 tables that ship as 2 boxes each (item box x package_count). */
    private static PackagingSpec kit() {
        return new PackagingSpec("TABLE-KIT", UUID.randomUUID(), 3, PackagingSpec.Status.RESOLVED, RuleLevel.ITEM_BOX,
                "SET", 1, 3, null, new PackagingSpec.CartonSpec(1600, 900, 120, new BigDecimal("24.000"), true), 2,
                List.of(), null);
    }

    @Test
    @DisplayName("a pallet line: <lineId>#n refs, the line as sourceRef, the SKU as part and label, the remainder last")
    void remainderCartonIsTheLastOne() {
        EstimateCartons.Built built = EstimateCartons.build("order-1", List.of(
                new EstimateCartons.Line(PALLET_LINE, pallet())));

        assertThat(built.cartons()).extracting(CargoPackage::ref)
                .containsExactly(PALLET_LINE + "#1", PALLET_LINE + "#2");
        CargoPackage full = built.cartons().get(0);
        CargoPackage partial = built.cartons().get(1);
        assertThat(full.sourceRef()).isEqualTo(PALLET_LINE.toString());
        assertThat(full.partCode()).isEqualTo("LYG-1L-12OZ");
        assertThat(full.kitGroup()).isNull();
        assertThat(full.groupKey()).isEqualTo("order-1");
        assertThat(full.weightKg()).isEqualByComparingTo("181");
        assertThat(new int[] {full.lengthMm(), full.widthMm(), full.heightMm()}).containsExactly(1200, 1000, 1500);
        assertThat(full.rotatable()).isFalse();
        // LP2-4: a full-size carton with the units' weight.
        assertThat(new int[] {partial.lengthMm(), partial.widthMm(), partial.heightMm()})
                .containsExactly(1200, 1000, 1500);
        assertThat(partial.weightKg()).isEqualByComparingTo("31.5");
        assertThat(built.labels()).containsEntry(PALLET_LINE + "#2", "LYG-1L-12OZ");
        assertThat(built.kits()).isEmpty();
    }

    @Test
    @DisplayName("a kit line: N same-size parts per unit sharing one kit group per unit, and the set declared once")
    void kitUnitsAreSets() {
        EstimateCartons.Built built = EstimateCartons.build("order-1", List.of(
                new EstimateCartons.Line(KIT_LINE, kit()), new EstimateCartons.Line(UUID.randomUUID(), kit())));

        List<CargoPackage> first = built.cartons().subList(0, 6);
        assertThat(first).extracting(CargoPackage::ref).containsExactly(KIT_LINE + "#1", KIT_LINE + "#2",
                KIT_LINE + "#3", KIT_LINE + "#4", KIT_LINE + "#5", KIT_LINE + "#6");
        assertThat(first).extracting(CargoPackage::partCode)
                .containsExactly("TABLE-KIT/1", "TABLE-KIT/2", "TABLE-KIT/1", "TABLE-KIT/2", "TABLE-KIT/1",
                        "TABLE-KIT/2");
        assertThat(first).extracting(CargoPackage::kitGroup).containsExactly(KIT_LINE + "/1", KIT_LINE + "/1",
                KIT_LINE + "/2", KIT_LINE + "/2", KIT_LINE + "/3", KIT_LINE + "/3");
        assertThat(first).allSatisfy(c -> {
            assertThat(c.weightKg()).isEqualByComparingTo("24");
            assertThat(c.sourceRef()).isEqualTo(KIT_LINE.toString());
        });
        assertThat(built.labels().get(KIT_LINE + "#4")).isEqualTo("TABLE-KIT");
        assertThat(built.cartons()).hasSize(12);
        assertThat(built.kits()).containsExactly(new KitSpec("TABLE-KIT", Map.of("TABLE-KIT/1", 1, "TABLE-KIT/2", 1)));
    }

    @Test
    @DisplayName("the same lines give the same cartons")
    void deterministic() {
        List<EstimateCartons.Line> lines = List.of(new EstimateCartons.Line(PALLET_LINE, pallet()),
                new EstimateCartons.Line(KIT_LINE, kit()));
        assertThat(EstimateCartons.build("o", lines).cartons()).isEqualTo(EstimateCartons.build("o", lines).cartons());
    }

    @Test
    @DisplayName("more boxes than one synchronous estimate places: 422 LOAD_ESTIMATE_TOO_MANY_CARTONS")
    void tooManyBoxes() {
        PackagingSpec loose = new PackagingSpec("CUP", UUID.randomUUID(), EstimateCartons.MAX_BOXES + 1,
                PackagingSpec.Status.RESOLVED, RuleLevel.ITEM_UNIT, "EA", 1, EstimateCartons.MAX_BOXES + 1, null,
                new PackagingSpec.CartonSpec(100, 100, 100, new BigDecimal("0.1"), true), 1, List.of(), null);
        assertThatThrownBy(() -> EstimateCartons.build("o", List.of(new EstimateCartons.Line(PALLET_LINE, loose))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LOAD_ESTIMATE_TOO_MANY_CARTONS));
    }
}
