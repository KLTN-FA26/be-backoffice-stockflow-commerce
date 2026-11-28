package com.stockflow.assistant.internal.service;

import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.CartonHistoryQueries;
import com.stockflow.inventory.api.CartonHistoryQueries.WorkKind;
import com.stockflow.procurement.api.GoodsReceiptQueries;
import com.stockflow.warehouse.api.PutawayTrips;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A missing carton investigated: the facts each screen shows, and where to look by fixed rules (contract 3.2). */
class CartonInvestigationTest {

    private static final UUID CARTON = UUID.randomUUID();
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID PUTAWAY = UUID.randomUUID();
    private static final UUID ADJUSTMENT = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID WORKER = UUID.randomUUID();

    private final CartonHistoryQueries cartons = mock(CartonHistoryQueries.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final PutawayTrips trips = mock(PutawayTrips.class);
    private final FulfillmentService fulfillment = mock(FulfillmentService.class);
    private final GoodsReceiptQueries receipts = mock(GoodsReceiptQueries.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final CurrentUserProvider currentUsers = mock(CurrentUserProvider.class);

    private CartonInvestigation investigation() {
        return new CartonInvestigation(cartons, warehouses, trips, fulfillment, receipts, identity, currentUsers,
                new Messages(new StaticMessageSource()));
    }

    private void caller(String... resources) {
        Set<PermissionCode> permissions = Set.copyOf(Arrays.stream(resources)
                .map(resource -> PermissionCode.of(resource, Action.READ)).toList());
        when(currentUsers.current()).thenReturn(Optional.of(new CurrentUser(UUID.randomUUID(), "u",
                Set.of(Role.WAREHOUSE_STAFF), permissions, DataScope.WAREHOUSE, Set.of("HCM"), Set.of(HCM))));
    }

    private static CartonHistoryQueries.CartonHistory carton(boolean booksHidden, String stockStatus,
                                                             List<CartonHistoryQueries.OpenWork> work) {
        return new CartonHistoryQueries.CartonHistory(CARTON, "LPN-000123", "CUP-12OZ-KH", "IN_STOCK", stockStatus,
                "HCM-A01-2-03", HCM, "HCM", "LOT-1", Instant.parse("2026-11-20T02:00:00Z"), booksHidden,
                booksHidden ? List.of() : List.of(new CartonHistoryQueries.LedgerLine(
                        Instant.parse("2026-11-21T02:00:00Z"), "PUTAWAY", "HCM-RCV01", "HCM-A01-2-03",
                        "PUTAWAY_TASK", PUTAWAY, WORKER)),
                booksHidden ? 0 : 1, work);
    }

    private static StorageLocationView bin(String code) {
        return new StorageLocationView(UUID.randomUUID(), StorageLocationView.Kind.BIN, HCM, code,
                StorageLocationView.Status.ACTIVE, StorageLocationView.Status.ACTIVE, true);
    }

    @Test
    @DisplayName("neighbours by the map's codes: next bin on the level, same bin a level up and down; letters too")
    void neighbours() {
        assertThat(CartonInvestigation.neighbours("HCM-A01-2-03"))
                .containsExactly("HCM-A01-2-02", "HCM-A01-2-04", "HCM-A01-1-03", "HCM-A01-3-03");
        assertThat(CartonInvestigation.neighbours("HCM-A01-1-B"))
                .containsExactly("HCM-A01-1-A", "HCM-A01-1-C", "HCM-A01-2-B");
        assertThat(CartonInvestigation.neighbours("HCM-A01-1-A1"))
                .contains("HCM-A01-1-A2", "HCM-A01-1-B1", "HCM-A01-2-A1").doesNotContain("HCM-A01-1-A0");
        assertThat(CartonInvestigation.neighbours("HCM-RCV01")).as("an area has no neighbours").isEmpty();
    }

    @Test
    @DisplayName("where to look, ranked: the books' place, the putaway trip's bin, then the bins beside it that exist")
    void whereToLookIsRanked() {
        caller(ToolPermissions.STOCK_PACKAGES, ToolPermissions.INVENTORY_STOCK_MOVEMENTS);
        when(cartons.history("LPN-000123", CartonInvestigation.TIMELINE)).thenReturn(carton(false, "AVAILABLE",
                List.of(new CartonHistoryQueries.OpenWork(WorkKind.PUTAWAY_TASK, PUTAWAY, null, "PENDING"))));
        when(trips.assignedLocationOf(PUTAWAY)).thenReturn(Optional.of(TARGET));
        when(warehouses.findLocation(TARGET)).thenReturn(Optional.of(bin("HCM-B02-1-01")));
        when(warehouses.findLocations(anyCollection())).thenReturn(List.of(bin("HCM-A01-2-04"), bin("HCM-A01-1-03")));
        when(identity.displayNames(anyCollection())).thenReturn(Map.of(WORKER, "Nguyễn Văn Kho"));

        CartonInvestigation.Investigation found = investigation().investigate("LPN-000123");

        assertThat(found.whereToLook()).extracting(CartonInvestigation.Place::locationCode,
                CartonInvestigation.Place::why).containsExactly(
                org.assertj.core.groups.Tuple.tuple("HCM-A01-2-03", "LAST_RECORDED"),
                org.assertj.core.groups.Tuple.tuple("HCM-B02-1-01", "PUTAWAY_TARGET"),
                org.assertj.core.groups.Tuple.tuple("HCM-A01-2-04", "NEIGHBOUR_BIN"),
                org.assertj.core.groups.Tuple.tuple("HCM-A01-1-03", "NEIGHBOUR_BIN"));
        assertThat(found.timeline()).singleElement().satisfies(entry -> {
            assertThat(entry.by()).isEqualTo("Nguyễn Văn Kho");
            assertThat(entry.to()).isEqualTo("HCM-A01-2-03");
        });
        assertThat(found.timelineWithheld()).isNull();
        assertThat(found.openWork()).singleElement().satisfies(work -> {
            assertThat(work.kind()).isEqualTo("PUTAWAY_TASK");
            assertThat(work.href()).isEqualTo("/admin/putaway/" + PUTAWAY);
        });
    }

    @Test
    @DisplayName("a caller who may not read the ledger gets no timeline, and is told why; adjustments need their "
            + "screen's permission")
    void timelineAndAdjustmentsFollowTheirScreens() {
        caller(ToolPermissions.STOCK_PACKAGES);
        when(cartons.history(any(), anyInt())).thenReturn(carton(false, "AVAILABLE", List.of(
                new CartonHistoryQueries.OpenWork(WorkKind.ADJUSTMENT, ADJUSTMENT, "ADJ-1", "PENDING_APPROVAL"))));

        CartonInvestigation.Investigation found = investigation().investigate("LPN-000123");

        assertThat(found.timeline()).isEmpty();
        assertThat(found.timelineTotal()).isNull();
        assertThat(found.timelineWithheld()).isEqualTo("NO_PERMISSION");
        assertThat(found.openWork()).isEmpty();
        verify(identity, never()).displayNames(anyCollection());
        verify(receipts, never()).openReturnReceiptsOf(any());
    }

    @Test
    @DisplayName("under a blind count the history and its total are withheld as BLIND_COUNT, never shown as nothing "
            + "having happened, even to a caller who may read the ledger")
    void blindCount() {
        caller(ToolPermissions.STOCK_PACKAGES, ToolPermissions.INVENTORY_STOCK_MOVEMENTS);
        when(cartons.history(any(), anyInt())).thenReturn(carton(true, "AVAILABLE", List.of()));

        CartonInvestigation.Investigation found = investigation().investigate("LPN-000123");

        assertThat(found.timelineWithheld()).isEqualTo("BLIND_COUNT");
        assertThat(found.timeline()).isEmpty();
        assertThat(found.timelineTotal()).as("withheld, not 0: a 0 reads as never moved").isNull();
        assertThat(found.locationCode()).as("the carton itself is shown, as the carton lookup shows it")
                .isEqualTo("HCM-A01-2-03");
    }

    @Test
    @DisplayName("a quarantined carton not standing in quarantine: the warehouse's quarantine areas are places to look")
    void quarantine() {
        caller(ToolPermissions.STOCK_PACKAGES);
        when(cartons.history(any(), anyInt())).thenReturn(carton(false, "QUARANTINE", List.of()));
        StorageLocationView area = new StorageLocationView(UUID.randomUUID(), StorageLocationView.Kind.AREA, HCM,
                "HCM-QRT01", StorageLocationView.Status.ACTIVE, StorageLocationView.Status.ACTIVE, true);
        when(warehouses.areasOf(HCM, StorageLocationView.AreaType.QUARANTINE)).thenReturn(List.of(area));

        CartonInvestigation.Investigation found = investigation().investigate("LPN-000123");

        assertThat(found.whereToLook()).extracting(CartonInvestigation.Place::why)
                .containsExactly("LAST_RECORDED", "QUARANTINE");
    }
}
