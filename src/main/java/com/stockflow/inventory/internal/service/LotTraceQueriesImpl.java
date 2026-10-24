package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.LotTraceQueries;
import com.stockflow.inventory.api.LotTraceView;
import com.stockflow.inventory.internal.domain.LotTrace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link LotTraceQueries} over {@link LotTraces}, so the published lookup and the lot screen are one report with one
 * warehouse rule: {@link LotTraceService} narrows both to the caller's warehouses, nothing here does it again.
 */
@Service
@Transactional(readOnly = true)
class LotTraceQueriesImpl implements LotTraceQueries {

    private final LotTraces traces;

    LotTraceQueriesImpl(LotTraces traces) {
        this.traces = traces;
    }

    @Override
    public List<LotTraceView> traceLot(String lotNumber, int limit) {
        if (lotNumber == null || lotNumber.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "lotNumber is required");
        }
        if (limit < 1 || limit > MAX_LOTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_LOTS);
        }
        return traces.lots(new LotTraces.Query(null, lotNumber.strip(), null, null, null, 0, limit)).items().stream()
                .map(lot -> view(traces.trace(lot.lotId(), null)))
                .toList();
    }

    private static LotTraceView view(LotTrace trace) {
        LotTrace.Summary lot = trace.lot();
        return new LotTraceView(lot.sku(), lot.lotNumber(), lot.expiryDate(), lot.receivedAt(), lot.origin(),
                lot.goodsReceiptNumber(), lot.purchaseOrderNumber(), lot.supplierName(),
                lot.receivedQuantity(), lot.onHand(), lot.available(), lot.held(),
                trace.balances().stream()
                        .map(b -> new LotTraceView.Balance(b.locationCode(), b.status(), b.onHand(), b.held()))
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
