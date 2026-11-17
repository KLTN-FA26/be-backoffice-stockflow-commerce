package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.api.LotTraceQueries;
import com.stockflow.inventory.api.LotTraceView;
import com.stockflow.inventory.internal.domain.LotTrace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * {@link LotTraceQueries} over {@link LotTraces}, so the published lookup and the lot screen are one report with one
 * warehouse rule: {@link LotTraceService} narrows both to the caller's warehouses, nothing here does it again.
 *
 * <p><b>The blind count too.</b> The lot screen passes {@code BlindCount.visibilityFor(user)}; this lookup is the same
 * person's read, so it classifies the caller by the same rule ({@link BookVisibility#of}) instead of the
 * {@code FULL} default, which would hand a counter the books of a slot they are counting (kltn-docs 20 BR-07).</p>
 */
@Service
@Transactional(readOnly = true)
class LotTraceQueriesImpl implements LotTraceQueries {

    private final LotTraces traces;
    private final CurrentUserProvider currentUsers;

    LotTraceQueriesImpl(LotTraces traces, CurrentUserProvider currentUsers) {
        this.traces = traces;
        this.currentUsers = currentUsers;
    }

    @Override
    public Traced traceLot(String lotNumber, String sku, int limit) {
        if (lotNumber == null || lotNumber.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "lotNumber is required");
        }
        if (limit < 1 || limit > MAX_LOTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_LOTS);
        }
        var page = traces.lots(new LotTraces.Query(sku == null || sku.isBlank() ? null : sku.strip(),
                lotNumber.strip(), null, null, null, 0, limit));
        BookVisibility visibility = BookVisibility.of(currentUsers.current());
        return new Traced(page.items().stream().map(lot -> view(traces.trace(lot.lotId(), null, visibility)))
                .toList(), page.totalElements());
    }

    private static LotTraceView view(LotTrace trace) {
        LotTrace.Summary lot = trace.lot();
        return new LotTraceView(lot.sku(), lot.lotNumber(), lot.receivedAt(), lot.origin(),
                lot.goodsReceiptNumber(), lot.productionOrderCode(),
                lot.receivedQuantity(), lot.onHand(), lot.available(), lot.held(),
                trace.balances().stream()
                        .map(b -> new LotTraceView.Balance(b.locationCode(), b.status(), b.onHand(), b.held(),
                                b.hiddenDuringCount()))
                        .toList(),
                trace.holds().stream()
                        .map(h -> new LotTraceView.Hold(h.orderNumber(), h.locationCode(), h.quantity(), h.status(),
                                h.reservedAt(), h.closedAt()))
                        .toList(),
                trace.orders().stream()
                        .map(o -> new LotTraceView.OrderLeg(o.orderNumber(), o.picked(), o.putBack(), o.firstAt(),
                                o.lastAt()))
                        .toList());
    }
}
