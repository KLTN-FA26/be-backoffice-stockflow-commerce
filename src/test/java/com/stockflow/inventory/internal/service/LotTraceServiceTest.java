package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.internal.domain.LotTrace;
import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.inventory.internal.repository.LotTraceSearch;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SCRUM-153: lot traceability, narrowed to the caller's warehouses (BR-SEC-002) and read in Vietnam's calendar. */
class LotTraceServiceTest {

    private static final UUID LOT = UUID.randomUUID();
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();

    private final LotTraceSearch search = mock(LotTraceSearch.class);
    private final WarehouseDirectory warehouses = mock(WarehouseDirectory.class);
    private final LotTraceService service = new LotTraceService(search, warehouses, Clock.fixed(NOW, ZoneOffset.UTC));

    private static CurrentUser clerkOf(UUID id, String prefix) {
        return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of(prefix), Set.of(id));
    }

    private static LotTrace.Summary summary() {
        return new LotTrace.Summary(LOT, "INK-RED", "L1", null, NOW, "GOODS_RECEIPT", null, null, null, null, null, null,
                null, 100, 60, 50, 10);
    }

    private ArgumentCaptor<LotTraceSearch.Filter> lotsFiltered() {
        ArgumentCaptor<LotTraceSearch.Filter> filter = ArgumentCaptor.forClass(LotTraceSearch.Filter.class);
        verify(search).countLots(filter.capture());
        return filter;
    }

    @Test
    @DisplayName("blank SKU and lot filters are no filter; a lot number is stripped; the SKU is normalised")
    void filtersAreNormalised() {
        service.lots(new LotTraces.Query("  ", " ", null, null, null, 0, 20));
        LotTraceSearch.Filter blank = lotsFiltered().getValue();
        assertThat(blank.sku()).isNull();
        assertThat(blank.lotNumber()).isNull();
        assertThat(blank.warehouses()).isEmpty();
        assertThat(blank.today()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("a lot number is stripped of spaces")
    void lotNumberStripped() {
        service.lots(new LotTraces.Query("ink-red", " L1 ", null, null, null, 0, 20));

        LotTraceSearch.Filter filter = lotsFiltered().getValue();
        assertThat(filter.lotNumber()).isEqualTo("L1");
        assertThat(filter.sku()).isEqualTo("INK-RED");
    }

    @Test
    @DisplayName("no lots found: the page is empty and the lots query is not run")
    void emptyPageSkipsQuery() {
        when(search.countLots(any())).thenReturn(0L);

        PageResponse<LotTrace.Summary> page = service.lots(new LotTraces.Query(null, null, null, null, null, 0, 20));

        assertThat(page.items()).isEmpty();
        verify(search, never()).lots(any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("lots found: the page carries the offset and total")
    void pageOfLots() {
        when(search.countLots(any())).thenReturn(45L);
        when(search.lots(any(), anyLong(), anyInt())).thenReturn(List.of(summary()));

        PageResponse<LotTrace.Summary> page = service.lots(new LotTraces.Query(null, null, null, null, null, 2, 20));

        verify(search).lots(any(), org.mockito.ArgumentMatchers.eq(40L), org.mockito.ArgumentMatchers.eq(20));
        assertThat(page.totalElements()).isEqualTo(45L);
        assertThat(page.items()).hasSize(1);
    }

    @Test
    @DisplayName("the date range covers whole Vietnamese days: from 00:00 of the first to 00:00 after the last")
    void dateRangeIsInVietnamTime() {
        service.lots(new LotTraces.Query(null, null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 0, 20));

        LotTraceSearch.Filter filter = lotsFiltered().getValue();
        assertThat(filter.from()).isEqualTo(Instant.parse("2026-09-30T17:00:00Z"));
        assertThat(filter.to()).isEqualTo(Instant.parse("2026-10-31T17:00:00Z"));
    }

    @Test
    @DisplayName("a range that ends before it starts is VALIDATION_FAILED")
    void reversedRange() {
        assertThat(codeOf(() -> service.lots(new LotTraces.Query(null, null, null, LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 4), 0, 20)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a named warehouse narrows the report to its prefix")
    void namedWarehouse() {
        when(warehouses.prefixOf(HCM)).thenReturn(Optional.of("HCM"));

        service.lots(new LotTraces.Query(null, null, HCM, null, null, 0, 20));

        assertThat(lotsFiltered().getValue().warehouses()).containsExactly("HCM");
    }

    @Test
    @DisplayName("an unknown warehouse is WAREHOUSE_NOT_FOUND")
    void unknownWarehouse() {
        when(warehouses.prefixOf(HCM)).thenReturn(Optional.empty());

        assertThat(codeOf(() -> service.lots(new LotTraces.Query(null, null, HCM, null, null, 0, 20))))
                .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
    }

    @Test
    @DisplayName("a clerk who names no warehouse is narrowed to their own")
    void clerkIsNarrowedToOwnWarehouses() {
        WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                service.lots(new LotTraces.Query(null, null, null, null, null, 0, 20)));

        assertThat(lotsFiltered().getValue().warehouses()).containsExactly("HCM");
    }

    @Test
    @DisplayName("a clerk who names another warehouse is refused before anything is read")
    void clerkNamingAnotherWarehouse() {
        WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                assertThat(codeOf(() -> service.lots(new LotTraces.Query(null, null, HN, null, null, 0, 20))))
                        .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        verify(search, never()).countLots(any());
    }

    @Test
    @DisplayName("a caller bound to warehouses yet assigned none sees nothing, not everything")
    void unassignedClerkSeesNothing() {
        CurrentUser nobody = new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.WAREHOUSE, Set.of(), Set.of());

        WithCurrentUser.run(nobody, () -> service.lots(new LotTraces.Query(null, null, null, null, null, 0, 20)));

        assertThat(lotsFiltered().getValue().warehouses()).containsExactly("");
    }

    @Test
    @DisplayName("tracing an unknown lot is NOT_FOUND")
    void unknownLot() {
        when(search.lot(LOT, Set.of(), TODAY)).thenReturn(Optional.empty());

        assertThat(codeOf(() -> service.trace(LOT, null))).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("a trace assembles the lot, its balances, holds and the orders it left for, all within the same scope")
    void trace() {
        LotTrace.Balance balance = new LotTrace.Balance("HCM-A01-1-A", "AVAILABLE", 60, 10);
        when(search.lot(LOT, Set.of(), TODAY)).thenReturn(Optional.of(summary()));
        when(search.balances(LOT, Set.of())).thenReturn(List.of(balance));
        when(search.holds(LOT, Set.of())).thenReturn(List.of());
        when(search.orders(LOT, Set.of())).thenReturn(List.of());

        LotTrace trace = service.trace(LOT, null);

        assertThat(trace.lot().lotNumber()).isEqualTo("L1");
        assertThat(trace.balances()).containsExactly(balance);
    }

    @Test
    @DisplayName("a lot that the clerk's warehouses do not hold is NOT_FOUND, not leaked")
    void lotOutsideScope() {
        when(search.lot(LOT, Set.of("HCM"), TODAY)).thenReturn(Optional.empty());

        WithCurrentUser.run(clerkOf(HCM, "HCM"), () ->
                assertThat(codeOf(() -> service.trace(LOT, null))).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("movements of an unknown lot is NOT_FOUND and no movement is read")
    void movementsOfUnknownLot() {
        when(search.lot(LOT, Set.of(), TODAY)).thenReturn(Optional.empty());

        assertThat(codeOf(() -> service.movements(LOT, null, null, null, 0, 20))).isEqualTo(ErrorCode.NOT_FOUND);
        verify(search, never()).countMovements(any(), any(), any(), any());
    }

    @Test
    @DisplayName("movements with none in range: empty page, the movements query is skipped")
    void noMovements() {
        when(search.lot(LOT, Set.of(), TODAY)).thenReturn(Optional.of(summary()));
        when(search.countMovements(any(), any(), any(), any())).thenReturn(0L);

        assertThat(service.movements(LOT, null, null, null, 0, 20).items()).isEmpty();
        verify(search, never()).movements(any(), any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("movements are paged within the Vietnamese-day range")
    void movementsPaged() {
        LocalDate day = LocalDate.of(2026, 10, 22);
        Instant start = Instant.parse("2026-10-21T17:00:00Z");
        Instant end = Instant.parse("2026-10-22T17:00:00Z");
        LotTrace.Movement movement = new LotTrace.Movement(UUID.randomUUID(), "RECEIPT", null, "HCM-RCV01", 100, null,
                "QUARANTINE", "GOODS_RECEIPT", UUID.randomUUID(), null, null, null, NOW, null);
        when(search.lot(LOT, Set.of(), TODAY)).thenReturn(Optional.of(summary()));
        when(search.countMovements(LOT, Set.of(), start, end)).thenReturn(1L);
        when(search.movements(LOT, Set.of(), start, end, 0L, 20)).thenReturn(List.of(movement));

        PageResponse<LotTrace.Movement> page = service.movements(LOT, null, day, day, 0, 20);

        assertThat(page.items()).containsExactly(movement);
        assertThat(page.totalElements()).isEqualTo(1L);
    }
}
