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
}
