package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.order.api.OrderStatus;
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
 * see. Prices are set by Sales as tiers and resolved by the server; the customer only picks cartons.</p>
 *
 * <p>4B-2 (kltn-docs 14 BR-04, BR-07, BR-08): a line is cartons of one of the customer's own SKUs; a tier starts at a
 * number of cartons and prices one piece. A quote drafted before on blanks and designs is kept to read
 * ({@code legacy}, QD-16): its lines count units, and it is not sent, accepted or converted.</p>
 */
public interface Quotes {

    /**
     * A price break.
     *
     * @param minQuantity where it starts: cartons (units on a legacy line)
     * @param unitPrice   VND for one piece
     */
    record Tier(int minQuantity, BigDecimal unitPrice) {
    }

    /**
     * A line as Sales writes it.
     *
     * @param sku     one of the customer's own SKUs
     * @param cartons cartons proposed; the customer may accept another number at or above the MOQ
     * @param tiers   empty to start from the customer's own price for the SKU (13 BR-05)
     * @param unpricedWithoutOwnPrice with no tiers and no own price at these cartons, leave the line unpriced instead of
     *                refusing it: a draft the system prefills from a customer's quote request (kltn-docs 16 §4.2, 14
     *                BR-22) is Sales' to finish, and a missing price must not stop it from being drawn up
     */
    record Line(String sku, int cartons, List<Tier> tiers, boolean unpricedWithoutOwnPrice) {

        /**
         * A line Sales writes: without tiers it starts from the customer's own price, and is refused when there is none.
         */
        public Line(String sku, int cartons, List<Tier> tiers) {
            this(sku, cartons, tiers, false);
        }
    }

    /**
     * @param requestedDeliveryDate the date the customer asks to receive the goods (SCRUM-502); Sales sets it while
     *                              the quote is a draft, it must not be in the past, and null means none was asked
     */
    record Draft(Instant validUntil, String terms, List<Line> lines, java.time.LocalDate requestedDeliveryDate,
                 List<Fee> fees) {

        /** A draft without fee lines Sales entered (a plate fee the system adds still comes, unpriced). */
        public Draft(Instant validUntil, String terms, List<Line> lines, java.time.LocalDate requestedDeliveryDate) {
            this(validUntil, terms, lines, requestedDeliveryDate, List.of());
        }

        /** A draft without a requested delivery date. */
        public Draft(Instant validUntil, String terms, List<Line> lines) {
            this(validUntil, terms, lines, null, List.of());
        }

        public Draft {
            fees = fees == null ? List.of() : List.copyOf(fees);
        }
    }

    /**
     * A fee line as Sales writes it (4B-3; kltn-docs 14 BR-17, BR-20).
     *
     * @param kind   {@code PLATE_FEE} or {@code SHIPPING_FEE}
     * @param sku    the SKU a plate fee is for - one on the quote, produced for the first time; null for shipping
     * @param amount whole VND; for a plate fee, null leaves it for later (the quote is not sent before)
     */
    record Fee(com.stockflow.order.api.FeeKind kind, String sku, BigDecimal amount) {
    }

    /**
     * A fee line as read, with its VAT.
     *
     * @param amount  null for a plate fee not priced yet
     * @param taxRate null while the SKU's product has no tax class
     * @param tax     the amount at the rate, rounded to the dong; null until both are known
     */
    record FeeView(UUID id, com.stockflow.order.api.FeeKind kind, String sku, BigDecimal amount, String taxClass,
                   BigDecimal taxRate, BigDecimal tax) {
    }

    /**
     * The three totals of an offer (kltn-docs 14 BR-09): the goods, the fee lines, the VAT, and the total with it. A
     * part not known yet - a line or fee unpriced, a rate missing - is null, and so is the total.
     */
    record Totals(BigDecimal subtotal, BigDecimal fees, BigDecimal tax, BigDecimal total) {
    }

    record Create(UUID requestId, UUID customerId, Draft draft) {
    }

    /**
     * @param measure           {@code CARTON}; {@code UNIT} on a line drafted before 4B-2 (a blank, a design, units)
     * @param cartons           cartons proposed; null on a legacy line
     * @param unitsPerCarton    pieces per carton fixed on the line; null on a legacy line
     * @param units             pieces proposed (cartons x pieces per carton; the units of a legacy line)
     * @param designSnapshotId  the design printed on the blank of a legacy line; null on a carton line
     * @param printSpec         how the blank of a legacy line is printed; null on a carton line
     * @param priced            false for a line drafted without a price - only a reorder drafted before team plan 5-2
     *                          has one (R1): no tiers, and {@code moq}, {@code proposedUnitPrice},
     *                          {@code proposedLineTotal} are null
     * @param moq               where the lowest tier starts, in the line's measure
     * @param proposedUnitPrice the price of one piece at the proposed cartons, computed on the server
     * @param proposedLineTotal {@code units} x {@code proposedUnitPrice}
     * @param acceptedCartons   cartons accepted; null until accepted, and on a legacy line
     * @param acceptedUnits     pieces accepted; null until accepted
     * @param taxClass          the product's tax class copied with the price (4B-3, 14 BR-21); null while it has none
     * @param taxRate           its VAT rate, a percentage
     * @param proposedTax       the VAT of {@code proposedLineTotal}; null without a rate or a price
     * @param acceptedTax       the VAT of {@code acceptedLineTotal}
     * @param purchasability    whether the SKU may go on a new quote or order now (kltn-docs 01 BR-04), read when the
     *                          quote is shown: an accepted quote is cancelled only when a line is not
     *                          {@code PURCHASABLE} (decision 2026-10-17); null on a legacy line, and for a SKU the
     *                          product module did not answer for
     * @param designName        R5-3: the name of the design the customer's SKU was made to; null for a SKU with none
     * @param previewUrl        R5-3: a short-lived link showing that design's preview; null when there is none
     * @param productName       the name of the product the SKU belongs to, so a reader tells lines apart without
     *                          knowing SKU codes; null for a SKU the product module does not know
     * @param variantName       the name of the SKU itself (its variant); null for an unknown SKU
     */
    record LineView(UUID id, int lineNo, String measure, String sku, Integer cartons, Integer unitsPerCarton,
                    int units, UUID designSnapshotId, String printSpec, boolean priced, Integer moq, List<Tier> tiers,
                    BigDecimal proposedUnitPrice, BigDecimal proposedLineTotal, Integer acceptedCartons,
                    Integer acceptedUnits, BigDecimal acceptedUnitPrice, BigDecimal acceptedLineTotal,
                    String taxClass, BigDecimal taxRate, BigDecimal proposedTax, BigDecimal acceptedTax,
                    com.stockflow.product.api.Purchasability purchasability, String designName, String previewUrl,
                    String productName, String variantName) {

        /** Without the product and variant names. */
        public LineView(UUID id, int lineNo, String measure, String sku, Integer cartons, Integer unitsPerCarton,
                        int units, UUID designSnapshotId, String printSpec, boolean priced, Integer moq,
                        List<Tier> tiers, BigDecimal proposedUnitPrice, BigDecimal proposedLineTotal,
                        Integer acceptedCartons, Integer acceptedUnits, BigDecimal acceptedUnitPrice,
                        BigDecimal acceptedLineTotal, String taxClass, BigDecimal taxRate, BigDecimal proposedTax,
                        BigDecimal acceptedTax, com.stockflow.product.api.Purchasability purchasability,
                        String designName, String previewUrl) {
            this(id, lineNo, measure, sku, cartons, unitsPerCarton, units, designSnapshotId, printSpec, priced, moq,
                    tiers, proposedUnitPrice, proposedLineTotal, acceptedCartons, acceptedUnits, acceptedUnitPrice,
                    acceptedLineTotal, taxClass, taxRate, proposedTax, acceptedTax, purchasability, designName,
                    previewUrl, null, null);
        }

        /** Without the design shown beside the SKU. */
        public LineView(UUID id, int lineNo, String measure, String sku, Integer cartons, Integer unitsPerCarton,
                        int units, UUID designSnapshotId, String printSpec, boolean priced, Integer moq,
                        List<Tier> tiers, BigDecimal proposedUnitPrice, BigDecimal proposedLineTotal,
                        Integer acceptedCartons, Integer acceptedUnits, BigDecimal acceptedUnitPrice,
                        BigDecimal acceptedLineTotal, String taxClass, BigDecimal taxRate, BigDecimal proposedTax,
                        BigDecimal acceptedTax, com.stockflow.product.api.Purchasability purchasability) {
            this(id, lineNo, measure, sku, cartons, unitsPerCarton, units, designSnapshotId, printSpec, priced, moq,
                    tiers, proposedUnitPrice, proposedLineTotal, acceptedCartons, acceptedUnits, acceptedUnitPrice,
                    acceptedLineTotal, taxClass, taxRate, proposedTax, acceptedTax, purchasability, null, null,
                    null, null);
        }
    }

    /** @param changedByName R5-8: the display name of {@code changedBy}; null on the customer's copy, as is the id */
    record Transition(int revision, QuoteStatus fromStatus, QuoteStatus toStatus, String reason, Instant changedAt,
                      UUID changedBy, String changedByName) {

        public Transition(int revision, QuoteStatus fromStatus, QuoteStatus toStatus, String reason, Instant changedAt,
                          UUID changedBy) {
            this(revision, fromStatus, toStatus, reason, changedAt, changedBy, null);
        }
    }

    record ConvertedOrder(UUID orderId, String orderNumber, OrderStatus status, BigDecimal total) {
    }

    /**
     * @param status the status as of now: a sent quote past its validity reads EXPIRED even before the
     *               expiry sweep has written it
     * @param legacy        drafted before 4B-2 on blanks and designs: kept to read (QD-16)
     * @param proposedTotal the total with tax at the proposed cartons ({@code proposed.total()}); null while a part of
     *                      it is not known
     * @param acceptedTotal the total with tax accepted; null until accepted
     * @param customerName  the customer's name, so a page names whose quote it is without a customer lookup
     * @param customerCode  the customer's code
     * @param sentByName    the display name of {@code sentBy}, for the back office; null on the customer's copy, which
     *                      names no account (as {@link Transition#changedByName})
     * @param respondedByName the display name of {@code respondedBy}, for the back office; null on the customer's copy
     * @param priceReviewPending 14 BR-10: the draft waits for the Sales Manager to approve its prices; always false on
     *                      the customer's copy, which never sees a draft
     * @param priceReview   14 BR-10: the latest submission of the quote's prices to the Sales Manager, whatever became of
     *                      it; null when there was none, and on the customer's copy
     * @param priceCheck    14 BR-10: how a draft's prices stand against their references right now - what sending it
     *                      would decide; null once sent, and on the customer's copy
     */
    record QuoteView(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
                     Instant validUntil, String terms,
                     java.time.LocalDate requestedDeliveryDate, boolean legacy, List<LineView> lines,
                     List<FeeView> fees, boolean priced, Totals proposed, Totals accepted,
                     BigDecimal proposedTotal, BigDecimal acceptedTotal,
                     Instant sentAt, UUID sentBy, Instant respondedAt,
                     UUID respondedBy, String customerFeedback, String cancellationReason,
                     ConvertedOrder convertedOrder, Instant convertedAt, List<Transition> history,
                     Instant createdAt, String createdBy, long version, String customerName, String customerCode,
                     String sentByName, String respondedByName, boolean priceReviewPending,
                     PriceReviewView priceReview, PriceCheckView priceCheck) {

        /** Without the price review (14 BR-10). */
        public QuoteView(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
                         Instant validUntil, String terms, java.time.LocalDate requestedDeliveryDate, boolean legacy,
                         List<LineView> lines, List<FeeView> fees, boolean priced, Totals proposed, Totals accepted,
                         BigDecimal proposedTotal, BigDecimal acceptedTotal, Instant sentAt, UUID sentBy,
                         Instant respondedAt, UUID respondedBy, String customerFeedback, String cancellationReason,
                         ConvertedOrder convertedOrder, Instant convertedAt, List<Transition> history,
                         Instant createdAt, String createdBy, long version, String customerName, String customerCode,
                         String sentByName, String respondedByName) {
            this(id, quoteNumber, customerId, status, revision, validUntil, terms, requestedDeliveryDate, legacy, lines,
                    fees, priced, proposed, accepted, proposedTotal, acceptedTotal, sentAt, sentBy, respondedAt,
                    respondedBy, customerFeedback, cancellationReason, convertedOrder, convertedAt, history, createdAt,
                    createdBy, version, customerName, customerCode, sentByName, respondedByName, false, null, null);
        }

        /** Without the names beside the ids: a view drawn for a paper, or a test. */
        public QuoteView(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
                         Instant validUntil, String terms, java.time.LocalDate requestedDeliveryDate, boolean legacy,
                         List<LineView> lines, List<FeeView> fees, boolean priced, Totals proposed, Totals accepted,
                         BigDecimal proposedTotal, BigDecimal acceptedTotal, Instant sentAt, UUID sentBy,
                         Instant respondedAt, UUID respondedBy, String customerFeedback, String cancellationReason,
                         ConvertedOrder convertedOrder, Instant convertedAt, List<Transition> history,
                         Instant createdAt, String createdBy, long version) {
            this(id, quoteNumber, customerId, status, revision, validUntil, terms, requestedDeliveryDate, legacy, lines,
                    fees, priced, proposed, accepted, proposedTotal, acceptedTotal, sentAt, sentBy, respondedAt,
                    respondedBy, customerFeedback, cancellationReason, convertedOrder, convertedAt, history, createdAt,
                    createdBy, version, null, null, null, null, false, null, null);
        }
    }

    /**
     * 14 BR-10: one price break of a line measured against its reference.
     *
     * @param referencePrice  the reference at the break's start - the customer's own price, else the variant's
     *                        standard price; null when there is none
     * @param source          {@code CUSTOMER_PRICE} or {@code STANDARD_PRICE}; null with no reference
     * @param discountPercent how far below the reference, in percent (negative when above); null with no reference
     * @param needsApproval   more than the threshold below, or no reference
     */
    record PriceCheckTier(int minQuantity, BigDecimal unitPrice, BigDecimal referencePrice, String source,
                          BigDecimal discountPercent, boolean needsApproval) {
    }

    /** 14 BR-10: one line's breaks as measured; {@code needsApproval} when one of them does. */
    record PriceCheckLine(int lineNo, String sku, int cartons, boolean needsApproval, List<PriceCheckTier> tiers) {
    }

    /**
     * 14 BR-10: a quote's prices against their references.
     *
     * @param thresholdPercent how far below its reference a break may be before the quote waits for the Sales Manager
     * @param needsApproval    whether sending it would hold it for approval
     */
    record PriceCheckView(BigDecimal thresholdPercent, boolean needsApproval, List<PriceCheckLine> lines) {
    }

    /**
     * 14 BR-10: one submission of the quote's prices to the Sales Manager, as decided on.
     *
     * @param outcome  {@code PENDING}, {@code APPROVED}, {@code REJECTED} or {@code WITHDRAWN} (by Sales, or by the
     *                 quote's cancellation)
     * @param reason   why it was rejected; a note the approver left; null otherwise
     * @param lines    every break as measured when it was submitted - a standard price changed since does not alter it
     */
    record PriceReviewView(UUID id, int revision, String outcome, BigDecimal thresholdPercent, Instant submittedAt,
                           UUID submittedBy, String submittedByName, Instant decidedAt, UUID decidedBy,
                           String decidedByName, String reason, List<PriceCheckLine> lines) {
    }

    /**
     * R5-2: a row tells quotes apart without opening each.
     *
     * @param total         VND with VAT of the offer the reader is shown, as the quote's own view adds it up: the
     *                      accepted total once accepted, else the proposed one; null while a part is not priced
     * @param lineCount     lines on that offer
     * @param firstItemName the design name of its first line, else that line's SKU; null for an offer with no line
     * @param customerName  the customer's name, read for the whole page at once (the list named no customer, and a
     *                      screen looked each one up per row)
     * @param customerCode  the customer's code
     * @param convertedOrderNumber the number of the order {@code convertedOrderId} names; null when there is none, and
     *                      on the customer's copy while that order is a DRAFT (17 §5.2, as the quote's own view)
     * @param priceReviewPending 14 BR-10: the draft waits for the Sales Manager to approve its prices; false on the
     *                      customer's copy
     */
    record Row(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
               Instant validUntil, Instant sentAt, UUID convertedOrderId, Instant createdAt, long version,
               BigDecimal total, Integer lineCount, String firstItemName, String customerName, String customerCode,
               String convertedOrderNumber, boolean priceReviewPending) {

        public Row(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
                   Instant validUntil, Instant sentAt, UUID convertedOrderId, Instant createdAt, long version,
                   BigDecimal total, Integer lineCount, String firstItemName, String customerName, String customerCode,
                   String convertedOrderNumber) {
            this(id, quoteNumber, customerId, status, revision, validUntil, sentAt, convertedOrderId, createdAt,
                    version, total, lineCount, firstItemName, customerName, customerCode, convertedOrderNumber, false);
        }

        public Row(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
                   Instant validUntil, Instant sentAt, UUID convertedOrderId, Instant createdAt, long version,
                   BigDecimal total, Integer lineCount, String firstItemName) {
            this(id, quoteNumber, customerId, status, revision, validUntil, sentAt, convertedOrderId, createdAt,
                    version, total, lineCount, firstItemName, null, null, null, false);
        }

        public Row(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
                   Instant validUntil, Instant sentAt, UUID convertedOrderId, Instant createdAt, long version) {
            this(id, quoteNumber, customerId, status, revision, validUntil, sentAt, convertedOrderId, createdAt,
                    version, null, null, null, null, null, null, false);
        }
    }

    /** @param priceReviewPendingOnly 14 BR-10: only drafts waiting for their prices to be approved - the Sales Manager's queue */
    record Criteria(UUID customerId, List<QuoteStatus> statuses, String search, int page, int size, String sort,
                    boolean priceReviewPendingOnly) {

        public Criteria(UUID customerId, List<QuoteStatus> statuses, String search, int page, int size, String sort) {
            this(customerId, statuses, search, page, size, sort, false);
        }
    }

    /** @param cartons per line id; a line not named is accepted at its proposed cartons */
    record Acceptance(long version, Map<UUID, Integer> cartons) {
    }

    record Conversion(long version, UUID shippingAddressId, UUID billingAddressId) {
    }

    /** Idempotent on {@code requestId}: a retry returns the quote the first call created. */
    QuoteView create(CurrentUser user, Create command);

    PageResponse<Row> list(CurrentUser user, Criteria criteria);

    QuoteView get(CurrentUser user, UUID quoteId);

    QuoteView edit(CurrentUser user, UUID quoteId, long version, Draft draft);

    /**
     * DRAFT → SENT, or - when a price break is more than the threshold below its reference, or has none (14 BR-10) - held
     * as a DRAFT for the Sales Manager to approve: {@link QuoteView#priceReviewPending} says which.
     */
    QuoteView send(CurrentUser user, UUID quoteId, long version);

    /** 14 BR-10: the Sales Manager approves the prices of a quote held for approval; it is sent at once. */
    QuoteView approvePrice(CurrentUser user, UUID quoteId, long version, String note);

    /** 14 BR-10: the Sales Manager rejects them, saying why; the draft goes back to Sales. */
    QuoteView rejectPrice(CurrentUser user, UUID quoteId, long version, String reason);

    /** 14 BR-10: Sales takes a quote held for approval back, to change it. */
    QuoteView withdrawPriceReview(CurrentUser user, UUID quoteId, long version);

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
