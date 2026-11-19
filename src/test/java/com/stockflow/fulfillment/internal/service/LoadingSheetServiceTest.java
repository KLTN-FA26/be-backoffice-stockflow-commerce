package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.fulfillment.internal.domain.LoadPlanStatus;
import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which plan statuses may print a vehicle's loading sheet (kltn-docs 09 §4.2 step 5, §5.3): the diagram is exported when
 * the plan is confirmed and locked, so only CONFIRMED and DISPATCHED print; a DRAFT plan has none, a PLANNED one can still
 * be edited and recalculated, a CANCELLED one is not loaded from.
 */
class LoadingSheetServiceTest {

    private final LoadPlans plans = mock(LoadPlans.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final LoadingSheetService service = new LoadingSheetService(plans, mock(DispatchLoadJpaRepository.class),
            mock(CarrierJpaRepository.class), warehouses, new LoadingSheetDocument(new LabelFonts()),
            Clock.fixed(Instant.parse("2026-11-19T03:00:00Z"), ZoneOffset.UTC));

    private void planIn(LoadPlanStatus status) {
        UUID id = UUID.randomUUID();
        LoadPlans.PlanDetail plan = mock(LoadPlans.PlanDetail.class);
        when(plan.status()).thenReturn(status.name());
        when(plan.code()).thenReturn("LP-20261119-0001");
        when(plan.warehouseId()).thenReturn(UUID.randomUUID());
        when(plan.vehicles()).thenReturn(List.of());
        when(plan.vehicleTypes()).thenReturn(List.of());
        when(plan.shipments()).thenReturn(List.of());
        when(plan.packages()).thenReturn(List.of());
        when(plans.get(id)).thenReturn(plan);
        when(plans.layout(id, 1)).thenReturn(new Layout(1, "TRUCK-5T", UUID.randomUUID(),
                new Layout.Dims(5200, 2050, 2050), new BigDecimal("5000"), List.of(), List.of(), 0, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO));
        when(warehouses.findWarehouse(plan.warehouseId())).thenReturn(Optional.empty());
        current = id;
    }

    private UUID current;

    @ParameterizedTest
    @EnumSource(value = LoadPlanStatus.class, names = {"CONFIRMED", "DISPATCHED"})
    @DisplayName("a confirmed or dispatched plan prints: a PDF named after the plan and the vehicle")
    void printable(LoadPlanStatus status) {
        planIn(status);
        LoadingSheetService.Pdf pdf = service.loadingSheet(current, 1);
        assertThat(pdf.fileName()).isEqualTo("loading-sheet-LP-20261119-0001-vehicle1.pdf");
        assertThat(pdf.content()).startsWith("%PDF".getBytes());
    }

    @ParameterizedTest
    @EnumSource(value = LoadPlanStatus.class, names = {"CONFIRMED", "DISPATCHED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a draft, planned or cancelled plan does not: 409 INVALID_LOAD_PLAN_TRANSITION, and no layout is read")
    void notPrintable(LoadPlanStatus status) {
        planIn(status);
        assertThatThrownBy(() -> service.loadingSheet(current, 1)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION));
        verify(plans, never()).layout(current, 1);
    }
}
