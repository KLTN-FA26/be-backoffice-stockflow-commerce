package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The sales contract of an order (kltn-docs 14 §4.3 steps 11-17, §5.2, BR-12, BR-14, BR-18, BR-23). Its goods, prices,
 * fee lines, taxes and totals are the order's and are never stored here twice (BR-12); what it adds are the parties as
 * they were when it was drawn up, the free print-sample rounds, the delivery schedule and the payment plan, which
 * {@link ContractTerms} checks against the order.
 *
 * <p>Drawn up for a DRAFT order, edited freely while a draft, sent (the PDF the customer received is kept), signed
 * outside the system - recorded with the signed file and the day it was signed - and immutable from then on (BR-14).
 * Cancelled before it is signed, with a reason; the order may then get a new one. A signed contract is never cancelled
 * here. The database keeps the same state machine ({@code tg_sales_contract_transition}) and the same rules over the
 * schedule and plan ({@code ordering.check_sales_contract_terms}).</p>
 *
 * <p>A draft whose credit check fails is not sent but waits for accounting (15 BR-11 as changed on 2026-11-26):
 * {@link #submitForCreditReview} keeps it a DRAFT - so the customer, who never sees a draft, sees nothing of it - and
 * locks it; {@link #approveCredit} sends it, {@link #refuseCredit} and {@link #withdrawCreditReview} hand it back to
 * Sales. Only a draft waits ({@code ck_sales_contract_credit_review}).</p>
 *
 * <p>Contract e-signing (plan of 2026-11-28): the customer signs first - {@link #customerSigned} - and the company's
 * signer after, which puts it in force ({@link #companySigned}); a paper signed by both can still be recorded from SENT or
 * CUSTOMER_SIGNED ({@link #sign}). A customer who wants changes sends it back to DRAFT as the next revision
 * ({@link #requestChanges}). A draft that deviates from the standard terms waits for a Sales Manager before it can be
 * sent ({@link TermsReview}); sending it fixes a signing deadline the owner may move ({@link #extendSignBy}).</p>
 */
public final class SalesContract {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    /** How a day is written in a message people read. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final UUID id;
    private final String contractNumber;
    private final UUID orderId;
    private SalesContractStatus status;
    private final ContractParty seller;
    private final ContractParty buyer;
    private int freeSampleRounds;
    /**
     * The complaint period agreed, in calendar days from each delivery (Luật Thương mại 2005 Điều 318; WF-05 TH-1);
     * null on a contract drawn up before it was written down, which reads the configured default.
     */
    private Integer returnWindowDays;
    private List<DeliveryBatch> schedule;
    private List<PlannedInstallment> plan;
    private Instant sentAt;
    private UUID sentBy;
    private String sentDocumentKey;
    private LocalDate signedOn;
    private String signedFileKey;
    private String signedFileName;
    private Instant signedAt;
    private UUID signedBy;
    private Instant cancelledAt;
    private UUID cancelledBy;
    private String cancellationReason;
    private Instant completedAt;
    /** WF-05 Q-6: when it was liquidated; null otherwise. */
    private Instant liquidatedAt;
    /** 15 BR-11: whether the draft waits for accounting to approve the customer's credit before it is sent. */
    private boolean creditReviewPending;
    /**
     * 14 BR-26: the customer's Sales owner when the contract was drawn up - sales per salesperson are counted by it.
     * Set once, at drafting (tg_sales_contract_sales_owner_fixed): a customer moved later leaves it as it was. Null
     * when the customer had no owner. Not who is told anything: notices follow the customer's owner of today.
     */
    private UUID salesOwnerId;
    /** The special terms, the three overrides and the signers; {@link ContractAgreement#NONE} when there are none. */
    private ContractAgreement agreement = ContractAgreement.NONE;
    /** Whether the agreement deviated from the standard terms when it was last edited (QĐ-4). */
    private boolean nonStandard;
    /** Starts at 1; each change request of the customer reopens the contract as the next one. */
    private int revision = 1;
    /** The day the customer should have signed by; null for a contract sent before deadlines, and for a draft. */
    private LocalDate signBy;
    private Instant customerSignedAt;
    private TermsReview termsReview = TermsReview.NONE;
    private String termsReviewNote;
    private UUID termsReviewRequestedBy;
    private Instant termsReviewRequestedAt;
    private UUID termsReviewedBy;
    private Instant termsReviewedAt;
    /** Who drew it up; the company's signer is somebody else (four eyes). Null on contracts drawn up before. */
    private UUID drawnUpBy;
    private final long version;

    private SalesContract(UUID id, String contractNumber, UUID orderId, SalesContractStatus status,
                          ContractParty seller, ContractParty buyer, int freeSampleRounds, List<DeliveryBatch> schedule,
                          List<PlannedInstallment> plan, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.contractNumber = Objects.requireNonNull(contractNumber, "contractNumber");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.status = Objects.requireNonNull(status, "status");
        this.seller = Objects.requireNonNull(seller, "seller");
        this.buyer = Objects.requireNonNull(buyer, "buyer");
        this.freeSampleRounds = freeSampleRounds;
        this.schedule = List.copyOf(schedule);
        this.plan = List.copyOf(plan);
        this.version = version;
    }

    /**
     * Drawn up for {@code order}, which is a DRAFT order of cartons (14 §4.3 step 11). The schedule and plan were built
     * by {@link ContractTerms} against the same order.
     *
     * @throws BusinessException {@code SALES_CONTRACT_ORDER_NOT_DRAFT}, {@code SALES_CONTRACT_ORDER_NOT_CARTONS},
     *                           {@code VALIDATION_FAILED} for the free sample rounds
     */
    public static SalesContract draft(UUID id, String contractNumber, Order order, ContractParty seller,
                                      ContractParty buyer, int freeSampleRounds, List<DeliveryBatch> schedule,
                                      List<PlannedInstallment> plan) {
        return draft(id, contractNumber, order, seller, buyer, freeSampleRounds, schedule, plan, null);
    }

    /** {@link #draft}, with the customer's Sales owner of this moment (14 BR-26); null when it has none. */
    public static SalesContract draft(UUID id, String contractNumber, Order order, ContractParty seller,
                                      ContractParty buyer, int freeSampleRounds, List<DeliveryBatch> schedule,
                                      List<PlannedInstallment> plan, UUID salesOwnerId) {
        requireContractable(order);
        requireRounds(freeSampleRounds);
        SalesContract contract = new SalesContract(id, contractNumber, order.id().value(), SalesContractStatus.DRAFT,
                seller, buyer, freeSampleRounds, schedule, plan, 0L);
        contract.salesOwnerId = salesOwnerId;
        return contract;
    }

    /**
     * Whether a contract may be drawn up for {@code order}: a DRAFT order (14 §4.3 step 11) whose lines are all cartons
     * of the customer's own SKUs, which the delivery schedule counts (BR-23).
     *
     * @throws BusinessException {@code SALES_CONTRACT_ORDER_NOT_DRAFT}, {@code SALES_CONTRACT_ORDER_NOT_CARTONS}
     */
    public static void requireContractable(Order order) {
        requireDraftOrder(order);
        if (order.lines().stream().anyMatch(line -> line.cartons() == null)) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_ORDER_NOT_CARTONS, ("Order %s has lines not ordered "
                    + "in cartons: a contract schedules cartons of the customer's own SKUs").formatted(
                    order.orderNumber())).localized("refusal.contract.orderNotCartons", order.orderNumber().value());
        }
    }

    /** Rebuilt from storage; no rule is checked again. */
    public static SalesContract restore(UUID id, String contractNumber, UUID orderId, SalesContractStatus status,
                                        ContractParty seller, ContractParty buyer, int freeSampleRounds,
                                        List<DeliveryBatch> schedule, List<PlannedInstallment> plan, Instant sentAt,
                                        UUID sentBy, String sentDocumentKey, LocalDate signedOn, String signedFileKey,
                                        String signedFileName, Instant signedAt, UUID signedBy, Instant cancelledAt,
                                        UUID cancelledBy, String cancellationReason, Instant completedAt,
                                        long version) {
        SalesContract contract = new SalesContract(id, contractNumber, orderId, status, seller, buyer,
                freeSampleRounds, schedule, plan, version);
        contract.sentAt = sentAt;
        contract.sentBy = sentBy;
        contract.sentDocumentKey = sentDocumentKey;
        contract.signedOn = signedOn;
        contract.signedFileKey = signedFileKey;
        contract.signedFileName = signedFileName;
        contract.signedAt = signedAt;
        contract.signedBy = signedBy;
        contract.cancelledAt = cancelledAt;
        contract.cancelledBy = cancelledBy;
        contract.cancellationReason = cancellationReason;
        contract.completedAt = completedAt;
        return contract;
    }

    /**
     * A draft revised (14 §4.3 step 16): the free sample rounds, the schedule and the plan replaced whole.
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID} once sent; {@code SALES_CONTRACT_ORDER_NOT_DRAFT}
     */
    public void revise(Order order, int freeSampleRounds, List<DeliveryBatch> schedule, List<PlannedInstallment> plan) {
        requireRevisable(order);
        requireRounds(freeSampleRounds);
        this.freeSampleRounds = freeSampleRounds;
        this.schedule = List.copyOf(schedule);
        this.plan = List.copyOf(plan);
        resetTermsReview();
    }

    /**
     * The special terms, overrides and signers written on the draft (replaced whole). Judged against the standard terms: a
     * deviation needs a Sales Manager's approval before it is sent ({@link #submitForTermsReview}); an approval or a
     * refusal already given is taken back, since what was decided on is no longer what is written.
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID} once sent; {@code CONTRACT_TERMS_REVIEW_PENDING}
     *                           while a manager has it; {@code SALES_CONTRACT_ORDER_NOT_DRAFT}
     */
    public void reviseAgreement(Order order, ContractAgreement agreement, ContractAgreement.Standard standard) {
        requireRevisable(order);
        this.agreement = Objects.requireNonNull(agreement, "agreement");
        this.nonStandard = agreement.deviates(Objects.requireNonNull(standard, "standard"));
        resetTermsReview();
    }

    /** Part of drawing it up: who it was, once. */
    public void drawnUpBy(UUID user) {
        if (drawnUpBy != null) {
            throw new IllegalStateException("Contract " + contractNumber + " was drawn up already");
        }
        this.drawnUpBy = user;
    }

    /**
     * The complaint period agreed (Điều 318; WF-05 TH-1): written on the contract while it is a draft - the configured
     * default when it is drawn up, what Sales and the customer agree on an edit - and frozen with the rest once sent
     * ({@code tg_sales_contract_transition}). Returns of the order are opened within it, and the order completes only
     * after it.
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID} once sent; {@code SALES_CONTRACT_ORDER_NOT_DRAFT};
     *         {@code VALIDATION_FAILED} outside 1..365 days ({@code ck_sales_contract_return_window_days})
     */
    public void agreeReturnWindow(Order order, int days) {
        requireRevisable(order);
        if (days < 1 || days > 365) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Return window: a whole number of days, 1 to 365")
                    .localized("refusal.contract.returnWindow");
        }
        this.returnWindowDays = days;
        resetTermsReview();
    }

    /** Rehydration of the complaint period; null for a contract drawn up before it was written down. */
    public SalesContract restoreReturnWindow(Integer days) {
        this.returnWindowDays = days;
        return this;
    }

    /**
     * Whether it may still be edited: a draft, of a draft order. Asked before a new schedule or plan is even checked,
     * so editing a sent contract answers that it is sent (BR-14) - not that the plan Sales typed does not add up, which
     * would send them fixing a plan they may not change at all.
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID}; {@code SALES_CONTRACT_ORDER_NOT_DRAFT}
     */
    public void requireRevisable(Order order) {
        requireStatus(SalesContractStatus.DRAFT, "edited");
        requireNoCreditReview();
        requireNoTermsReview();
        requireOrder(order);
        requireDraftOrder(order);
    }

    /**
     * Sent to the customer (14 §4.3 step 13): every delivery window known and none in the past (BR-23); the PDF the
     * customer received is kept under {@code documentKey}. From here the content is locked (BR-14).
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID}; {@code SALES_CONTRACT_ORDER_NOT_DRAFT};
     *                           {@code DELIVERY_SCHEDULE_INVALID}
     */
    public void send(Order order, Instant at, UUID by, String documentKey) {
        send(order, at, by, documentKey, null);
    }

    /**
     * {@link #send(Order, Instant, UUID, String)} with the day the customer should have signed by; null leaves no
     * deadline (a contract sent before deadlines existed). Refused while a non-standard draft is not approved.
     *
     * @throws BusinessException as above; {@code CONTRACT_TERMS_REVIEW_REQUIRED}, {@code CONTRACT_TERMS_REVIEW_PENDING}
     */
    public void send(Order order, Instant at, UUID by, String documentKey, LocalDate signBy) {
        requireSendable(order, at);
        markSent(at, by, documentKey, signBy);
    }

    private void markSent(Instant at, UUID by, String documentKey, LocalDate signBy) {
        this.status = SalesContractStatus.SENT;
        this.sentAt = Objects.requireNonNull(at, "at");
        this.sentBy = Objects.requireNonNull(by, "by");
        this.sentDocumentKey = Objects.requireNonNull(documentKey, "documentKey");
        this.signBy = signBy;
    }

    /** The revision just sent: what the revision row keeps. */
    public SentRevision sentRevision() {
        if (sentAt == null) {
            throw new IllegalStateException("Contract " + contractNumber + " is not sent");
        }
        return new SentRevision(revision, sentDocumentKey, sentAt, sentBy);
    }

    /**
     * Whether it may be sent now - a draft, of a draft order, every window known and none in the past - checked before
     * the PDF is drawn, so a refusal leaves no document behind.
     *
     * @throws BusinessException as {@link #send}
     */
    public void requireSendable(Order order, Instant at) {
        requireStatus(SalesContractStatus.DRAFT, "sent");
        requireNoCreditReview();
        requireTermsApproved();
        requireSendableContent(order, at);
    }

    /**
     * 15 BR-11 (2026-11-26): the draft, ready to be sent, waits for accounting instead - its credit check failed. It
     * stays a DRAFT, locked against edits until approved, refused or withdrawn. Checked as {@link #send} is, so a
     * contract that could not be sent is not put in front of accounting either.
     *
     * @throws BusinessException as {@link #send}; {@code CONTRACT_CREDIT_REVIEW_PENDING} when it waits already
     */
    public void submitForCreditReview(Order order, Instant at) {
        requireSendable(order, at);
        creditReviewPending = true;
    }

    /**
     * Whether accounting may approve it now and so send it: it waits, and could be sent - the windows may have passed
     * while it waited, and a refusal leaves it waiting. Checked before the PDF is drawn.
     *
     * @throws BusinessException {@code CONTRACT_CREDIT_REVIEW_NOT_PENDING}; as {@link #send}
     */
    public void requireCreditApprovable(Order order, Instant at) {
        requireStatus(SalesContractStatus.DRAFT, "sent");
        requireCreditReview();
        requireSendableContent(order, at);
    }

    /**
     * 15 BR-11: accounting approved the customer's credit; the contract is sent at once (decision 1 of the credit plan),
     * as {@code sentBy} - the Sales account that asked, who follows it with the customer.
     *
     * @throws BusinessException as {@link #requireCreditApprovable}
     */
    public void approveCredit(Order order, Instant at, UUID sentBy, String documentKey) {
        approveCredit(order, at, sentBy, documentKey, null);
    }

    /** {@link #approveCredit(Order, Instant, UUID, String)} with the day the customer should have signed by. */
    public void approveCredit(Order order, Instant at, UUID sentBy, String documentKey, LocalDate signBy) {
        requireCreditApprovable(order, at);
        creditReviewPending = false;
        markSent(at, sentBy, documentKey, signBy);
    }

    /** 15 BR-11: accounting refused; the draft is Sales' to change - the payment plan, most likely - and send again. */
    public void refuseCredit() {
        requireCreditReview();
        creditReviewPending = false;
    }

    /** 15 BR-11: Sales takes the draft back from accounting to change it. */
    public void withdrawCreditReview() {
        requireCreditReview();
        creditReviewPending = false;
    }

    private void requireSendableContent(Order order, Instant at) {
        requireOrder(order);
        requireDraftOrder(order);
        ContractTerms.requireWindows(schedule, LocalDate.ofInstant(at, VIETNAM));
    }

    private void requireNoCreditReview() {
        if (creditReviewPending) {
            throw new BusinessException(ErrorCode.CONTRACT_CREDIT_REVIEW_PENDING, ("Contract %s is waiting for "
                    + "accounting to approve the customer's credit; withdraw it first").formatted(contractNumber))
                    .localized("refusal.contract.creditReviewPending", contractNumber);
        }
    }

    private void requireCreditReview() {
        if (!creditReviewPending) {
            throw new BusinessException(ErrorCode.CONTRACT_CREDIT_REVIEW_NOT_PENDING, ("Contract %s is not waiting for "
                    + "accounting to approve the customer's credit").formatted(contractNumber))
                    .localized("refusal.contract.creditReviewNotPending", contractNumber);
        }
    }

    /**
     * Signed outside the system and recorded (14 §4.3 step 14, BR-14): the signed file - a scan of the signed paper or
     * a digitally signed PDF - and the day it was signed, not before the contract was sent nor after today.
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID}; {@code VALIDATION_FAILED} for the day
     */
    public void sign(LocalDate on, String fileKey, String fileName, Instant at, UUID by) {
        requireSignable(on, at);
        markSigned(on, fileKey, fileName, at, by);
    }

    private void markSigned(LocalDate on, String fileKey, String fileName, Instant at, UUID by) {
        this.status = SalesContractStatus.SIGNED;
        this.signedOn = on;
        this.signedFileKey = Objects.requireNonNull(fileKey, "fileKey");
        this.signedFileName = Objects.requireNonNull(fileName, "fileName");
        this.signedAt = Objects.requireNonNull(at, "at");
        this.signedBy = Objects.requireNonNull(by, "by");
    }

    /**
     * The customer signed (QĐ-1: they sign first): SENT → CUSTOMER_SIGNED, at {@code at}. The proof of who signed and how
     * - the code they entered, the file they sent - is the signing machinery's, kept beside the contract; this is the
     * state. Refused after the signing deadline ({@link #requireCustomerSignable}).
     *
     * @throws BusinessException {@code CONTRACT_NOT_SIGNABLE}, {@code CONTRACT_SIGN_DEADLINE_PASSED}
     */
    public void customerSigned(Instant at) {
        requireCustomerSignable(LocalDate.ofInstant(Objects.requireNonNull(at, "at"), VIETNAM));
        this.status = SalesContractStatus.CUSTOMER_SIGNED;
        this.customerSignedAt = at;
    }

    /**
     * Whether the customer may sign now: the contract is SENT - not a draft, not already signed by them, not cancelled -
     * and {@code today} is not past {@link #signBy()}; Sales extends the deadline to let a late customer sign.
     *
     * @throws BusinessException {@code CONTRACT_NOT_SIGNABLE}, {@code CONTRACT_SIGN_DEADLINE_PASSED}
     */
    public void requireCustomerSignable(LocalDate today) {
        if (status != SalesContractStatus.SENT) {
            throw new BusinessException(ErrorCode.CONTRACT_NOT_SIGNABLE, "Contract %s is %s: only a sent contract is "
                    .formatted(contractNumber, status) + "signed by the customer")
                    .localized("refusal.contract.notSignable", contractNumber, status);
        }
        if (overdueToSign(today)) {
            throw new BusinessException(ErrorCode.CONTRACT_SIGN_DEADLINE_PASSED, ("Contract %s was to be signed by %s; "
                    + "ask Sales to extend the deadline").formatted(contractNumber, signBy))
                    .localized("refusal.contract.signDeadlinePassed", contractNumber, DAY.format(signBy));
        }
    }

    /**
     * The company signed after the customer: CUSTOMER_SIGNED → SIGNED, with the file that was signed (the PDF sealed by
     * the company's signature, or the one signed outside and uploaded) and the day. From here the contract is in force
     * and immutable (BR-14).
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID} unless the customer signed;
     *                           {@code VALIDATION_FAILED} for the day
     */
    public void companySigned(LocalDate on, String fileKey, String fileName, Instant at, UUID by) {
        requireCompanySignable(on, at);
        markSigned(on, fileKey, fileName, at, by);
    }

    /** Whether the company may sign on {@code on}, now: the customer has signed, and the day is not before it was sent nor after today. */
    public void requireCompanySignable(LocalDate on, Instant at) {
        if (status != SalesContractStatus.CUSTOMER_SIGNED) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, ("Contract %s is %s: the company "
                    + "signs a contract the customer has signed").formatted(contractNumber, status))
                    .localized("refusal.contract.status.companySigned", contractNumber, status);
        }
        requireSignedDay(on, at);
    }

    /**
     * Four eyes (QĐ-2): the account that drew the contract up or sent it does not sign it for the company.
     *
     * @throws BusinessException {@code CONTRACT_SIGNER_IS_AUTHOR}
     */
    public void requireNotAuthor(UUID user) {
        if (user != null && (user.equals(drawnUpBy) || user.equals(sentBy))) {
            throw new BusinessException(ErrorCode.CONTRACT_SIGNER_IS_AUTHOR, ("Contract %s was drawn up or sent by you: "
                    + "someone else signs it for the company").formatted(contractNumber))
                    .localized("refusal.contract.signerIsAuthor", contractNumber);
        }
    }

    /**
     * The customer asks for changes after reading it (a reason of 10 to 2000 characters): SENT → DRAFT as the next
     * revision, for Sales to change and send again. What was sent is no longer sent - the revision row keeps the PDF and
     * the reason - and the deadline goes with it. Not from CUSTOMER_SIGNED: a contract the customer signed is theirs to
     * have honoured, or Sales cancels it.
     *
     * @return the revision that ended, for the revision row
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID} unless SENT; {@code VALIDATION_FAILED} for the reason
     */
    public SentRevision requestChanges(String reason) {
        if (status != SalesContractStatus.SENT) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, ("Contract %s is %s: the customer "
                    + "asks for changes to a sent contract").formatted(contractNumber, status))
                    .localized("refusal.contract.status.changeRequested", contractNumber, status);
        }
        requireChangeReason(reason);
        SentRevision ended = sentRevision();
        this.status = SalesContractStatus.DRAFT;
        this.revision = revision + 1;
        this.sentAt = null;
        this.sentBy = null;
        this.sentDocumentKey = null;
        this.signBy = null;
        return ended;
    }

    /** The reason a customer gives for asking for changes, stripped. @throws BusinessException {@code VALIDATION_FAILED} */
    public static String requireChangeReason(String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.length() < 10 || text.length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A reason of 10 to 2000 characters")
                    .localized("refusal.contract.changeReason", 10, 2000);
        }
        return text;
    }

    /** How far ahead Sales may put the signing deadline. */
    public static final int MAX_SIGN_DAYS_AHEAD = 60;

    /**
     * Sales extends (or shortens) the signing deadline while the contract waits for the customer: not in the past, at
     * most {@value #MAX_SIGN_DAYS_AHEAD} days from {@code today}.
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID} unless SENT; {@code VALIDATION_FAILED} for the day
     */
    public void extendSignBy(LocalDate newSignBy, LocalDate today) {
        if (status != SalesContractStatus.SENT) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, ("Contract %s is %s: the signing "
                    + "deadline moves while it waits for the customer").formatted(contractNumber, status))
                    .localized("refusal.contract.status.signBy", contractNumber, status);
        }
        Objects.requireNonNull(today, "today");
        if (newSignBy == null || newSignBy.isBefore(today) || newSignBy.isAfter(today.plusDays(MAX_SIGN_DAYS_AHEAD))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("The signing deadline is today or later, at "
                    + "most %d days ahead").formatted(MAX_SIGN_DAYS_AHEAD))
                    .localized("refusal.contract.signBy", MAX_SIGN_DAYS_AHEAD);
        }
        this.signBy = newSignBy;
    }

    /** Whether it waits for the customer past the day they were to sign by (nothing is cancelled for it). */
    public boolean overdueToSign(LocalDate today) {
        return status == SalesContractStatus.SENT && signBy != null && signBy.isBefore(today);
    }

    // ---- terms review (QĐ-4) ---------------------------------------------------------------------------------------

    /**
     * A draft that deviates from the standard terms is put in front of a Sales Manager. It stays a DRAFT - the customer
     * sees nothing of it - locked against edits until it is approved, refused or withdrawn. Asked again once approved
     * changes nothing.
     *
     * @throws BusinessException {@code CONTRACT_TERMS_REVIEW_NOT_NEEDED} for a standard contract;
     *         {@code CONTRACT_TERMS_REVIEW_PENDING} when it waits already; {@code CONTRACT_CREDIT_REVIEW_PENDING};
     *         {@code SALES_CONTRACT_STATUS_INVALID}; {@code SALES_CONTRACT_ORDER_NOT_DRAFT}
     */
    public void submitForTermsReview(Order order, Instant at, UUID by) {
        requireStatus(SalesContractStatus.DRAFT, "sent");
        requireNoCreditReview();
        requireOrder(order);
        requireDraftOrder(order);
        if (!nonStandard) {
            throw new BusinessException(ErrorCode.CONTRACT_TERMS_REVIEW_NOT_NEEDED, ("Contract %s has the standard "
                    + "terms: nobody has to approve them").formatted(contractNumber))
                    .localized("refusal.contract.termsReviewNotNeeded", contractNumber);
        }
        requireNoTermsReview();
        if (termsReview == TermsReview.APPROVED) {
            return;
        }
        this.termsReview = TermsReview.PENDING;
        this.termsReviewRequestedBy = Objects.requireNonNull(by, "by");
        this.termsReviewRequestedAt = Objects.requireNonNull(at, "at");
        this.termsReviewNote = null;
        this.termsReviewedBy = null;
        this.termsReviewedAt = null;
    }

    /** A Sales Manager approves the terms as written; the contract may now be sent. The note is optional. */
    public void approveTerms(String note, Instant at, UUID by) {
        requireTermsReviewPending();
        String text = decisionNote(note, false);
        this.termsReview = TermsReview.APPROVED;
        this.termsReviewNote = text;
        this.termsReviewedBy = Objects.requireNonNull(by, "by");
        this.termsReviewedAt = Objects.requireNonNull(at, "at");
    }

    /** A Sales Manager refuses, with the reason; the draft goes back to Sales to change and send for review again. */
    public void rejectTerms(String note, Instant at, UUID by) {
        requireTermsReviewPending();
        String text = decisionNote(note, true);
        this.termsReview = TermsReview.REJECTED;
        this.termsReviewNote = text;
        this.termsReviewedBy = Objects.requireNonNull(by, "by");
        this.termsReviewedAt = Objects.requireNonNull(at, "at");
    }

    /** Sales takes the draft back from the Sales Manager to change it. */
    public void withdrawTermsReview() {
        requireTermsReviewPending();
        clearTermsReview();
    }

    private void requireTermsReviewPending() {
        if (termsReview != TermsReview.PENDING) {
            throw new BusinessException(ErrorCode.CONTRACT_TERMS_REVIEW_NOT_PENDING, ("Contract %s is not waiting for a "
                    + "Sales Manager to approve its terms").formatted(contractNumber))
                    .localized("refusal.contract.termsReviewNotPending", contractNumber);
        }
    }

    private void requireNoTermsReview() {
        if (termsReview == TermsReview.PENDING) {
            throw new BusinessException(ErrorCode.CONTRACT_TERMS_REVIEW_PENDING, ("Contract %s is waiting for a Sales "
                    + "Manager to approve its terms; withdraw it first").formatted(contractNumber))
                    .localized("refusal.contract.termsReviewPending", contractNumber);
        }
    }

    /** Sent only when it is standard or approved as it is (QĐ-4). */
    private void requireTermsApproved() {
        requireNoTermsReview();
        if (nonStandard && termsReview != TermsReview.APPROVED) {
            throw new BusinessException(ErrorCode.CONTRACT_TERMS_REVIEW_REQUIRED, ("Contract %s deviates from the "
                    + "standard terms: a Sales Manager approves it before it is sent").formatted(contractNumber))
                    .localized("refusal.contract.termsReviewRequired", contractNumber);
        }
    }

    /** What was decided is on the draft as it stood; once it changes, it is not. */
    private void resetTermsReview() {
        if (termsReview == TermsReview.APPROVED || termsReview == TermsReview.REJECTED) {
            clearTermsReview();
        }
    }

    private void clearTermsReview() {
        this.termsReview = TermsReview.NONE;
        this.termsReviewNote = null;
        this.termsReviewRequestedBy = null;
        this.termsReviewRequestedAt = null;
        this.termsReviewedBy = null;
        this.termsReviewedAt = null;
    }

    private static String decisionNote(String note, boolean required) {
        String text = note == null ? "" : note.strip();
        if (text.length() > 500 || (required && text.isEmpty())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, required
                    ? "A refusal says why, in at most 500 characters" : "A note of at most 500 characters")
                    .localized(required ? "refusal.contract.termsReview.reasonRequired"
                            : "refusal.contract.termsReview.note", 500);
        }
        return text.isEmpty() ? null : text;
    }

    /**
     * 4B-8 (kltn-docs 14 §5.2, 17 §4.6 step 21): the order it binds completed - every batch delivered, every installment
     * paid - so the contract is fulfilled: SIGNED → COMPLETED, once. {@code ck_sales_contract_completed} and the
     * transition trigger say the same.
     *
     * @return whether this changed the contract (false when it was completed already)
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID} for a contract not signed
     */
    public boolean complete(Instant at) {
        if (status == SalesContractStatus.COMPLETED || status == SalesContractStatus.LIQUIDATED) {
            return false;
        }
        if (!status.canTransitionTo(SalesContractStatus.COMPLETED)) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, "Contract %s is %s: only a signed "
                    .formatted(contractNumber, status) + "contract is completed")
                    .localized("refusal.contract.status.completed", contractNumber, status);
        }
        this.status = SalesContractStatus.COMPLETED;
        this.completedAt = Objects.requireNonNull(at, "at");
        return true;
    }

    /**
     * Cancelled before it was signed, with a reason (14 §4.3 step 16, §5.2): the customer refused it, its content has
     * to change, or the order is cancelled. A signed contract is never cancelled here (BR-14).
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID}; {@code VALIDATION_FAILED} without a reason
     */
    public void cancel(String reason, Instant at, UUID by) {
        if (!status.canTransitionTo(SalesContractStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, "Contract %s is %s: only a draft or a "
                    .formatted(contractNumber, status) + "sent contract, signed or not by the customer, is cancelled")
                    .localized("refusal.contract.status.cancelled", contractNumber, status);
        }
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A reason of at most 500 characters")
                    .localized("refusal.contract.cancellationReason", 500);
        }
        this.status = SalesContractStatus.CANCELLED;
        // Cancelled while it waited, it waits no more: the adapter closes the review WITHDRAWN with this reason.
        this.creditReviewPending = false;
        if (termsReview == TermsReview.PENDING) {
            // Likewise a manager's review: it waits for nothing once the contract is cancelled.
            clearTermsReview();
        }
        this.cancellationReason = reason.strip();
        this.cancelledAt = Objects.requireNonNull(at, "at");
        this.cancelledBy = Objects.requireNonNull(by, "by");
    }

    /**
     * Whether a file signed by both sides may be recorded: a sent contract, or one the customer signed on the portal.
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID}
     */
    public void requireSignable() {
        if (!status.awaitingSignatures()) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, ("Contract %s is %s: only a sent "
                    + "contract is signed").formatted(contractNumber, status))
                    .localized("refusal.contract.status.signed", contractNumber, status);
        }
    }

    /**
     * Whether it may be recorded signed on {@code on}, now: a sent contract, signed not before it was sent nor after
     * today - checked before the signed file is, so a refusal costs no upload.
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID}; {@code VALIDATION_FAILED} for the day
     */
    public void requireSignable(LocalDate on, Instant at) {
        requireSignable();
        requireSignedDay(on, at);
    }

    private void requireSignedDay(LocalDate on, Instant at) {
        Objects.requireNonNull(on, "on");
        LocalDate today = LocalDate.ofInstant(at, VIETNAM);
        if (on.isAfter(today) || on.isBefore(LocalDate.ofInstant(sentAt, VIETNAM))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("Contract %s: signed on %s - not before it was "
                    + "sent (%s), not after today").formatted(contractNumber, on, LocalDate.ofInstant(sentAt, VIETNAM)))
                    .localized("refusal.contract.signedOn", contractNumber, DAY.format(on),
                            DAY.format(LocalDate.ofInstant(sentAt, VIETNAM)));
        }
    }

    private void requireStatus(SalesContractStatus wanted, String verb) {
        if (status != wanted) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, "Contract %s is %s: only a %s contract "
                    .formatted(contractNumber, status, wanted.name().toLowerCase(java.util.Locale.ROOT)) + "is " + verb)
                    .localized("refusal.contract.status." + verb, contractNumber, status);
        }
    }

    private void requireOrder(Order order) {
        if (!order.id().value().equals(orderId)) {
            throw new IllegalArgumentException("Contract " + contractNumber + " is not for order " + order.orderNumber());
        }
    }

    private static void requireDraftOrder(Order order) {
        if (order.status() != com.stockflow.order.api.OrderStatus.DRAFT) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_ORDER_NOT_DRAFT, ("Order %s is %s: a contract is "
                    + "drawn up, edited and sent for a draft order").formatted(order.orderNumber(), order.status()))
                    .localized("refusal.contract.orderNotDraft", order.orderNumber().value(), order.status());
        }
    }

    private static void requireRounds(int rounds) {
        if (rounds < 0 || rounds > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Free sample rounds: a whole number, 0 to 100")
                    .localized("refusal.contract.freeSampleRounds");
        }
    }

    public UUID id() { return id; }
    public String contractNumber() { return contractNumber; }
    public UUID orderId() { return orderId; }
    public SalesContractStatus status() { return status; }
    public ContractParty seller() { return seller; }
    public ContractParty buyer() { return buyer; }
    public int freeSampleRounds() { return freeSampleRounds; }
    /** The complaint period agreed, in days from each delivery; null: the configured default. */
    public Integer returnWindowDays() { return returnWindowDays; }
    public List<DeliveryBatch> schedule() { return schedule; }
    public List<PlannedInstallment> plan() { return plan; }
    public Instant sentAt() { return sentAt; }
    public UUID sentBy() { return sentBy; }
    /** 14 BR-26: the customer's Sales owner when the contract was drawn up; null when it had none. */
    public UUID salesOwnerId() { return salesOwnerId; }
    public String sentDocumentKey() { return sentDocumentKey; }
    public LocalDate signedOn() { return signedOn; }
    public String signedFileKey() { return signedFileKey; }
    public String signedFileName() { return signedFileName; }
    public Instant signedAt() { return signedAt; }
    public UUID signedBy() { return signedBy; }
    public Instant cancelledAt() { return cancelledAt; }
    public UUID cancelledBy() { return cancelledBy; }
    public String cancellationReason() { return cancellationReason; }
    public Instant completedAt() { return completedAt; }
    public Instant liquidatedAt() { return liquidatedAt; }

    /** 15 BR-11: whether the draft waits for accounting to approve the customer's credit. */
    public boolean creditReviewPending() { return creditReviewPending; }

    /** Rehydration of whether it waits for accounting (15 BR-11). */
    public SalesContract restoreCreditReview(boolean pending) {
        this.creditReviewPending = pending;
        return this;
    }

    /** Rehydration of when it was liquidated (WF-05 Q-6). */
    public SalesContract restoreLiquidated(Instant at) {
        this.liquidatedAt = at;
        return this;
    }

    /** Rehydration: the Sales owner recorded when it was drawn up (14 BR-26). */
    public SalesContract restoreSalesOwner(UUID ownerId) {
        this.salesOwnerId = ownerId;
        return this;
    }

    /**
     * WF-05 Q-6 (kltn-docs 14 §5.2): the order it binds ended with nothing delivered - every shipment came back from
     * the carrier or was lost, or it was cancelled after signing - and its money is settled: SIGNED → LIQUIDATED, once.
     * Never COMPLETED: that says the goods were delivered. The caller decides that nothing was; the database checks it
     * again ({@code check_sales_contract_transition}).
     *
     * @return whether this changed the contract (false when it was liquidated, or completed, already)
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID} for a contract not signed
     */
    public boolean liquidate(Instant at) {
        if (status == SalesContractStatus.LIQUIDATED || status == SalesContractStatus.COMPLETED) {
            return false;
        }
        if (!status.canTransitionTo(SalesContractStatus.LIQUIDATED)) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, "Contract %s is %s: only a signed "
                    .formatted(contractNumber, status) + "contract is liquidated");
        }
        this.status = SalesContractStatus.LIQUIDATED;
        this.liquidatedAt = Objects.requireNonNull(at, "at");
        return true;
    }
    public long version() { return version; }

    public ContractAgreement agreement() { return agreement; }
    /** QĐ-4: whether it deviated from the standard terms when last edited; such a contract is sent once approved. */
    public boolean nonStandard() { return nonStandard; }
    public int revision() { return revision; }
    public LocalDate signBy() { return signBy; }
    public Instant customerSignedAt() { return customerSignedAt; }
    public TermsReview termsReview() { return termsReview; }
    public String termsReviewNote() { return termsReviewNote; }
    public UUID termsReviewRequestedBy() { return termsReviewRequestedBy; }
    public Instant termsReviewRequestedAt() { return termsReviewRequestedAt; }
    public UUID termsReviewedBy() { return termsReviewedBy; }
    public Instant termsReviewedAt() { return termsReviewedAt; }
    public UUID drawnUpBy() { return drawnUpBy; }

    /** Rehydration of what the contract says beyond the parties and the plan. */
    public SalesContract restoreAgreement(ContractAgreement agreement, boolean nonStandard) {
        this.agreement = agreement;
        this.nonStandard = nonStandard;
        return this;
    }

    /** Rehydration of the revision, the signing deadline, the customer's signature day and who drew it up. */
    public SalesContract restoreSigning(int revision, LocalDate signBy, Instant customerSignedAt, UUID drawnUpBy) {
        this.revision = revision;
        this.signBy = signBy;
        this.customerSignedAt = customerSignedAt;
        this.drawnUpBy = drawnUpBy;
        return this;
    }

    /** Rehydration of the terms review. */
    public SalesContract restoreTermsReview(TermsReview state, String note, UUID requestedBy, Instant requestedAt,
                                            UUID reviewedBy, Instant reviewedAt) {
        this.termsReview = state;
        this.termsReviewNote = note;
        this.termsReviewRequestedBy = requestedBy;
        this.termsReviewRequestedAt = requestedAt;
        this.termsReviewedBy = reviewedBy;
        this.termsReviewedAt = reviewedAt;
        return this;
    }
}
