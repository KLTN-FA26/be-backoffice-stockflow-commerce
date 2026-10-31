package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Durable cross-module request to deliver one purchase order to its supplier: the order itself, its cancellation
 * ({@code cancellation}) or an amendment approved after the supplier had it ({@code amendmentRevisionNo}, SCRUM-117).
 * The notice a supplier is sent is told apart by {@link #operationReference()}: the order and its recovery
 * generations share {@code purchase-order:{id}}, the cancellation and each amendment have a reference of their own, so
 * that neither "already sent successfully" nor the supplier API's idempotency key swallows them.
 *
 * <p>Persisted as JSON in {@code event_publication} until it is processed, so a component is only ever appended and
 * every earlier constructor is kept: an event written before a component existed reads back with it null.</p>
 */
public record PurchaseOrderSent(UUID purchaseOrderId, String poNumber, UUID supplierId,
        String channel, String recipient, BigDecimal totalAmount, String currency,
        LocalDate expectedAt, int paymentTermDays, java.util.List<Line> lines, int deliveryGeneration,
        Buyer buyer, boolean cancellation, String cancellationReason,
        Integer amendmentRevisionNo, String amendmentReason, java.util.List<Change> changes) {
    /** Compatibility constructor for publications created before amendments were announced to the supplier. */
    public PurchaseOrderSent(UUID purchaseOrderId, String poNumber, UUID supplierId,
            String channel, String recipient, BigDecimal totalAmount, String currency,
            LocalDate expectedAt, int paymentTermDays, java.util.List<Line> lines, int deliveryGeneration,
            Buyer buyer, boolean cancellation, String cancellationReason) {
        this(purchaseOrderId, poNumber, supplierId, channel, recipient, totalAmount, currency,
                expectedAt, paymentTermDays, lines, deliveryGeneration, buyer, cancellation, cancellationReason,
                null, null, null);
    }
    /** Compatibility constructor for publications created before buyer snapshots were introduced. */
    public PurchaseOrderSent(UUID purchaseOrderId, String poNumber, UUID supplierId,
            String channel, String recipient, BigDecimal totalAmount, String currency,
            LocalDate expectedAt, int paymentTermDays, java.util.List<Line> lines, int deliveryGeneration) {
        this(purchaseOrderId, poNumber, supplierId, channel, recipient, totalAmount, currency,
                expectedAt, paymentTermDays, lines, deliveryGeneration, null, false, null);
    }
    public PurchaseOrderSent(UUID purchaseOrderId, String poNumber, UUID supplierId,
            String channel, String recipient, BigDecimal totalAmount, String currency,
            LocalDate expectedAt, int paymentTermDays, java.util.List<Line> lines) {
        this(purchaseOrderId, poNumber, supplierId, channel, recipient, totalAmount, currency,
                expectedAt, paymentTermDays, lines, 0);
    }
    public PurchaseOrderSent {
        lines = lines == null ? java.util.List.of() : java.util.List.copyOf(lines);
        changes = changes == null ? java.util.List.of() : java.util.List.copyOf(changes);
    }
    public record Line(String sku, String description, int quantity, BigDecimal unitPrice) { }
    public record Buyer(String companyName, String companyAddress, String contactName,
            String phone, String email, String receivingAddress) { }
    /**
     * One change of an amendment against the revision the supplier had; {@code before} / {@code after} may be null.
     * A line change names its line and SKU, {@code field} being quantity, unitPrice, taxRate, description, removed or
     * added; a header change ({@code expectedDate}, {@code note}) has neither. The field stays a code: a supplier API
     * reads it, and the e-mail puts it into words.
     */
    public record Change(Integer lineNo, String sku, String field, String before, String after) { }
    public boolean amendment() { return amendmentRevisionNo != null; }
    public String operationReference() {
        if (amendment()) return "purchase-order:" + purchaseOrderId + ":revision:" + amendmentRevisionNo;
        return "purchase-order:" + purchaseOrderId + (cancellation ? ":cancellation" : "");
    }
    public String templateCode() {
        if (amendment()) return "purchase-order.amended";
        return cancellation ? "purchase-order.cancelled" : "purchase-order.sent";
    }
}
