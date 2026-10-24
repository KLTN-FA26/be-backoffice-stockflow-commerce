package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.inventory.api.ExpiryAlertView;
import com.stockflow.inventory.api.LotTraceQueries;
import com.stockflow.inventory.api.LotTraceView;
import com.stockflow.inventory.api.StockAlertQueries;
import com.stockflow.inventory.internal.domain.LotTrace;
import com.stockflow.inventory.internal.domain.StockAlert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two published lookups the assistant needs (ADR-0010): they ask the same internal services the lot and alert
 * screens use — so the warehouse rule stays in one place — and only reshape what comes back.
 */
class InventoryReportQueriesTest {

    @Test
    @DisplayName("a lot number finds every lot printed with it, each traced through the lot screen's own report")
    void traceLot() {
        LotTraces traces = mock(LotTraces.class);
        UUID lotId = UUID.randomUUID();
        LotTrace.Summary summary = new LotTrace.Summary(lotId, "CUP-12OZ", "L-2310", LocalDate.of(2027, 1, 31),
                Instant.parse("2026-10-01T03:00:00Z"), "GOODS_RECEIPT", UUID.randomUUID(), "GR-001", UUID.randomUUID(),
                "PO-001", UUID.randomUUID(), "Giấy Sài Gòn", UUID.randomUUID(), 1000, 600, 500, 100);
        when(traces.lots(any())).thenReturn(PageResponse.of(List.of(summary), 0, 5, 1));
        when(traces.trace(lotId, null)).thenReturn(new LotTrace(summary,
                List.of(new LotTrace.Balance("HCM-A01-1-B", "AVAILABLE", 600, 100)),
                List.of(new LotTrace.Hold(UUID.randomUUID(), UUID.randomUUID(), "SO-7", null, null, "HCM-A01-1-B", 100,
                        "ALLOCATED", Instant.parse("2026-10-20T03:00:00Z"), null)),
                List.of(new LotTrace.OrderLeg(UUID.randomUUID(), "SO-5", 400, 0,
                        Instant.parse("2026-10-10T03:00:00Z"), Instant.parse("2026-10-10T04:00:00Z")))));

        LotTraceQueries.Traced traced = new LotTraceQueriesImpl(traces).traceLot("  L-2310 ", null, 5);

        assertThat(traced.totalLots()).isEqualTo(1);
        assertThat(traced.lots()).singleElement().satisfies(lot -> {
            assertThat(lot.lotNumber()).isEqualTo("L-2310");
            assertThat(lot.purchaseOrderNumber()).isEqualTo("PO-001");
            assertThat(lot.orders()).extracting(LotTraceView.OrderLeg::orderNumber, LotTraceView.OrderLeg::picked)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("SO-5", 400L));
            assertThat(lot.holds()).extracting(LotTraceView.Hold::orderNumber).containsExactly("SO-7");
        });
        // No warehouse named: the report narrows to the caller's own warehouses itself.
        verify(traces).lots(eq(new LotTraces.Query(null, "L-2310", null, null, null, 0, 5)));
        assertThatThrownBy(() -> new LotTraceQueriesImpl(traces).traceLot(" ", null, 5))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new LotTraceQueriesImpl(traces).traceLot("L", null, 11))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a supplier date code printed on many SKUs: the total says how many lots were not traced")
    void traceLotReportsTheTotal() {
        LotTraces traces = mock(LotTraces.class);
        when(traces.lots(any())).thenReturn(PageResponse.of(List.of(), 0, 5, 8));

        assertThat(new LotTraceQueriesImpl(traces).traceLot("231025", " CUP-12OZ ", 5).totalLots()).isEqualTo(8);
        verify(traces).lots(eq(new LotTraces.Query("CUP-12OZ", "231025", null, null, null, 0, 5)));
    }

    @Test
    @DisplayName("open expiry alerts come back soonest expiry first, across every page, expired ones apart")
    void openExpiryAlerts() {
        StockAlerts alerts = mock(StockAlerts.class);
        StockAlerts.AlertView later = alert(StockAlert.Kind.EXPIRING, "CUP-1", LocalDate.of(2026, 11, 20), 26L);
        StockAlerts.AlertView expired = alert(StockAlert.Kind.EXPIRED, "LID-1", LocalDate.of(2026, 10, 20), -5L);
        StockAlerts.AlertView sooner = alert(StockAlert.Kind.EXPIRING, "BOX-1", LocalDate.of(2026, 11, 1), 7L);
        when(alerts.list(any(), any(), isNull(), isNull(), eq(0), anyInt(), isNull()))
                .thenReturn(new PageResponse<>(List.of(later, expired), 0, 200, 3, 2, true, false));
        when(alerts.list(any(), any(), isNull(), isNull(), eq(1), anyInt(), isNull()))
                .thenReturn(new PageResponse<>(List.of(sooner), 1, 200, 3, 2, false, true));

        StockAlertQueries.ExpiryAlerts found = new StockAlertQueriesImpl(alerts).openExpiryAlerts(30, 10);

        assertThat(found.expiring()).extracting(ExpiryAlertView::sku).containsExactly("BOX-1", "CUP-1");
        assertThat(found.expired()).extracting(ExpiryAlertView::sku).containsExactly("LID-1");
        assertThat(found.expired().getFirst().expired()).isTrue();
        // The window applies to EXPIRING only; a limit of 1 keeps the soonest of each kind and the totals stay exact.
        StockAlertQueries.ExpiryAlerts week = new StockAlertQueriesImpl(alerts).openExpiryAlerts(7, 1);
        assertThat(week.expiring()).extracting(ExpiryAlertView::sku).containsExactly("BOX-1");
        assertThat(week.expiringTotal()).isEqualTo(1);
        assertThat(week.expiredTotal()).isEqualTo(1);
        assertThat(new StockAlertQueriesImpl(alerts).openExpiryAlerts(30, 1).expiringTotal()).isEqualTo(2);
        assertThatThrownBy(() -> new StockAlertQueriesImpl(alerts).openExpiryAlerts(30, 0))
                .isInstanceOf(BusinessException.class);
    }

    private static StockAlerts.AlertView alert(StockAlert.Kind kind, String sku, LocalDate expiry, Long days) {
        return new StockAlerts.AlertView(UUID.randomUUID(), kind, sku, UUID.randomUUID(), "HCM", "L-" + sku, expiry,
                days, 30, null, false, StockAlert.Status.OPEN, Instant.parse("2026-10-24T19:00:00Z"),
                Instant.parse("2026-10-24T19:00:00Z"), null, null, null, null, null, null, 0);
    }
}
