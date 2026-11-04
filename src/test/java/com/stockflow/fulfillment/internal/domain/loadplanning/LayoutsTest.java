package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one mapper from a plan to the viewer's {@code Layout} contract (FE #71), shared by the estimate and the real
 * load plan: every field the frontend schema reads, in the frame and codes it reads them in.
 */
class LayoutsTest {

    private static LoadPlanRequest request(List<CargoPackage> cartons, VehicleSpec type) {
        return new LoadPlanRequest(cartons, List.of(type), SelectionMode.SINGLE_TYPE, List.of(), KitDistribution.AUTO,
                false, null, 0L, Duration.ofSeconds(2));
    }

    private static CargoPackage estimated(String ref, String lineId, String part, String kitGroup) {
        return new CargoPackage(ref, lineId, 1200, 800, 600, new BigDecimal("40.5"), true, part, kitGroup, "order",
                false);
    }

    @Test
    @DisplayName("a container's layout: inner size l/w/h, payload, its gusset blocks, every carton once with its keys")
    void containerLayout() {
        VehicleSpec hc = LoadPlanAssertions.hc40(2, 150);
        List<CargoPackage> cartons = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            cartons.add(estimated("L1#" + i, "L1", "SKU-A", null));
        }
        cartons.add(estimated("L2#1", "L2", "SKU-K/1", "L2/1"));
        cartons.add(estimated("L2#2", "L2", "SKU-K/2", "L2/1"));
        LoadPlanRequest request = request(cartons, hc);
        LoadPlanResult result = new LoadPlanner().plan(request);

        Layouts.Mapped mapped = Layouts.of(request, result, c -> c.partCode().split("/")[0]);

        assertThat(mapped.unplaced()).isEmpty();
        assertThat(mapped.vehicles()).hasSize(1);
        Layout layout = mapped.vehicles().getFirst();
        assertThat(layout.seq()).isEqualTo(1);
        assertThat(layout.code()).isEqualTo(hc.code());
        assertThat(layout.innerMm()).isEqualTo(new Layout.Dims(hc.innerLengthMm(), hc.innerWidthMm(),
                hc.innerHeightMm()));
        assertThat(layout.maxPayloadKg()).isEqualByComparingTo(hc.maxPayloadKg());
        // A 40-foot box: the two cubes at the top of the front wall.
        assertThat(layout.gussets()).containsExactly(
                new Layout.Block(0, hc.innerHeightMm() - 150, 0, 150, 150, 150),
                new Layout.Block(hc.innerWidthMm() - 150, hc.innerHeightMm() - 150, 0, 150, 150, 150));
        assertThat(layout.boxes()).extracting(Layout.Box::ref)
                .containsExactlyInAnyOrderElementsOf(cartons.stream().map(CargoPackage::ref).toList());
        assertThat(layout.usedWeightKg()).isEqualByComparingTo("324.0");
        assertThat(layout.usedVolumeMm3()).isEqualTo(8L * 1200 * 800 * 600);

        Map<String, Layout.Box> byRef = layout.boxes().stream()
                .collect(Collectors.toMap(Layout.Box::ref, b -> b));
        Layout.Box plain = byRef.get("L1#3");
        assertThat(plain.sourceRef()).isEqualTo("L1");
        assertThat(plain.label()).isEqualTo("SKU-A");
        assertThat(plain.partName()).isEqualTo("SKU-A");
        assertThat(plain.kitGroup()).isNull();
        assertThat(plain.origMm()).isEqualTo(new Layout.Dims(1200, 800, 600));
        assertThat(plain.weightKg()).isEqualByComparingTo("40.5");
        Layout.Box part = byRef.get("L2#2");
        assertThat(part.partName()).isEqualTo("SKU-K/2");
        assertThat(part.label()).isEqualTo("SKU-K");
        assertThat(part.kitGroup()).isEqualTo("L2/1");
        for (Layout.Box b : layout.boxes()) {
            // FE dims.ts orientedExtent: the orientation's letters name the measured side along z, x, y.
            Orientation o = Orientation.ofCode(b.orientation());
            int[] e = o.extents(estimated(b.ref(), "x", "p", null));
            assertThat(new int[] {b.wMm(), b.hMm(), b.dMm()}).containsExactly(e);
            assertThat(b.loadSequence()).isPositive();
        }
    }

    @Test
    @DisplayName("a packed parcel's sourceRef only repeats its ref: the viewer gets null; no part code: the label")
    void parcelsHaveNoSourceRef() {
        VehicleSpec truck = LoadPlanAssertions.truck5t(1, 0);
        CargoPackage parcel = CargoPackage.of("pkg-1", 500, 400, 300, new BigDecimal("12"), false);
        LoadPlanRequest request = request(List.of(parcel), truck);

        Layouts.Mapped mapped = Layouts.of(request, new LoadPlanner().plan(request), c -> "PKG-0001");

        Layout.Box box = mapped.vehicles().getFirst().boxes().getFirst();
        assertThat(box.sourceRef()).isNull();
        assertThat(box.label()).isEqualTo("PKG-0001");
        assertThat(box.partName()).isEqualTo("PKG-0001");
        assertThat(box.orientation()).isEqualTo("LWH");
        assertThat(box.rotatable()).isFalse();
        assertThat(mapped.vehicles().getFirst().gussets()).isEmpty();
    }

    @Test
    @DisplayName("cartons left out are grouped per line, part, size and reason with a quantity: FE UnplacedLineSchema")
    void unplacedAreGroupedLines() {
        VehicleSpec truck = LoadPlanAssertions.truck5t(1, 0);
        List<CargoPackage> cartons = List.of(
                new CargoPackage("L9#1", "L9", 20_000, 3_000, 3_000, new BigDecimal("10"), true, "BIG", null, null,
                        false),
                new CargoPackage("L9#2", "L9", 20_000, 3_000, 3_000, new BigDecimal("4"), true, "BIG", null, null,
                        false),
                new CargoPackage("L8#1", "L8", 20_000, 3_000, 3_000, new BigDecimal("10"), true, "BIG", null, null,
                        false));
        LoadPlanRequest request = request(cartons, truck);

        Layouts.Mapped mapped = Layouts.of(request, new LoadPlanner().plan(request), c -> "BIG");

        assertThat(mapped.vehicles()).isEmpty();
        assertThat(mapped.unplaced()).containsExactly(
                new Layout.UnplacedLine("L9", "BIG", "BIG", new Layout.Dims(20_000, 3_000, 3_000),
                        UnplacedReason.TOO_LARGE, 2, List.of("L9#1", "L9#2")),
                new Layout.UnplacedLine("L8", "BIG", "BIG", new Layout.Dims(20_000, 3_000, 3_000),
                        UnplacedReason.TOO_LARGE, 1, List.of("L8#1")));
    }
}
