package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 */
public final class SalesContract {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final UUID id;
    private final String contractNumber;
    private final UUID orderId;
    private SalesContractStatus status;
    private final ContractParty seller;
    private final ContractParty buyer;
    private int freeSampleRounds;
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
        requireContractable(order);
        requireRounds(freeSampleRounds);
        return new SalesContract(id, contractNumber, order.id().value(), SalesContractStatus.DRAFT, seller, buyer,
                freeSampleRounds, schedule, plan, 0L);
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
                    order.orderNumber()));
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
        requireStatus(SalesContractStatus.DRAFT, "edited");
        requireOrder(order);
        requireDraftOrder(order);
        requireRounds(freeSampleRounds);
        this.freeSampleRounds = freeSampleRounds;
        this.schedule = List.copyOf(schedule);
        this.plan = List.copyOf(plan);
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
        requireOrder(order);
        requireDraftOrder(order);
        ContractTerms.requireWindows(schedule, LocalDate.ofInstant(at, VIETNAM));
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
     * Cancelled before it was signed, with a reason (14 §4.3 step 16, §5.2): the customer refused it, its content has
     * to change, or the order is cancelled. A signed contract is never cancelled here (BR-14).
     *
     * @throws BusinessException {@code SALES_CONTRACT_STATUS_INVALID}; {@code VALIDATION_FAILED} without a reason
     */
    public void cancel(String reason, Instant at, UUID by) {
        if (!status.canTransitionTo(SalesContractStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, "Contract %s is %s: only a draft or a "
                    .formatted(contractNumber, status) + "sent contract is cancelled");
        }
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A reason of at most 500 characters");
        }
        this.status = SalesContractStatus.CANCELLED;
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
                    + "sent (%s), not after today").formatted(contractNumber, on, LocalDate.ofInstant(sentAt, VIETNAM)));
        }
    }

    private void requireStatus(SalesContractStatus wanted, String verb) {
        if (status != wanted) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, "Contract %s is %s: only a %s contract "
                    .formatted(contractNumber, status, wanted.name().toLowerCase(java.util.Locale.ROOT)) + "is " + verb);
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
                    + "drawn up, edited and sent for a draft order").formatted(order.orderNumber(), order.status()));
        }
    }

    private static void requireRounds(int rounds) {
        if (rounds < 0 || rounds > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Free sample rounds: a whole number, 0 to 100");
        }
    }

    public UUID id() { return id; }
    public String contractNumber() { return contractNumber; }
    public UUID orderId() { return orderId; }
    public SalesContractStatus status() { return status; }
    public ContractParty seller() { return seller; }
    public ContractParty buyer() { return buyer; }
    public int freeSampleRounds() { return freeSampleRounds; }
    public List<DeliveryBatch> schedule() { return schedule; }
    public List<PlannedInstallment> plan() { return plan; }
    public Instant sentAt() { return sentAt; }
    public UUID sentBy() { return sentBy; }
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
    public long version() { return version; }
}
