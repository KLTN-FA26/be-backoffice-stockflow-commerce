package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The alert inbox: what each caller may see (BR-SEC-002), the optimistic acknowledge, and the view's derived fields. */
class StockAlertsImplTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();
    private static final UUID PLANNER = UUID.randomUUID();

    private final StockAlertRepository alerts = mock(StockAlertRepository.class);
    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final StockAlertsImpl service = new StockAlertsImpl(alerts, policies, warehouses, identities,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static CurrentUser clerkOf(UUID warehouseId, String prefix) {
        return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.WAREHOUSE, Set.of(prefix), Set.of(warehouseId));
    }

    private static StockAlert reorder(UUID id) {
        return StockAlert.open(id, StockAlert.Key.stockLevel(StockAlert.Kind.REORDER, "CUP-12OZ"), 8, 20, false, NOW);
    }

    private static StockAlert expiring(UUID id, UUID warehouse, LocalDate expiry) {
        return StockAlert.open(id, StockAlert.Key.expiry(StockAlert.Kind.EXPIRING, "INK-RED", warehouse, "L1", expiry),
                30, null, false, NOW);
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        @DisplayName("an unknown sort column is refused before the database is asked")
        void unknownSortRefused() {
            assertThatThrownBy(() -> service.list(null, null, null, null, 0, 10, "password,asc"))
                    .isInstanceOf(RuntimeException.class);
            verify(alerts, never()).search(any(), anyString(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("newest first by default, with the id as a stable last tie-break")
        void defaultOrder() {
            when(alerts.search(any(), anyString(), anyInt(), anyLong())).thenReturn(List.of());

            service.list(null, null, null, null, 0, 10, null);

            ArgumentCaptor<String> order = ArgumentCaptor.forClass(String.class);
            verify(alerts).search(any(), order.capture(), eq(10), eq(0L));
            assertThat(order.getValue()).isEqualTo("opened_at DESC, id DESC");
        }

        @Test
        @DisplayName("a caller limited to warehouses is searched within them only")
        void limitedCallerIsNarrowed() {
            when(alerts.search(any(), anyString(), anyInt(), anyLong())).thenReturn(List.of());

            WithCurrentUser.run(clerkOf(HCM, "HCM"), () -> service.list(null, null, null, null, 0, 10, "sku,asc"));

            ArgumentCaptor<StockAlertRepository.Criteria> criteria =
                    ArgumentCaptor.forClass(StockAlertRepository.Criteria.class);
            verify(alerts).search(criteria.capture(), eq("sku ASC, id DESC"), eq(10), eq(0L));
            assertThat(criteria.getValue().allowedWarehouses()).containsExactly(HCM);
        }

        @Test
        @DisplayName("asking for a warehouse the clerk is not assigned to is OUT_OF_DATA_SCOPE")
        void foreignWarehouseRefused() {
            WithCurrentUser.run(clerkOf(HCM, "HCM"), () -> assertThat(codeOf(
                    () -> service.list(null, null, null, HN, 0, 10, null))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("an unrestricted caller is not narrowed")
        void unrestrictedCaller() {
            when(alerts.search(any(), anyString(), anyInt(), anyLong())).thenReturn(List.of());

            service.list(null, null, null, null, 0, 10, null);

            ArgumentCaptor<StockAlertRepository.Criteria> criteria =
                    ArgumentCaptor.forClass(StockAlertRepository.Criteria.class);
            verify(alerts).search(criteria.capture(), anyString(), anyInt(), anyLong());
            assertThat(criteria.getValue().allowedWarehouses()).isNull();
        }

        @Test
        @DisplayName("an expiry alert shows its warehouse code and the days left, counted in Vietnam's calendar")
        void expiryViewHasDaysAndWarehouseCode() {
            UUID id = UUID.randomUUID();
            // NOW is 10:00 on 22 Oct in Ho Chi Minh City: 5 Nov is 14 days away.
            StockAlert alert = expiring(id, HCM, LocalDate.of(2026, 11, 5));
            when(alerts.search(any(), anyString(), anyInt(), anyLong())).thenReturn(List.of(alert));
            when(alerts.count(any())).thenReturn(1L);
            when(warehouses.findWarehouse(HCM)).thenReturn(Optional.of(
                    new WarehouseView(HCM, "HCM", "Ho Chi Minh", null, true)));

            PageResponse<StockAlerts.AlertView> page = service.list(null, null, null, null, 0, 10, null);

            assertThat(page.totalElements()).isEqualTo(1L);
            StockAlerts.AlertView view = page.items().get(0);
            assertThat(view.warehouseCode()).isEqualTo("HCM");
            assertThat(view.daysToExpiry()).isEqualTo(14L);
        }

        @Test
        @DisplayName("an expired lot has negative days to expiry")
        void expiredLotIsNegative() {
            StockAlert alert = expiring(UUID.randomUUID(), HCM, LocalDate.of(2026, 10, 20));
            when(alerts.search(any(), anyString(), anyInt(), anyLong())).thenReturn(List.of(alert));
            when(warehouses.findWarehouse(HCM)).thenReturn(Optional.empty());

            StockAlerts.AlertView view = service.list(null, null, null, null, 0, 10, null).items().get(0);

            assertThat(view.daysToExpiry()).isEqualTo(-2L);
            assertThat(view.warehouseCode()).isNull();
        }

        @Test
        @DisplayName("a stock-level alert is per SKU: no warehouse, no expiry")
        void stockLevelView() {
            when(alerts.search(any(), anyString(), anyInt(), anyLong())).thenReturn(List.of(reorder(UUID.randomUUID())));

            StockAlerts.AlertView view = service.list(null, null, null, null, 0, 10, null).items().get(0);

            assertThat(view.warehouseId()).isNull();
            assertThat(view.warehouseCode()).isNull();
            assertThat(view.daysToExpiry()).isNull();
            assertThat(view.observedQty()).isEqualTo(8);
            assertThat(view.thresholdQty()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("reading one alert")
    class Reading {

        @Test
        @DisplayName("an unknown id is ALERT_NOT_FOUND")
        void unknown() {
            UUID id = UUID.randomUUID();
            when(alerts.findById(id)).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.get(id))).isEqualTo(ErrorCode.ALERT_NOT_FOUND);
        }

        @Test
        @DisplayName("a clerk cannot open an alert of another warehouse")
        void foreignAlertRefused() {
            UUID id = UUID.randomUUID();
            when(alerts.findById(id)).thenReturn(Optional.of(expiring(id, HN, LocalDate.of(2026, 11, 5))));

            WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                    assertThat(codeOf(() -> service.get(id))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("a warehouse-limited clerk cannot open a per-SKU alert, which belongs to no warehouse")
        void perSkuAlertIsOutOfScopeForAClerk() {
            UUID id = UUID.randomUUID();
            when(alerts.findById(id)).thenReturn(Optional.of(reorder(id)));

            WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                    assertThat(codeOf(() -> service.get(id))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }
    }

    @Nested
    @DisplayName("summary")
    class Summary {

        @Test
        @DisplayName("every kind is present, zero when nothing is open")
        void everyKindPresent() {
            when(alerts.openCountsByKind(null)).thenReturn(Map.of(StockAlert.Kind.REORDER, 4L));

            Map<StockAlert.Kind, Long> counts = service.summary();

            assertThat(counts).containsEntry(StockAlert.Kind.REORDER, 4L)
                    .containsEntry(StockAlert.Kind.SAFETY, 0L)
                    .containsEntry(StockAlert.Kind.EXPIRING, 0L)
                    .containsEntry(StockAlert.Kind.EXPIRED, 0L);
        }

        @Test
        @DisplayName("a clerk's counts are those of their warehouses")
        void clerkCountsAreScoped() {
            when(alerts.openCountsByKind(Set.of(HCM))).thenReturn(Map.of(StockAlert.Kind.EXPIRED, 2L));

            WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                    assertThat(service.summary()).containsEntry(StockAlert.Kind.EXPIRED, 2L));
        }
    }

    @Nested
    @DisplayName("SKUs without thresholds")
    class Unconfigured {

        @Test
        @DisplayName("thresholds are per SKU for the whole system: a warehouse-limited caller does not get the list")
        void notPerWarehouse() {
            WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                    assertThat(codeOf(() -> service.unconfigured(0, 20))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("usable on-hand is counted as of today in Vietnam, and the total comes from the count query")
        void listsRows() {
            LocalDate today = LocalDate.of(2026, 10, 22);
            when(policies.unconfigured(20, 0L, today)).thenReturn(List.of(
                    new InventoryPolicyRepository.UnconfiguredRow("CUP-12OZ", 12, null)));
            when(policies.countUnconfigured(today)).thenReturn(41L);

            PageResponse<StockAlerts.UnconfiguredView> page = service.unconfigured(0, 20);

            assertThat(page.totalElements()).isEqualTo(41L);
            assertThat(page.items()).containsExactly(new StockAlerts.UnconfiguredView("CUP-12OZ", 12, null));
        }
    }

    @Nested
    @DisplayName("acknowledging")
    class Acknowledging {

        @Test
        @DisplayName("an alert changed since it was read is OPTIMISTIC_LOCK, and nothing is written")
        void staleVersion() {
            UUID id = UUID.randomUUID();
            when(alerts.findByIdForUpdate(id)).thenReturn(Optional.of(reorder(id)));

            assertThat(codeOf(() -> service.acknowledge(id, 5, "seen", PLANNER))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            verify(alerts, never()).update(any());
        }

        @Test
        @DisplayName("an unknown alert is ALERT_NOT_FOUND")
        void unknown() {
            UUID id = UUID.randomUUID();
            when(alerts.findByIdForUpdate(id)).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.acknowledge(id, 0, null, PLANNER))).isEqualTo(ErrorCode.ALERT_NOT_FOUND);
        }

        @Test
        @DisplayName("an open alert becomes ACKNOWLEDGED, by whom and when, with the trimmed note, and is answered with the name")
        void acknowledges() {
            UUID id = UUID.randomUUID();
            StockAlert alert = reorder(id);
            when(alerts.findByIdForUpdate(id)).thenReturn(Optional.of(alert));
            when(alerts.findById(id)).thenReturn(Optional.of(alert));
            when(identities.displayNames(Set.of(PLANNER))).thenReturn(Map.of(PLANNER, "Lan Nguyen"));

            StockAlerts.AlertView view = service.acknowledge(id, 0, "  PO raised  ", PLANNER);

            verify(alerts).update(alert);
            assertThat(view.status()).isEqualTo(StockAlert.Status.ACKNOWLEDGED);
            assertThat(view.acknowledgedBy()).isEqualTo(PLANNER);
            assertThat(view.acknowledgedByName()).isEqualTo("Lan Nguyen");
            assertThat(view.acknowledgedAt()).isEqualTo(NOW);
            assertThat(view.ackNote()).isEqualTo("PO raised");
        }

        @Test
        @DisplayName("an alert that is already acknowledged cannot be acknowledged again")
        void twiceIsRefused() {
            UUID id = UUID.randomUUID();
            StockAlert alert = reorder(id);
            alert.acknowledge(UUID.randomUUID(), null, Instant.parse("2026-10-21T00:00:00Z"));
            when(alerts.findByIdForUpdate(id)).thenReturn(Optional.of(alert));

            assertThat(codeOf(() -> service.acknowledge(id, 0, null, PLANNER)))
                    .isEqualTo(ErrorCode.INVALID_ALERT_TRANSITION);
            verify(alerts, never()).update(any());
        }

        @Test
        @DisplayName("a clerk cannot acknowledge an alert of another warehouse")
        void foreignAlertRefused() {
            UUID id = UUID.randomUUID();
            when(alerts.findByIdForUpdate(id)).thenReturn(Optional.of(expiring(id, HN, LocalDate.of(2026, 11, 5))));

            WithCurrentUser.run(clerkOf(HCM, "HCM"), () -> assertThat(codeOf(
                    () -> service.acknowledge(id, 0, null, PLANNER))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
            verify(alerts, never()).update(any());
        }
    }
}
