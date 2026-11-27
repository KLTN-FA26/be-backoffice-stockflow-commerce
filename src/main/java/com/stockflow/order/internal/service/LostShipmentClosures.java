package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.RecordRetention;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.repository.OrderHistoryNotes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Sales close a shipment the carrier lost (WF-05 decision Q-12 = a, BE-17; kltn-docs 09 line 613). Before this, a
 * shipment that failed as LOST and never came back left its order in DELIVERY_FAILED for good: nothing moved it but a
 * false "returned" report. Now Sales, with the approval-level grant {@code sales-lost-shipment-closures:APPROVE} and
 * the carrier's confirmation of the loss, close its units: payment settles them as a carrier's return (Q-2) at this
 * moment - no goods come back to wait for - and the order follows its shipments (PARTIALLY_FULFILLED, or RETURNED when
 * nothing of it arrived). The carrier is claimed against outside the system; goods the customer still wants go on a new
 * order (Q-12, follow-up).
 *
 * <p>Not transactional itself: the evidence file is checked and kept first, then the closure is made under the order's
 * lock, and the file adopted only if it committed - as a delivery window change keeps its consent file.</p>
 */
@Service
public class LostShipmentClosures {

    private static final Logger log = LoggerFactory.getLogger(LostShipmentClosures.class);

    private final OrderRepository orders;
    private final OrderServiceImpl orderService;
    private final OrderHistoryNotes notes;
    private final DeliveryFailureService deliveryFailures;
    private final FileTransfers files;
    private final StagedUploads staged;
    private final TransactionTemplate tx;
    private final Clock clock;

    LostShipmentClosures(OrderRepository orders, OrderServiceImpl orderService, OrderHistoryNotes notes,
                         DeliveryFailureService deliveryFailures, FileTransfers files, StagedUploads staged,
                         TransactionTemplate tx, Clock clock) {
        this.deliveryFailures = deliveryFailures;
        this.orders = orders;
        this.orderService = orderService;
        this.notes = notes;
        this.files = files;
        this.staged = staged;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Where the browser uploads the carrier's confirmation of the loss before the closure is recorded. A read: it only
     * answers whether the caller may see the order and the order has the shipment, so it reads without the row lock -
     * {@code findByIdInScope} locks, and Postgres refuses {@code SELECT ... FOR UPDATE} in a read-only transaction (the
     * 500 of the 24/11 demo run, D-1). The closure itself takes the lock and checks again.
     */
    @Transactional(readOnly = true)
    public UploadSlot beginEvidenceUpload(CurrentUser user, UUID orderId, UUID shipmentId, UploadSlotRequest request) {
        Order order = orders.findByIdInScopeForReading(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        if (order.shipments().stream().noneMatch(s -> s.id().equals(shipmentId))) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Order %s has no shipment %s".formatted(
                    order.orderNumber(), shipmentId));
        }
        return staged.begin(FileCategory.DOCUMENT, user.userId(), request);
    }

    /**
     * Closes the lost shipment's units (see the class).
     *
     * @param version the order's version the caller read; null: not checked
     * @throws BusinessException {@code VALIDATION_FAILED} without a note of at most 1000 characters;
     *         {@code ORDER_SHIPMENT_LOSS_EVIDENCE_REQUIRED} without the carrier's confirmation;
     *         {@code ORDER_SHIPMENT_NOT_LOST_CLOSABLE} for a shipment not DELIVERY_FAILED for LOST, or whose units the
     *         order no longer owes; {@code NOT_FOUND}; {@code OPTIMISTIC_LOCK} when the order changed since it was read
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "order", resourceId = "#orderId",
            details = "{closed: 'LOST_SHIPMENT'}")
    public OrderSummary close(CurrentUser user, UUID orderId, UUID shipmentId, String note, UUID evidenceUploadId,
                              Long version) {
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        if (trimmed == null || trimmed.length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "note: why the shipment is closed as lost, at "
                    + "most 1000 characters");
        }
        if (evidenceUploadId == null) {
            throw new BusinessException(ErrorCode.ORDER_SHIPMENT_LOSS_EVIDENCE_REQUIRED, "Closing shipment %s of order %s "
                    .formatted(shipmentId, orderId) + "as lost needs the carrier's confirmation of the loss");
        }
        try (PreparedFiles prepared = IncomingFiles.uploaded(user.userId(), List.of(evidenceUploadId))
                .prepare(FileCategory.DOCUMENT, files, staged)) {
            StoredFile evidence = prepared.single().file();
            return tx.execute(status -> {
                OrderSummary closed = closeLocked(user.userId(), orderId, shipmentId, trimmed, evidence.key(),
                        evidence.originalName(), version);
                prepared.adopt();
                files.retainAfterCommit(evidence.key(), RecordRetention.SALES_CONTRACT);
                return closed;
            });
        }
    }

    /**
     * The closure itself, under the order's lock, in the caller's transaction, the carrier's confirmation stored
     * already as {@code evidenceKey}. Package-private and not proxied: called from {@link #close}'s transaction
     * template (and by the integration test inside one).
     */
    OrderSummary closeLocked(UUID by, UUID orderId, UUID shipmentId, String note, String evidenceKey,
                             String evidenceName, Long version) {
        Order order = orders.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        if (version != null && version != order.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "Order %s changed since it was read"
                    .formatted(order.orderNumber()));
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Order.ShipmentClosure closure = order.closeLostShipment(shipmentId, note, now, by);
        orderService.settleShipmentClosure(order, closure,
                com.stockflow.payment.api.SettleShipmentReturnCommand.Cause.LOST, note, by, now);
        notes.lossClosed(orderId, shipmentId, closure.closureId(), note, evidenceKey, evidenceName, by, now);
        OrderSummary summary = orderService.saveClosed(order);
        log.info("Order {}: shipment {} closed as lost by {}; order {}", order.orderNumber(), shipmentId, by,
                order.status());
        // Refunds made already, or nothing made refundable: no refund record will come to move the order on. With
        // nothing refundable it stays where its shipments put it and closes from there (BE-45, Order.closeWithoutRefund).
        if (deliveryFailures.followRefunds(orderId, by)) {
            return orderService.summaryOf(orderId);
        }
        return summary;
    }
}
