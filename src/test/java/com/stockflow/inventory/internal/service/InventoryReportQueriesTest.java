package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.LotTraceQueries;
import com.stockflow.inventory.api.LotTraceView;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.internal.domain.LotTrace;
import com.stockflow.support.TestUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lot lookup, the adjustment list and the blind-count rows the assistant needs (ADR-0011): each asks the same
 * internal service its screen uses - so the warehouse rule and the blind count's hiding stay in one place - classifies
 * the caller by the screens' own rule (kltn-docs 20 BR-07), and only reshapes what comes back.
 */
class InventoryReportQueriesTest {

    /** Who may decide counts, and so reads the books of a slot under one. */
    private static final CurrentUserProvider APPROVER = users(TestUsers.builder().role(Role.WAREHOUSE_MANAGER)
            .scope(DataScope.ALL).can("inventory-stock-movements", Action.READ)
            .can("inventory-cycle-counts", Action.APPROVE).build());

    /** The seeded counter: counts (UPDATE) without deciding (APPROVE). */
    private static final CurrentUserProvider COUNTER = users(TestUsers.builder().role(Role.WAREHOUSE_STAFF)
            .scope(DataScope.ALL).can("inventory-stock-movements", Action.READ)
            .can("inventory-cycle-counts", Action.UPDATE).build());

    private static CurrentUserProvider users(CurrentUser user) {
        CurrentUserProvider provider = mock(CurrentUserProvider.class);
        when(provider.current()).thenReturn(Optional.of(user));
        return provider;
    }

    private static LotTrace.Summary summary(UUID lotId) {
        return new LotTrace.Summary(lotId, "CUP-12OZ", "L-2310",
                Instant.parse("2026-10-01T03:00:00Z"), "GOODS_RECEIPT", UUID.randomUUID(), "GR-001", UUID.randomUUID(),
                1000, 600, 500, 100, UUID.randomUUID(), "LSX-001");
    }

    @Test
    @DisplayName("a lot number finds every lot printed with it, each traced through the lot screen's own report")
    void traceLot() {
        LotTraces traces = mock(LotTraces.class);
        UUID lotId = UUID.randomUUID();
        LotTrace.Summary summary = summary(lotId);
        when(traces.lots(any())).thenReturn(PageResponse.of(List.of(summary), 0, 5, 1));
        when(traces.trace(lotId, null, BookVisibility.FULL)).thenReturn(new LotTrace(summary,
                List.of(new LotTrace.Balance("HCM-A01-1-B", "AVAILABLE", 600, 100),
                        new LotTrace.Balance("HCM-A02-1-B", "AVAILABLE", 50, 0).hidden()),
                List.of(new LotTrace.Hold(UUID.randomUUID(), UUID.randomUUID(), "SO-7", null, "HCM-A01-1-B", 100,
                        "HELD", Instant.parse("2026-10-20T03:00:00Z"), null)),
                List.of(new LotTrace.OrderLeg(UUID.randomUUID(), "SO-5", 400, 0,
                        Instant.parse("2026-10-10T03:00:00Z"), Instant.parse("2026-10-10T04:00:00Z")))));

        LotTraceQueries.Traced traced = new LotTraceQueriesImpl(traces, APPROVER).traceLot("  L-2310 ", null, 5);

        assertThat(traced.totalLots()).isEqualTo(1);
        assertThat(traced.lots()).singleElement().satisfies(lot -> {
            assertThat(lot.lotNumber()).isEqualTo("L-2310");
            assertThat(lot.productionOrderCode()).isEqualTo("LSX-001");
            // A balance the report hid is passed on hidden: no figures and the flag.
            assertThat(lot.balances()).extracting(LotTraceView.Balance::onHand, LotTraceView.Balance::hiddenDuringCount)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(600, false),
                            org.assertj.core.groups.Tuple.tuple(null, true));
            assertThat(lot.orders()).extracting(LotTraceView.OrderLeg::orderNumber, LotTraceView.OrderLeg::picked)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("SO-5", 400L));
            assertThat(lot.holds()).extracting(LotTraceView.Hold::orderNumber).containsExactly("SO-7");
        });
        // No warehouse named: the report narrows to the caller's own warehouses itself.
        verify(traces).lots(eq(new LotTraces.Query(null, "L-2310", null, null, null, 0, 5)));
        assertThatThrownBy(() -> new LotTraceQueriesImpl(traces, APPROVER).traceLot(" ", null, 5))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new LotTraceQueriesImpl(traces, APPROVER).traceLot("L", null, 11))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a lot number printed on many SKUs: the total says how many lots were not traced")
    void traceLotReportsTheTotal() {
        LotTraces traces = mock(LotTraces.class);
        when(traces.lots(any())).thenReturn(PageResponse.of(List.of(), 0, 5, 8));

        assertThat(new LotTraceQueriesImpl(traces, APPROVER).traceLot("231025", " CUP-12OZ ", 5).totalLots())
                .isEqualTo(8);
        verify(traces).lots(eq(new LotTraces.Query("CUP-12OZ", "231025", null, null, null, 0, 5)));
    }

    @Test
    @DisplayName("a counter is traced as the lot screen traces them, blind to the slots under a count (20 BR-07); so "
            + "is a caller nobody signed in as")
    void traceLotIsBlindForACounter() {
        LotTraces traces = mock(LotTraces.class);
        UUID lotId = UUID.randomUUID();
        LotTrace.Summary summary = summary(lotId);
        when(traces.lots(any())).thenReturn(PageResponse.of(List.of(summary), 0, 5, 1));
        when(traces.trace(eq(lotId), any(), any())).thenReturn(new LotTrace(summary, List.of(), List.of(),
                List.of()));
        CurrentUserProvider nobody = mock(CurrentUserProvider.class);
        when(nobody.current()).thenReturn(Optional.empty());

        new LotTraceQueriesImpl(traces, COUNTER).traceLot("L-2310", null, 5);
        new LotTraceQueriesImpl(traces, nobody).traceLot("L-2310", null, 5);

        verify(traces, times(2)).trace(lotId, null, BookVisibility.HIDE_COUNTED_SLOTS);
        verify(traces, never()).trace(lotId, null, BookVisibility.FULL);
    }

    @Test
    @DisplayName("the adjustment list leaves out counted slots for a counter, as the screen does, and not for who "
            + "decides counts")
    void adjustmentsAreBlindForACounter() {
        StockOperations operations = mock(StockOperations.class);
        when(operations.adjustments(any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(PageResponse.of(List.of(), 0, 10, 0));

        new StockAdjustmentQueriesImpl(operations, COUNTER)
                .adjustments(StockAdjustmentStatus.PENDING_APPROVAL, "CUP-12OZ", null, 0, 10, null);
        new StockAdjustmentQueriesImpl(operations, APPROVER).adjustments(null, null, null, 0, 10, "createdAt,asc");

        verify(operations).adjustments(StockAdjustmentStatus.PENDING_APPROVAL, "CUP-12OZ", null, 0, 10, null,
                BookVisibility.HIDE_COUNTED_SLOTS);
        verify(operations).adjustments(null, null, null, 0, 10, "createdAt,asc", BookVisibility.FULL);
    }

    @Test
    @DisplayName("which stock rows a counter may not read: those under a count; a user who decides counts costs no "
            + "query")
    void blindCountRows() {
        StockBrowsing browsing = mock(StockBrowsing.class);
        UUID counted = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        when(browsing.underCount(any())).thenReturn(Set.of(counted));

        assertThat(new BlindCountQueriesImpl(browsing, COUNTER).hiddenFromCaller(List.of(counted, free)))
                .containsExactly(counted);
        assertThat(new BlindCountQueriesImpl(browsing, COUNTER).hiddenFromCaller(List.of())).isEmpty();
        assertThat(new BlindCountQueriesImpl(browsing, APPROVER).hiddenFromCaller(List.of(counted, free))).isEmpty();
        verify(browsing).underCount(List.of(counted, free));
    }
}
