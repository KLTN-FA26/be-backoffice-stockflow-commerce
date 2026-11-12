package com.stockflow.order.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * What the order asks about the e-invoices of its deliveries before it completes (WF-05 plan §4, condition 5):
 * which of its delivered shipments have an invoice issued.
 *
 * <p>An interface in {@code order :: api} implemented <b>by the invoicing module</b>, as payment's
 * {@code InstallmentOrders} is implemented by order: invoicing already depends on {@code order :: api} to read what a
 * delivered shipment is invoiced for, so order calling invoicing would close a cycle {@code ModularityTest} rejects.
 * Order depends on this interface only; Spring hands it invoicing's implementation. A module test that boots order
 * without invoicing mocks it.</p>
 */
public interface DeliveryInvoices {

    /**
     * Those of {@code shipmentIds} that have an invoice in force - issued, and neither a draft, a refused one nor an
     * adjustment: the original invoice of the delivery, or the invoice that replaced it. Read in the caller's
     * transaction.
     */
    Set<UUID> invoiced(Collection<UUID> shipmentIds);
}
