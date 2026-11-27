package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** The back-office queue of cancellation requests. */
public interface CancellationRequestSearch {

    /** {@code status} and {@code orderId} null mean "any". */
    Page<CancellationRequest> search(CancellationRequestStatus status, UUID orderId, Pageable pageable);

    /**
     * What Sales weigh a request against (R5-6), of the orders a page of requests names.
     *
     * @param total             the order's total with VAT
     * @param printingStartedAt when its printing was recorded started (17 §4.7: from then on a cancellation costs a fee);
     *                          null before
     */
    record OrderFacts(UUID orderId, String orderNumber, UUID customerId, String status, java.math.BigDecimal total,
                      String currency, java.time.Instant printingStartedAt) {
    }

    /** {@link OrderFacts} of each of {@code orderIds} in one statement, by order id; an unknown id is absent. */
    java.util.Map<UUID, OrderFacts> orderFacts(java.util.Collection<UUID> orderIds);
}
