package com.stockflow.payment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.CreditRequestDecided;
import com.stockflow.contracts.CreditRequestSubmitted;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.internal.domain.CreditAdvice;
import com.stockflow.payment.internal.domain.CreditRequest;
import com.stockflow.payment.internal.domain.CreditRequestStatus;
import com.stockflow.payment.internal.domain.CreditRequests;
import com.stockflow.payment.internal.domain.PaymentHistories;
import com.stockflow.payment.internal.domain.PaymentHistory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

/**
 * Credit requests (owner decision QĐ-A, 2026-11-26; kltn-docs 15 §4.3, 18 §4 step 5, BR-03): the customer asks from the
 * portal or Sales ask for them, Sales give their opinion, the accountant decides. Lives in {@code payment} because the
 * figures the accountant weighs are payment's and because {@code customer} may not call {@code payment} (that would be
 * a cycle); the credit itself stays the customer's, written through {@link CustomerService#saveCreditTerms}.
 *
 * <p><b>Approving</b> is one transaction: the request row {@code FOR UPDATE}, the version the accountant read, the
 * domain's rules (four eyes, at most the limit asked), then the credit profile - under the customer row lock
 * {@code saveCreditTerms} takes, against the version of the terms the accountant read. Two accountants approving at
 * once: the second waits for the first's lock and is refused {@code OPTIMISTIC_LOCK}, the request's version having
 * moved; terms changed through the credit profile form since they were read refuse it the same way. Either way nothing
 * is written. Lock order: credit request, then customer.</p>
 *
 * <p>Events go out after commit ({@link CreditRequestSubmitted}, {@link CreditRequestDecided}); the notification
 * module tells whoever acts next.</p>
 */
@Service
@Transactional
public class CreditRequestService {

    /** What is asked, and for a request Sales file, their opinion on it. */
    public record Filing(BigDecimal limit, int termDays, String reason, String salesNote, BigDecimal recommendedLimit) {
    }

    /**
     * What the accountant weighs a request on (plan §4.4): how the customer has paid, what they owe now, their credit
     * now, and the warnings - with the two settings the warnings were raised against.
     */
    public record Review(PaymentHistory history, CreditPosition position, CreditTerms currentTerms,
                         CreditAdvice advice, int minSettledContracts, int suggestedMonths) {
    }

    private final CreditRequests requests;
    private final PaymentHistories histories;
    private final PaymentService payments;
    private final InstallmentService installments;
    private final CustomerService customers;
    private final CreditReviewProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CreditRequestService(CreditRequests requests, PaymentHistories histories, PaymentService payments,
                                InstallmentService installments, CustomerService customers,
                                CreditReviewProperties properties, ApplicationEventPublisher events, Clock clock) {
        this.requests = requests;
        this.histories = histories;
        this.payments = payments;
        this.installments = installments;
        this.customers = customers;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The customer asks from the portal, for themselves; it waits for Sales' opinion.
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND} for an account with no customer profile;
     *         {@code CREDIT_REQUEST_ALREADY_OPEN}; {@code VALIDATION_FAILED}
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "credit-request", resourceId = "#result?.id()")
    public CreditRequest fileOwn(UUID portalUserId, Filing filing) {
        UUID customerId = ownCustomerId(portalUserId);
        requireNoneOpen(customerId);
        CreditRequest filed = requests.save(CreditRequest.fromPortal(customerId, filing.limit(), filing.termDays(),
                filing.reason(), portalUserId, clock.instant()));
        submitted(filed, CreditRequestSubmitted.Stage.AWAITING_SALES, portalUserId);
        return filed;
    }

    /**
     * Sales ask for a customer, with their opinion; it waits for the accountant.
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND}; {@code CREDIT_REQUEST_ALREADY_OPEN};
     *         {@code VALIDATION_FAILED} (an opinion is required)
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "credit-request", resourceId = "#result?.id()",
            details = "{customerId: #customerId}")
    public CreditRequest fileForCustomer(UUID customerId, Filing filing, UUID salesUserId) {
        if (customerId == null || customers.findById(customerId).isEmpty()) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + customerId);
        }
        requireNoneOpen(customerId);
        CreditRequest filed = requests.save(CreditRequest.bySales(customerId, filing.limit(), filing.termDays(),
                filing.reason(), filing.salesNote(), filing.recommendedLimit(), salesUserId, clock.instant()));
        submitted(filed, CreditRequestSubmitted.Stage.AWAITING_DECISION, salesUserId);
        return filed;
    }

    /**
     * Sales' opinion on a request the customer filed; it then waits for the accountant.
     *
     * @throws BusinessException {@code CREDIT_REQUEST_NOT_FOUND}; {@code OPTIMISTIC_LOCK};
     *         {@code CREDIT_REQUEST_INVALID_TRANSITION}; {@code VALIDATION_FAILED}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "credit-request", resourceId = "#id")
    public CreditRequest recommend(UUID id, long version, String note, BigDecimal recommendedLimit, UUID by) {
        CreditRequest request = lock(id, version);
        request.recommend(note, recommendedLimit, by, clock.instant());
        CreditRequest saved = requests.save(request);
        submitted(saved, CreditRequestSubmitted.Stage.AWAITING_DECISION, by);
        return saved;
    }

    /**
     * The accountant grants a limit and the days to pay - at most the limit asked - and the customer's credit is set to
     * them in the same transaction.
     *
     * @param creditTermsVersion the version of the customer's credit the accountant read; null (or -1) for a customer
     *                           with none yet
     * @throws BusinessException {@code CREDIT_REQUEST_NOT_FOUND}; {@code OPTIMISTIC_LOCK} for a request or terms
     *         changed since read; {@code CREDIT_REQUEST_INVALID_TRANSITION}; {@code CREDIT_REQUEST_SELF_DECISION};
     *         {@code CREDIT_REQUEST_ABOVE_REQUESTED}; {@code VALIDATION_FAILED}
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "credit-request", resourceId = "#id")
    public CreditRequest approve(UUID id, long version, BigDecimal limit, int termDays, String note,
                                 Long creditTermsVersion, UUID by) {
        CreditRequest request = lock(id, version);
        request.approve(limit, termDays, note, by, clock.instant());
        CreditRequest saved = requests.save(request);
        customers.saveCreditTerms(new SaveCreditTermsCommand(saved.customerId(), saved.approvedLimit(),
                saved.approvedTermDays(), saved.decisionNote(), by, creditTermsVersion));
        decided(saved, CreditRequestDecided.Outcome.APPROVED);
        return saved;
    }

    /**
     * The accountant refuses it, saying why. The customer's credit is unchanged.
     *
     * @throws BusinessException as {@link #approve}, without the terms; {@code VALIDATION_FAILED} without a reason
     */
    @Auditable(action = AuditAction.REJECT, resourceType = "credit-request", resourceId = "#id")
    public CreditRequest reject(UUID id, long version, String reason, UUID by) {
        CreditRequest request = lock(id, version);
        request.reject(reason, by, clock.instant());
        CreditRequest saved = requests.save(request);
        decided(saved, CreditRequestDecided.Outcome.REJECTED);
        return saved;
    }

    /**
     * Whoever filed it takes it back while it is open: the customer their own from the portal
     * ({@code ownCustomerId} set), Sales the one they filed.
     *
     * @param ownCustomerId the caller's customer when they are a portal customer; null for the back office
     * @throws BusinessException {@code CREDIT_REQUEST_NOT_FOUND} (for a customer: not theirs);
     *         {@code OPTIMISTIC_LOCK}; {@code CREDIT_REQUEST_NOT_FILER}; {@code CREDIT_REQUEST_INVALID_TRANSITION}
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "credit-request", resourceId = "#id",
            details = "{to: 'WITHDRAWN'}")
    public CreditRequest withdraw(UUID id, long version, UUID by, UUID ownCustomerId) {
        CreditRequest request = requests.findForUpdate(id).filter(r -> visible(r, ownCustomerId))
                .orElseThrow(() -> notFound(id));
        request.requireVersion(version);
        if (ownCustomerId != null) {
            request.withdrawByCustomer(by, clock.instant());
        } else {
            request.withdrawByStaff(by, clock.instant());
        }
        return requests.save(request);
    }

    /**
     * One request; for a portal customer, only their own.
     *
     * @throws BusinessException {@code CREDIT_REQUEST_NOT_FOUND}, also for another customer's
     */
    @Transactional(readOnly = true)
    public CreditRequest find(UUID id, UUID ownCustomerId) {
        return requests.find(id).filter(r -> visible(r, ownCustomerId)).orElseThrow(() -> notFound(id));
    }

    /** The back office's list, newest first; every filter optional. */
    @Transactional(readOnly = true)
    public PageResponse<CreditRequest> list(UUID customerId, CreditRequestStatus status, int page, int size) {
        return requests.page(customerId, status, page, size);
    }

    /** The portal customer's own requests, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<CreditRequest> mine(UUID portalUserId, int page, int size) {
        return requests.page(ownCustomerId(portalUserId), null, page, size);
    }

    /** The customer of a portal account. @throws BusinessException {@code CUSTOMER_NOT_FOUND} */
    @Transactional(readOnly = true)
    public UUID ownCustomerId(UUID portalUserId) {
        return customers.findByUserId(portalUserId).map(CustomerSummary::customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    /** What the accountant weighs {@code request} on (plan §4.4), read now. */
    @Transactional(readOnly = true)
    public Review review(CreditRequest request) {
        PaymentHistory history = histories.of(request.customerId(), installments.today());
        CreditPosition position = payments.creditPosition(request.customerId());
        CreditAdvice advice = CreditAdvice.of(history, request.requestedLimit(), position.hasOverdue(),
                properties.minSettledContracts(), properties.suggestedMonths());
        return new Review(history, position, customers.creditTerms(request.customerId()), advice,
                properties.minSettledContracts(), properties.suggestedMonths());
    }

    private CreditRequest lock(UUID id, long version) {
        CreditRequest request = requests.findForUpdate(id).orElseThrow(() -> notFound(id));
        request.requireVersion(version);
        return request;
    }

    private void requireNoneOpen(UUID customerId) {
        requests.findOpen(customerId).ifPresent(open -> {
            throw new BusinessException(ErrorCode.CREDIT_REQUEST_ALREADY_OPEN,
                    "Customer %s already has open credit request %s".formatted(customerId, open.id()));
        });
    }

    private static boolean visible(CreditRequest request, UUID ownCustomerId) {
        return ownCustomerId == null || ownCustomerId.equals(request.customerId());
    }

    private static BusinessException notFound(UUID id) {
        return new BusinessException(ErrorCode.CREDIT_REQUEST_NOT_FOUND, "No credit request " + id);
    }

    private void submitted(CreditRequest request, CreditRequestSubmitted.Stage stage, UUID by) {
        events.publishEvent(new CreditRequestSubmitted(request.id(), request.customerId(), stage,
                request.requestedLimit(), request.requestedTermDays(), request.currency(), by, clock.instant()));
    }

    private void decided(CreditRequest request, CreditRequestDecided.Outcome outcome) {
        events.publishEvent(new CreditRequestDecided(request.id(), request.customerId(), outcome,
                request.approvedLimit(), request.approvedTermDays(), request.currency(), request.submittedBy(),
                request.recommendedBy(), request.decidedBy(), request.decidedAt()));
    }
}
