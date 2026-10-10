package com.stockflow.procurement.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.api.CreateSubcontractOrderCommand;
import com.stockflow.procurement.api.SubcontractOrder;
import com.stockflow.procurement.api.SubcontractPurchasing;
import com.stockflow.procurement.api.SubcontractorTerms;
import com.stockflow.procurement.internal.repository.SubcontractOrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SUBCONTRACT purchase orders (SCRUM-434). The checks a caller can act on are made here, with a real
 * error code; the database states them again ({@code check_subcontract_po}: print subcontractor,
 * SUBCONTRACTED production order of the same warehouse, one line, one live PO per production order).
 */
@Service
@Transactional
class SubcontractPurchasingImpl implements SubcontractPurchasing {

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

    private final SubcontractOrderStore store;
    private final InventoryService inventory;
    private final InventoryControlService inventoryControl;
    private final Clock clock;

    SubcontractPurchasingImpl(SubcontractOrderStore store, InventoryService inventory,
                              InventoryControlService inventoryControl, Clock clock) {
        this.store = store;
        this.inventory = inventory;
        this.inventoryControl = inventoryControl;
        this.clock = clock;
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
        SubcontractOrderStore.Row order = store.findById(purchaseOrderId, true).orElseThrow(() ->
                new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "No subcontract order " + purchaseOrderId));
        if (!EDITABLE.equals(order.status())) {
            throw new BusinessException(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION,
                    ("PENDING_APPROVAL".equals(order.status())
                            ? "%s is awaiting approval of its submitted quantity: reject it back to DRAFT first"
                            : "%s is %s: an approved order is changed by an amendment (SCRUM-117), not in place")
                            .formatted(order.poNumber(), order.status()));
        }
        int newQuantity = order.quantity().intValueExact() + additionalQuantity;
        store.changeQuantity(order, newQuantity, reason.trim(), actorId);
        log.info("Supplemented {} by {} to {}: {}", order.poNumber(), additionalQuantity, newQuantity, reason);
        return toOrder(store.findById(purchaseOrderId, false).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubcontractorTerms> subcontractorTerms(UUID supplierId) {
        return store.supplier(supplierId).map(s -> new SubcontractorTerms(s.id(), s.code(), s.name(),
                s.printSubcontractor(), s.lossTolerancePercent()));
    }

    private SubcontractOrder toOrder(SubcontractOrderStore.Row row) {
        String sku = inventory.itemPolicy(row.inventoryItemId()).map(InventoryItemPolicy::sku).orElse(null);
        return new SubcontractOrder(row.id(), row.poNumber(), row.productionOrderId(), row.supplierId(),
                row.warehouseId(), row.status(), APPROVED.contains(row.status()), sku,
                row.quantity().intValueExact(), row.unitPrice(), row.currency(), row.totalAmount(), row.expectedDate());
    }
}
