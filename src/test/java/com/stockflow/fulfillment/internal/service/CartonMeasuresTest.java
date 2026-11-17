package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.entity.DispatchLoadPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import com.stockflow.fulfillment.internal.repository.DispatchLoadPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadPlanPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickLineJpaRepository;
import com.stockflow.inventory.api.CartonSpec;
import com.stockflow.inventory.api.InventoryControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A labelled carton takes its SKU's corrected carton spec when its vehicle is planned (team plan 4C-6, review M2;
 * kltn-docs 08 edge case "the real carton differs from the declared spec"), unless a load plan or a vehicle holds it
 * already: its layout was computed from the measures it has.
 */
class CartonMeasuresTest {

    private static final String SOFA = "SOFA-3S-GREY";

    private final InventoryControlService itemControls = mock(InventoryControlService.class);
    private final PickLineJpaRepository pickLines = mock(PickLineJpaRepository.class);
    private final PackageJpaRepository packages = mock(PackageJpaRepository.class);
    private final LoadPlanPackageJpaRepository planMembers = mock(LoadPlanPackageJpaRepository.class);
    private final DispatchLoadPackageJpaRepository loadParcels = mock(DispatchLoadPackageJpaRepository.class);
    private final CartonMeasures measures = new CartonMeasures(itemControls, pickLines, packages, planMembers,
            loadParcels, 6000);

    private final UUID shipment = UUID.randomUUID();

    @BeforeEach
    void theSofaSpecWasCorrected() {
        spec(SOFA, "230", "95", "90", "55", true);
    }

    private void spec(String sku, String length, String width, String height, String weight, boolean rotatable) {
        when(itemControls.cartonSpec(new Sku(sku))).thenReturn(Optional.of(new CartonSpec(sku, "CARTON", 1,
                length == null ? null : new BigDecimal(length), width == null ? null : new BigDecimal(width),
                height == null ? null : new BigDecimal(height), weight == null ? null : new BigDecimal(weight),
                rotatable)));
    }

    /** A LABELED carton of one sofa, measured as the old spec said: 215 x 95 x 90 cm, 52.5 kg. */
    private PackageJpaEntity labelled(String sku) {
        PackageJpaEntity carton = mock(PackageJpaEntity.class);
        UUID id = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        when(carton.getId()).thenReturn(id);
        when(carton.getPickLineId()).thenReturn(line);
        when(carton.getLpn()).thenReturn("LPN-" + id.toString().substring(0, 6));
        when(carton.getStatus()).thenReturn(PackageStatus.LABELED);
        when(carton.getShipmentId()).thenReturn(shipment);
        when(carton.getLengthCm()).thenReturn(new BigDecimal("215"));
        when(carton.getWidthCm()).thenReturn(new BigDecimal("95"));
        when(carton.getHeightCm()).thenReturn(new BigDecimal("90"));
        when(carton.getWeightKg()).thenReturn(new BigDecimal("52.5"));
        when(carton.isRotatable()).thenReturn(true);
        PickLineJpaEntity pickLine = mock(PickLineJpaEntity.class);
        when(pickLine.getId()).thenReturn(line);
        when(pickLine.getSku()).thenReturn(sku);
        lines.add(pickLine);
        return carton;
    }

    private final java.util.List<PickLineJpaEntity> lines = new java.util.ArrayList<>();

    private void linesKnown() {
        when(pickLines.findAllById(any())).thenReturn(lines);
    }

    private static LoadPlanPackageJpaEntity member(PackageJpaEntity carton) {
        LoadPlanPackageJpaEntity row = mock(LoadPlanPackageJpaEntity.class);
        UUID id = carton.getId();
        when(row.getPackageId()).thenReturn(id);
        return row;
    }

    private static DispatchLoadPackageJpaEntity loaded(PackageJpaEntity carton) {
        DispatchLoadPackageJpaEntity row = mock(DispatchLoadPackageJpaEntity.class);
        UUID id = carton.getId();
        when(row.getPackageId()).thenReturn(id);
        return row;
    }

    @Test
    @DisplayName("a labelled carton no plan holds takes the corrected spec: size, full weight, rotatable, and the "
            + "volumetric weight 230 x 95 x 90 / 6000 = 327.750; it is saved and flushed")
    void aFreeCartonIsRemeasured() {
        PackageJpaEntity carton = labelled(SOFA);
        linesKnown();

        measures.refreshForPlanning(List.of(carton));

        verify(carton).remeasure(argThat(w -> w.compareTo(new BigDecimal("55")) == 0),
                argThat(l -> l.compareTo(new BigDecimal("230")) == 0),
                argThat(w -> w.compareTo(new BigDecimal("95")) == 0),
                argThat(h -> h.compareTo(new BigDecimal("90")) == 0),
                argThat(d -> d.compareTo(new BigDecimal("327.750")) == 0), org.mockito.ArgumentMatchers.eq(true));
        verify(packages).saveAllAndFlush(List.of(carton));
    }

    @Test
    @DisplayName("a carton a load plan holds, or one on a vehicle, keeps what its layout was computed from")
    void plannedOrLoadedCartonsKeepTheirMeasures() {
        PackageJpaEntity inPlan = labelled(SOFA);
        PackageJpaEntity onVehicle = labelled(SOFA);
        PackageJpaEntity free = labelled(SOFA);
        linesKnown();
        LoadPlanPackageJpaEntity membership = member(inPlan);
        DispatchLoadPackageJpaEntity onBoard = loaded(onVehicle);
        when(planMembers.findByPackageIdInAndActiveTrue(anyCollection())).thenReturn(List.of(membership));
        when(loadParcels.findByShipmentIdInAndActiveTrue(anyCollection())).thenReturn(List.of(onBoard));

        measures.refreshForPlanning(List.of(inPlan, onVehicle, free));

        verify(inPlan, never()).remeasure(any(), any(), any(), any(), any(), anyBoolean());
        verify(onVehicle, never()).remeasure(any(), any(), any(), any(), any(), anyBoolean());
        verify(free).remeasure(any(), any(), any(), any(), any(), anyBoolean());
        verify(packages).saveAllAndFlush(List.of(free));
    }

    @Test
    @DisplayName("a spec that did not change writes nothing")
    void unchangedSpecWritesNothing() {
        spec(SOFA, "215", "95", "90", "52.5", true);
        PackageJpaEntity carton = labelled(SOFA);
        linesKnown();

        measures.refreshForPlanning(List.of(carton));

        verify(carton, never()).remeasure(any(), any(), any(), any(), any(), anyBoolean());
        verify(packages, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("a spec that lost its size, or a SKU with no carton unit any more, keeps the measures the carton has")
    void unmeasuredOrMissingSpecKeeps() {
        spec(SOFA, null, null, null, null, true);
        when(itemControls.cartonSpec(new Sku("TABLE-OAK-160"))).thenReturn(Optional.empty());
        PackageJpaEntity sofa = labelled(SOFA);
        PackageJpaEntity table = labelled("TABLE-OAK-160");
        linesKnown();

        measures.refreshForPlanning(List.of(sofa, table));

        verify(sofa, never()).remeasure(any(), any(), any(), any(), any(), anyBoolean());
        verify(table, never()).remeasure(any(), any(), any(), any(), any(), anyBoolean());
        verify(packages, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("only a rotatable flag changed is a change too")
    void rotatableChangeCounts() {
        spec(SOFA, "215", "95", "90", "52.5", false);
        PackageJpaEntity carton = labelled(SOFA);
        linesKnown();

        measures.refreshForPlanning(List.of(carton));

        verify(carton).remeasure(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    @DisplayName("a repack parcel (no pick line) and a carton not LABELED are left alone, without a query")
    void repackParcelsAndUnlabelledCartonsAreIgnored() {
        PackageJpaEntity parcel = mock(PackageJpaEntity.class);
        when(parcel.getStatus()).thenReturn(PackageStatus.SEALED);
        PackageJpaEntity removed = labelled(SOFA);
        when(removed.getStatus()).thenReturn(PackageStatus.REMOVED);

        measures.refreshForPlanning(List.of(parcel, removed));

        verify(parcel, never()).remeasure(any(), any(), any(), any(), any(), anyBoolean());
        verify(removed, never()).remeasure(any(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(planMembers, loadParcels, pickLines, packages, itemControls);
    }

    @Test
    @DisplayName("planned: a load plan member or a carton on a vehicle is planned; one neither holds is not")
    void planned() {
        UUID inPlan = UUID.randomUUID();
        UUID onVehicle = UUID.randomUUID();
        LoadPlanPackageJpaEntity membership = mock(LoadPlanPackageJpaEntity.class);
        when(planMembers.findByPackageIdInAndActiveTrue(List.of(inPlan))).thenReturn(List.of(membership));
        when(loadParcels.findByPackageIdAndActiveTrue(onVehicle))
                .thenReturn(Optional.of(mock(DispatchLoadPackageJpaEntity.class)));

        assertThat(measures.planned(inPlan)).isTrue();
        assertThat(measures.planned(onVehicle)).isTrue();
        assertThat(measures.planned(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("specs: a SKU with no carton unit is missing; one with an unmeasured unit is there and not measured; a "
            + "divisor that is not positive is a configuration error")
    void specsAndDivisor() {
        spec("TABLE-OAK-160", null, "95", "20", "38", true);
        var specs = measures.specs(List.of(SOFA, "TABLE-OAK-160", "NO-CARTON", SOFA));

        assertThat(specs).containsOnlyKeys(SOFA, "TABLE-OAK-160");
        assertThat(specs.get(SOFA).measured()).isTrue();
        assertThat(specs.get("TABLE-OAK-160").measured()).isFalse();
        assertThat(measures.divisor()).isEqualTo(6000);
        assertThatThrownBy(() -> new CartonMeasures(itemControls, pickLines, packages, planMembers, loadParcels, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
