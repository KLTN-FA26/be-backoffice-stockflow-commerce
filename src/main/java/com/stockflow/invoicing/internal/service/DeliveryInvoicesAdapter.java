package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.order.api.DeliveredShipments;
import com.stockflow.order.api.DeliveryInvoices;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
     * {@link com.stockflow.invoicing.internal.domain.FeeAllocation#shippingFeeOwed}), and every plate fee no invoice of the order carries (BE-62) - so the order
     * waits for precisely what that method would draw up, never for a waived share nobody invoices.
     *
     * <p><b>The plate fee too</b> (audit r2 lifecycle M-1 / money M-2): an order whose goods all came back keeps its
     * plate fee, and only the rest invoice ever carries it. Measured once every piece the order still means to deliver
     * is on an invoice - while a delivery is still to be invoiced, that delivery's invoice carries the plate fee of its
     * SKU ({@link com.stockflow.invoicing.internal.domain.FeeAllocation#carriesPlateFee}), and its own blocker (the shipment not invoiced) already stands.</p>
     *
     * <p><b>Measured again, as the draw-up measures it</b> (audit r3 LM-2): by {@link EInvoiceDrafts#feeRestOf}, the one
     * rule. A rest invoice issued before a delivery was undone (HĐ-3) or a shipment came back (Q-19) may leave a plate
     * fee on no invoice, or carry a shipping fee payment waived since; both are outstanding until the draw-up has put
     * the one on a new rest invoice and taken the other off by a reduction. The reduction drawn up and not issued is
     * then information only ({@link #deliveryReductionUnissued}): a decrease, as a return's adjustment is (Q-16).
     * Before, this answered "the plate fee is on no invoice" for good while the draw-up returned the rest invoice
     * standing - an order waiting for ever, its contract never liquidated.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public boolean shippingFeeUnissued(UUID orderId) {
        if (queries.shippingFeeInvoiceUnissued(orderId)) {
            return true;
        }
        return shipments.orderFees(orderId, clock.instant())
                .map(fees -> EInvoiceDrafts.feeRestOf(invoices, fees).outstanding()).orElse(false);
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

    /**
     * Audit r3 EINV-7: a reduction for a delivery that did not happen, drawn up and not issued - information on the
     * order's settlement readiness, for the reasons {@link #returnAdjustmentUnissued} gives. Its overdue reminders
     * keep coming until the accountant issues it, after the order closed too.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean deliveryReductionUnissued(UUID orderId) {
        return orderId != null && invoices.deliveryReductionUnissued(orderId);
    }

    /**
     * WF-05 O-1: the rates the shipment's goods are invoiced at, read where the return's adjustment reads them
     * ({@link EInvoiceCorrections#drawUpForReturn} reduces the invoice that stands for the shipment, each line at its
     * own rate) - one source for the refund and the adjustment, so they cannot disagree. An invoice drawn up and not
     * issued yet is what will be issued unless the accountant voids it and draws it up again, and the approval reads
     * the rates again ({@code ReturnRequest.revalue}). No invoice at all yet - the delivery's draft is drawn up after
     * its commit - is the rate the draw-up gives ({@link DeliveredShipments#find}, the one rule a draft takes its rates
     * from). A shipment reversed for a failed delivery stands for no invoice and has no delivered goods: nothing.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> invoicedRates(UUID orderId, UUID shipmentId) {
        if (orderId == null || shipmentId == null) {
            return Map.of();
        }
        Map<UUID, BigDecimal> rates = new HashMap<>();
        Optional<EInvoice> invoice = invoices.findLiveOriginalOfShipment(shipmentId);
        if (invoice.isPresent()) {
            invoice.get().lines().stream()
                    .filter(line -> line.kind() == EInvoiceLine.Kind.GOODS && line.orderLineId() != null)
                    .forEach(line -> rates.putIfAbsent(line.orderLineId(), line.taxRate()));
            return Map.copyOf(rates);
        }
        shipments.find(orderId, shipmentId).ifPresent(delivered -> delivered.lines().stream()
                .filter(line -> line.taxRate() != null)
                .forEach(line -> rates.putIfAbsent(line.orderLineId(), line.taxRate())));
        return Map.copyOf(rates);
    }
}
