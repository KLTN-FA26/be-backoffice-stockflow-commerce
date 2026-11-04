package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The paged lists of returns: the back-office queue and a customer's own. A projection of the header, never the
 * aggregate - a page of returns loads no lines, photos or history (list serves a row, detail the aggregate).
 */
public interface ReturnRequestSearch {

    /**
     * Every filter null (or empty) means "any".
     *
     * @param text            part of the RMA number or of the order number
     * @param vatAdjustment   true: resolved returns whose money needs an adjustment invoice (accounting's list)
     */
    record Criteria(List<ReturnStatus> statuses, UUID customerId, UUID orderId, String text, Boolean vatAdjustment) {
    }

    /** One return as a list shows it. */
    record ReturnRequestRow(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                            ReturnStatus status, ReturnReason reason, ReturnResolution requestedResolution,
                            String channel, Instant requestedAt, Instant returnDeadline, BigDecimal settlementAmount,
                            BigDecimal refundedAmount, BigDecimal creditedAmount, BigDecimal offsetAmount,
                            String currency, boolean vatAdjustmentRequired, UUID replacementOrderId,
                            Instant closedAt, long version) {
    }

    Page<ReturnRequestRow> search(Criteria criteria, Pageable pageable);
}
