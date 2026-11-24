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
import com.stockflow.inventory.api.CartonReturns;
import com.stockflow.inventory.api.CustodyInstruction;
import com.stockflow.order.api.DeliveryInvoices;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ReturnReceiptTracking;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.OrderShipment;
import com.stockflow.order.internal.domain.ReturnChannel;
import com.stockflow.order.internal.domain.ReturnClaims;
import com.stockflow.order.internal.domain.ReturnCustodyChoice;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnPolicy;
import com.stockflow.order.internal.domain.ReturnLine;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRefusalReason;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p><b>The goods come back through return receipts</b> (03 §4.2): the warehouse scans the cartons by LPN into
 * quarantine on a goods receipt of type RETURN ({@code procurement}), and its confirmation, announced as
 * {@link ReturnedGoodsReceived}, is what tells the return its goods are back ({@link #goodsReceived}). A customer may
 * send them in several parcels, each on a receipt of its own (WF-05 TH-3): the return waits for the rest until
 * everything approved is back, Sales mark the goods complete ({@link #markGoodsComplete}), or its deadline passes
 * ({@link #expire}). There is no QC of returned goods: a warehouse manager decides on each carton in quarantine
 * (20 §4.8).</p>
 *
 * <p><b>The order follows its returns</b> (17 §5.1; BR-ORD-005). Opening one takes a DELIVERED order to
 * RETURN_REQUESTED, which keeps it from completing; goods back take it to RETURNED; once no return of it is open and
 * everything its settlements made refundable was refunded - the accountant records each refund (15 §4.5 step 16) -
 * it moves on to REFUNDED or PARTIALLY_REFUNDED. A return that ends before its goods came back takes the order back
 * to DELIVERED.</p>
 *
 * <p><b>Only once the order is DELIVERED</b> (WF-05 TH-1). A return is against one delivered shipment, and may be
 * opened while later batches of the order are still to come - the order PARTIALLY_FULFILLED, SHIPPED, ON_HOLD... Its
 * status is then its deliveries': releasing, picking and handing over the next batch need it, so a return moves none
 * of it, and its money and invoice are settled per return as always. When the last batch is delivered and the order
 * becomes DELIVERED, {@link #followDelivery} looks at its returns as they stand: one still open takes it to
 * RETURN_REQUESTED (or RETURNED, its goods back) as if it had been opened then; returns that ended before leave it
 * DELIVERED, their refunds already tracked by payment.</p>
 *
 * <p><b>The money</b>: Sales propose the refund, a second person approves it, and payment settles it against the
 * order's installments in the same transaction ({@link PaymentService#settleReturn}): the goods' value comes off what
 * the installments still owe, what was paid is to be refunded. An order with no installments had nothing paid in the
 * system, and its refund is worth 0 (WF-05 TH-2). Nothing is replaced and nothing kept as credit (17 §4.10 step 41: an
 * exchange is a new quote; 15 BR-05). Each step is announced with a {@code contracts.Return*} event after commit.</p>
 *
 * <p><b>A refund may be refused</b> (WF-05 Q-4): goods that came back usable are not refunded - damaged by the customer,
 * no defect found, outside the policy - when Sales propose nothing for them with a reason from a fixed list, and the
 * accountant approves it like any refund. The return ends RESOLVED with nothing settled, and its order goes back to
 * the delivery path rather than to PARTIALLY_REFUNDED (BE-45): nothing was refunded.</p>
 *
 * <p><b>A parcel being counted holds the return</b> (WF-05 BE-23): procurement tells the return when a draft receipt is
 * opened for it and when that draft is confirmed or cancelled ({@link ReturnReceiptTracking}, implemented here, in the
 * receipt's transaction); meanwhile the goods are not marked complete, the deadline does not end the return, and no
 * refund is proposed or approved. A confirmed draft's goods are taken in that same transaction (audit round 2, M1).</p>
 *
 * <p><b>Whose goods</b> (WF-05 Q-4): the refund decision settles the cartons that came back sealed, in the transaction
 * that resolves the return ({@link CartonReturns#settleOwnership}), per SKU: the cartons the refund of their SKU takes
 * back are the company's; the others stay the customer's - their stock-in reversed, held for the customer - and Sales
 * record what the customer chose for them ({@link #recordCustodyChoice}). Goods no receipt takes are custody notes on
 * the return, each ended as the customer chose ({@link ReturnCustodyNotes}).</p>
 */
@Service
@Transactional
public class ReturnRequestService implements ReturnReceiptTracking {

    private static final Logger log = LoggerFactory.getLogger(ReturnRequestService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Open a return on {@code orderId}: the lines and units claimed, why, and what the customer would like.
     * {@code lateClaimReason}: Sales accept a claim outside the return window, and say why (back office only).
     * {@code shipmentId}: the delivered shipment the goods came on (WF-05 TH-1); null names the order's only delivered
     * one, and is what an order delivered without shipments takes.
     */
    public record OpenReturnCommand(UUID requestId, UUID orderId, ReturnReason reason, String description,
                                    ReturnResolution requestedResolution, List<ReturnRequest.Claim> lines,
                                    String lateClaimReason, UUID shipmentId) {

        /** Within the return window. */
        public OpenReturnCommand(UUID requestId, UUID orderId, ReturnReason reason, String description,
                                 ReturnResolution requestedResolution, List<ReturnRequest.Claim> lines) {
            this(requestId, orderId, reason, description, requestedResolution, lines, null, null);
        }

        /** Against the order's only delivered shipment. */
        public OpenReturnCommand(UUID requestId, UUID orderId, ReturnReason reason, String description,
                                 ReturnResolution requestedResolution, List<ReturnRequest.Claim> lines,
                                 String lateClaimReason) {
            this(requestId, orderId, reason, description, requestedResolution, lines, lateClaimReason, null);
        }
    }

    /** Sales' review: units approved per return line (none named: all); the goods always come back. */
    public record ApproveReturnCommand(Map<UUID, Integer> approved, Boolean returnRequired, String note, Long version) {
    }

    /**
     * The refund Sales propose: units per return line (none named: every unit approved and back), and why anything that
     * came back is withheld (WF-05 Q-4): {@code refusalReason} - required when every line is 0 while units came back
     * usable - and {@code refusalNote}, the note the customer reads beside it. {@code note} is internal.
     */
    public record ProposeRefundCommand(Map<UUID, Integer> units, String note, Long version,
                                       ReturnRefusalReason refusalReason, String refusalNote) {

        /** Nothing withheld for a reason. */
        public ProposeRefundCommand(Map<UUID, Integer> units, String note, Long version) {
            this(units, note, version, null, null);
        }
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
    private final ReturnAllowances allowances;
    private final CartonReturns cartonReturns;
    private final ReturnWarehouseScope warehouseScope;
    private final DeliveryInvoices deliveryInvoices;

    ReturnRequestService(ReturnRequestRepository returns, OrderRepository orders, OrderEventPublisher events,
                         PaymentService payments, CustomerService customers, FileTransfers files,
                         StagedUploads staged, TransactionOperations tx, ReturnPolicy policy, Clock clock,
                         DeliveryFailureService deliveryFailures, ReturnAllowances allowances,
                         CartonReturns cartonReturns, ReturnWarehouseScope warehouseScope,
                         DeliveryInvoices deliveryInvoices) {
        this.deliveryInvoices = deliveryInvoices;
        this.cartonReturns = cartonReturns;
        this.warehouseScope = warehouseScope;
        this.deliveryFailures = deliveryFailures;
        this.allowances = allowances;
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
        // WF-05 TH-1: the delivery the goods came on; what its other returns claim bounds this one.
        OrderShipment shipment = ReturnRequest.deliveredShipment(order, command.shipmentId());
        UUID shipmentId = shipment == null ? null : shipment.id();
        // Read as the customer's order detail reads it (ReturnAllowances), so what it showed returnable is what passes.
        ReturnClaims claims = allowances.claimsOf(order.id().value());
        Instant now = now();
        ReturnRequest request = ReturnRequest.open(returns.nextRmaNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)),
                command.requestId(), order, shipmentId, channel, command.reason(), command.description(),
                command.requestedResolution(), command.lines(), claims.inAll(), claims.onShipment(shipmentId),
                claims.openOn(shipmentId), claims.refundedShipments(), by, now,
                allowances.policyOf(order.id().value()),
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
        return addEvidence(returnId, ownCustomerId, stage, null, upload, by);
    }

    /**
     * As {@link #addEvidence(UUID, UUID, ReturnEvidence.Stage, FileUpload, UUID)}; a CUSTODY photo carries
     * {@code custody}, what it shows (WF-05 Q-11): goods that came back and that the return does not take, kept for
     * the customer - staff only.
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "return-request", resourceId = "#returnId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReturnRequest addEvidence(UUID returnId, UUID ownCustomerId, ReturnEvidence.Stage stage,
                                     ReturnEvidence.Custody custody, FileUpload upload, UUID by) {
        ReturnEvidence.Stage taken = stageOf(stage);
        requireCustodyFits(taken, custody, ownCustomerId);
        tx.executeWithoutResult(status -> evidenceTarget(returnId, ownCustomerId, taken, false));
        try (PreparedFiles prepared = files.prepare(FileCategory.RETURN_EVIDENCE, upload)) {
            return keepEvidence(returnId, ownCustomerId, taken, custody, prepared, by);
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
        return addUploadedEvidence(returnId, ownCustomerId, stage, null, uploadId, by);
    }

    /** As the overload without {@code custody}, for a CUSTODY photo too (WF-05 Q-11, staff only). */
    @Auditable(action = AuditAction.CREATE, resourceType = "return-request", resourceId = "#returnId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReturnRequest addUploadedEvidence(UUID returnId, UUID ownCustomerId, ReturnEvidence.Stage stage,
                                             ReturnEvidence.Custody custody, UUID uploadId, UUID by) {
        if (uploadId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "uploadId is required");
        }
        ReturnEvidence.Stage taken = stageOf(stage);
        requireCustodyFits(taken, custody, ownCustomerId);
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
            return keepEvidence(returnId, ownCustomerId, taken, custody, prepared, by);
        }
    }

    /**
     * A custody note says what it keeps, and only a custody note does; and it is staff's - the warehouse or Sales take
     * the goods in for the customer, the customer does not record it about themselves (WF-05 Q-11).
     */
    private static void requireCustodyFits(ReturnEvidence.Stage stage, ReturnEvidence.Custody custody,
                                           UUID ownCustomerId) {
        if (stage == ReturnEvidence.Stage.CUSTODY && ownCustomerId != null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Goods are taken into custody by staff, not on the portal");
        }
        if ((stage == ReturnEvidence.Stage.CUSTODY) != (custody != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, stage == ReturnEvidence.Stage.CUSTODY
                    ? "A custody note names the goods kept: their SKU or LPN and how many"
                    : "Only a custody note names goods kept for the customer");
        }
    }

    /** Null reads as the claim's: the only stage a return still takes. The aggregate refuses the others by name. */
    private static ReturnEvidence.Stage stageOf(ReturnEvidence.Stage stage) {
        return stage == null ? ReturnEvidence.Stage.CLAIM : stage;
    }

    /** The short transaction: lock the return, check again, keep the prepared photo. */
    private ReturnRequest keepEvidence(UUID returnId, UUID ownCustomerId, ReturnEvidence.Stage taken,
                                       ReturnEvidence.Custody custody, PreparedFiles prepared, UUID by) {
        StoredFile file = prepared.single().file();
        return tx.execute(status -> {
            ReturnRequest request = evidenceTarget(returnId, ownCustomerId, taken, true);
            request.addEvidence(new ReturnEvidence(Identifiers.newId(), taken, file.key(), file.originalName(),
                    file.contentType(), file.sizeBytes(), by, now(), custody), policy);
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
        // BR-SEC-002 (audit r3 RAD-2): the custody note is the warehouse's write since audit r2 M4, and a clerk of
        // another warehouse is refused it as they are refused the return's detail - before the photo is checked or
        // stored, and in the short transaction that keeps it.
        warehouseScope.require(request.orderId(), ownCustomerId);
        request.requireEvidenceAllowed(taken, policy);
        return request;
    }

    // ================================================================================================= withdrawal

    /**
     * The customer (their own return) or Sales withdraw a return before any of its goods is back: REQUESTED or
     * AWAITING_RETURN → CANCELLED; the order goes back to DELIVERED if no other return of it is open. Refused once a
     * parcel came back ({@code RETURN_GOODS_PARTLY_RECEIVED}): Sales then mark the goods complete
     * ({@link #markGoodsComplete}).
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
     * A parcel of a return's goods came back on a confirmed return receipt (17 §4.10 step 39; 03 §4.2 step 12): each
     * line adds the units of its SKU among the cartons; once everything approved is back the return is RECEIVED and its
     * order RETURNED (WF-05 TH-3: until then it awaits the rest). Called by {@link ReturnedGoodsListener} after the
     * receipt committed, in a transaction of its own; a receipt seen again changes nothing. Procurement calls it first,
     * in the receipt's own transaction ({@link ReturnReceiptTracking#goodsReceived}, audit round 2 M1), so for a return
     * request the listener finds the receipt taken already.
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
    @Override
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
        // WF-05 TH-4: what was counted inside each carton, not its label, and the units that came back loose.
        List<ReturnRequest.ReturnedCarton> back = new ArrayList<>();
        event.cartons().forEach(c -> back.add(new ReturnRequest.ReturnedCarton(c.sku(), c.unitsInside())));
        event.looseLines().forEach(l -> back.add(new ReturnRequest.ReturnedCarton(l.sku(), l.quantity())));
        boolean taken = request.goodsReceived(event.receiptId(), event.receiptNumber(), event.locationCode(), back,
                event.receivedBy(), event.receivedAt().truncatedTo(ChronoUnit.MICROS));
        if (!taken) {
            log.info("Return {}: receipt {} was taken already", request.rmaNumber(), event.receiptNumber());
            return;
        }
        finish(locked, event.receivedBy());
        log.info("Return {}: goods back on receipt {} at {}, now {}", request.rmaNumber(), event.receiptNumber(),
                event.locationCode(), request.status());
    }

    /**
     * Whether return {@code returnId} has taken the goods of receipt {@code receiptId} already: the receipt's own
     * transaction takes them (audit round 2, M1), so the event after commit has nothing left to do.
     */
    @Transactional(readOnly = true)
    public boolean tookReceipt(UUID returnId, UUID receiptId) {
        return returns.findById(returnId).map(found -> receiptId.equals(found.goodsReceiptId())
                || found.parcels().stream().anyMatch(parcel -> receiptId.equals(parcel.goodsReceiptId())))
                .orElse(false);
    }

    /**
     * Sales say the goods of a return are all here although fewer than approved came back (WF-05 TH-3): the customer
     * sent what they had, or another SKU by mistake. AWAITING_RETURN → RECEIVED with what the parcels brought, the order
     * RETURNED; the refund is decided on those goods, or the return closed without one.
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest markGoodsComplete(UUID returnId, String note, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        locked.request().markGoodsComplete(by, now(), note);
        return finish(locked, by);
    }

    // ================================================================================================= refund

    /**
     * Sales propose the refund of the goods that came back (17 §4.10 step 40; RECEIVED → SETTLEMENT_PENDING): the units
     * per line, worth what the order line charged for them with VAT. A second person approves it.
     *
     * <p>An order with no installments at all (WF-05 TH-2: a sample order, one of before contracts) had nothing paid in
     * the system - money is recorded only against installments (15 BR-01, BR-05) - so its refund is settled against
     * that: the units are refunded worth 0, and a second person still approves it. Refused before (review M-4,
     * {@code RETURN_REFUND_NO_INSTALLMENTS}), which left the return in RECEIVED and its order RETURNED for good.</p>
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest proposeRefund(UUID returnId, ProposeRefundCommand command, UUID by) {
        Locked locked = lock(returnId, null, command.version());
        boolean nothingPaid = !payments.installmentStanding(locked.order().id().value()).opened();
        locked.request().proposeRefund(by, now(), command.units(), locked.order(), command.note(), nothingPaid,
                command.refusalReason(), command.refusalNote(), refundedBefore(locked.request()),
                invoicedRates(locked.request()));
        return finish(locked, by);
    }

    /**
     * Per order line, the units the other RESOLVED returns of the order refunded: where this return's units start on
     * the line's cumulative scale (WF-05 BE-50). Read under the order's lock, so two approvals of one line take
     * consecutive ranges.
     */
    private Map<UUID, Integer> refundedBefore(ReturnRequest request) {
        Map<UUID, Integer> before = new HashMap<>();
        for (ReturnRequest other : returns.findByOrder(request.orderId())) {
            if (!other.id().equals(request.id()) && other.status() == ReturnStatus.RESOLVED) {
                for (ReturnLine line : other.lines()) {
                    before.merge(line.orderLineId(), line.refundQuantity(), Integer::sum);
                }
            }
        }
        return before;
    }

    /**
     * WF-05 O-1: per order line, the VAT rate the invoice of the return's shipment carries for its goods - what the
     * return's adjustment invoice will take them off at, so the refund is that adjustment to the dong. Asked of
     * invoicing ({@link DeliveryInvoices#invoicedRates}), which answers from the invoice it adjusts. A return that names
     * no shipment (one opened before returns were per delivery) keeps its lines' own rates.
     */
    private Map<UUID, java.math.BigDecimal> invoicedRates(ReturnRequest request) {
        return request.shipmentId() == null ? Map.of()
                : deliveryInvoices.invoicedRates(request.orderId(), request.shipmentId());
    }

    /**
     * The second person approves the refund (four eyes): payment takes the goods' value off what the order's
     * installments still owe and makes the rest refundable (15 §4.5 step 15), the return is RESOLVED - all in this
     * transaction, or none of it. The accountant then records the transfer (15 §4.5 step 16); the order is REFUNDED
     * once everything to refund is. A refund worth nothing - the order had nothing paid (WF-05 TH-2) - moves no money
     * and is resolved without payment.
     *
     * @throws BusinessException {@code RETURN_REFUND_NO_INSTALLMENTS} when a refund worth something finds the order
     *         without installments - they were there when it was proposed; a defect, never the normal answer
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest approveSettlement(UUID returnId, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        ReturnRequest request = locked.request();
        request.requireSettlementApprover(by);
        Instant now = now();
        // WF-05 BE-50: valued where its units sit now, after what the line's other returns settled meanwhile; O-1: at
        // the rate its shipment's invoice carries now.
        if (request.revalue(locked.order(), refundedBefore(request), invoicedRates(request))) {
            log.info("Return {}: refund valued again on its lines as they stand now, {}", request.rmaNumber(),
                    request.settlementAmount());
        }
        if (request.settlementAmount().signum() == 0) {
            request.resolve(by, now, ReturnRequest.MoneyOutcome.NONE);
            settleGoods(request, by, now);
            return finish(locked, by);
        }
        // WF-05 TH-1: the delivery batch the goods came on takes the refund first; TT-5: the batches still to come
        // keep what they owe for their own goods while paid money is left to hand back.
        UUID batch = locked.order().shipments().stream().filter(s -> s.id().equals(request.shipmentId()))
                .map(OrderShipment::deliveryBatchId).filter(Objects::nonNull).findFirst().orElse(null);
        OrderSettlementResult settled = payments.settleReturn(new SettleReturnCommand(request.orderId(), request.id(),
                request.settlementAmount(), by, now, batch, HandedOverBatches.of(locked.order()))).orElseThrow(() ->
                new BusinessException(ErrorCode.RETURN_REFUND_NO_INSTALLMENTS, ("Order %s is not paid through a "
                        + "contract's installments: refund return %s outside the system").formatted(
                        request.orderNumber(), request.rmaNumber())));
        request.resolve(by, now, new ReturnRequest.MoneyOutcome(settled.reduced(), settled.refundable()));
        settleGoods(request, by, now);
        return finish(locked, by);
    }

    /**
     * WF-05 Q-4: the return's refund is decided, and with it whose its goods are - in this transaction, so no carton of a
     * RESOLVED return waits ({@code tg_return_request_goods_settled}). Per SKU (audit round 3, RAD-1): the cartons the
     * refund of their SKU reaches are the company's, to release or write off; the others - every carton of a SKU
     * refunded 0, and whole cartons beyond the refunded units - stay the customer's: "không có thoả thuận trả hàng thì
     * hàng vẫn là của khách", their stock-in reversed and held for them. Before, one refunded unit anywhere on the
     * return made every carton of it the company's, a refused SKU's included, sold again with no refund and no way to
     * hand it back. Inventory takes the SKU locks after the order and the return this transaction holds, the global
     * order.
     */
    private void settleGoods(ReturnRequest request, UUID by, Instant now) {
        Map<String, int[]> bySku = new java.util.LinkedHashMap<>();
        for (ReturnLine line : request.lines()) {
            int[] units = bySku.computeIfAbsent(line.sku(), sku -> new int[2]);
            units[0] += line.receivedQuantity() == null ? 0 : line.receivedQuantity();
            units[1] += line.refundQuantity();
        }
        List<com.stockflow.inventory.api.RefundedSku> refunded = bySku.entrySet().stream()
                .map(e -> new com.stockflow.inventory.api.RefundedSku(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        int settled = cartonReturns.settleOwnership(request.id(), refunded, by, now);
        if (settled > 0) {
            log.info("Return {} {}: {} carton(s) settled per SKU {}", request.rmaNumber(), request.outcome(), settled,
                    refunded);
        }
    }

    /**
     * The goods came back with nothing usable in them - every carton counted empty, or nothing the return claims - so
     * nothing approved is refunded (WF-05 TH-4): RECEIVED → RESOLVED, no money, no adjustment invoice, by one person.
     * Without it such a return could never leave RECEIVED.
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest closeWithoutRefund(UUID returnId, String note, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        Instant now = now();
        locked.request().closeWithoutRefund(by, now, note);
        settleGoods(locked.request(), by, now);
        return finish(locked, by);
    }

    /**
     * Sales record what the customer chose for goods of the return its refund did not take back (WF-05 Q-4): handed back
     * at their cost, or destroyed on their behalf with their written consent. The cartons held for them take the
     * instruction in this transaction, and the warehouse manager carries it out from the custody list (TH-6). Any
     * RESOLVED return with a carton held for the customer takes it - one that refunded another SKU, or part of this one,
     * too (audit round 3, RAD-1); {@code tg_return_request_custody_held} checks the same at commit.
     *
     * @throws BusinessException as {@link ReturnRequest#recordCustodyChoice}; {@code RETURN_INVALID_TRANSITION} when
     *         no carton of the return is held for the customer (all were refunded, or nothing came back sealed -
     *         opened cartons and loose units are destroyed on the receipt, Q-18); as
     *         {@link CartonReturns#instructCustody} once it was carried out
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-request", resourceId = "#returnId",
            details = "{custodyChoice: #choice}")
    public ReturnRequest recordCustodyChoice(UUID returnId, ReturnCustodyChoice choice, String note, Long version,
                                             UUID by) {
        Locked locked = lock(returnId, null, version);
        ReturnRequest request = locked.request();
        request.recordCustodyChoice(choice, note, by, now());
        int held = cartonReturns.instructCustody(request.id(), CustodyInstruction.valueOf(choice.name()));
        if (held == 0) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION, ("Nothing of return %s is held for the "
                    + "customer: every carton of it that came back sealed was refunded, or none did")
                    .formatted(request.rmaNumber()));
        }
        log.info("Return {}: the customer chose {} for {} carton(s) held for them", request.rmaNumber(), choice, held);
        return returns.save(request);
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

    /**
     * A shipment of the order was delivered (WF-05 TH-1): once that made the order DELIVERED - its last batch arrived -
     * its returns are looked at as they stand, under the order's lock, exactly as when one of them moves. A return
     * opened while batches were still on their way and still open takes the order to RETURN_REQUESTED (RETURNED with
     * its goods back) now; returns that ended before leave it DELIVERED. Called by {@link OrderDeliveredReturnsListener}
     * after the delivery committed; seen again it changes nothing.
     */
    public void followDelivery(UUID orderId, UUID by) {
        Optional<Order> found = orders.findByIdForUpdate(new OrderId(orderId));
        if (found.isEmpty() || found.get().status() != OrderStatus.DELIVERED) {
            return;
        }
        Order order = found.get();
        List<ReturnRequest> all = returns.findByOrder(orderId);
        if (all.isEmpty()) {
            return;
        }
        Instant now = now();
        // An open return would have taken a DELIVERED order to RETURN_REQUESTED when it was opened; it does now.
        boolean moved = all.stream().anyMatch(r -> r.status().isOpen())
                && order.requestReturn(by, now, "RETURN_REQUESTED_ON_FULL_DELIVERY");
        moved |= followReturns(order, all, by, now);
        if (moved) {
            orders.save(order);
            events.publishEventsOf(order);
            log.info("Order {} delivered in full follows its returns: {}", order.orderNumber(), order.status());
        }
    }

    // ================================================================================================= receipts

    /**
     * {@inheritDoc}
     *
     * <p>Called by procurement inside the transaction that opens the draft; takes the order's lock, then the return's.</p>
     */
    @Override
    public void draftOpened(UUID returnRequestId, UUID receiptId) {
        Locked locked = lock(returnRequestId, null, null);
        // Audit round 2 M2, L-3: against the order and what its other returns claim, read under the order's lock.
        List<ReturnRequest> all = returns.findByOrder(locked.order().id().value());
        locked.request().draftReceiptOpened(receiptId, locked.order(), ReturnClaims.ofOthers(all, returnRequestId),
                all);
        returns.save(locked.request());
    }

    /** {@inheritDoc} */
    @Override
    public void draftClosed(UUID returnRequestId, UUID receiptId) {
        Locked locked = lock(returnRequestId, null, null);
        if (locked.request().draftReceiptClosed(receiptId)) {
            returns.save(locked.request());
        }
    }

    // ================================================================================================= expiry

    /** The returns whose goods should have arrived by now, for the sweep. */
    @Transactional(readOnly = true)
    public List<UUID> expiryCandidates(int limit) {
        return returns.awaitingReturnPast(clock.instant(), limit);
    }

    /**
     * The deadline for the approved goods passed, each return in its own transaction: none of them came back,
     * AWAITING_RETURN → EXPIRED; part of them did (WF-05 TH-3), → RECEIVED with what came. Goods received in full
     * meanwhile win: the return is no longer waiting, and nothing changes.
     *
     * @return whether it moved
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
        log.info("Return {}: its deadline {} passed, now {}", request.rmaNumber(), request.returnDeadline(),
                request.status());
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
     * ({@code RefundProgress.settled}) - right away when the goods' value only came off installments still owed. A
     * RETURNED order none of whose returns refunded a unit, with nothing refundable on it at all, goes back to the
     * delivery path instead (WF-05 BE-45, {@code Order.returnsEndedWithoutRefund}).
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
                && all.stream().anyMatch(r -> r.status() == ReturnStatus.RESOLVED)) {
            com.stockflow.payment.api.RefundProgress progress = payments.refundProgress(order.id().value());
            BigDecimal returned = all.stream().filter(r -> r.status() == ReturnStatus.RESOLVED)
                    .map(ReturnRequest::settlementAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean unitRefunded = all.stream().filter(r -> r.status() == ReturnStatus.RESOLVED)
                    .anyMatch(r -> r.lines().stream().anyMatch(line -> line.refundQuantity() > 0));
            if (!unitRefunded && returned.signum() == 0 && progress.refundable().signum() == 0) {
                // WF-05 BE-45 (Q-4): every return of the order ended with no unit refunded - refused with a reason, or
                // nothing usable came back - and nothing else on it was ever made refundable (a carrier's return, a
                // cancellation). "Partially refunded" would be false: the order goes back to the delivery path
                // (DELIVERED, or COMPLETED when it had completed before the claim) and completes and closes as one
                // nothing was returned from. Units refunded worth 0 on an order with nothing paid (TH-2) are a return
                // all the same - the invoice is adjusted for them (BE-44) - and keep the refund path below.
                moved |= order.returnsEndedWithoutRefund(by, now);
            } else if (progress.settled() && !deliveryFailures.completionHeld(order.id().value())) {
                // WF-05 Q-17 (audit r2 lifecycle L-1): not into the refund branch while Sales hold the completion -
                // as the carrier's path refuses it; releasing the hold follows the order on (OrderCompletionHolds).
                moved |= order.refunded(returned.compareTo(goodsWorth(order)) >= 0, by, now, "REFUNDED");
            }
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
