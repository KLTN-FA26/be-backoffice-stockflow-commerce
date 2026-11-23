package com.stockflow.order.internal.service;

import com.stockflow.order.api.ReturnRequestLookup;
import com.stockflow.order.api.ReturnRequestReference;
import com.stockflow.order.internal.domain.ReturnLine;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.ReturnStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ReturnRequestLookup}: a return request for the warehouse that takes its goods back on a return receipt (team
 * plan 4D-2). A receipt is opened for it while it is approved and its refund not settled
 * ({@link ReturnRequest#receiptOpenable}, WF-05 Q-11) - the state the database also requires when the receipt is opened
 * ({@code check_return_receipt}) - and takes only what it approved ({@code claimedLines}, BE-22).
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
        return returns.findById(returnRequestId).map(ReturnRequestLookupService::reference);
    }

    static ReturnRequestReference reference(ReturnRequest r) {
        Map<String, Integer> approved = new LinkedHashMap<>();
        for (ReturnLine line : r.lines()) {
            approved.merge(line.sku(), line.approvedQuantity() == null ? 0 : line.approvedQuantity(), Integer::sum);
        }
        List<ReturnRequestReference.ClaimedLine> claimed = approved.entrySet().stream()
                .map(e -> new ReturnRequestReference.ClaimedLine(e.getKey(), e.getValue())).toList();
        return new ReturnRequestReference(r.id(), r.rmaNumber(), r.orderId(), r.orderNumber(), r.customerId(),
                r.status().name(), r.status() == ReturnStatus.AWAITING_RETURN, r.shipmentId(), r.receiptOpenable(),
                claimed, r.openDraftReceiptId());
    }
}
