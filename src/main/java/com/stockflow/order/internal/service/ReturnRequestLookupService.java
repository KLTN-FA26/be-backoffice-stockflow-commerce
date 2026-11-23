package com.stockflow.order.internal.service;

import com.stockflow.order.api.ReturnRequestLookup;
import com.stockflow.order.api.ReturnRequestReference;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnClaims;
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
 * ({@code check_return_receipt}) - and takes only what it approved ({@code claimedLines}, BE-22). For a return withdrawn
 * after approval or expired, {@code receiptOpenable} also asks what its order and the order's other returns say now
 * ({@link ReturnRequest#receiptRefusal}, audit round 2 M2 and L-3): not on a CLOSED order, and not once another return
 * claimed the units it approved - the screen says so before a draft is opened, as the draft itself would refuse.
 */
@Service
@Transactional(readOnly = true)
public class ReturnRequestLookupService implements ReturnRequestLookup {

    private final ReturnRequestRepository returns;
    private final OrderRepository orders;

    public ReturnRequestLookupService(ReturnRequestRepository returns, OrderRepository orders) {
        this.returns = returns;
        this.orders = orders;
    }

    @Override
    public Optional<ReturnRequestReference> find(UUID returnRequestId) {
        return returns.findById(returnRequestId).map(this::reference);
    }

    /**
     * Whether a receipt may be opened for it now, and why not. A live return (awaiting, back, refund pending) is checked
     * on its own: its order cannot be CLOSED while it is open, and its approval is its claim. Only a closed one reads its
     * order and the other returns - on the few paths that ask.
     */
    private ReturnRequestReference reference(ReturnRequest r) {
        if (r.receiptOpenable() && r.status() != ReturnStatus.CANCELLED && r.status() != ReturnStatus.EXPIRED) {
            return reference(r, true, null, List.of());
        }
        if (!r.receiptOpenable()) {
            return reference(r, false, ("Return %s is %s: its goods are received while it is approved and its refund "
                    + "not settled; anything else that came back is kept in custody on the return request")
                    .formatted(r.rmaNumber(), r.status()), List.of());
        }
        List<ReturnRequest> all = returns.findByOrder(r.orderId());
        return orders.findById(new OrderId(r.orderId())).map(order -> {
            String refusal = r.receiptRefusal(order, ReturnClaims.ofOthers(all, r.id()));
            List<String> holders = refusal == null || order.status() == com.stockflow.order.api.OrderStatus.CLOSED
                    ? List.<String>of() : r.claimHolders(all);
            return reference(r, refusal == null, refusal, holders);
        }).orElseGet(() -> reference(r, false, "Order %s not found".formatted(r.orderNumber()), List.of()));
    }

    static ReturnRequestReference reference(ReturnRequest r, boolean receiptOpenable, String refusal,
                                            List<String> claimedBy) {
        Map<String, Integer> approved = new LinkedHashMap<>();
        for (ReturnLine line : r.lines()) {
            approved.merge(line.sku(), line.approvedQuantity() == null ? 0 : line.approvedQuantity(), Integer::sum);
        }
        List<ReturnRequestReference.ClaimedLine> claimed = approved.entrySet().stream()
                .map(e -> new ReturnRequestReference.ClaimedLine(e.getKey(), e.getValue())).toList();
        return new ReturnRequestReference(r.id(), r.rmaNumber(), r.orderId(), r.orderNumber(), r.customerId(),
                r.status().name(), r.status() == ReturnStatus.AWAITING_RETURN, r.shipmentId(), receiptOpenable,
                claimed, r.openDraftReceiptId(), refusal, claimedBy);
    }
}
