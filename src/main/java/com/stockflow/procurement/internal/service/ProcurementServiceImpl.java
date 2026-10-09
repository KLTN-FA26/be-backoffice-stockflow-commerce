package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderDeliveryDecision;
import com.stockflow.procurement.api.PurchaseOrderStatusCount;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.RecordSupplierConfirmationCommand;
import com.stockflow.procurement.api.RecoverPurchaseOrderDeliveryCommand;
import com.stockflow.procurement.api.SendPurchaseOrderCommand;
import com.stockflow.procurement.api.SupplierSpendReportQuery;
import com.stockflow.procurement.api.SupplierSpendSummary;
import com.stockflow.procurement.internal.domain.InvalidPurchaseOrderTransitionException;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.procurement.internal.domain.SupplierConfirmationStatus;
import com.stockflow.procurement.internal.domain.SupplierDetails;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.repository.PoDeliveryDecisionRepository;
import com.stockflow.procurement.internal.repository.ProcurementReportRepository;
import com.stockflow.procurement.internal.repository.PurchaseOrderSearchCriteria;
import com.stockflow.procurement.internal.repository.PurchaseOrderSearchRepository;
import com.stockflow.procurement.internal.repository.SupplierSpendCriteria;
import com.stockflow.product.api.ProductService;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The only implementation of {@link ProcurementService}, and the module's transaction boundary.
 *
 * <p>Package-private class, public interface: Spring injects it by the interface, so nobody can
 * bypass the port by autowiring the concrete class.</p>
 *
 * <p>Every transition also appends to the order's history ({@code purchase_order_events}); submission
 * freezes the order as a revision, and each approval decision is recorded against that revision.
 * Those rows are append-only in the database, which is what makes them evidence.</p>
 */
@Service
@Transactional
class ProcurementServiceImpl implements ProcurementService {

    private static final SortWhitelist SORT =
            SortWhitelist.of("poNumber", "expectedAt", "orderDate", "createdAt", "lastModifiedAt", "status",
                            "totalAmount")
                    .withDefault("lastModifiedAt", Sort.Direction.DESC);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");

    private final PurchaseOrderRepository purchaseOrders;
    private final PurchaseOrderSearchRepository search;
    private final ProcurementReportRepository reports;
    private final Clock clock;
    private final SupplierRepository suppliers;
    private final ApplicationEventPublisher events;
    private final NotificationService notifications;
    private final PoDeliveryDecisionRepository deliveryDecisions;
    private final PoCommunicationProfile communication;
    private final ProductService products;
    private final InventoryService inventory;
    private final InventoryControlService inventoryControl;
    private final WarehouseService warehouses;
    private final CurrentUserProvider users;

    ProcurementServiceImpl(PurchaseOrderRepository purchaseOrders, PurchaseOrderSearchRepository search,
                           ProcurementReportRepository reports, Clock clock, SupplierRepository suppliers,
                           ApplicationEventPublisher events, NotificationService notifications,
                           PoDeliveryDecisionRepository deliveryDecisions, PoCommunicationProfile communication,
                           ProductService products, InventoryService inventory,
                           InventoryControlService inventoryControl, WarehouseService warehouses,
                           CurrentUserProvider users) {
        this.purchaseOrders = purchaseOrders;
        this.search = search;
        this.reports = reports;
        this.clock = clock;
        this.suppliers = suppliers;
        this.events = events;
        this.notifications = notifications;
        this.deliveryDecisions = deliveryDecisions;
        this.communication = communication;
        this.products = products;
        this.inventory = inventory;
        this.inventoryControl = inventoryControl;
        this.warehouses = warehouses;
        this.users = users;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each line's SKU must have an inventory item (BR-01, plan Q4): the line orders the item, and
     * its unit of measure is the item's. <b>BR-PO-001 (MOQ) is not enforced</b>: no per-SKU minimum is
     * kept for a supplier yet.</p>
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "purchase-order", resourceId = "#result?.purchaseOrderId()")
    public PurchaseOrderSummary createPurchaseOrder(CreatePurchaseOrderCommand command) {
        SupplierDetails supplier = suppliers.findByIdForUpdate(command.supplierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "No supplier with id " + command.supplierId()))
                .details();
        if (supplier.status() != SupplierStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE,
                    "Supplier " + command.supplierId() + " is " + supplier.status());
        }
        var warehouse = warehouses.findWarehouse(command.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND,
                        "No warehouse with id " + command.warehouseId()));
        if (!warehouse.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Warehouse " + warehouse.prefix() + " is not active");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(command.currency());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unsupported purchase order currency");
        }
        if (command.note() != null && command.note().length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "note must be at most 2000 characters");
        }
        List<PoLine> lines = toLines(command.lines(), currency);

        // Purchasing calendar dates use the same Vietnam zone as supplier delivery KPIs.
        LocalDate today = BusinessCalendar.date(clock.instant());
        LocalDate expectedAt = command.expectedAt() == null ? today.plusDays(supplier.leadTimeDays())
                : command.expectedAt();
        PurchaseOrder order = PurchaseOrder.draft(purchaseOrders.nextPoNumber(today), command.supplierId(),
                command.warehouseId(), currency, lines, today, expectedAt,
                command.note() == null || command.note().isBlank() ? null : command.note().trim(),
                supplier.paymentTermDays(), supplier.leadTimeDays());

        boolean possibleDuplicate = isPossibleDuplicate(order);
        purchaseOrders.save(order);
        purchaseOrders.recordEvent(order.id(), null, "CREATED", actor(), null, PurchaseOrderStatus.DRAFT, null);
        return summaryOf(order, possibleDuplicate);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PurchaseOrderSummary> findById(UUID purchaseOrderId) {
        return search.summary(new PurchaseOrderId(purchaseOrderId), false);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderSummary> list(ListPurchaseOrdersQuery query) {
        var pageable = Pages.of(query.page(), query.size(), SORT.parse(query.sort()));
        // null means "no filter" to Specs.in; an empty list would mean "match nothing".
        List<PurchaseOrderStatus> statuses;
        try {
            statuses = query.statuses() == null ? null
                    : query.statuses().stream().map(s -> PurchaseOrderStatus.valueOf(s.trim().toUpperCase(Locale.ROOT)))
                    .toList();
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unknown purchase order status");
        }
        var criteria = new PurchaseOrderSearchCriteria(query.supplierId(), query.warehouseId(), statuses,
                query.search());
        return Pages.toResponse(search.search(criteria, pageable));
    }

    // ------------------------------------------------------------------ approval

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary submit(UUID purchaseOrderId, UUID submittedBy) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        // Checked before the revision is written: a revision is append-only and must not outlive a refusal.
        if (order.status() != PurchaseOrderStatus.DRAFT) {
            throw new InvalidPurchaseOrderTransitionException(order.id(), order.status(),
                    PurchaseOrderStatus.PENDING_APPROVAL);
        }
        long revisionNo = purchaseOrders.nextRevisionNo(order.id());
        UUID revisionId = purchaseOrders.recordRevision(order, revisionNo, submittedBy,
                revisionNo == 0 ? null : "Resubmitted after rejection");
        order.submit(submittedBy, revisionId, revisionNo, clock.instant());
        purchaseOrders.save(order);
        purchaseOrders.recordEvent(order.id(), revisionId, "SUBMITTED", submittedBy, PurchaseOrderStatus.DRAFT,
                PurchaseOrderStatus.PENDING_APPROVAL, null);
        return summaryOf(order, false);
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary approve(UUID purchaseOrderId, UUID approverId) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        order.approve(approverId, clock.instant());
        purchaseOrders.save(order);
        purchaseOrders.recordApproval(order.activeRevisionId(), approverId, "APPROVED", null);
        purchaseOrders.recordEvent(order.id(), order.activeRevisionId(), "APPROVED", approverId,
                PurchaseOrderStatus.PENDING_APPROVAL, PurchaseOrderStatus.APPROVED, null);
        return summaryOf(order, false);
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary reject(UUID purchaseOrderId, UUID approverId, String reason) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        UUID revisionId = order.pendingRevisionId();
        order.reject(reason);
        purchaseOrders.save(order);
        purchaseOrders.recordApproval(revisionId, approverId, "REJECTED", reason.trim());
        purchaseOrders.recordEvent(order.id(), revisionId, "REJECTED", approverId,
                PurchaseOrderStatus.PENDING_APPROVAL, PurchaseOrderStatus.DRAFT, reason);
        return summaryOf(order, false);
    }

    // ------------------------------------------------------------------ sending (#36)

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary confirm(UUID purchaseOrderId, UUID userId, SendPurchaseOrderCommand command) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        LocalDate previousDate = order.expectedAt();
        order.confirm(userId, command.expectedAt(), command.reason(), clock.instant());
        purchaseOrders.save(order);
        purchaseOrders.recordEvent(order.id(), order.activeRevisionId(), "CONFIRMED", userId,
                PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.CONFIRMED, command.reason());
        publishDelivery(order, false, previousDate, command.reason(), false, false);
        return summaryOf(order, false);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary recoverDelivery(UUID purchaseOrderId, RecoverPurchaseOrderDeliveryCommand command) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        order.requireDeliveryRecovery(BusinessCalendar.date(clock.instant()), command.reason(), command.reconciled(),
                command.acknowledgePastDue());
        publishDelivery(order, true, order.expectedAt(), command.reason(), command.reconciled(),
                command.acknowledgePastDue());
        return summaryOf(order, false);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderDeliveryDecision> deliveryDecisions(UUID id, int page, int size) {
        if (!purchaseOrders.existsById(new PurchaseOrderId(id))) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND);
        }
        return deliveryDecisions.list(id, page, size);
    }

    private void publishDelivery(PurchaseOrder saved, boolean recovery, LocalDate previousDate, String reason,
                                 boolean reconciled, boolean acknowledgePastDue) {
        var supplier = suppliers.findById(saved.supplierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "No supplier with id " + saved.supplierId()))
                .details();
        String recipient = supplier.communicationChannel() == SupplierCommunicationChannel.EMAIL
                ? supplier.email() : supplier.apiEndpoint();
        if (supplier.status() != SupplierStatus.ACTIVE || recipient == null || recipient.isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT, "An active supplier with a delivery contact is required");
        }
        notifications.validateSupplierDelivery(supplier.communicationChannel().name(), recipient);
        int generation = notifications.prepareSupplierDelivery(saved.id().value(), recovery);
        deliveryDecisions.record(saved.id().value(), generation, previousDate, saved.expectedAt(), reason, reconciled,
                acknowledgePastDue, supplier.communicationChannel().name(), recipient);
        var previous = deliveryDecisions.latestSnapshot(saved.id().value());
        var buyer = previous.map(PurchaseOrderSent::buyer).orElseGet(communication::requireBuyer);
        var lines = previous.map(PurchaseOrderSent::lines).orElseGet(() -> saved.lines().stream()
                .map(line -> new PurchaseOrderSent.Line(line.sku().code(), description(line), line.quantityOrdered(),
                        line.unitPrice().amount()))
                .toList());
        var event = new PurchaseOrderSent(saved.id().value(), saved.poNumber(), saved.supplierId(),
                supplier.communicationChannel().name(), recipient, saved.totalAmount().amount(),
                saved.currency().getCurrencyCode(), saved.expectedAt(), saved.paymentTermDays(), lines, generation,
                buyer, false, null);
        deliveryDecisions.snapshot(event);
        events.publishEvent(event);
    }

    private String description(PoLine line) {
        if (line.description() != null && !line.description().isBlank()) return line.description().trim();
        return products.nameForSku(line.sku().code())
                .filter(name -> !name.isBlank())
                .orElseThrow(() -> new BusinessException(ErrorCode.PO_LINE_DESCRIPTION_REQUIRED,
                        "Missing product description for SKU " + line.sku().code()));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary recordSupplierConfirmation(UUID purchaseOrderId,
                                                           RecordSupplierConfirmationCommand command) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        SupplierConfirmationStatus response;
        try {
            response = SupplierConfirmationStatus.valueOf(command.status());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(ErrorCode.PO_SUPPLIER_RESPONSE_INVALID);
        }
        order.recordSupplierConfirmation(response, command.supplierReference(), command.note(), clock.instant());
        notifications.suppressSupplierDelivery(purchaseOrderId);
        purchaseOrders.save(order);
        return summaryOf(order, false);
    }

    // ------------------------------------------------------------------ ending

    /** A confirmed order was already sent: its supplier is told of the cancellation, at the address it was sent to. */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary cancel(UUID purchaseOrderId, UUID userId, String reason) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        if (purchaseOrders.hasReceiptInProgress(order.id())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "A goods receipt of this order is being counted; cancel or confirm it first");
        }
        PurchaseOrderStatus from = order.status();
        order.requireCancellable(reason);
        if (order.activeRevisionId() == null && order.pendingRevisionId() == null) {
            long revisionNo = purchaseOrders.nextRevisionNo(order.id());
            order.freeze(purchaseOrders.recordRevision(order, revisionNo, userId, revisionNo == 0 ? null : "Cancelled"),
                    revisionNo);
        }
        order.cancel(reason);
        notifications.suppressSupplierDelivery(purchaseOrderId);
        purchaseOrders.save(order);
        purchaseOrders.recordEvent(order.id(), order.activeRevisionId(), "CANCELLED", userId, from,
                PurchaseOrderStatus.CANCELLED, reason);
        if (from == PurchaseOrderStatus.CONFIRMED) {
            notifications.prepareSupplierCancellation(purchaseOrderId);
            var previous = deliveryDecisions.latestSnapshot(purchaseOrderId);
            var supplier = suppliers.findById(order.supplierId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND))
                    .details();
            String channel = previous.map(PurchaseOrderSent::channel).orElse(supplier.communicationChannel().name());
            String recipient = previous.map(PurchaseOrderSent::recipient)
                    .orElse(supplier.communicationChannel() == SupplierCommunicationChannel.EMAIL
                            ? supplier.email() : supplier.apiEndpoint());
            events.publishEvent(new PurchaseOrderSent(purchaseOrderId, order.poNumber(), order.supplierId(), channel,
                    recipient, order.totalAmount().amount(), order.currency().getCurrencyCode(), order.expectedAt(),
                    order.paymentTermDays(), previous.map(PurchaseOrderSent::lines).orElse(List.of()), 0,
                    previous.map(PurchaseOrderSent::buyer).orElse(null), true, reason));
        }
        return summaryOf(order, false);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary closeShort(UUID purchaseOrderId, UUID userId, String reason) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        order.closeShort(reason, userId, clock.instant());
        purchaseOrders.save(order);
        purchaseOrders.recordEvent(order.id(), order.activeRevisionId(), "SHORT_CLOSED", userId,
                PurchaseOrderStatus.PARTIALLY_RECEIVED, PurchaseOrderStatus.CLOSED, reason);
        return summaryOf(order, false);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary close(UUID purchaseOrderId, UUID userId) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        order.close(userId, clock.instant());
        purchaseOrders.save(order);
        purchaseOrders.recordEvent(order.id(), order.activeRevisionId(), "CLOSED", userId,
                PurchaseOrderStatus.RECEIVED, PurchaseOrderStatus.CLOSED, null);
        return summaryOf(order, false);
    }

    // ------------------------------------------------------------------ reports

    @Override
    @Transactional(readOnly = true)
    public List<PurchaseOrderStatusCount> statusDashboard() {
        return reports.statusDashboard();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SupplierSpendSummary> supplierSpend(SupplierSpendReportQuery query) {
        if (query.supplierId() != null) {
            purchaseOrders.supplierStatus(query.supplierId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND,
                            "No supplier with id " + query.supplierId()));
        }
        var pageable = Pages.of(query.page(), query.size());
        var criteria = new SupplierSpendCriteria(query.supplierId(),
                query.currency() == null || query.currency().isBlank() ? null
                        : query.currency().trim().toUpperCase(Locale.ROOT),
                query.expectedAtFrom(), query.expectedAtTo());
        return Pages.toResponse(reports.supplierSpend(criteria, pageable));
    }

    // ------------------------------------------------------------------ helpers

    private PurchaseOrder loadForUpdate(UUID purchaseOrderId) {
        return purchaseOrders.findByIdForUpdate(new PurchaseOrderId(purchaseOrderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND,
                        "No purchase order with id " + purchaseOrderId));
    }

    private PurchaseOrderSummary summaryOf(PurchaseOrder order, boolean possibleDuplicate) {
        return search.summary(order.id(), possibleDuplicate).orElseThrow();
    }

    private UUID actor() {
        return users.current().map(CurrentUser::userId).orElse(null);
    }

    /**
     * BR-PO-003: "two POs to the same supplier, same SKU, same delivery date are flagged as possible
     * duplicates" — a warning on the create response, not a rejection.
     */
    private boolean isPossibleDuplicate(PurchaseOrder order) {
        if (order.expectedAt() == null) {
            return false;
        }
        Set<UUID> items = new HashSet<>(order.lines().stream().map(PoLine::inventoryItemId).toList());
        return purchaseOrders.findOpenBySupplierAndExpectedAt(order.supplierId(), order.expectedAt()).stream()
                .anyMatch(other -> other.lines().stream().map(PoLine::inventoryItemId).anyMatch(items::contains));
    }

    private List<PoLine> toLines(List<CreatePOLineCommand> commands, Currency currency) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A purchase order must have at least one line");
        }
        if (currency.getDefaultFractionDigits() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unsupported purchase order currency");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (var c : commands) {
            if (c == null || c.quantityOrdered() <= 0 || c.quantityOrdered() > 1_000_000 || c.unitPrice() == null
                    || c.unitPrice().signum() <= 0 || c.unitPrice().compareTo(MAX_AMOUNT) > 0
                    || c.unitPrice().stripTrailingZeros().scale() > Math.min(2, currency.getDefaultFractionDigits())
                    || c.taxRate() != null && (c.taxRate().signum() < 0
                    || c.taxRate().compareTo(BigDecimal.valueOf(100)) > 0)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Invalid purchase order quantity, unit price or tax rate");
            }
            total = total.add(c.unitPrice().multiply(BigDecimal.valueOf(c.quantityOrdered())));
        }
        if (total.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Purchase order total exceeds the supported limit");
        }
        List<PoLine> lines = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (var c : commands) {
            var sku = new Sku(c.sku());
            InventoryItemPolicy item = inventory.itemPolicy(sku)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND,
                            "SKU " + sku.code() + " has no inventory item"));
            if (!seen.add(item.inventoryItemId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "SKU " + sku.code() + " appears twice; put the whole quantity on one line");
            }
            String description = c.description() != null && !c.description().isBlank() ? c.description().trim()
                    : products.nameForSku(sku.code()).filter(name -> !name.isBlank()).map(String::trim)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PO_LINE_DESCRIPTION_REQUIRED,
                            "Missing product description for SKU " + sku.code()));
            if (description.length() > 255) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Product description must not exceed 255 characters");
            }
            String uom = inventoryControl.item(sku).unitOfMeasure();
            lines.add(PurchaseOrder.line(lines.size() + 1, item.inventoryItemId(), sku, uom, description,
                    c.quantityOrdered(), new Money(c.unitPrice(), currency), c.taxRate()));
        }
        return lines;
    }
}
