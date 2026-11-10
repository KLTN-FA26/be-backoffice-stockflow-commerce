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
     */
    record Line(String sku, int cartons, List<Tier> tiers) {
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
     * @param priced            false for a reorder line the customer's prices could not price (R1): no tiers, and
     *                          {@code moq}, {@code proposedUnitPrice}, {@code proposedLineTotal} are null
     * @param moq               where the lowest tier starts, in the line's measure
     * @param proposedUnitPrice the price of one piece at the proposed cartons, computed on the server
     * @param proposedLineTotal {@code units} x {@code proposedUnitPrice}
     * @param acceptedCartons   cartons accepted; null until accepted, and on a legacy line
     * @param acceptedUnits     pieces accepted; null until accepted
     * @param taxClass          the product's tax class copied with the price (4B-3, 14 BR-21); null while it has none
     * @param taxRate           its VAT rate, a percentage
     * @param proposedTax       the VAT of {@code proposedLineTotal}; null without a rate or a price
     * @param acceptedTax       the VAT of {@code acceptedLineTotal}
     */
    record LineView(UUID id, int lineNo, String measure, String sku, Integer cartons, Integer unitsPerCarton,
                    int units, UUID designSnapshotId, String printSpec, boolean priced, Integer moq, List<Tier> tiers,
                    BigDecimal proposedUnitPrice, BigDecimal proposedLineTotal, Integer acceptedCartons,
                    Integer acceptedUnits, BigDecimal acceptedUnitPrice, BigDecimal acceptedLineTotal,
                    String taxClass, BigDecimal taxRate, BigDecimal proposedTax, BigDecimal acceptedTax) {
    }

    record Transition(int revision, QuoteStatus fromStatus, QuoteStatus toStatus, String reason, Instant changedAt,
                      UUID changedBy) {
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
     */
    record QuoteView(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
                     Instant validUntil, String terms,
                     java.time.LocalDate requestedDeliveryDate, boolean legacy, List<LineView> lines,
                     List<FeeView> fees, boolean priced, Totals proposed, Totals accepted,
                     BigDecimal proposedTotal, BigDecimal acceptedTotal,
                     Instant sentAt, UUID sentBy, Instant respondedAt,
                     UUID respondedBy, String customerFeedback, String cancellationReason,
                     ConvertedOrder convertedOrder, Instant convertedAt, List<Transition> history,
                     Instant createdAt, String createdBy, long version) {
    }

    record Row(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
               Instant validUntil, Instant sentAt, UUID convertedOrderId, Instant createdAt, long version) {
    }

    record Criteria(UUID customerId, List<QuoteStatus> statuses, String search, int page, int size, String sort) {
    }

    /** @param cartons per line id; a line not named is accepted at its proposed cartons */
    record Acceptance(long version, Map<UUID, Integer> cartons) {
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
     * A new DRAFT quote for the same SKUs and cartons as the order - a legacy order's designs as the SKUs issued for
     * them - priced from the customer's own prices now, never at the old negotiated price (user decision 2026-10-12: a
     * deal applies to its own quote and order only). A line those prices cannot price - none for the SKU, or the
     * cartons below its first tier - is left explicitly unpriced for Sales (user decision R1); the quote cannot be
     * sent until Sales prices it. Nothing is payable until Sales sends it
     * and the customer accepts; the draft is Sales' to negotiate and the customer sees it once sent.
     * Idempotent on {@code requestId}. A portal customer reorders only their own orders (404 otherwise).
     * The payment term is the old one while the customer may still use it; otherwise the customer's current
     * default, never a restored credit (user decision R1b, 2026-10-13) - the history says so.
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_NOT_QUOTED} for an order bought at the
     *         price list (place it again instead), {@code CUSTOMER_NOT_ACTIVE}; {@code CUSTOMER_SKU_REQUIRED} for a
     *         legacy order whose design has no SKU of the customer
     */
    QuoteView reorder(CurrentUser user, Reorder command);

    /** Writes EXPIRED on sent quotes past their validity, at most {@code limit}; returns how many. */
    int expireDue(int limit);
}
