package com.stockflow.order.internal.service;

import com.stockflow.order.api.ReturnRequestLookup;
import com.stockflow.order.api.ReturnRequestReference;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.ReturnStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link ReturnRequestLookup}: a return request for the warehouse that takes its goods back on a return receipt (team
 * plan 4D-2). Its goods are expected while it is {@code AWAITING_RETURN}, approved and not yet received - the state the
 * database also requires when the receipt is opened ({@code check_return_receipt}).
 */
@Service
@Transactional(readOnly = true)
public class ReturnRequestLookupService implements ReturnRequestLookup {

    private final ReturnRequestRepository returns;

    public ReturnRequestLookupService(ReturnRequestRepository returns) {
        this.returns = returns;
    }

    @Override
    public Optional<ReturnRequestReference> find(UUID returnRequestId) {
        return returns.findById(returnRequestId).map(r -> new ReturnRequestReference(r.id(), r.rmaNumber(),
                r.orderId(), r.orderNumber(), r.customerId(), r.status().name(),
                r.status() == ReturnStatus.AWAITING_RETURN));
    }
}
