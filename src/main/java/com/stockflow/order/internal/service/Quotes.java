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

    record LineView(UUID id, int lineNo, String blankSku, UUID designSnapshotId, String printSpec, int quantity,
                    int moq, List<Tier> tiers, Integer acceptedQuantity, BigDecimal acceptedUnitPrice,
                    BigDecimal acceptedLineTotal, SampleGate sample) {
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
                     List<LineView> lines, BigDecimal acceptedTotal, Instant sentAt, UUID sentBy, Instant respondedAt,
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

    /** Writes EXPIRED on sent quotes past their validity, at most {@code limit}; returns how many. */
    int expireDue(int limit);
}
