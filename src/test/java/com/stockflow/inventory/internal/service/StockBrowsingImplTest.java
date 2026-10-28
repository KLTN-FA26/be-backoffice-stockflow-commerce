package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.inventory.internal.repository.SlotsUnderCount;
import com.stockflow.inventory.internal.repository.StockBrowseSearch;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static com.stockflow.inventory.internal.service.InventoryFixtures.TODAY;
import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The read side of stock: reservations and a warehouse's stock, scoped to the caller's warehouses (BR-SEC-002). */
class StockBrowsingImplTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();

    private final StockBrowseSearch search = mock(StockBrowseSearch.class);
    private final WarehouseDirectory warehouses = mock(WarehouseDirectory.class);
    private final SlotsUnderCount underCount = mock(SlotsUnderCount.class);
    private final StockBrowsingImpl service = new StockBrowsingImpl(search, underCount, warehouses, Clock.fixed(NOW, ZoneOffset.UTC));

    private static CurrentUser clerkOf(UUID id, String prefix) {
        return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of(prefix), Set.of(id));
    }

    private static StockBrowseSearch.ReservationRow reservationAt(String location) {
        return new StockBrowseSearch.ReservationRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP-12OZ",
                location, "L1", 12, ReservationStatus.HELD, NOW, NOW.plusSeconds(1800), null, null);
    }

    private static StockBrowseSearch.StockRow stock(StockStatus status, LocalDate expiry, int onHand, int reserved) {
        return new StockBrowseSearch.StockRow(UUID.randomUUID(), "CUP-12OZ", "HCM-A01-1-A", "L1", expiry, onHand,
                reserved, status, NOW);
    }

    private StockBrowsing.StockRow oneStockRow(StockBrowseSearch.StockRow row) {
        when(warehouses.prefixOf(HCM)).thenReturn(Optional.of("HCM"));
        when(search.stock(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row)));
        return service.warehouseStock(HCM, new StockBrowsing.StockQuery(null, null, null, null, false, 0, 20, null), BookVisibility.FULL)
                .items().get(0);
    }

    @Nested
    @DisplayName("reservations")
    class Reservations {

        @Test
        @DisplayName("the warehouse filter is trimmed and upper-cased; a blank one is no filter")
        void warehouseFilterNormalised() {
            when(search.reservations(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.reservations(new StockBrowsing.ReservationQuery(null, "CUP-12OZ", List.of(ReservationStatus.HELD),
                    " hcm ", 0, 20));
            service.reservations(new StockBrowsing.ReservationQuery(null, null, null, "  ", 0, 20));

            ArgumentCaptor<StockBrowseSearch.ReservationCriteria> criteria =
                    ArgumentCaptor.forClass(StockBrowseSearch.ReservationCriteria.class);
            verify(search, org.mockito.Mockito.times(2)).reservations(criteria.capture(), any(Pageable.class));
            assertThat(criteria.getAllValues().get(0).warehousePrefix()).isEqualTo("HCM");
            assertThat(criteria.getAllValues().get(1).warehousePrefix()).isNull();
        }

        @Test
        @DisplayName("a clerk asking for another warehouse is OUT_OF_DATA_SCOPE before anything is read")
        void otherWarehouseRefused() {
            WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                    assertThat(codeOf(() -> service.reservations(new StockBrowsing.ReservationQuery(null, null, null,
                            "HN", 0, 20)))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
            verify(search, never()).reservations(any(), any());
        }

        @Test
        @DisplayName("rows are mapped to views field for field")
        void mapped() {
            StockBrowseSearch.ReservationRow row = reservationAt("HCM-A01-1-A");
            when(search.reservations(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row)));

            PageResponse<StockBrowsing.Reservation> page = service.reservations(
                    new StockBrowsing.ReservationQuery(null, null, null, null, 0, 20));

            assertThat(page.items()).singleElement().satisfies(view -> {
                assertThat(view.reservationId()).isEqualTo(row.reservationId());
                assertThat(view.locationCode()).isEqualTo("HCM-A01-1-A");
                assertThat(view.quantity()).isEqualTo(12);
                assertThat(view.status()).isEqualTo(ReservationStatus.HELD);
            });
        }

        @Test
        @DisplayName("an unknown reservation is RESERVATION_NOT_FOUND")
        void unknown() {
            UUID id = UUID.randomUUID();
            when(search.reservation(id)).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.reservation(id))).isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
        }

        @Test
        @DisplayName("a reservation in another warehouse's bin is OUT_OF_DATA_SCOPE for a clerk")
        void foreignBin() {
            StockBrowseSearch.ReservationRow row = reservationAt("HN-A01-1-A");
            when(search.reservation(row.reservationId())).thenReturn(Optional.of(row));

            WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                    assertThat(codeOf(() -> service.reservation(row.reservationId())))
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("SCRUM-152: a SKU reservation sits in no warehouse, so a warehouse-limited clerk cannot see it")
        void skuReservationHiddenFromClerk() {
            StockBrowseSearch.ReservationRow row = reservationAt(null);
            when(search.reservation(row.reservationId())).thenReturn(Optional.of(row));

            WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                    assertThat(codeOf(() -> service.reservation(row.reservationId())))
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("an unrestricted caller sees a SKU reservation")
        void skuReservationVisibleToPlanner() {
            StockBrowseSearch.ReservationRow row = reservationAt(null);
            when(search.reservation(row.reservationId())).thenReturn(Optional.of(row));

            assertThat(service.reservation(row.reservationId()).locationCode()).isNull();
        }

        @Test
        @DisplayName("a clerk sees a reservation in their own warehouse")
        void ownBin() {
            StockBrowseSearch.ReservationRow row = reservationAt("HCM-A01-1-A");
            when(search.reservation(row.reservationId())).thenReturn(Optional.of(row));

            WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                    assertThat(service.reservation(row.reservationId()).quantity()).isEqualTo(12));
        }
    }

    @Nested
    @DisplayName("a warehouse's stock")
    class WarehouseStock {

        @Test
        @DisplayName("an unknown warehouse is WAREHOUSE_NOT_FOUND")
        void unknownWarehouse() {
            when(warehouses.prefixOf(HCM)).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.warehouseStock(HCM,
                    new StockBrowsing.StockQuery(null, null, null, null, false, 0, 20, null), BookVisibility.FULL)))
                    .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        }

        @Test
        @DisplayName("a clerk cannot browse another warehouse's stock")
        void foreignWarehouse() {
            when(warehouses.prefixOf(HN)).thenReturn(Optional.of("HN"));

            WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                    assertThat(codeOf(() -> service.warehouseStock(HN,
                            new StockBrowsing.StockQuery(null, null, null, null, false, 0, 20, null), BookVisibility.FULL)))
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("a location filter must be a location of that warehouse")
        void locationOfAnotherWarehouse() {
            when(warehouses.prefixOf(HCM)).thenReturn(Optional.of("HCM"));

            assertThat(codeOf(() -> service.warehouseStock(HCM,
                    new StockBrowsing.StockQuery("HN-A01", null, null, null, false, 0, 20, null), BookVisibility.FULL)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            verify(search, never()).stock(any(), any());
        }

        @Test
        @DisplayName("the location filter is trimmed and passed with the warehouse prefix")
        void locationFilterPassed() {
            when(warehouses.prefixOf(HCM)).thenReturn(Optional.of("HCM"));
            when(search.stock(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.warehouseStock(HCM, new StockBrowsing.StockQuery(" hcm-a01 ", "CUP-12OZ", null, null, true, 0, 20,
                    null), BookVisibility.FULL);

            ArgumentCaptor<StockBrowseSearch.StockCriteria> criteria =
                    ArgumentCaptor.forClass(StockBrowseSearch.StockCriteria.class);
            verify(search).stock(criteria.capture(), any(Pageable.class));
            assertThat(criteria.getValue().warehousePrefix()).isEqualTo("HCM");
            assertThat(criteria.getValue().locationPrefix()).isEqualTo("hcm-a01");
            assertThat(criteria.getValue().includeEmpty()).isTrue();
        }

        @Test
        @DisplayName("an unknown sort column is refused")
        void unknownSort() {
            when(warehouses.prefixOf(HCM)).thenReturn(Optional.of("HCM"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.warehouseStock(HCM,
                    new StockBrowsing.StockQuery(null, null, null, null, false, 0, 20, "password,asc"), BookVisibility.FULL))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("available is on hand minus reserved for sellable stock")
        void availableIsOnHandMinusReserved() {
            assertThat(oneStockRow(stock(StockStatus.AVAILABLE, null, 50, 12)).available()).isEqualTo(38);
        }

        @Test
        @DisplayName("available is never negative")
        void availableNeverNegative() {
            assertThat(oneStockRow(stock(StockStatus.AVAILABLE, null, 5, 9)).available()).isZero();
        }

        @Test
        @DisplayName("stock that is not AVAILABLE - quarantine, blocked - has nothing available")
        void notSellableHasNoAvailable() {
            assertThat(oneStockRow(stock(StockStatus.QUARANTINE, null, 50, 0)).available()).isZero();
        }

        @Test
        @DisplayName("expired stock has nothing available, stock expiring today still has")
        void expiryBoundary() {
            assertThat(oneStockRow(stock(StockStatus.AVAILABLE, TODAY.minusDays(1), 50, 0)).available()).isZero();
            assertThat(oneStockRow(stock(StockStatus.AVAILABLE, TODAY, 50, 0)).available()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("blind reading of counted slots (SCRUM-146)")
    class Blind {

        private StockBrowsing.StockQuery query(String sort) {
            return new StockBrowsing.StockQuery(null, null, null, null, false, 0, 20, sort);
        }

        @Test
        @DisplayName("a blind reader gets the quantities of a slot a count holds masked, the other rows as they are")
        void heldRowsAreMasked() {
            var held = stock(StockStatus.AVAILABLE, null, 50, 12);
            var free = stock(StockStatus.AVAILABLE, null, 30, 0);
            when(warehouses.prefixOf(HCM)).thenReturn(Optional.of("HCM"));
            when(search.stock(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(held, free)));
            when(underCount.stockItemsUnderCount(any())).thenReturn(Set.of(held.stockItemId()));

            var rows = service.warehouseStock(HCM, query(null), BookVisibility.HIDE_COUNTED_SLOTS).items();

            assertThat(rows.get(0).hiddenDuringCount()).isTrue();
            assertThat(rows.get(0).onHand()).isNull();
            assertThat(rows.get(0).reserved()).isNull();
            assertThat(rows.get(0).available()).isNull();
            assertThat(rows.get(0).sku()).isEqualTo("CUP-12OZ");
            assertThat(rows.get(1).hiddenDuringCount()).isFalse();
            assertThat(rows.get(1).onHand()).isEqualTo(30);
        }

        @Test
        @DisplayName("an approver sees every quantity and the count lookup is never made")
        void fullVisibilitySeesEverything() {
            var row = stock(StockStatus.AVAILABLE, null, 50, 12);

            assertThat(oneStockRow(row).onHand()).isEqualTo(50);
            verify(underCount, never()).stockItemsUnderCount(any());
        }

        @Test
        @DisplayName("ordering by on hand is dropped for a blind reader, kept for an approver")
        void onHandOrderingIsNotOfferedToABlindReader() {
            when(warehouses.prefixOf(HCM)).thenReturn(Optional.of("HCM"));
            when(search.stock(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.warehouseStock(HCM, query("onHand,desc"), BookVisibility.HIDE_COUNTED_SLOTS);
            service.warehouseStock(HCM, query("onHand,desc"), BookVisibility.FULL);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(search, org.mockito.Mockito.times(2)).stock(any(), pageable.capture());
            assertThat(pageable.getAllValues().get(0).getSort().getOrderFor("onHand")).isNull();
            assertThat(pageable.getAllValues().get(1).getSort().getOrderFor("onHand")).isNotNull();
        }
    }
}
