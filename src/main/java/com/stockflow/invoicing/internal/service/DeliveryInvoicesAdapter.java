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
     * it up by hand since. The rest is measured exactly as {@link EInvoiceDrafts#drawUpShippingFeeRest} measures it -
     * the share of the shipping fee waived for shipments that came back left out (Q-19,
     * {@link FeeAllocation#shippingFeeOwed}), and every plate fee no invoice of the order carries (BE-62) - so the order
     * waits for precisely what that method would draw up, never for a waived share nobody invoices.
     *
     * <p><b>The plate fee too</b> (audit r2 lifecycle M-1 / money M-2): an order whose goods all came back keeps its
     * plate fee, and only the rest invoice ever carries it. Measured once every piece the order still means to deliver
     * is on an invoice - while a delivery is still to be invoiced, that delivery's invoice carries the plate fee of its
     * SKU ({@link FeeAllocation#carriesPlateFee}), and its own blocker (the shipment not invoiced) already stands.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public boolean shippingFeeUnissued(UUID orderId) {
        if (queries.shippingFeeInvoiceUnissued(orderId)) {
            return true;
        }
        return shipments.orderFees(orderId, clock.instant()).map(fees -> {
            FeeAllocation.Invoiced invoiced = invoices.invoicedOfOrder(orderId);
            boolean shippingRest = fees.fee() != null && FeeAllocation.shippingRest(FeeAllocation.shippingFeeOwed(
                    fees.fee().amount(), fees.shippingFeeWaiver().feeGross(), fees.shippingFeeWaiver().waived()),
                    fees.orderUnits(), invoiced).signum() > 0;
            boolean plateUncarried = invoiced.units() >= fees.orderUnits() && fees.plateFees().stream()
                    .anyMatch(plate -> plate.amount().signum() > 0
                            && FeeAllocation.carriesPlateFee(plate.sku(), invoiced));
            return shippingRest || plateUncarried;
        }).orElse(false);
    }

    /**
     * WF-05 X-6 under decision Q-16 b: a return's decrease adjustment drawn up and not issued - information on the
     * order's settlement readiness, never a reason to hold its close. The adjustment is a decrease (the business
     * declares more output tax than it owes until it is issued), TT 91/2026/TT-BTC sets no deadline for it, and it can
     * still be issued after the order closes - nothing in invoicing refuses an invoice of a closed order - while the
     * overdue reminders keep coming until it is.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean returnAdjustmentUnissued(UUID orderId) {
        return orderId != null && invoices.returnAdjustmentUnissued(orderId);
    }
}
