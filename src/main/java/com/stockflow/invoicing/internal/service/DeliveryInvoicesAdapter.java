package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.order.api.DeliveryInvoices;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Answers the order's question before it completes (WF-05 plan §4, {@link DeliveryInvoices}): which delivered
 * shipments have an invoice issued. Order declares the interface because it cannot depend on this module, which
 * depends on it.
 *
 * <p>Read only, one statement, in the order's transaction: an invoice still decides nothing about an order (kltn-docs
 * 21 BR-11) - the order decides, and asks.</p>
 */
@Component
class DeliveryInvoicesAdapter implements DeliveryInvoices {

    private final EInvoiceQueries queries;

    DeliveryInvoicesAdapter(EInvoiceQueries queries) {
        this.queries = queries;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> invoiced(Collection<UUID> shipmentIds) {
        return shipmentIds == null || shipmentIds.isEmpty() ? Set.of() : queries.invoicedAmong(shipmentIds);
    }
}
