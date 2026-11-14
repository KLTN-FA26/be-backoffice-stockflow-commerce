package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.contracts.ReturnedGoodsReceived;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnChannel;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnLine;
import com.stockflow.order.internal.domain.ReturnPolicy;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.payment.api.OrderSettlementResult;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.SettleReturnCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Return requests (RMA, "đổi trả"; kltn-docs 17 §4.10, BR-18; team plan 4D-3): the transaction boundary of every step,
 * and the one place the return, its order and its money are moved together.
 *
 * <p><b>Locks.</b> Every step takes the order's row lock first, then the return's - the order payment and the nightly
 * completion take the order in - so a step and the nightly completion, a step and a refund record, two steps of one
 * return, or two returns of one order are decided one after the other, each on what the other committed. A screen
 * sending the version it showed is refused with {@code OPTIMISTIC_LOCK} when the return moved meanwhile.</p>
 *
 * <p><b>The goods come back through a return receipt</b> (03 §4.2): the warehouse scans the cartons by LPN into
 * quarantine on a goods receipt of type RETURN ({@code procurement}), and its confirmation, announced as
 * {@link ReturnedGoodsReceived}, is what tells the return its goods are back ({@link #goodsReceived}). There is no QC
 * of returned goods: a warehouse manager decides on each carton in quarantine (20 §4.8).</p>
 *
 * <p><b>The order follows its returns</b> (17 §5.1; BR-ORD-005). Opening one takes a DELIVERED order to
 * RETURN_REQUESTED, which keeps it from completing; goods back take it to RETURNED; once no return of it is open and
 * everything its settlements made refundable was refunded - the accountant records each refund (15 §4.5 step 16) -
 * it moves on to REFUNDED or PARTIALLY_REFUNDED. A return that ends before its goods came back takes the order back
 * to DELIVERED.</p>
 *
 * <p><b>The money</b>: Sales propose the refund, a second person approves it, and payment settles it against the
 * order's installments in the same transaction ({@link PaymentService#settleReturn}): the goods' value comes off what
 * the installments still owe, what was paid is to be refunded. Nothing is replaced and nothing kept as credit (17 §4.10
 * step 41: an exchange is a new quote; 15 BR-05). Each step is announced with a {@code contracts.Return*} event after
 * commit.</p>
 */
@Service
@Transactional
public class ReturnRequestService {

    private static final Logger log = LoggerFactory.getLogger(ReturnRequestService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Open a return on {@code orderId}: the lines and units claimed, why, and what the customer would like.
     * {@code lateClaimReason}: Sales accept a claim outside the return window, and say why (back office only).
     */
    public record OpenReturnCommand(UUID requestId, UUID orderId, ReturnReason reason, String description,
                                    ReturnResolution requestedResolution, List<ReturnRequest.Claim> lines,
                                    String lateClaimReason) {

        /** Within the return window. */
        public OpenReturnCommand(UUID requestId, UUID orderId, ReturnReason reason, String description,
                                 ReturnResolution requestedResolution, List<ReturnRequest.Claim> lines) {
            this(requestId, orderId, reason, description, requestedResolution, lines, null);
        }
    }

    /** Sales' review: units approved per return line (none named: all); the goods always come back. */
    public record ApproveReturnCommand(Map<UUID, Integer> approved, Boolean returnRequired, String note, Long version) {
    }

    /** The refund Sales propose: units per return line (none named: every unit approved and back). */
    public record ProposeRefundCommand(Map<UUID, Integer> units, String note, Long version) {
    }

    private final ReturnRequestRepository returns;
    private final OrderRepository orders;
    private final OrderEventPublisher events;
    private final PaymentService payments;
    private final CustomerService customers;
    private final FileTransfers files;
    private final TransactionOperations tx;
    private final StagedUploads staged;
    private final ReturnPolicy policy;
    private final Clock clock;
    private final DeliveryFailureService deliveryFailures;

    ReturnRequestService(ReturnRequestRepository returns, OrderRepository orders, OrderEventPublisher events,
                         PaymentService payments, CustomerService customers, FileTransfers files,
                         StagedUploads staged, TransactionOperations tx, ReturnPolicy policy, Clock clock,
                         DeliveryFailureService deliveryFailures) {
        this.deliveryFailures = deliveryFailures;
        this.returns = returns;
        this.orders = orders;
        this.events = events;
        this.payments = payments;
        this.customers = customers;
        this.files = files;
        this.staged = staged;
        this.tx = tx;
        this.policy = policy;
        this.clock = clock;
    }

    // ================================================================================================= opening

    /**
     * The customer opens a return on the portal: only on their own order (another customer's, or none, is 404 either
     * way) and only while their account is ACTIVE. Idempotent on {@code requestId}: the same request again returns the
     * return it opened.
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "return-request", resourceId = "#result?.id()")
    public ReturnRequest openOwn(UUID ownCustomerId, OpenReturnCommand command, UUID userId) {
        Objects.requireNonNull(ownCustomerId, "ownCustomerId");
        return open(command, ReturnChannel.PORTAL, userId, ownCustomerId);
    }

    /**
     * Sales open a return for any customer (a phone call, a visit), a blocked customer's included - also outside the
     * return window when they say why, and on an order a return already refunded. Someone else approves it.
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "return-request", resourceId = "#result?.id()")
    public ReturnRequest openForCustomer(OpenReturnCommand command, UUID staffId) {
        return open(command, ReturnChannel.BACK_OFFICE, staffId, null);
    }

    private ReturnRequest open(OpenReturnCommand command, ReturnChannel channel, UUID by, UUID ownCustomerId) {
        Objects.requireNonNull(command, "command");
        if (command.requestId() == null || command.orderId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A return names its request id and its order");
        }
        // The order first, locked: a retry and its original, two returns of one order, a return and the nightly
        // completion are decided one after the other.
        Order order = orders.findByIdForUpdate(new OrderId(command.orderId()))
                .filter(found -> ownCustomerId == null || ownCustomerId.equals(found.customerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + command.orderId()));
        Optional<ReturnRequest> replay = returns.findByRequestId(command.requestId());
        if (replay.isPresent()) {
            if (!replay.get().orderId().equals(order.id().value())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Request %s already opened a return on another order".formatted(command.requestId()));
            }
            log.info("Request {} already opened return {}", command.requestId(), replay.get().rmaNumber());
            return replay.get();
        }
        if (channel == ReturnChannel.PORTAL) {
            String status = customers.findById(order.customerId()).map(CustomerSummary::status).orElse(null);
            if (!"ACTIVE".equals(status)) {
                throw new BusinessException(ErrorCode.RETURN_CUSTOMER_NOT_ACTIVE,
                        "This account is %s: ask Sales to open the return".formatted(status));
            }
        }
        Map<UUID, Integer> claimed = new HashMap<>();
        Set<UUID> open = new HashSet<>();
        for (ReturnRequest other : returns.findByOrder(order.id().value())) {
            for (ReturnLine line : other.lines()) {
                claimed.merge(line.orderLineId(), line.claimedUnits(other.status()), Integer::sum);
                if (other.status().isOpen()) {
                    open.add(line.orderLineId());
                }
            }
        }
        Instant now = now();
        ReturnRequest request = ReturnRequest.open(returns.nextRmaNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)),
                command.requestId(), order, channel, command.reason(), command.description(),
                command.requestedResolution(), command.lines(), claimed, open, by, now, policy,
                channel == ReturnChannel.BACK_OFFICE ? command.lateClaimReason() : null);
        if (order.requestReturn(by, now, "RETURN_REQUESTED " + request.rmaNumber())) {
            orders.save(order);
            events.publishEventsOf(order);
        }
        ReturnRequest saved = returns.save(request);
        events.publishEventsOf(request);
        log.info("Return {} opened on order {} ({}, {} line(s))", saved.rmaNumber(), order.orderNumber(),
                command.reason(), saved.lines().size());
        return saved;
    }

    // ================================================================================================= evidence

    /**
     * Keep a photo of the problem with the return (stage CLAIM): the customer's on their own return only, or Sales'
     * for them. The file's declared type must match its bytes (an image, never a document or a script) and it is
     * scanned like every user upload before it is stored. Photos of the goods as they came back are the return
     * receipt's (team plan 4D-2); there is no inspection to photograph any more.
     *
     * <p>Not transactional itself: checking, scanning and storing a 15 MB photo takes seconds, and the return used to
     * stay locked for all of them. The return is read without a lock first, so a closed stage or a full one is refused
     * before anything is uploaded; the photo is prepared with no transaction ({@link FileTransfers#prepare}); then a
     * short transaction locks the return, checks again what may have changed meanwhile, and keeps the photo - or leaves
     * it to be deleted.</p>
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "return-request", resourceId = "#returnId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReturnRequest addEvidence(UUID returnId, UUID ownCustomerId, ReturnEvidence.Stage stage, FileUpload upload,
                                     UUID by) {
        ReturnEvidence.Stage taken = stageOf(stage);
        tx.executeWithoutResult(status -> evidenceTarget(returnId, ownCustomerId, taken, false));
        try (PreparedFiles prepared = files.prepare(FileCategory.RETURN_EVIDENCE, upload)) {
            return keepEvidence(returnId, ownCustomerId, taken, prepared, by);
        }
    }

    /**
     * A slot to upload a photo straight to the object store ({@link StagedUploads}), for a return that takes one now -
     * the same checks as the upload itself, so nobody uploads a photo that could not be kept.
     */
    @Transactional(readOnly = true)
    public UploadSlot beginEvidenceUpload(UUID returnId, UUID ownCustomerId, ReturnEvidence.Stage stage,
                                          UploadSlotRequest request, UUID by) {
        evidenceTarget(returnId, ownCustomerId, stageOf(stage), false);
        return staged.begin(FileCategory.RETURN_EVIDENCE, by, request);
    }

    /**
     * Keeps a photo the caller uploaded through {@link #beginEvidenceUpload}. Not transactional itself: the upload is
     * checked, scanned and moved into place first. The same upload sent again answers with the return it was kept on.
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "return-request", resourceId = "#returnId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReturnRequest addUploadedEvidence(UUID returnId, UUID ownCustomerId, ReturnEvidence.Stage stage,
                                             UUID uploadId, UUID by) {
        if (uploadId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "uploadId is required");
        }
        ReturnEvidence.Stage taken = stageOf(stage);
        // The same upload again - also after a commit whose outcome the first request never learned - answers with the
        // return it was kept on: the key is the upload's own, so a photo naming it is that upload.
        Optional<String> key = staged.keyOf(FileCategory.RETURN_EVIDENCE, by, uploadId);
        Optional<ReturnRequest> earlier = key.flatMap(k -> tx.execute(status -> returns.findById(returnId)
                .filter(found -> ownCustomerId == null || found.belongsTo(ownCustomerId))
                .filter(found -> found.evidence().stream().anyMatch(photo -> photo.fileKey().equals(k)))));
        if (earlier.isPresent()) {
            return earlier.get();
        }
        tx.executeWithoutResult(status -> evidenceTarget(returnId, ownCustomerId, taken, false));
        try (PreparedFiles prepared = staged.accept(FileCategory.RETURN_EVIDENCE, by, List.of(uploadId))) {
            return keepEvidence(returnId, ownCustomerId, taken, prepared, by);
        }
    }

    /** Null reads as the claim's: the only stage a return still takes. The aggregate refuses the others by name. */
    private static ReturnEvidence.Stage stageOf(ReturnEvidence.Stage stage) {
        return stage == null ? ReturnEvidence.Stage.CLAIM : stage;
    }

    /** The short transaction: lock the return, check again, keep the prepared photo. */
    private ReturnRequest keepEvidence(UUID returnId, UUID ownCustomerId, ReturnEvidence.Stage taken,
                                       PreparedFiles prepared, UUID by) {
        StoredFile file = prepared.single().file();
        return tx.execute(status -> {
            ReturnRequest request = evidenceTarget(returnId, ownCustomerId, taken, true);
            request.addEvidence(new ReturnEvidence(Identifiers.newId(), taken, file.key(), file.originalName(),
                    file.contentType(), file.sizeBytes(), by, now()), policy);
            ReturnRequest saved = returns.save(request);
            prepared.adopt();
            return saved;
        });
    }

    /** The return a photo of {@code stage} goes to, if it takes one now; locked when {@code forUpdate}. */
    private ReturnRequest evidenceTarget(UUID returnId, UUID ownCustomerId, ReturnEvidence.Stage taken,
                                         boolean forUpdate) {
        ReturnRequest request = (forUpdate ? returns.findByIdForUpdate(returnId) : returns.findById(returnId))
                .filter(found -> ownCustomerId == null || found.belongsTo(ownCustomerId))
                .orElseThrow(() -> notFound(returnId));
        request.requireEvidenceAllowed(taken, policy);
        return request;
    }

    // ================================================================================================= withdrawal

    /**
     * The customer (their own return) or Sales withdraw a return before its goods are back: REQUESTED or
     * AWAITING_RETURN → CANCELLED; the order goes back to DELIVERED if no other return of it is open.
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest cancel(UUID returnId, UUID ownCustomerId, String reason, UUID by) {
        Locked locked = lock(returnId, ownCustomerId, null);
        locked.request().cancel(by, now(), reason, ownCustomerId != null);
        return finish(locked, by);
    }

    // ================================================================================================= review

    /** Sales approve - in full, or fewer units per line (17 §4.10 step 38: REQUESTED → AWAITING_RETURN, the RMA). */
    @Auditable(action = AuditAction.APPROVE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest approve(UUID returnId, ApproveReturnCommand command, UUID by) {
        Locked locked = lock(returnId, null, command.version());
        locked.request().approve(by, now(), command.approved(), command.returnRequired(), command.note(), policy);
        return finish(locked, by);
    }

    /** Sales refuse the return, saying why (17 §4.10 step 38: REQUESTED → REJECTED; the order back to DELIVERED). */
    @Auditable(action = AuditAction.REJECT, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest reject(UUID returnId, String reason, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        locked.request().reject(by, now(), reason);
        return finish(locked, by);
    }

    // ================================================================================================= goods back

    /**
     * The goods of a return came back on a confirmed return receipt (17 §4.10 step 39; 03 §4.2 step 12): the return is
     * RECEIVED with the units of each line's SKU among the cartons, and its order RETURNED. Called by
     * {@link ReturnedGoodsListener} after the receipt committed, in a transaction of its own; a receipt seen again
     * changes nothing.
     *
     * <p>A receipt against a shipment the carrier brought back (RTO) names no return. It used to be dropped here - the
     * order was RETURNED by the shipment's outcome and nothing followed, so no money was ever settled and the order
     * stayed RETURNED (WF-05 audit, DS-05b). It now opens the shipment's delivery failure case
     * ({@link DeliveryFailureService#goodsReceived}), for Sales to deliver the goods again or cancel what was not
     * delivered.</p>
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} when the return takes no goods now - the event stays
     *         in the publication registry and is retried, so a defect here is seen rather than lost
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "return-request",
            resourceId = "#event.returnRequestId()", details = "{receipt: #event.receiptNumber()}")
    public void goodsReceived(ReturnedGoodsReceived event) {
        if (event.returnRequestId() == null) {
            deliveryFailures.goodsReceived(event);
            return;
        }
        Locked locked = lock(event.returnRequestId(), null, null);
        ReturnRequest request = locked.request();
        if (!request.orderId().equals(event.orderId())) {
            throw new IllegalStateException("Return receipt %s names order %s, not %s's order %s".formatted(
                    event.receiptNumber(), event.orderId(), request.rmaNumber(), request.orderId()));
        }
        boolean taken = request.goodsReceived(event.receiptId(), event.receiptNumber(), event.locationCode(),
                event.cartons().stream().map(c -> new ReturnRequest.ReturnedCarton(c.sku(), c.quantity())).toList(),
                event.receivedBy(), event.receivedAt().truncatedTo(ChronoUnit.MICROS));
        if (!taken) {
            log.info("Return {}: receipt {} was taken already", request.rmaNumber(), event.receiptNumber());
            return;
        }
        finish(locked, event.receivedBy());
        log.info("Return {}: goods back on receipt {} at {}", request.rmaNumber(), event.receiptNumber(),
                event.locationCode());
    }

    // ================================================================================================= refund

    /**
     * Sales propose the refund of the goods that came back (17 §4.10 step 40; RECEIVED → SETTLEMENT_PENDING): the units
     * per line, worth what the order line charged for them with VAT. A second person approves it.
     *
     * <p>Refused here already, not only at approval, for an order not paid through a contract's installments (review
     * M-4): there is nothing for the refund to be settled against, and a proposal nobody can approve would only wait.</p>
     *
     * @throws BusinessException {@code RETURN_REFUND_NO_INSTALLMENTS}, as {@link #approveSettlement} does
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest proposeRefund(UUID returnId, ProposeRefundCommand command, UUID by) {
        Locked locked = lock(returnId, null, command.version());
        if (!locked.order().paidByContract()) {
            throw new BusinessException(ErrorCode.RETURN_REFUND_NO_INSTALLMENTS, ("Order %s is not paid through a "
                    + "contract's installments: refund return %s outside the system").formatted(
                    locked.request().orderNumber(), locked.request().rmaNumber()));
        }
        locked.request().proposeRefund(by, now(), command.units(), locked.order(), command.note());
        return finish(locked, by);
    }

    /**
     * The second person approves the refund (four eyes): payment takes the goods' value off what the order's
     * installments still owe and makes the rest refundable (15 §4.5 step 15), the return is RESOLVED - all in this
     * transaction, or none of it. The accountant then records the transfer (15 §4.5 step 16); the order is REFUNDED
     * once everything to refund is.
     *
     * @throws BusinessException {@code RETURN_REFUND_NO_INSTALLMENTS} for an order not paid through a contract's
     *         installments: there is nothing for payment to settle against
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest approveSettlement(UUID returnId, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        ReturnRequest request = locked.request();
        request.requireSettlementApprover(by);
        Instant now = now();
        OrderSettlementResult settled = payments.settleReturn(new SettleReturnCommand(request.orderId(), request.id(),
                request.settlementAmount(), by, now)).orElseThrow(() -> new BusinessException(
                ErrorCode.RETURN_REFUND_NO_INSTALLMENTS, ("Order %s is not paid through a contract's installments: "
                        + "refund return %s outside the system").formatted(request.orderNumber(), request.rmaNumber())));
        request.resolve(by, now, new ReturnRequest.MoneyOutcome(settled.reduced(), settled.refundable()));
        return finish(locked, by);
    }

    /** The approver sends the refund back to Sales, saying why: SETTLEMENT_PENDING → RECEIVED. */
    @Auditable(action = AuditAction.REJECT, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest rejectSettlement(UUID returnId, String reason, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        locked.request().rejectSettlement(by, now(), reason);
        return finish(locked, by);
    }

    /**
     * The accountant recorded (or voided) a refund of the order (15 §4.5 step 16): a returned order refunded in full,
     * none of its returns still open, moves on to REFUNDED / PARTIALLY_REFUNDED. Called by {@link ReturnedGoodsListener}
     * after the record committed; the standing is read again here, under the order's lock.
     */
    public void followRefunds(UUID orderId, UUID by) {
        Optional<Order> found = orders.findByIdForUpdate(new OrderId(orderId));
        if (found.isEmpty() || found.get().status() != OrderStatus.RETURNED) {
            return;
        }
        Order order = found.get();
        if (followReturns(order, returns.findByOrder(orderId), by, now())) {
            orders.save(order);
            events.publishEventsOf(order);
        }
    }

    // ================================================================================================= expiry

    /** The returns whose goods should have arrived by now, for the sweep. */
    @Transactional(readOnly = true)
    public List<UUID> expiryCandidates(int limit) {
        return returns.awaitingReturnPast(clock.instant(), limit);
    }

    /**
     * The approved goods did not arrive by the deadline (AWAITING_RETURN → EXPIRED), each return in its own
     * transaction. Goods received meanwhile win: the return is no longer waiting, and nothing changes.
     *
     * @return whether it expired
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "return-request", resourceId = "#returnId")
    public boolean expire(UUID returnId) {
        Optional<UUID> orderId = returns.orderOf(returnId);
        if (orderId.isEmpty()) {
            return false;
        }
        Order order = orders.findByIdForUpdate(new OrderId(orderId.get())).orElseThrow();
        ReturnRequest request = returns.findByIdForUpdate(returnId).orElseThrow();
        if (!request.expire(now())) {
            return false;
        }
        finish(new Locked(order, request), null);
        log.info("Return {} expired: its goods did not arrive by {}", request.rmaNumber(), request.returnDeadline());
        return true;
    }

    // ================================================================================================= helpers

    /** A return and its order, both locked: the order first, as every money and stock path takes them. */
    private record Locked(Order order, ReturnRequest request) {
    }

    private Locked lock(UUID returnId, UUID ownCustomerId, Long expectedVersion) {
        UUID orderId = returns.orderOf(returnId).orElseThrow(() -> notFound(returnId));
        Order order = orders.findByIdForUpdate(new OrderId(orderId)).orElseThrow();
        ReturnRequest request = returns.findByIdForUpdate(returnId)
                .filter(found -> ownCustomerId == null || found.belongsTo(ownCustomerId))
                .orElseThrow(() -> notFound(returnId));
        if (expectedVersion != null && expectedVersion != request.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Return %s changed meanwhile (version %d, not %d): reload it".formatted(request.rmaNumber(),
                            request.version(), expectedVersion));
        }
        return new Locked(order, request);
    }

    /**
     * Moves the order after its returns, as they now stand (this one's state in memory, not yet saved), saves the
     * return, and announces both.
     */
    private ReturnRequest finish(Locked locked, UUID by) {
        ReturnRequest request = locked.request();
        Order order = locked.order();
        List<ReturnRequest> all = new ArrayList<>(returns.findByOrder(order.id().value()));
        all.removeIf(other -> other.id().equals(request.id()));
        all.add(request);
        boolean moved = followReturns(order, all, by, now());
        ReturnRequest saved = returns.save(request);
        if (moved) {
            orders.save(order);
            events.publishEventsOf(order);
        }
        events.publishEventsOf(request);
        return saved;
    }

    /**
     * The order's status after its returns (17 §5.1): RETURN_REQUESTED → RETURNED once goods of an open return are back,
     * or once every return ended and one had brought goods back; → DELIVERED when every return ended before its goods
     * came. A return that brought goods back to an order that had moved on (DELIVERED, COMPLETED, REFUNDED) opens the
     * claim on it again first. A RETURNED order with no return open moves on to REFUNDED (what came back covers the
     * goods it was worth) or PARTIALLY_REFUNDED once everything its settlements made refundable was refunded
     * ({@code RefundProgress.settled}) - right away when the goods' value only came off installments still owed.
     *
     * @return whether the order changed
     */
    private boolean followReturns(Order order, List<ReturnRequest> all, UUID by, Instant now) {
        boolean open = all.stream().anyMatch(r -> r.status().isOpen());
        boolean backNow = all.stream().anyMatch(r -> r.status().isOpen() && r.status().goodsBack());
        boolean everBack = all.stream().anyMatch(r -> r.status().goodsBack());
        boolean moved = false;
        if (backNow && order.status() != OrderStatus.RETURNED && order.status() != OrderStatus.RETURN_REQUESTED) {
            moved = order.requestReturn(by, now, "RETURN_GOODS_ARRIVED");
        }
        if (order.status() == OrderStatus.RETURN_REQUESTED) {
            if (backNow || !open && everBack) {
                moved |= order.goodsReturned(by, now, "RETURN_GOODS_BACK");
            } else if (!open) {
                moved |= order.withdrawReturn(by, now, "RETURN_ENDED");
            }
        }
        if (order.status() == OrderStatus.RETURNED && !open
                && all.stream().anyMatch(r -> r.status() == ReturnStatus.RESOLVED)
                && payments.refundProgress(order.id().value()).settled()) {
            BigDecimal returned = all.stream().filter(r -> r.status() == ReturnStatus.RESOLVED)
                    .map(ReturnRequest::settlementAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            moved |= order.refunded(returned.compareTo(goodsWorth(order)) >= 0, by, now, "REFUNDED");
        }
        return moved;
    }

    /** What the order's goods are worth with their VAT, the units closed left out and the fee lines not counted. */
    private static BigDecimal goodsWorth(Order order) {
        return order.lines().stream().map(l -> l.grossOf(l.quantity() - l.cancelledQuantity()).amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Microseconds, as the database keeps them. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static BusinessException notFound(UUID returnId) {
        return new BusinessException(ErrorCode.RETURN_REQUEST_NOT_FOUND, "No return request " + returnId);
    }
}
