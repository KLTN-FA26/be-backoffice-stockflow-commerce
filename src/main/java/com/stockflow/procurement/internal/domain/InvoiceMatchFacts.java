package com.stockflow.procurement.internal.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port: what purchase orders, their receipts and the invoices already matched say about the PO lines an invoice
 * bills (SCRUM-308/309). Read straight from the tables rather than through the {@link PurchaseOrder} and
 * {@link GoodsReceipt} aggregates: matching needs a few numbers per line across many documents, not their behaviour.
 */
public interface InvoiceMatchFacts {

    /**
     * A PO line as an invoice sees it: whose order, in which state and currency, at what price.
     *
     * @param discountRate percent off {@code unitPrice} the order agreed
     */
    record OrderLine(UUID poLineId, UUID purchaseOrderId, String purchaseOrderNumber, UUID supplierId,
                     PurchaseOrder.Type type, PurchaseOrderStatus orderStatus, String currency, int lineNo,
                     String sku, int ordered, BigDecimal unitPrice, BigDecimal discountRate, BigDecimal taxRate) {

        /** The price after the line's discount: what the supplier may bill per unit. */
        public BigDecimal netUnitPrice() {
            return unitPrice.multiply(BigDecimal.valueOf(100).subtract(discountRate))
                    .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
        }
    }

    /**
     * Units brought in by posted receipts (not a draft, not waiting for approval, not cancelled): {@code billable} -
     * received on a line without QC, accepted by QC on one with it - and {@code awaitingQc}, received on a QC line but
     * not concluded on (not inspected, or held in quarantine for a second decision). Rejected units are neither.
     */
    record Received(int billable, int awaitingQc) {

        public static final Received NONE = new Received(0, 0);
    }

    /** The PO lines that exist among {@code poLineIds}, by id. */
    Map<UUID, OrderLine> orderLines(Collection<UUID> poLineIds);

    /** Every line of one order, in line order, for the accountant entering its invoice. */
    List<OrderLine> orderLinesOf(UUID purchaseOrderId);

    /** The PO line of each receipt line that exists among {@code receiptLineIds}. */
    Map<UUID, UUID> poLinesOfReceiptLines(Collection<UUID> receiptLineIds);

    /**
     * Takes the purchase orders' row locks, in id order - the lock receiving and amending take first too - so a
     * receipt posted, or another invoice matched, against the same orders waits for this match, and the other way
     * round: two invoices never both bill the same received units.
     */
    void lockOrders(Collection<UUID> purchaseOrderIds);

    Map<UUID, Received> receivedOnPoLines(Collection<UUID> poLineIds);

    Map<UUID, Received> receivedOnReceiptLines(Collection<UUID> receiptLineIds);

    /** Units billed per PO line by MATCHED and PAID invoices, {@code excludingInvoice} left out. */
    Map<UUID, Integer> invoicedOnPoLines(Collection<UUID> poLineIds, UUID excludingInvoice);

    /** The same per receipt line. */
    Map<UUID, Integer> invoicedOnReceiptLines(Collection<UUID> receiptLineIds, UUID excludingInvoice);

    /** What MATCHED and PAID invoices billed on one order, before tax, {@code excludingInvoice} left out. */
    BigDecimal invoicedAmountOnOrder(UUID purchaseOrderId, UUID excludingInvoice);
}
