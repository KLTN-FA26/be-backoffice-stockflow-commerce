package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's request for a credit limit (owner decision QĐ-A, 2026-11-26; kltn-docs 15 §4.3, 18 §4 step 5, BR-03).
 *
 * <pre>
 * the customer, from the portal ─► SUBMITTED ─► Sales' opinion ─► PENDING_APPROVAL
 * Sales, for the customer, with their opinion ─────────────────► PENDING_APPROVAL
 * PENDING_APPROVAL ─► the accountant approves ─► APPROVED (the credit profile is written in the same transaction)
 *                  └► the accountant rejects ──► REJECTED (with a reason)
 * SUBMITTED / PENDING_APPROVAL ─► whoever filed it withdraws it ─► WITHDRAWN
 * </pre>
 *
 * <p>The accountant grants at most the limit asked (plan §5 question 3, decided 2026-11-26): a higher one is a new
 * request, so what the customer asked for and what Sales said about it is always what a grant was weighed against.
 * Whoever decides neither filed it nor gave Sales' opinion (18 BR-02). Each rule is stated again in the database
 * ({@code ck_credit_request_*}). Nothing here decides whether the customer deserves the credit: the accountant does,
 * on the figures the review shows ({@link CreditAdvice}).</p>
 */
public final class CreditRequest {

    /** The longest reason, opinion or decision note kept. */
    public static final int TEXT_MAX = 1000;
    /** The most days to pay after delivery, as a credit profile holds them ({@code ck_credit_profile_credit}). */
    public static final int TERM_DAYS_MAX = 365;
    /** The largest amount a {@code numeric(19,4)} column holds whole. */
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("999999999999999");

    private final UUID id;
    private final UUID customerId;
    private final CreditRequestSource source;
    private CreditRequestStatus status;
    private final BigDecimal requestedLimit;
    private final int requestedTermDays;
    private final String currency;
    private final String reason;
    private final UUID submittedBy;
    private final Instant submittedAt;
    private String salesNote;
    private BigDecimal salesRecommendedLimit;
    private UUID recommendedBy;
    private Instant recommendedAt;
    private BigDecimal approvedLimit;
    private Integer approvedTermDays;
    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private final long version;

    private CreditRequest(UUID id, UUID customerId, CreditRequestSource source, CreditRequestStatus status,
                          BigDecimal requestedLimit, int requestedTermDays, String currency, String reason,
                          UUID submittedBy, Instant submittedAt, String salesNote, BigDecimal salesRecommendedLimit,
                          UUID recommendedBy, Instant recommendedAt, BigDecimal approvedLimit,
                          Integer approvedTermDays, UUID decidedBy, Instant decidedAt, String decisionNote,
                          long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.source = Objects.requireNonNull(source, "source");
        this.status = Objects.requireNonNull(status, "status");
        this.requestedLimit = Objects.requireNonNull(requestedLimit, "requestedLimit");
        this.requestedTermDays = requestedTermDays;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.reason = reason;
        this.submittedBy = Objects.requireNonNull(submittedBy, "submittedBy");
        this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
        this.salesNote = salesNote;
        this.salesRecommendedLimit = salesRecommendedLimit;
        this.recommendedBy = recommendedBy;
        this.recommendedAt = recommendedAt;
        this.approvedLimit = approvedLimit;
        this.approvedTermDays = approvedTermDays;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote;
        this.version = version;
    }

    /**
     * The customer asks from the portal; Sales give their opinion next.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for a limit not above zero, days outside 0-365, a reason
     *         longer than {@value #TEXT_MAX}
     */
    public static CreditRequest fromPortal(UUID customerId, BigDecimal limit, int termDays, String reason, UUID by,
                                           Instant at) {
        requireAsked(limit, termDays);
        return new CreditRequest(Identifiers.newId(), customerId, CreditRequestSource.PORTAL,
                CreditRequestStatus.SUBMITTED, limit, termDays, "VND", optionalText(reason, "reason"), by, at, null,
                null, null, null, null, null, null, null, null, 0);
    }

    /**
     * Sales ask for the customer, with their opinion: it waits for the accountant at once.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} as {@link #fromPortal}, and without an opinion, or with a
     *         suggested limit not above zero
     */
    public static CreditRequest bySales(UUID customerId, BigDecimal limit, int termDays, String reason,
                                        String salesNote, BigDecimal recommendedLimit, UUID by, Instant at) {
        requireAsked(limit, termDays);
        return new CreditRequest(Identifiers.newId(), customerId, CreditRequestSource.SALES,
                CreditRequestStatus.PENDING_APPROVAL, limit, termDays, "VND", optionalText(reason, "reason"), by, at,
                requiredText(salesNote, "salesNote"), suggested(recommendedLimit), by, at, null, null, null, null,
                null, 0);
    }

    /** As stored; nothing is checked. */
    public static CreditRequest restore(UUID id, UUID customerId, CreditRequestSource source,
                                        CreditRequestStatus status, BigDecimal requestedLimit, int requestedTermDays,
                                        String currency, String reason, UUID submittedBy, Instant submittedAt,
                                        String salesNote, BigDecimal salesRecommendedLimit, UUID recommendedBy,
                                        Instant recommendedAt, BigDecimal approvedLimit, Integer approvedTermDays,
                                        UUID decidedBy, Instant decidedAt, String decisionNote, long version) {
        return new CreditRequest(id, customerId, source, status, requestedLimit, requestedTermDays, currency, reason,
                submittedBy, submittedAt, salesNote, salesRecommendedLimit, recommendedBy, recommendedAt,
                approvedLimit, approvedTermDays, decidedBy, decidedAt, decisionNote, version);
    }

    /**
     * Refused unless {@code expected} is the version read: two people acting on one request at once - two accountants,
     * an accountant and a withdrawal - do not both win.
     *
     * @throws BusinessException {@code OPTIMISTIC_LOCK}
     */
    public void requireVersion(long expected) {
        if (expected != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Credit request %s changed since it was read".formatted(id));
        }
    }

    /**
     * Sales' opinion on what the customer asked: it then waits for the accountant.
     *
     * @throws BusinessException {@code CREDIT_REQUEST_INVALID_TRANSITION} unless it waits for Sales;
     *         {@code VALIDATION_FAILED} without an opinion, or with a suggested limit not above zero
     */
    public void recommend(String note, BigDecimal recommendedLimit, UUID by, Instant at) {
        require(status == CreditRequestStatus.SUBMITTED, "give Sales' opinion on");
        this.salesNote = requiredText(note, "salesNote");
        this.salesRecommendedLimit = suggested(recommendedLimit);
        this.recommendedBy = Objects.requireNonNull(by, "by");
        this.recommendedAt = at;
        this.status = CreditRequestStatus.PENDING_APPROVAL;
    }

    /**
     * The accountant grants a limit and the days to pay: at most the limit asked.
     *
     * @throws BusinessException {@code CREDIT_REQUEST_INVALID_TRANSITION} unless it waits for the accountant;
     *         {@code CREDIT_REQUEST_SELF_DECISION} for whoever filed it or gave Sales' opinion;
     *         {@code CREDIT_REQUEST_ABOVE_REQUESTED} for a limit above the one asked; {@code VALIDATION_FAILED} for a
     *         limit not above zero or days outside 0-365
     */
    public void approve(BigDecimal limit, int termDays, String note, UUID by, Instant at) {
        requireDecidable(by);
        requireAsked(limit, termDays);
        if (limit.compareTo(requestedLimit) > 0) {
            throw new BusinessException(ErrorCode.CREDIT_REQUEST_ABOVE_REQUESTED, ("Credit request %s asked for %s; "
                    + "a higher limit is a new request").formatted(id, requestedLimit.toPlainString()));
        }
        this.approvedLimit = limit;
        this.approvedTermDays = termDays;
        this.decisionNote = optionalText(note, "note");
        decided(CreditRequestStatus.APPROVED, by, at);
    }

    /**
     * The accountant refuses it, saying why.
     *
     * @throws BusinessException as {@link #approve}, and {@code VALIDATION_FAILED} without a reason
     */
    public void reject(String reason, UUID by, Instant at) {
        requireDecidable(by);
        this.decisionNote = requiredText(reason, "reason");
        decided(CreditRequestStatus.REJECTED, by, at);
    }

    /**
     * The customer takes back the request they filed from the portal.
     *
     * @throws BusinessException {@code CREDIT_REQUEST_NOT_FILER} for one Sales filed;
     *         {@code CREDIT_REQUEST_INVALID_TRANSITION} once it is decided
     */
    public void withdrawByCustomer(UUID by, Instant at) {
        if (source != CreditRequestSource.PORTAL) {
            throw notFiler();
        }
        withdraw(by, at);
    }

    /**
     * Sales take back the request they filed for the customer: only whoever filed it.
     *
     * @throws BusinessException {@code CREDIT_REQUEST_NOT_FILER} for anyone else's, and for one the customer filed;
     *         {@code CREDIT_REQUEST_INVALID_TRANSITION} once it is decided
     */
    public void withdrawByStaff(UUID by, Instant at) {
        if (source != CreditRequestSource.SALES || !submittedBy.equals(by)) {
            throw notFiler();
        }
        withdraw(by, at);
    }

    private void withdraw(UUID by, Instant at) {
        require(status.open(), "withdraw");
        decided(CreditRequestStatus.WITHDRAWN, by, at);
    }

    private void requireDecidable(UUID by) {
        require(status == CreditRequestStatus.PENDING_APPROVAL, "decide");
        if (by == null || by.equals(submittedBy) || by.equals(recommendedBy)) {
            throw new BusinessException(ErrorCode.CREDIT_REQUEST_SELF_DECISION, ("Credit request %s is decided by "
                    + "someone other than whoever filed it or gave Sales' opinion").formatted(id));
        }
    }

    private void decided(CreditRequestStatus outcome, UUID by, Instant at) {
        this.status = outcome;
        this.decidedBy = Objects.requireNonNull(by, "by");
        this.decidedAt = Objects.requireNonNull(at, "at");
    }

    private void require(boolean allowed, String what) {
        if (!allowed) {
            throw new BusinessException(ErrorCode.CREDIT_REQUEST_INVALID_TRANSITION,
                    "Cannot %s credit request %s: it is %s".formatted(what, id, status));
        }
    }

    private BusinessException notFiler() {
        return new BusinessException(ErrorCode.CREDIT_REQUEST_NOT_FILER,
                "Credit request %s is withdrawn by whoever filed it".formatted(id));
    }

    private static void requireAsked(BigDecimal limit, int termDays) {
        if (limit == null || limit.signum() <= 0 || limit.compareTo(AMOUNT_MAX) > 0) {
            throw invalid("The credit limit is above zero");
        }
        if (termDays < 0 || termDays > TERM_DAYS_MAX) {
            throw invalid("The days to pay are between 0 and %d".formatted(TERM_DAYS_MAX));
        }
    }

    private static BigDecimal suggested(BigDecimal limit) {
        if (limit != null && (limit.signum() <= 0 || limit.compareTo(AMOUNT_MAX) > 0)) {
            throw invalid("A suggested credit limit is above zero");
        }
        return limit;
    }

    private static String requiredText(String text, String field) {
        String kept = optionalText(text, field);
        if (kept == null) {
            throw invalid("%s is required".formatted(field));
        }
        return kept;
    }

    private static String optionalText(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String kept = text.strip();
        if (kept.length() > TEXT_MAX) {
            throw invalid("%s is at most %d characters".formatted(field, TEXT_MAX));
        }
        return kept;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public CreditRequestSource source() { return source; }
    public CreditRequestStatus status() { return status; }
    public BigDecimal requestedLimit() { return requestedLimit; }
    public int requestedTermDays() { return requestedTermDays; }
    public String currency() { return currency; }
    public String reason() { return reason; }
    public UUID submittedBy() { return submittedBy; }
    public Instant submittedAt() { return submittedAt; }
    public String salesNote() { return salesNote; }
    public BigDecimal salesRecommendedLimit() { return salesRecommendedLimit; }
    public UUID recommendedBy() { return recommendedBy; }
    public Instant recommendedAt() { return recommendedAt; }
    public BigDecimal approvedLimit() { return approvedLimit; }
    public Integer approvedTermDays() { return approvedTermDays; }
    public UUID decidedBy() { return decidedBy; }
    public Instant decidedAt() { return decidedAt; }
    public String decisionNote() { return decisionNote; }
    public long version() { return version; }
}
