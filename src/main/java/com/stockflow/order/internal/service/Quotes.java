package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.QuoteStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * B2B quotes (SCRUM-426/448, 03-state-machines §18, F-CART).
 *
 * <p>Sales drafts, sends, revises after the customer's feedback, cancels a draft and converts an accepted
 * quote into its one sales order. The customer — only the customer, on the portal — accepts or asks for
 * changes: the company issues an offer, the customer assents to it, and neither does the other's part.
 * A portal user reaches only their own customer's quotes, and only once sent; anything else answers 404.</p>
 *
 * <p>Every change names the {@code version} the caller last read and is refused (409
 * {@code OPTIMISTIC_LOCK}) if the quote moved since, so nobody accepts or revises a quote they did not
 * see. Prices are set by Sales as tiers and resolved by the server; the customer only picks quantities.</p>
 */
public interface Quotes {

    record Tier(int minQuantity, BigDecimal unitPrice) {
    }

    record Line(String blankSku, UUID designSnapshotId, String printSpec, int quantity, List<Tier> tiers) {
    }

    record Draft(PaymentTerm paymentTerm, BigDecimal depositPercent, Instant validUntil, String terms,
                 List<Line> lines) {
    }

    record Create(UUID requestId, UUID customerId, Draft draft) {
    }

    /**
     * Whether acceptance would pass the sample rule for this line (BR-ORD-008): an approved sample of
     * this customer for the design on the blank, or a reorder of what was already printed for them.
     */
    record SampleGate(boolean satisfied, UUID approvedSampleId, boolean reorder) {
    }

    /**
     * @param priced            false for a reorder line the price list could not price (R1): no tiers, and
     *                          {@code moq}, {@code proposedUnitPrice}, {@code proposedLineTotal} are null
     * @param proposedUnitPrice the tier price of the proposed {@code quantity}, computed on the server
     * @param proposedLineTotal {@code quantity} x {@code proposedUnitPrice}
     */
    record LineView(UUID id, int lineNo, String blankSku, UUID designSnapshotId, String printSpec, int quantity,
                    boolean priced, Integer moq, List<Tier> tiers, BigDecimal proposedUnitPrice,
                    BigDecimal proposedLineTotal,
                    Integer acceptedQuantity, BigDecimal acceptedUnitPrice, BigDecimal acceptedLineTotal,
                    SampleGate sample) {
    }

    record Transition(int revision, QuoteStatus fromStatus, QuoteStatus toStatus, String reason, Instant changedAt,
                      UUID changedBy) {
    }

    record ConvertedOrder(UUID orderId, String orderNumber, OrderStatus status, BigDecimal total) {
    }

    /**
     * @param status the status as of now: a sent quote past its validity reads EXPIRED even before the
     *               expiry sweep has written it
     */
    record QuoteView(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
                     PaymentTerm paymentTerm, BigDecimal depositPercent, Instant validUntil, String terms,
                     List<LineView> lines, boolean priced, BigDecimal proposedTotal, BigDecimal acceptedTotal,
                     Instant sentAt, UUID sentBy, Instant respondedAt,
                     UUID respondedBy, String customerFeedback, String cancellationReason,
                     ConvertedOrder convertedOrder, Instant convertedAt, List<Transition> history,
                     Instant createdAt, String createdBy, long version) {
    }

    record Row(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
               PaymentTerm paymentTerm, Instant validUntil, Instant sentAt, UUID convertedOrderId, Instant createdAt,
               long version) {
    }

    record Criteria(UUID customerId, List<QuoteStatus> statuses, String search, int page, int size, String sort) {
    }

    /** @param quantities per line id; a line not named is accepted at its proposed quantity */
    record Acceptance(long version, Map<UUID, Integer> quantities) {
    }

    record Conversion(long version, UUID shippingAddressId, UUID billingAddressId) {
    }

    /** Ordering again what {@code orderId} - an order converted from a quote - bought. */
    record Reorder(UUID requestId, UUID orderId) {
    }

    /** Idempotent on {@code requestId}: a retry returns the quote the first call created. */
    QuoteView create(CurrentUser user, Create command);

    PageResponse<Row> list(CurrentUser user, Criteria criteria);

    QuoteView get(CurrentUser user, UUID quoteId);

    QuoteView edit(CurrentUser user, UUID quoteId, long version, Draft draft);

    QuoteView send(CurrentUser user, UUID quoteId, long version);

    QuoteView accept(CurrentUser user, UUID quoteId, Acceptance acceptance);

    QuoteView requestChanges(CurrentUser user, UUID quoteId, long version, String feedback);

    /** CHANGES_REQUESTED → DRAFT, the next revision, for Sales to edit and send again. */
    QuoteView reopen(CurrentUser user, UUID quoteId, long version);

    QuoteView cancel(CurrentUser user, UUID quoteId, long version, String reason);

    /**
     * ACCEPTED → CONVERTED with exactly one sales order, at the accepted quantities and prices. Calling it
     * again for a converted quote returns the same order; a failed placement (stock, credit terms) leaves
     * the quote ACCEPTED and creates nothing.
     */
    QuoteView convert(CurrentUser user, UUID quoteId, Conversion conversion);

    /**
     * A new DRAFT quote for the same blanks, designs, print specifications and quantities as the order,
     * priced from the customer's current price list - never at the old negotiated price (user decision
     * 2026-10-12: a deal applies to its own quote and order only). A line the list cannot price - no list
     * price, or the blank's total below the list's first tier - is left explicitly unpriced for Sales (user
     * decision R1); the quote cannot be sent until Sales prices it. Nothing is payable until Sales sends it
     * and the customer accepts; the draft is Sales' to negotiate and the customer sees it once sent.
     * Idempotent on {@code requestId}. A portal customer reorders only their own orders (404 otherwise).
     * The payment term is the old one while the customer may still use it; otherwise the customer's current
     * default, never a restored credit (user decision R1b, 2026-10-13) - the history says so.
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_NOT_QUOTED} for an order bought at the
     *         price list (place it again instead), {@code CUSTOMER_NOT_ACTIVE}
     */
    QuoteView reorder(CurrentUser user, Reorder command);

    /** Writes EXPIRED on sent quotes past their validity, at most {@code limit}; returns how many. */
    int expireDue(int limit);
}
