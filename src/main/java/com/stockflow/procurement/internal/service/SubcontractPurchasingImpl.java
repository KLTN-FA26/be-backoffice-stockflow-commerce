package com.stockflow.procurement.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.api.CreateSubcontractOrderCommand;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.SettleSubcontractCommand;
import com.stockflow.procurement.api.SubcontractOrder;
import com.stockflow.procurement.api.SubcontractPurchasing;
import com.stockflow.procurement.api.SubcontractSettlement;
import com.stockflow.procurement.api.SubcontractorTerms;
import com.stockflow.procurement.internal.domain.ApprovalLimit;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.repository.SubcontractOrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SUBCONTRACT purchase orders (SCRUM-434). The checks a caller can act on are made here, with a real
 * error code; the database states them again ({@code check_subcontract_po}: print subcontractor,
 * SUBCONTRACTED production order of the same warehouse, one line, one live PO per production order;
 * {@code ck_subcontract_settlement_*} for the settlement).
 */
@Service
@Transactional
class SubcontractPurchasingImpl implements SubcontractPurchasing, SubcontractAmendments {

    private static final Logger log = LoggerFactory.getLogger(SubcontractPurchasingImpl.class);

    /** Order dates and numbers follow the business's calendar, not UTC. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** BR-PRD-13: the work goes out once the PO is approved, and stays out through receipt. */
    private static final Set<String> APPROVED = Set.of("APPROVED", "CONFIRMED", "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED");

    /**
     * Only a draft is resized in place. From submission on, the approver decides on the revision
     * snapshot taken at submit; resizing the live line under it would have the order approved for one
     * quantity and placed for another. A pending order goes back to DRAFT (rejected) first.
     */
    private static final String EDITABLE = "DRAFT";

    /** An approved order still open to receiving gets more quantity through an amendment approved again (BR-02). */
    private static final Set<String> AMENDABLE = Set.of("APPROVED", "CONFIRMED", "PARTIALLY_RECEIVED", "RECEIVED");

    private final SubcontractOrderStore store;
    private final InventoryService inventory;
    private final InventoryControlService inventoryControl;
    private final ProcurementService procurement;
    private final Clock clock;
    private final ApprovalLimits approvalLimits;

    SubcontractPurchasingImpl(SubcontractOrderStore store, InventoryService inventory,
                              InventoryControlService inventoryControl, ProcurementService procurement, Clock clock,
                              ApprovalLimits approvalLimits) {
        this.store = store;
        this.inventory = inventory;
        this.inventoryControl = inventoryControl;
        this.procurement = procurement;
        this.clock = clock;
        this.approvalLimits = approvalLimits;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "subcontract-purchase-order", resourceId = "#result?.purchaseOrderId()")
    public SubcontractOrder create(CreateSubcontractOrderCommand command) {
        Optional<SubcontractOrderStore.Row> existing = store.findLiveByProductionOrder(command.productionOrderId());
        if (existing.isPresent()) {
            return toOrder(existing.get());
        }
        if (command.quantity() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A subcontract order needs a positive quantity");
        }
        if (command.unitPrice().signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The print work needs a positive unit price");
        }
        if (!command.currency().matches("^[A-Z]{3}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Currency must be an ISO code such as VND");
        }
        SubcontractOrderStore.Supplier supplier = store.supplier(command.supplierId()).orElseThrow(() ->
                new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND, "No supplier " + command.supplierId()));
        if (!supplier.printSubcontractor()) {
            throw new BusinessException(ErrorCode.SUPPLIER_NOT_SUBCONTRACTOR,
                    "%s is not flagged as a print subcontractor".formatted(supplier.code()));
        }
        InventoryItemPolicy item = inventory.itemPolicy(command.finishedSku()).orElseThrow(() ->
                new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND,
                        "%s has no inventory item; complete its logistics data first".formatted(command.finishedSku())));
        LocalDate today = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
        if (command.expectedDate() != null && command.expectedDate().isBefore(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The expected date is in the past");
        }

        // The line is in the item's own unit, as on every purchase order (ProcurementServiceImpl).
        String uom = inventoryControl.item(command.finishedSku()).unitOfMeasure();
        UUID id = store.insert(new SubcontractOrderStore.NewOrder(store.nextNumber(today), command.productionOrderId(),
                supplier, command.warehouseId(), command.currency(), today, command.expectedDate(),
                item.inventoryItemId(), item.sku(), uom, command.quantity(), command.unitPrice(), command.createdBy()));
        SubcontractOrder created = toOrder(store.findById(id, false).orElseThrow());
        log.info("Raised {} to {} for production order {}: {} x {}", created.poNumber(), supplier.code(),
                command.productionOrderId(), command.quantity(), command.finishedSku());
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubcontractOrder> findByProductionOrder(UUID productionOrderId) {
        return store.findLiveByProductionOrder(productionOrderId).map(this::toOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubcontractOrder> findById(UUID purchaseOrderId) {
        return store.findById(purchaseOrderId, false).map(this::toOrder);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "subcontract-purchase-order", resourceId = "#purchaseOrderId")
    public SubcontractOrder supplement(UUID purchaseOrderId, int additionalQuantity, String reason, UUID actorId) {
        if (additionalQuantity <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A supplement adds a positive quantity");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A supplement needs a reason");
        }
        SubcontractOrderStore.Row order = lock(purchaseOrderId);
        int newQuantity = order.quantity().intValueExact() + additionalQuantity;
        if (EDITABLE.equals(order.status())) {
            store.changeQuantity(order, newQuantity, reason.trim(), actorId);
            // Ids and counts only: the reason is typed text (develop #99 log hygiene); it is on the PO's timeline.
            log.info("Supplemented {} by {} to {}", order.poNumber(), additionalQuantity, newQuantity);
            return toOrder(store.findById(purchaseOrderId, false).orElseThrow());
        }
        if (!AMENDABLE.contains(order.status())) {
            throw new BusinessException(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION,
                    ("PENDING_APPROVAL".equals(order.status())
                            ? "%s is awaiting approval of its submitted quantity: reject it back to DRAFT first"
                            : "%s is %s: it takes no more quantity").formatted(order.poNumber(), order.status()));
        }
        store.pendingAmendment(purchaseOrderId).ifPresent(waiting -> {
            throw new BusinessException(ErrorCode.CONFLICT, "%s already has an amendment to %d waiting for approval"
                    .formatted(order.poNumber(), waiting.quantity()));
        });
        store.requestAmendment(order, newQuantity, reason.trim(), actorId);
        log.info("Amendment of {} to {} (+{}) asked for", order.poNumber(), newQuantity, additionalQuantity);
        autoApproveWithinLimit(order, actorId);
        return toOrder(store.findById(purchaseOrderId, false).orElseThrow());
    }

    /**
     * SCRUM-124 rule 5: an amendment raises the total of an order that was approved, so it is decided again against
     * the new total with the limit of whoever asked for it - the same rules as a submission (rules 1, 3 and 4, see
     * {@link ApprovalLimits#autoApprovalLimit}). Within that limit the amendment is approved here, as
     * {@code AUTO_APPROVED} on the limit that allowed it; above it, or with no limit configured for the requester, it
     * waits for someone else (four-eyes) whose own limit covers the new total ({@link #approveAmendment}).
     */
    private void autoApproveWithinLimit(SubcontractOrderStore.Row order, UUID requesterId) {
        if (requesterId == null) {
            return;
        }
        SubcontractOrderStore.Amendment amendment = store.pendingAmendment(order.id()).orElseThrow();
        Money newTotal = new Money(amendment.total(), Currency.getInstance(order.currency()));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        approvalLimits.autoApprovalLimit(new PurchaseOrderId(order.id()), requesterId, order.supplierId(), newTotal, now)
                .ifPresent(limit -> {
                    String reason = "Auto-approved within limit: new total %s, limit %s".formatted(newTotal, limit.amount());
                    store.approveAmendment(order, amendment, requesterId,
                            new SubcontractOrderStore.AutoApproval(requesterId, limit, now, reason));
                    log.info("Amendment of {} to {} auto-approved within the requester's limit", order.poNumber(),
                            amendment.quantity());
                });
    }

    /**
     * Four-eyes (an amendment is approved by someone other than who asked for it), and the approver's own limit must
     * cover the new total (SCRUM-124 rule 5, as for a purchase order, BR-PO-002) - when they have one: an approver none
     * of whose roles has a limit configured is bounded by the approve permission alone (owner decision PO-NOLIMIT).
     */
    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public SubcontractOrder approveAmendment(UUID purchaseOrderId, UUID approverId) {
        SubcontractOrderStore.Row order = lock(purchaseOrderId);
        SubcontractOrderStore.Amendment amendment = store.pendingAmendment(purchaseOrderId).orElseThrow(() ->
                new BusinessException(ErrorCode.CONFLICT, "%s has no amendment waiting".formatted(order.poNumber())));
        if (amendment.requestedBy() != null && amendment.requestedBy().equals(approverId)) {
            throw new BusinessException(ErrorCode.SELF_APPROVAL_NOT_ALLOWED,
                    "An amendment is approved by someone other than who asked for it");
        }
        Currency currency = Currency.getInstance(order.currency());
        Money newTotal = new Money(amendment.total(), currency);
        Optional<ApprovalLimit> limit = approvalLimits.limitOf(approverId, currency,
                BusinessCalendar.date(clock.instant()));
        if (limit.isPresent() && newTotal.compareTo(limit.get().amount()) > 0) {
            throw new BusinessException(ErrorCode.PO_APPROVAL_LIMIT_EXCEEDED,
                    "Amendment of %s makes it total %s; your approval limit is %s".formatted(order.poNumber(), newTotal,
                            limit.get().amount()));
        }
        store.approveAmendment(order, amendment, approverId, null);
        log.info("Amendment of {} to {} approved", order.poNumber(), amendment.quantity());
        return toOrder(store.findById(purchaseOrderId, false).orElseThrow());
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public SubcontractOrder rejectAmendment(UUID purchaseOrderId, UUID approverId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A rejected amendment says why");
        }
        SubcontractOrderStore.Row order = lock(purchaseOrderId);
        SubcontractOrderStore.Amendment amendment = store.pendingAmendment(purchaseOrderId).orElseThrow(() ->
                new BusinessException(ErrorCode.CONFLICT, "%s has no amendment waiting".formatted(order.poNumber())));
        store.dropAmendment(order, amendment, approverId, "REJECTED", reason.trim());
        return toOrder(store.findById(purchaseOrderId, false).orElseThrow());
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "subcontract-purchase-order", resourceId = "#purchaseOrderId")
    public SubcontractOrder closeForProduction(UUID purchaseOrderId, String reason, UUID actorId) {
        SubcontractOrderStore.Row order = lock(purchaseOrderId);
        store.pendingAmendment(purchaseOrderId).ifPresent(waiting ->
                store.dropAmendment(order, waiting, actorId, "WITHDRAWN", reason));
        int received = store.receivedQuantity(purchaseOrderId);
        switch (order.status()) {
            case "DRAFT", "PENDING_APPROVAL", "APPROVED", "CONFIRMED" -> {
                if (received == 0) {
                    procurement.cancel(purchaseOrderId, actorId, reason);
                }
            }
            case "PARTIALLY_RECEIVED" -> procurement.closeShort(purchaseOrderId, actorId, reason);
            default -> { }   // RECEIVED, CLOSED, CANCELLED: nothing open is left
        }
        SubcontractOrder after = toOrder(store.findById(purchaseOrderId, false).orElseThrow());
        log.info("{} closed for production: {} -> {} ({} received)", order.poNumber(), order.status(),
                after.status(), received);
        return after;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "subcontract-settlement", resourceId = "#command.purchaseOrderId()")
    public SubcontractSettlement settle(SettleSubcontractCommand command) {
        SubcontractOrderStore.Row order = lock(command.purchaseOrderId());
        Optional<SubcontractSettlement> done = store.settlement(command.purchaseOrderId(), true);
        if (done.isPresent()) {
            return done.get();
        }
        if (!order.productionOrderId().equals(command.productionOrderId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "%s does not pay for production order %s"
                    .formatted(order.poNumber(), command.productionOrderId()));
        }
        BigDecimal fee = command.cancellationFee();
        String feeReason = command.cancellationFeeReason() == null || command.cancellationFeeReason().isBlank()
                ? null : command.cancellationFeeReason().strip();
        if (fee.signum() < 0 || fee.signum() > 0 && feeReason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A cancellation fee is not negative and says why");
        }
        BigDecimal tolerancePercent = store.supplier(order.supplierId())
                .map(SubcontractOrderStore.Supplier::lossTolerancePercent).orElse(BigDecimal.ZERO);
        // BR-PRD-17: blanks lost within the subcontractor's tolerance are ours to bear; above it, theirs.
        int toleranceBlanks = BigDecimal.valueOf(command.blanksSent()).multiply(tolerancePercent)
                .movePointLeft(2).setScale(0, RoundingMode.FLOOR).intValueExact();
        int excess = Math.max(0, command.blanksLost() - toleranceBlanks);
        BigDecimal unitPrice = order.unitPrice().setScale(2, RoundingMode.HALF_UP);
        BigDecimal payable = unitPrice.multiply(BigDecimal.valueOf(command.passedQuantity()));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        SubcontractSettlement settlement = new SubcontractSettlement(order.id(), command.productionOrderId(),
                excess > 0 ? "AWAITING_APPROVAL" : "FINAL", order.currency(), unitPrice, command.receivedQuantity(),
                command.passedQuantity(), command.failedQuantity(), payable, command.blanksSent(), command.blanksUsed(),
                command.blanksReturned(), command.blanksLost(), tolerancePercent, toleranceBlanks, excess, null,
                BigDecimal.ZERO, fee, feeReason, payable.add(fee), now, command.settledBy(), null, null, null);
        store.insertSettlement(settlement);
        log.info("{} settled: {} passed x {} = {}, {} failed not paid, {} blank(s) lost ({} tolerated), fee {}; {}",
                order.poNumber(), command.passedQuantity(), unitPrice, payable, command.failedQuantity(),
                command.blanksLost(), toleranceBlanks, fee, settlement.status());
        return store.settlement(order.id(), false).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubcontractSettlement> settlement(UUID purchaseOrderId) {
        // The same warehouse scope as every write on the order (BR-SEC-002): a manager of another warehouse reads
        // none of what this one owes its subcontractor.
        store.findById(purchaseOrderId, false).ifPresent(row -> WarehouseScope.requireWarehouse(row.warehouseId()));
        return store.settlement(purchaseOrderId, false);
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "subcontract-settlement", resourceId = "#purchaseOrderId")
    public SubcontractSettlement approveLoss(UUID purchaseOrderId, BigDecimal deductionPerBlank, String note,
                                             UUID approverId) {
        if (deductionPerBlank == null || deductionPerBlank.signum() < 0 || deductionPerBlank.scale() > 2
                && deductionPerBlank.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The deduction per lost blank is an amount of at most 2 decimals, not negative");
        }
        String text = note == null || note.isBlank() ? null : note.strip();
        if (text == null || text.length() > 1000) {
            // QA1: the deduction is the one agreed with the subcontractor, and the approver says so.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Approving a loss above tolerance needs a reason (at most 1000 characters)");
        }
        lock(purchaseOrderId);
        SubcontractSettlement settlement = store.settlement(purchaseOrderId, true).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "Purchase order %s is not settled yet".formatted(purchaseOrderId)));
        BigDecimal perBlank = deductionPerBlank.setScale(2, RoundingMode.UNNECESSARY);
        if ("FINAL".equals(settlement.status())) {
            if (settlement.deductionPerBlank() != null && settlement.deductionPerBlank().compareTo(perBlank) == 0) {
                return settlement;   // a replay of the same approval
            }
            throw new BusinessException(ErrorCode.CONFLICT, settlement.excessLoss() == 0
                    ? "No blank was lost above the subcontractor's tolerance: nothing to approve"
                    : "The loss was approved already, at %s per blank".formatted(settlement.deductionPerBlank()));
        }
        BigDecimal deduction = perBlank.multiply(BigDecimal.valueOf(settlement.excessLoss()));
        store.approveSettlement(purchaseOrderId, perBlank, deduction,
                settlement.payableAmount().subtract(deduction).add(settlement.cancellationFee()), text, approverId,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        log.info("Loss of {} blank(s) above tolerance on {} approved: {} deducted", settlement.excessLoss(),
                purchaseOrderId, deduction);
        return store.settlement(purchaseOrderId, false).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubcontractorTerms> subcontractorTerms(UUID supplierId) {
        return store.supplier(supplierId).map(s -> new SubcontractorTerms(s.id(), s.code(), s.name(),
                s.printSubcontractor(), s.lossTolerancePercent()));
    }

    /**
     * The order, locked, and only for a caller of its warehouse (BR-SEC-002). Checked here, before any change: every
     * write of this class is {@code @Transactional}, so a check left to a read after it would come once the change is
     * already committed. The system (an event listener) is not warehouse-bound.
     */
    private SubcontractOrderStore.Row lock(UUID purchaseOrderId) {
        SubcontractOrderStore.Row row = store.findById(purchaseOrderId, true).orElseThrow(() ->
                new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "No subcontract order " + purchaseOrderId));
        WarehouseScope.requireWarehouse(row.warehouseId());
        return row;
    }

    private SubcontractOrder toOrder(SubcontractOrderStore.Row row) {
        String sku = inventory.itemPolicy(row.inventoryItemId()).map(InventoryItemPolicy::sku).orElse(null);
        return new SubcontractOrder(row.id(), row.poNumber(), row.productionOrderId(), row.supplierId(),
                row.warehouseId(), row.status(), APPROVED.contains(row.status()), sku,
                row.quantity().intValueExact(), row.unitPrice(), row.currency(), row.totalAmount(), row.expectedDate(),
                store.receivedQuantity(row.id()),
                store.pendingAmendment(row.id()).map(SubcontractOrderStore.Amendment::quantity).orElse(null));
    }
}
