package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockAdjustmentRepository;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import com.stockflow.warehouse.api.PutawaySource;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Team plan 4D-1: what {@link CartonQuarantineService} adds around the stock change. A carton found faulty while it
 * is being put away still has its receipt's task open; quarantining it finishes that task into the quarantine area and
 * announces it, so the receipt can close (kltn-docs 03, 05). The task row is locked before the stock is touched, as a
 * putaway confirmation locks it. A received carton whose task is not open yet is refused rather than quarantined
 * past the task that is about to be opened for it.
 */
class CartonQuarantineServiceTest {

    private static final String LPN = "LPN-20261109-000001";
    private static final String DOCK = "WH1-RCV01";
    private static final String AREA = "WH1-QC01";
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID RECEIPT = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();

    private final StockOperations operations = mock(StockOperations.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final PutawayTasks tasks = mock(PutawayTasks.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CartonQuarantineService service = new CartonQuarantineService(operations, stockPackages,
            mock(StockAdjustmentRepository.class), mock(UomConversionRepository.class),
            mock(InventoryPolicyRepository.class), tasks, warehouses, events,
            mock(com.stockflow.inventory.internal.domain.ReturnedCartonOwnership.class));

    private final StockPackageId cartonId = new StockPackageId(UUID.randomUUID());
    private final UUID areaId = UUID.randomUUID();

    /** The carton as it stands in {@code stockStatus} at {@code location}: received by the test's receipt line. */
    private StockPackage carton(String location, StockStatus stockStatus) {
        return new StockPackage(cartonId, LPN, new Sku("CUP-12OZ"), UUID.randomUUID(), UUID.randomUUID(), 10,
                Instant.parse("2026-11-01T00:00:00Z"), new LocationId(location), StockPackageStatus.IN_STOCK,
                stockStatus, UUID.randomUUID(), "LOT-1", Instant.parse("2026-11-01T00:00:00Z"), LINE, null,
                List.of(), 0L);
    }

    @BeforeEach
    void setUp() {
        // Received, still to be put away: INBOUND on the dock.
        when(stockPackages.findByLpns(List.of(LPN))).thenReturn(List.of(carton(DOCK, StockStatus.INBOUND)));
        org.mockito.Mockito.lenient().when(stockPackages.stockStatusOf(cartonId))
                .thenReturn(Optional.of(StockStatus.INBOUND));
        when(operations.quarantine(anyString(), anyString(), anyString(), any())).thenReturn(new StockPackageView(
                cartonId.value(), LPN, LINE, "CUP-12OZ", UUID.randomUUID(), "CARTON", 10, 10,
                Instant.parse("2026-11-01T00:00:00Z"), AREA, "IN_STOCK"));
        when(warehouses.findLocation(AREA)).thenReturn(Optional.of(new StorageLocationView(areaId,
                StorageLocationView.Kind.AREA, UUID.randomUUID(), AREA,
                StorageLocationView.Status.ACTIVE, StorageLocationView.Status.ACTIVE, true)));
    }

    private PutawayTaskView task(String status) {
        return new PutawayTaskView(UUID.randomUUID(), RECEIPT, LINE, "CUP-12OZ", 10, cartonId.value(), null, status,
                PutawaySource.RECEIPT);
    }

    @Test
    @DisplayName("a carton quarantined while being put away finishes its receipt task into quarantine, and says so")
    void openReceiptTaskIsFinishedIntoQuarantine() {
        PutawayTaskView open = task("PENDING");
        when(tasks.openTaskOf(cartonId.value())).thenReturn(Optional.of(open));
        when(tasks.lockForUpdate(open.id())).thenReturn(open);
        when(tasks.recordPutaway(open.id(), 10, 10, areaId)).thenReturn(task("DONE"));

        service.quarantine(LPN, AREA, "Torn", MANAGER);

        InOrder order = inOrder(tasks, operations);
        order.verify(tasks).lockForUpdate(open.id());
        order.verify(operations).quarantine(LPN, AREA, "Torn", MANAGER);
        order.verify(tasks).recordPutaway(open.id(), 10, 10, areaId);
        verify(events).publishEvent(new PutawayCompleted(RECEIPT, LINE, open.id()));
    }

    @Test
    @DisplayName("a task finished by someone else while this waited for its lock is left alone")
    void taskFinishedMeanwhileIsLeftAlone() {
        PutawayTaskView open = task("PENDING");
        when(tasks.openTaskOf(cartonId.value())).thenReturn(Optional.of(open));
        when(tasks.lockForUpdate(open.id())).thenReturn(task("DONE"));
        // Put away meanwhile: read before the wait the carton was INBOUND on the dock; read once the task's lock is
        // held it is AVAILABLE in its bin.
        when(stockPackages.stockStatusOf(cartonId)).thenReturn(Optional.of(StockStatus.AVAILABLE));

        service.quarantine(LPN, AREA, "Torn", MANAGER);

        verify(operations).quarantine(LPN, AREA, "Torn", MANAGER);
        verify(tasks, never()).recordPutaway(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("a received carton whose putaway task is not open yet is refused, not quarantined past it")
    void inboundCartonWithoutItsTaskIsRefused() {
        when(tasks.openTaskOf(cartonId.value())).thenReturn(Optional.empty());

        BusinessException refused = catchThrowableOfType(BusinessException.class,
                () -> service.quarantine(LPN, AREA, "Torn", MANAGER));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.STOCK_PACKAGE_DECISION_PENDING);
        verifyNoInteractions(operations);
    }
}
