package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.FeeAllocation;
import com.stockflow.order.api.DeliveredShipments;
import com.stockflow.order.api.DeliveryInvoices;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Answers the order's question before it completes (WF-05 plan §4, {@link DeliveryInvoices}): which delivered
 * shipments have an invoice issued. Order declares the interface because it cannot depend on this module, which
 * depends on it.
 *
 * <p>Read only, in the order's transaction: an invoice still decides nothing about an order (kltn-docs 21 BR-11) -
 * the order decides, and asks.</p>
 */
@Component
class DeliveryInvoicesAdapter implements DeliveryInvoices {

    private final EInvoiceQueries queries;
    private final EInvoiceRepository invoices;
    private final DeliveredShipments shipments;
    private final Clock clock;

    DeliveryInvoicesAdapter(EInvoiceQueries queries, EInvoiceRepository invoices, DeliveredShipments shipments,
                            Clock clock) {
        this.queries = queries;
        this.invoices = invoices;
        this.shipments = shipments;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> invoiced(Collection<UUID> shipmentIds) {
        return shipmentIds == null || shipmentIds.isEmpty() ? Set.of() : queries.invoicedAmong(shipmentIds);
    }

    /**
     * An invoice of the fee's rest drawn up and not issued, or - WF05-E0 L11 - a rest owed that no invoice carries at
     * all: its draft was refused when the order's rest was closed (the seller not configured, say) and nobody has drawn
     * it up by hand since. The rest is measured exactly as {@link EInvoiceDrafts#drawUpShippingFeeRest} measures it, so
     * the order waits for precisely what that method would draw up.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean shippingFeeUnissued(UUID orderId) {
        if (queries.shippingFeeInvoiceUnissued(orderId)) {
            return true;
        }
        return shipments.shippingFee(orderId, clock.instant())
                .map(fee -> FeeAllocation.shippingRest(fee.fee().amount(), fee.orderUnits(),
                        invoices.invoicedOfOrder(orderId)).signum() > 0)
                .orElse(false);
    }
}
