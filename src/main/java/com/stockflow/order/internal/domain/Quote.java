package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A B2B quote (SCRUM-448, 03-state-machines §18): what Sales offers a known customer — lines of cartons of the
 * customer's own SKUs with price breaks (kltn-docs 14 BR-04, BR-07, BR-08; 4B-2), a payment term, a validity — and
 * the customer's answer.
 *
 * <p>Rules carried over from PR #38's {@code DesignQuote}: the company issues (sends) and the customer
 * assents, separately; accepted terms are never revised; only a draft is edited. New here: several
 * lines, price tiers with a minimum order quantity, the payment term, EXPIRED, and conversion to exactly
 * one order.</p>
 *
 * <p>A quote drafted before 4B-2 on blanks and designs ({@link QuoteLine#legacy()}) is kept as it was, to read
 * (QD-16): it is not sent, accepted or converted ({@code QUOTE_LINES_LEGACY}). A draft of one is revised onto the
 * customer's own SKUs, which replaces every line; anything else about it ends as its status allows (cancelled,
 * expired).</p>
 *
 * <p>Every method checks before it changes anything, so a refused call leaves the quote as it was. The
 * CHECK constraints of {@code ordering.quote} mirror each invariant here.</p>
 */
public final class Quote {

    public static final int MAX_TEXT = 2000;
    public static final int MAX_CANCELLATION_REASON = 500;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * The commercial content Sales edits while the quote is a draft. No payment term or deposit share since 4B-10: how
     * the customer pays is the sales contract's installment plan (15 BR-01).
     *
     * @param requestedDeliveryDate the date the customer asks to receive the goods (SCRUM-502, PROD-DUE): copied onto
     *                              every print line of the order the quote becomes. Null when the customer asked for
     *                              none.
     */
    public record Offer(Instant validUntil, String terms, java.time.LocalDate requestedDeliveryDate,
                        List<QuoteLine> lines, List<QuoteFee> fees) {

        /** An offer without fee lines. */
        public Offer(Instant validUntil, String terms, java.time.LocalDate requestedDeliveryDate,
                     List<QuoteLine> lines) {
            this(validUntil, terms, requestedDeliveryDate, lines, List.of());
        }

        /** An offer without a requested delivery date. */
        public Offer(Instant validUntil, String terms, List<QuoteLine> lines) {
            this(validUntil, terms, null, lines, List.of());
        }

        public Offer {
            Objects.requireNonNull(validUntil, "validUntil");
            terms = terms == null || terms.isBlank() ? null : terms.strip();
            if (terms != null && terms.length() > MAX_TEXT) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Terms are at most " + MAX_TEXT + " characters");
            }
            if (lines == null || lines.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A quote needs at least one line");
            }
            var numbers = new HashSet<Integer>();
            var quoted = new HashSet<String>();
            boolean legacy = lines.getFirst().legacy();
            for (QuoteLine line : lines) {
                if (!numbers.add(line.lineNo())) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Line " + line.lineNo() + " appears twice");
                }
                if (line.legacy() != legacy) {
                    throw new IllegalArgumentException("A quote's lines are all cartons of the customer's SKUs, or all "
                            + "lines drafted before them");
                }
                // A SKU once per quote (14 BR-22); on a legacy quote, one design once per blank.
                String key = line.legacy() ? line.sku().code() + "|" + line.designSnapshotId() : line.sku().code();
                if (!quoted.add(key)) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, line.legacy()
                            ? "The same design on blank " + line.sku().code() + " is quoted twice"
                            : "SKU " + line.sku().code() + " is quoted twice; one line per SKU");
                }
            }
            lines = List.copyOf(lines);
            fees = fees == null ? List.of() : List.copyOf(fees);
            // 4B-3 (14 BR-17, BR-20): at most one shipping fee; one plate fee per SKU on the quote, none on a legacy one.
            var platedSkus = new HashSet<String>();
            var onQuote = new HashSet<String>();
            lines.forEach(line -> onQuote.add(line.sku().code()));
            int shipping = 0;
            for (QuoteFee fee : fees) {
                if (legacy) {
                    throw new IllegalArgumentException("A quote drafted on blanks and designs has no fee lines");
                }
                if (fee.kind() == com.stockflow.order.api.FeeKind.SHIPPING_FEE) {
                    if (++shipping > 1) {
                        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A quote has one shipping fee at most");
                    }
                } else if (!onQuote.contains(fee.sku().code())) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "A plate fee is for a SKU on the quote, not " + fee.sku().code());
                } else if (!platedSkus.add(fee.sku().code())) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "SKU " + fee.sku().code() + " has one plate fee at most");
                }
            }
            Map<String, Integer> bySku = quantityBySku(lines, QuoteLine::quantity);
            for (QuoteLine line : lines) {
                int quantity = bySku.get(line.sku().code());
                if (line.priced() && quantity < line.moq()) {
                    throw new BusinessException(ErrorCode.QUOTE_BELOW_MOQ,
                            "Line %d: %d %s of %s on this quote, below its MOQ of %d".formatted(line.lineNo(), quantity,
                                    line.noun(), line.sku().code(), line.moq()));
                }
            }
        }

        /** Whether the lines were drafted before 4B-2, on blanks and designs: the quote is kept to read (QD-16). */
        public boolean legacy() {
            return lines.getFirst().legacy();
        }

        /** Whether every line has its price: what a quote needs before it is sent (R1). */
        public boolean priced() {
            return lines.stream().allMatch(QuoteLine::priced);
        }

        /**
         * The proposed quantity of this SKU over every line (cartons; units of a blank on a legacy quote, every design
         * on it together - P5): what picks each line's price break (14 BR-07).
         */
        public int proposedQuantity(String sku) {
            return quantityBySku(lines, QuoteLine::quantity).getOrDefault(sku, 0);
        }
    }

    /** The quantity per SKU (per blank on a legacy quote, every design on it together); never across SKUs. */
    private static Map<String, Integer> quantityBySku(List<QuoteLine> lines,
                                                      java.util.function.ToIntFunction<QuoteLine> quantity) {
        Map<String, Integer> total = new HashMap<>();
        for (QuoteLine line : lines) {
            total.merge(line.sku().code(), quantity.applyAsInt(line), Math::addExact);
        }
        return total;
    }

    private final UUID id;
    private final String quoteNumber;
    private final UUID requestId;
    private final UUID customerId;
    private QuoteStatus status;
    private int revision;
    private Offer offer;
    private Instant sentAt;
    private UUID sentBy;
    private Instant respondedAt;
    private UUID respondedBy;
    private String customerFeedback;
    private String cancellationReason;
    private UUID convertedOrderId;
    private Instant convertedAt;

    private Quote(UUID id, String quoteNumber, UUID requestId, UUID customerId, QuoteStatus status, int revision,
                  Offer offer) {
        this.id = Objects.requireNonNull(id, "id");
        if (quoteNumber == null || quoteNumber.isBlank()) {
            throw new IllegalArgumentException("A quote needs a number");
        }
        this.quoteNumber = quoteNumber;
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.status = Objects.requireNonNull(status, "status");
        if (revision < 1) {
            throw new IllegalArgumentException("Revisions start at 1");
        }
        this.revision = revision;
        this.offer = Objects.requireNonNull(offer, "offer");
    }

    public static Quote draft(UUID id, String quoteNumber, UUID requestId, UUID customerId, Offer offer) {
        return new Quote(id, quoteNumber, requestId, customerId, QuoteStatus.DRAFT, 1, offer);
    }

    /** Rehydration from the rows: no transition is replayed. */
    public static Quote restore(UUID id, String quoteNumber, UUID requestId, UUID customerId, QuoteStatus status,
                                int revision, Offer offer, Instant sentAt, UUID sentBy, Instant respondedAt,
                                UUID respondedBy, String customerFeedback, String cancellationReason,
                                UUID convertedOrderId, Instant convertedAt) {
        Quote quote = new Quote(id, quoteNumber, requestId, customerId, status, revision, offer);
        quote.sentAt = sentAt;
        quote.sentBy = sentBy;
        quote.respondedAt = respondedAt;
        quote.respondedBy = respondedBy;
        quote.customerFeedback = customerFeedback;
        quote.cancellationReason = cancellationReason;
        quote.convertedOrderId = convertedOrderId;
        quote.convertedAt = convertedAt;
        return quote;
    }

    /** The status as the customer and Sales see it: a sent quote past its validity is EXPIRED. */
    public QuoteStatus statusAt(Instant now) {
        return status == QuoteStatus.SENT && !now.isBefore(offer.validUntil()) ? QuoteStatus.EXPIRED : status;
    }

    /** Only a draft changes; a quote already sent is revised by the customer asking for changes. */
    public void revise(Offer replacement) {
        Objects.requireNonNull(replacement, "replacement");
        requireStatus(QuoteStatus.DRAFT, "edit");
        offer = replacement;
    }

    /** DRAFT → SENT. The validity must still lie ahead: a quote is never sent already expired. */
    public void send(UUID by, Instant now) {
        Objects.requireNonNull(by, "by");
        requireStatus(QuoteStatus.DRAFT, "send");
        requireCartonLines("sent");
        for (QuoteLine line : offer.lines()) {
            if (!line.priced()) {
                throw new BusinessException(ErrorCode.QUOTE_NOT_PRICED,
                        "Quote %s line %d has no price yet; enter its tiers before sending".formatted(quoteNumber,
                                line.lineNo()));
            }
        }
        // 14 §4.1 step 3 (4B-3): every fee line has its amount, every line and fee line its VAT rate.
        for (QuoteFee fee : offer.fees()) {
            if (!fee.priced()) {
                throw new BusinessException(ErrorCode.QUOTE_NOT_PRICED, ("Quote %s: the %s of %s has no amount yet; "
                        + "enter it before sending").formatted(quoteNumber, fee.kind(), fee.sku().code()));
            }
        }
        for (QuoteLine line : offer.lines()) {
            if (line.taxRate() == null) {
                throw new BusinessException(ErrorCode.QUOTE_TAX_CLASS_MISSING, ("Quote %s line %d: the product of %s "
                        + "has no tax class; set it before sending").formatted(quoteNumber, line.lineNo(),
                        line.sku().code()));
            }
        }
        for (QuoteFee fee : offer.fees()) {
            if (fee.taxRate() == null) {
                throw new BusinessException(ErrorCode.QUOTE_TAX_CLASS_MISSING, ("Quote %s: the product of %s has no "
                        + "tax class, so its plate fee has no VAT rate; set it before sending").formatted(quoteNumber,
                        fee.sku().code()));
            }
        }
        if (!offer.validUntil().isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Quote " + quoteNumber + " would be sent expired");
        }
        status = QuoteStatus.SENT;
        sentAt = now;
        sentBy = by;
    }

    /**
     * SENT → ACCEPTED within the validity (BR-ORD-008). Each line is accepted at the cartons asked for — its
     * proposed cartons when none are given — and at its own break for the accepted total of its SKU (14 BR-07).
     *
     * @param cartons per line id; a line not named is accepted at its proposed cartons
     */
    public void accept(Map<UUID, Integer> cartons, UUID by, Instant now) {
        Objects.requireNonNull(by, "by");
        requireAnswerable(now, QuoteStatus.ACCEPTED);
        requireCartonLines("accepted");
        Map<UUID, Integer> asked = cartons == null ? Map.of() : cartons;
        var known = new HashSet<UUID>();
        offer.lines().forEach(line -> known.add(line.id()));
        for (UUID lineId : asked.keySet()) {
            if (!known.contains(lineId)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Line " + lineId + " is not on quote " + quoteNumber);
            }
        }
        Map<UUID, Integer> chosen = new HashMap<>();
        for (QuoteLine line : offer.lines()) {
            int quantity = asked.getOrDefault(line.id(), line.quantity());
            if (quantity < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Line " + line.lineNo() + " needs at least 1 carton");
            }
            chosen.put(line.id(), quantity);
        }
        Map<String, Integer> bySku = quantityBySku(offer.lines(), line -> chosen.get(line.id()));
        for (QuoteLine line : offer.lines()) {
            line.priceFor(bySku.get(line.sku().code()));   // refuses below the MOQ before anything changes
        }
        offer.lines().forEach(line -> line.accept(chosen.get(line.id()), bySku.get(line.sku().code())));
        status = QuoteStatus.ACCEPTED;
        respondedAt = now;
        respondedBy = by;
    }

    /** SENT → CHANGES_REQUESTED within the validity, saying what to change. */
    public void requestChanges(String feedback, UUID by, Instant now) {
        Objects.requireNonNull(by, "by");
        String text = feedback == null ? "" : feedback.strip();
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Asking for changes must say what to change, in at most " + MAX_TEXT + " characters");
        }
        requireAnswerable(now, QuoteStatus.CHANGES_REQUESTED);
        status = QuoteStatus.CHANGES_REQUESTED;
        respondedAt = now;
        respondedBy = by;
        customerFeedback = text;
    }

    /**
     * CHANGES_REQUESTED → DRAFT, the next revision. The feedback stays on the history row of the
     * request; the quote row starts the new revision clean.
     */
    public void reopen() {
        move(QuoteStatus.DRAFT, "revise");
        revision = Math.incrementExact(revision);
        respondedAt = null;
        respondedBy = null;
        customerFeedback = null;
        offer.lines().forEach(QuoteLine::clearAcceptance);
    }

    /** SENT → EXPIRED once the validity has passed; false when there is nothing to expire. */
    public boolean expire(Instant now) {
        if (status != QuoteStatus.SENT || now.isBefore(offer.validUntil())) {
            return false;
        }
        status = QuoteStatus.EXPIRED;
        return true;
    }

    /**
     * Cancelled with a reason the customer reads too: a DRAFT (the first nobody saw; for a later revision the customer
     * sees the quote end with the offer they were last sent - the service's view) or - Sales' service decides when -
     * an ACCEPTED one. A SENT or CHANGES_REQUESTED quote is not cancelled: it is answered, revised or expires.
     */
    public void cancel(String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty() || text.length() > MAX_CANCELLATION_REASON) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A cancellation must say why, in at most " + MAX_CANCELLATION_REASON + " characters");
        }
        move(QuoteStatus.CANCELLED, "cancel");
        cancellationReason = text;
    }

    /** ACCEPTED → CONVERTED: the one sales order this quote became. */
    public void convert(UUID orderId, Instant now) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(now, "now");
        requireCartonLines("converted");
        move(QuoteStatus.CONVERTED, "convert");
        convertedOrderId = orderId;
        convertedAt = now;
    }

    /** QD-16: a quote on blanks and designs, drafted before 4B-2, is kept to read. */
    private void requireCartonLines(String verb) {
        if (offer.legacy()) {
            throw new BusinessException(ErrorCode.QUOTE_LINES_LEGACY, ("Quote %s was drafted on blanks and designs, "
                    + "before the customer's own SKUs: it is not %s. Revise a draft onto the customer's SKUs, or draft "
                    + "a new quote").formatted(quoteNumber, verb));
        }
    }

    private void requireAnswerable(Instant now, QuoteStatus target) {
        Objects.requireNonNull(now, "now");
        if (status == QuoteStatus.SENT && !now.isBefore(offer.validUntil())) {
            throw new BusinessException(ErrorCode.QUOTE_EXPIRED,
                    "Quote %s was valid until %s".formatted(quoteNumber, offer.validUntil()));
        }
        if (!status.canTransitionTo(target)) {
            throw transition("answer", target);
        }
    }

    private void requireStatus(QuoteStatus expected, String verb) {
        if (status != expected) {
            throw transition(verb, expected == QuoteStatus.DRAFT && "send".equals(verb) ? QuoteStatus.SENT : expected);
        }
    }

    private void move(QuoteStatus target, String verb) {
        if (!status.canTransitionTo(target)) {
            throw transition(verb, target);
        }
        status = target;
    }

    private BusinessException transition(String verb, QuoteStatus target) {
        return new BusinessException(ErrorCode.INVALID_QUOTE_TRANSITION,
                "Cannot %s quote %s: it is %s, not a state that leads to %s".formatted(verb, quoteNumber, status, target));
    }

    public UUID id() { return id; }
    public String quoteNumber() { return quoteNumber; }
    public UUID requestId() { return requestId; }
    public UUID customerId() { return customerId; }
    public QuoteStatus status() { return status; }
    public int revision() { return revision; }
    public Offer offer() { return offer; }
    public Instant sentAt() { return sentAt; }

    /**
     * Whether the customer has been shown this quote: everything once sent - an accepted quote Sales cancelled
     * included, so the customer sees what became of it - but not a draft, nor one cancelled before it was sent.
     */
    public boolean seenByCustomer() {
        return status.seenByCustomer() || status == QuoteStatus.CANCELLED && sentAt != null;
    }
    public UUID sentBy() { return sentBy; }
    public Instant respondedAt() { return respondedAt; }
    public UUID respondedBy() { return respondedBy; }
    public String customerFeedback() { return customerFeedback; }
    public String cancellationReason() { return cancellationReason; }
    public UUID convertedOrderId() { return convertedOrderId; }
    public Instant convertedAt() { return convertedAt; }
}
