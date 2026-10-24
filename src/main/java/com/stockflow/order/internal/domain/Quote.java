package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.PaymentTerm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A B2B quote (SCRUM-448, 03-state-machines §18): what Sales offers a known customer — lines of printed
 * blanks with price breaks, a payment term, a validity — and the customer's answer.
 *
 * <p>Rules carried over from PR #38's {@code DesignQuote}: the company issues (sends) and the customer
 * assents, separately; accepted terms are never revised; only a draft is edited. New here: several
 * lines, price tiers with a minimum order quantity, the payment term, EXPIRED, and conversion to exactly
 * one order.</p>
 *
 * <p>Whether every new design has a sample the customer approved (BR-ORD-008) is the service's to check
 * before {@link #accept}: it needs what production has announced, which the aggregate does not hold.</p>
 *
 * <p>Every method checks before it changes anything, so a refused call leaves the quote as it was. The
 * CHECK constraints of {@code ordering.quote} mirror each invariant here.</p>
 */
public final class Quote {

    public static final int MAX_TEXT = 2000;
    public static final int MAX_CANCELLATION_REASON = 500;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** The commercial content Sales edits while the quote is a draft. */
    public record Offer(PaymentTerm paymentTerm, BigDecimal depositPercent, Instant validUntil, String terms,
                        List<QuoteLine> lines) {
        public Offer {
            Objects.requireNonNull(paymentTerm, "paymentTerm");
            Objects.requireNonNull(validUntil, "validUntil");
            if (depositPercent != null && (paymentTerm != PaymentTerm.DEPOSIT || depositPercent.signum() <= 0
                    || depositPercent.compareTo(HUNDRED) >= 0)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "A deposit percentage belongs to a DEPOSIT quote and lies above 0 and below 100");
            }
            terms = terms == null || terms.isBlank() ? null : terms.strip();
            if (terms != null && terms.length() > MAX_TEXT) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Terms are at most " + MAX_TEXT + " characters");
            }
            if (lines == null || lines.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A quote needs at least one line");
            }
            var numbers = new HashSet<Integer>();
            var printed = new HashSet<String>();
            for (QuoteLine line : lines) {
                if (!numbers.add(line.lineNo())) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Line " + line.lineNo() + " appears twice");
                }
                if (!printed.add(line.blankSku().code() + "|" + line.designSnapshotId())) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "The same design on blank " + line.blankSku().code() + " is quoted twice");
                }
            }
            lines = List.copyOf(lines);
            Map<String, Integer> blanks = unitsByBlank(lines, line -> line.quantity());
            for (QuoteLine line : lines) {
                int units = blanks.get(line.blankSku().code());
                if (line.priced() && units < line.moq()) {
                    throw new BusinessException(ErrorCode.QUOTE_BELOW_MOQ,
                            "Line %d: %d units of %s on this quote, below its MOQ of %d".formatted(line.lineNo(), units,
                                    line.blankSku().code(), line.moq()));
                }
            }
        }

        /** Whether every line has its price: what a quote needs before it is sent (R1). */
        public boolean priced() {
            return lines.stream().allMatch(QuoteLine::priced);
        }

        /** The proposed units of this blank over every line (P5): what picks each line's price break. */
        public int proposedUnits(String blankSku) {
            return unitsByBlank(lines, QuoteLine::quantity).getOrDefault(blankSku, 0);
        }
    }

    /** Units per blank SKU, every design on it together; never across SKUs (P5). */
    private static Map<String, Integer> unitsByBlank(List<QuoteLine> lines,
                                                     java.util.function.ToIntFunction<QuoteLine> units) {
        Map<String, Integer> total = new HashMap<>();
        for (QuoteLine line : lines) {
            total.merge(line.blankSku().code(), units.applyAsInt(line), Math::addExact);
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
        for (QuoteLine line : offer.lines()) {
            if (!line.priced()) {
                throw new BusinessException(ErrorCode.QUOTE_NOT_PRICED,
                        "Quote %s line %d has no price yet; enter its tiers before sending".formatted(quoteNumber,
                                line.lineNo()));
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
     * SENT → ACCEPTED within the validity (BR-ORD-008). Each line is accepted at the quantity asked for —
     * its proposed quantity when none is given — and at its own break for the accepted total of its blank
     * (P5: every design on the same blank counted together, never across blanks).
     *
     * @param units per line id; a line not named is accepted at its proposed quantity
     */
    public void accept(Map<UUID, Integer> units, UUID by, Instant now) {
        Objects.requireNonNull(by, "by");
        requireAnswerable(now, QuoteStatus.ACCEPTED);
        Map<UUID, Integer> asked = units == null ? Map.of() : units;
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
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Line " + line.lineNo() + " needs at least 1 unit");
            }
            chosen.put(line.id(), quantity);
        }
        Map<String, Integer> blanks = unitsByBlank(offer.lines(), line -> chosen.get(line.id()));
        for (QuoteLine line : offer.lines()) {
            line.priceFor(blanks.get(line.blankSku().code()));   // refuses below the MOQ before anything changes
        }
        offer.lines().forEach(line -> line.accept(chosen.get(line.id()), blanks.get(line.blankSku().code())));
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
     * Withdrawn or dropped, with a reason the customer reads too: a DRAFT (the first nobody saw; for a later revision
     * the customer sees the quote end with the offer they were last sent - the service's view), a SENT offer Sales
     * takes back before the customer answers (it can no longer be accepted, even if its validity has not run out), a
     * CHANGES_REQUESTED one whose changes are not offered, or - Sales' service decides when - an ACCEPTED one.
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
        move(QuoteStatus.CONVERTED, "convert");
        convertedOrderId = orderId;
        convertedAt = now;
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
