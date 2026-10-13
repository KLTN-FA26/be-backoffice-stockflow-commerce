package com.stockflow.production.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.contracts.ProductionOrderHeld;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.api.FulfillmentArtifact;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The workshop's transitions, one transaction each, on the order's row lock (taken before loading, so a
 * second caller sees the first one's result and is refused for its state, not for a stale version).
 *
 * <p><b>Prepress</b> never trusts a client's verdict on the file alone: PASS re-hashes the stored artifact
 * through {@code design :: api} and refuses (409 {@code PRINT_FILE_MISMATCH}) when it no longer matches the
 * checksum the order was released with (BR-PRD-02). FAIL puts the order ON_HOLD with reason
 * {@code FILE_REJECTED} and announces {@link ProductionOrderHeld} so Sales can take the design change up
 * with the customer; the announcement commits with the hold or not at all.</p>
 */
@Service
@Transactional
class ProductionWorkflowService implements ProductionWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ProductionWorkflowService.class);

    private final ProductionOrderRepository orders;
    private final ProductionOrders reads;
    private final DesignService designs;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ProductionWorkflowService(ProductionOrderRepository orders, ProductionOrders reads, DesignService designs,
                              ApplicationEventPublisher events, Clock clock) {
        this.orders = orders;
        this.reads = reads;
        this.designs = designs;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PrintFile printFile(CurrentUser user, UUID productionOrderId) {
        ScopeGuards.requireBackOffice(user);
        ProductionOrder order = orders.findById(Objects.requireNonNull(productionOrderId, "productionOrderId"))
                .orElseThrow(() -> new NotFoundException("production-order", productionOrderId));
        WarehouseScope.requireWarehouse(order.work().warehouseId());
        FulfillmentArtifact file = designs.fulfillmentDownload(order.work().designSnapshotId(),
                order.work().designChecksum(), DesignArtifactRole.PRINT_READY);
        return new PrintFile(file.artifactId(), file.originalName(), file.contentType(), file.sizeBytes(),
                file.checksum(), file.url(), file.expiresAt());
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail prepress(CurrentUser user, UUID productionOrderId, boolean pass, String note) {
        ProductionOrder order = lock(user, productionOrderId);
        Instant now = clock.instant();
        if (!pass) {
            if (note == null || note.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A rejected print file must say what is wrong");
            }
            if (order.status() != ProductionOrder.Status.PENDING_PREPRESS) {
                throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                        "Production order %s is %s; prepress is checked while PENDING_PREPRESS"
                                .formatted(order.orderNumber(), order.status()));
            }
            return held(user, order, order.hold(ProductionOrder.HoldReason.FILE_REJECTED, note, now), now);
        }
        boolean matching = designs.verifyForFulfillment(order.work().designSnapshotId(), order.work().designChecksum())
                .matching();
        if (!matching) {
            throw new BusinessException(ErrorCode.PRINT_FILE_MISMATCH,
                    "The stored print file of production order %s no longer matches its locked design (BR-PRD-02)"
                            .formatted(order.orderNumber()));
        }
        ProductionOrder.Transition passed = order.passPrepress(user.userId(), now);
        orders.save(order);
        orders.recordTransition(order.id(), passed, now, user.userId());
        log.info("Production order {} passed prepress", order.orderNumber());
        return reads.get(user, order.id());
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail hold(CurrentUser user, UUID productionOrderId, ProductionOrder.HoldReason reason,
                                        String note) {
        ProductionOrder order = lock(user, productionOrderId);
        Instant now = clock.instant();
        return held(user, order, order.hold(reason, note, now), now);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail resume(CurrentUser user, UUID productionOrderId) {
        ProductionOrder order = lock(user, productionOrderId);
        Instant now = clock.instant();
        ProductionOrder.Transition resumed = order.resume();
        orders.save(order);
        orders.recordTransition(order.id(), resumed, now, user.userId());
        log.info("Production order {} resumed to {}", order.orderNumber(), order.status());
        return reads.get(user, order.id());
    }

    private ProductionOrders.Detail held(CurrentUser user, ProductionOrder order, ProductionOrder.Transition held,
                                         Instant now) {
        orders.save(order);
        orders.recordTransition(order.id(), held, now, user.userId());
        // The note stays on the order (contracts carry codes, not typed text); a BLANKS_SHORT hold says how many.
        Integer blanksNeeded = order.holdReason() == ProductionOrder.HoldReason.BLANKS_SHORT
                && order.outstandingBlanks() > 0 ? order.outstandingBlanks() : null;
        events.publishEvent(new ProductionOrderHeld(order.id(), order.orderNumber(), order.holdReason().name(),
                order.sampleRequestId(), order.salesOrderId(), order.work().warehouseId(),
                order.work().blankSku().code(), blanksNeeded));
        log.info("Production order {} on hold: {}", order.orderNumber(), order.holdReason());
        return reads.get(user, order.id());
    }

    private ProductionOrder lock(CurrentUser user, UUID productionOrderId) {
        ScopeGuards.requireBackOffice(user);
        ProductionOrder order = orders.findByIdForUpdate(Objects.requireNonNull(productionOrderId, "productionOrderId"))
                .orElseThrow(() -> new NotFoundException("production-order", productionOrderId));
        WarehouseScope.requireWarehouse(order.work().warehouseId());
        return order;
    }
}
