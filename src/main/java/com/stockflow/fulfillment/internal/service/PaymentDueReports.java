package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.PaymentDueAtPacking;
import com.stockflow.order.api.CustomerDocument;
import com.stockflow.order.api.CustomerDocuments;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Reports a completion that leaves an installment due before delivery unpaid (kltn-docs 08 §4.4 step 9; owner decision
 * RTM-PAYNOTIFY, 2026-11-19), so the warehouse managers and the order's Sales are told in-app. Published in the
 * completion's own transaction: the event goes out only if the shipment is made. The Sales account the event names is the
 * order's {@code responsibleUserId} ({@code order :: api}: whoever looks after its customer today), for the record;
 * notification asks again whom to tell when it writes the notice ({@code SalesAudiences}), so a customer with no active
 * owner reaches the Sales Managers (08 §4.4 step 9 as changed on 2026-11-28).
 */
@Component
class PaymentDueReports {

    private final ApplicationEventPublisher publisher;
    private final CustomerDocuments documents;

    PaymentDueReports(ApplicationEventPublisher publisher, CustomerDocuments documents) {
        this.publisher = publisher;
        this.documents = documents;
    }

    void report(UUID orderId, String orderNumber, UUID shipmentId, String shipmentCode, UUID warehouseId,
                BigDecimal amountDue, String currency, Instant at) {
        UUID sales = documents.order(orderId).map(CustomerDocument::responsibleUserId).orElse(null);
        publisher.publishEvent(new PaymentDueAtPacking(Identifiers.newId(), orderId, orderNumber, shipmentId,
                shipmentCode, warehouseId, sales, amountDue, currency, at));
    }
}
