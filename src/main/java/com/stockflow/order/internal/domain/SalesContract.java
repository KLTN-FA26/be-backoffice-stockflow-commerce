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
        requireSendable(order, at);
        markSent(at, by, documentKey);
    }

    private void markSent(Instant at, UUID by, String documentKey) {
        this.status = SalesContractStatus.SENT;
        this.sentAt = Objects.requireNonNull(at, "at");
        this.sentBy = Objects.requireNonNull(by, "by");
        this.sentDocumentKey = Objects.requireNonNull(documentKey, "documentKey");
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
        requireCreditApprovable(order, at);
        creditReviewPending = false;
        markSent(at, sentBy, documentKey);
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
        this.status = SalesContractStatus.SIGNED;
        this.signedOn = on;
        this.signedFileKey = Objects.requireNonNull(fileKey, "fileKey");
        this.signedFileName = Objects.requireNonNull(fileName, "fileName");
        this.signedAt = Objects.requireNonNull(at, "at");
        this.signedBy = Objects.requireNonNull(by, "by");
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
                    .formatted(contractNumber, status) + "sent contract is cancelled")
                    .localized("refusal.contract.status.cancelled", contractNumber, status);
        }
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A reason of at most 500 characters")
                    .localized("refusal.contract.cancellationReason", 500);
        }
        this.status = SalesContractStatus.CANCELLED;
        // Cancelled while it waited, it waits no more: the adapter closes the review WITHDRAWN with this reason.
        this.creditReviewPending = false;
        this.cancellationReason = reason.strip();
        this.cancelledAt = Objects.requireNonNull(at, "at");
        this.cancelledBy = Objects.requireNonNull(by, "by");
    }

    /** Whether it may be signed: a sent contract. @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID} */
    public void requireSignable() {
        requireStatus(SalesContractStatus.SENT, "signed");
    }

    /**
     * Whether it may be recorded signed on {@code on}, now: a sent contract, signed not before it was sent nor after
     * today - checked before the signed file is, so a refusal costs no upload.
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID}; {@code VALIDATION_FAILED} for the day
     */
    public void requireSignable(LocalDate on, Instant at) {
        requireSignable();
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
}
