package com.stockflow.order.internal.service;

import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.repository.CancellationRequestSearch;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.PaymentService;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What Sales weigh a cancellation request against, beside each row of the queue (R5-6): the order, whose it is, what it
 * is worth, what was paid on it and whether its printing has started - the facts that decide whether a cancellation
 * costs a fee (17 §4.7) and how much of the money is kept. Without them Sales learned it by trying and reading a 409.
 * Three reads for the page, whatever its size: the orders, their customers' names, their installments.
 */
@Service
@Transactional(readOnly = true)
public class CancellationQueue {

    private final CancellationRequestSearch search;
    private final CustomerService customers;
    private final PaymentService payments;

    CancellationQueue(CancellationRequestSearch search, CustomerService customers, PaymentService payments) {
        this.search = search;
        this.customers = customers;
        this.payments = payments;
    }

    /**
     * @param paidTotal       what the customer paid on the order's installments; 0 before a contract is signed
     * @param printingStarted whether its printing was recorded started
     */
    public record Facts(String orderNumber, String customerName, BigDecimal orderTotal, BigDecimal paidTotal,
                        String orderStatus, boolean printingStarted) {
    }

    /** By order id, for the orders {@code requests} name. */
    public Map<UUID, Facts> of(Collection<CancellationRequest> requests) {
        List<UUID> orderIds = requests.stream().map(CancellationRequest::orderId).filter(Objects::nonNull).distinct()
                .toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CancellationRequestSearch.OrderFacts> orders = search.orderFacts(orderIds);
        Map<UUID, CustomerLabel> labels = customers.labels(orders.values().stream()
                .map(CancellationRequestSearch.OrderFacts::customerId).filter(Objects::nonNull).distinct().toList());
        Map<UUID, InstallmentStanding> standings = payments.installmentStandings(orders.keySet());
        Map<UUID, Facts> facts = new HashMap<>();
        orders.forEach((id, order) -> {
            CustomerLabel label = order.customerId() == null ? null : labels.get(order.customerId());
            InstallmentStanding standing = standings.get(id);
            facts.put(id, new Facts(order.orderNumber(), label == null ? null : label.name(), order.total(),
                    standing == null ? BigDecimal.ZERO : standing.paid(), order.status(),
                    order.printingStartedAt() != null));
        });
        return facts;
    }
}
