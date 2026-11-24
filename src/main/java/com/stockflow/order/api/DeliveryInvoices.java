package com.stockflow.order.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
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

    /**
     * Whether the rest of the order's shipping fee - what no delivery's invoice carries once its undelivered rest was
     * closed after every delivery was invoiced - is not on an issued invoice yet: its invoice is drawn up and not
     * issued, or the rest is owed and no invoice of it was drawn up at all (its draft was refused). That amount is
     * invoiced like a delivery, so the order waits for it as it waits for its deliveries' invoices. Read in the
     * caller's transaction.
     */
    boolean shippingFeeUnissued(UUID orderId);

    /**
     * WF-05 decision Q-16 (BE-34, BE-55): whether a return of the order has a decrease adjustment drawn up and not
     * issued yet. For information only - shown among the order's settlement conditions ("a return's adjustment is not
     * issued yet") - never a reason to keep the order from closing: a decrease left unissued overstates output VAT,
     * it does not understate it, and TT 91/2026 Điều 10 khoản 5 điểm c.1 sets it no deadline. Read in the caller's
     * transaction.
     *
     * <p>Declared by the WF-05 shared base (PR-0) answering false; the invoicing module overrides it.</p>
     */
    default boolean returnAdjustmentUnissued(UUID orderId) {
        return false;
    }

    /**
     * Audit r3 EINV-7 (decision §3 HĐ-3 "Kế toán phát hành"; Q-16): whether a reduction of the order's invoices for a
     * delivery that did not happen - the full reduction of a shipment's invoice whose delivery was corrected to a
     * failure, or the reduction of the shipping fee the rest invoice carries once payment waived it - is drawn up and
     * not issued yet. For information only, as {@link #returnAdjustmentUnissued}: it is a decrease, so leaving it
     * unissued overstates output VAT rather than understating it, and it can still be issued after the order closes;
     * but the buyer holds an invoice for goods or a fee it does not owe until it is, and the order's condition block,
     * which says what is still missing, said nothing of it. Read in the caller's transaction.
     */
    default boolean deliveryReductionUnissued(UUID orderId) {
        return false;
    }

    /**
     * WF-05 O-1 (decided 24/11): per order line, the VAT rate the goods of delivered shipment {@code shipmentId} are
     * invoiced at - what a return of them is refunded at, so the refund is exactly what the return's adjustment invoice
     * takes off ({@code OrderLine.grossAt}). Read from the invoice that stands for the shipment - its original, drawn up
     * or issued, or the replacement issued in its place - since that is the invoice the adjustment reduces, at its own
     * lines' rates; before any is drawn up, the rate drawing one up would give. A line the invoice does not carry is
     * left out. Read in the caller's transaction.
     *
     * <p>Answers nothing by default, which values every line at its own rate: a module test that boots order without
     * invoicing.</p>
     */
    default Map<UUID, BigDecimal> invoicedRates(UUID orderId, UUID shipmentId) {
        return Map.of();
    }
}
