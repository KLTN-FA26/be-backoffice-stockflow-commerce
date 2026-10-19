package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.FieldError;
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
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderDeliveryDecision;
import com.stockflow.procurement.api.PurchaseOrderStatusCount;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.RecordSupplierConfirmationCommand;
import com.stockflow.procurement.api.RecoverPurchaseOrderDeliveryCommand;
import com.stockflow.procurement.api.RevisePurchaseOrderCommand;
import com.stockflow.procurement.api.SendPurchaseOrderCommand;
import com.stockflow.procurement.api.SupplierSpendReportQuery;
import com.stockflow.procurement.api.SupplierSpendSummary;
import com.stockflow.procurement.internal.domain.InvalidPurchaseOrderTransitionException;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.PurchasePrices;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.procurement.internal.domain.SupplierConfirmationStatus;
import com.stockflow.procurement.internal.domain.SupplierDetails;
import com.stockflow.procurement.internal.domain.SupplierItem;
import com.stockflow.procurement.internal.domain.SupplierItemRepository;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.repository.PoDeliveryDecisionRepository;
import com.stockflow.procurement.internal.repository.ProcurementReportRepository;
import com.stockflow.procurement.internal.repository.PurchaseOrderSearchCriteria;
import com.stockflow.procurement.internal.repository.PurchaseOrderSearchRepository;
import com.stockflow.procurement.internal.repository.SupplierSpendCriteria;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.Purchasability;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The only implementation of {@link ProcurementService}, and the module's transaction boundary.
 *
 * <p>Package-private class, public interface: Spring injects it by the interface, so nobody can
 * bypass the port by autowiring the concrete class.</p>
 *
 * <p>A new line takes only an active SKU (kltn-docs 02 BR-01, SCRUM-461): checked when a standard order is
 * created, when its draft is edited, and when it is submitted and approved, because a product can be
 * discontinued in between. Once the order is confirmed it is not asked again: goods on their way are received
 * whatever happened to the product (02, edge cases), and a subcontract order, which is created for a
 * production order that is already running, is not asked at all.</p>
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
    private final SupplierItemRepository supplierItems;

    ProcurementServiceImpl(PurchaseOrderRepository purchaseOrders, PurchaseOrderSearchRepository search,
                           ProcurementReportRepository reports, Clock clock, SupplierRepository suppliers,
                           ApplicationEventPublisher events, NotificationService notifications,
                           PoDeliveryDecisionRepository deliveryDecisions, PoCommunicationProfile communication,
                           ProductService products, InventoryService inventory,
                           InventoryControlService inventoryControl, WarehouseService warehouses,
                           CurrentUserProvider users, SupplierItemRepository supplierItems) {
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
        this.supplierItems = supplierItems;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each line's SKU must have an inventory item (BR-01, plan Q4): the line orders the item, and
     * its unit of measure is the item's. The line must also follow the supplier's catalog (SCRUM-78,
     * BR-PO-001): the supplier lists the SKU, the quantity meets its MOQ in whole packs, and a line
     * without a price takes the catalog price; see {@link #checkLines}. The order's lead time is its
     * slowest line's, and a missing delivery date is today plus that lead time, not the supplier's.</p>
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
        requireReceivingWarehouse(command.warehouseId());
        Currency currency;
        try {
            currency = Currency.getInstance(command.currency());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unsupported purchase order currency");
        }
        String note = note(command.note());
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A purchase order must have at least one line");
        }
        CheckedLines checked = checkLines(command.lines().stream()
                .map(c -> c == null ? null : new LineInput(c.sku(), c.description(), c.quantityOrdered(), c.unitPrice(),
                        c.taxRate()))
                .toList(), currency, command.supplierId(), supplier.leadTimeDays());
        List<PoLine> lines = toLines(checked.lines());

        // Purchasing calendar dates use the same Vietnam zone as supplier delivery KPIs.
        LocalDate today = BusinessCalendar.date(clock.instant());
        LocalDate expectedAt = command.expectedAt() == null ? today.plusDays(checked.leadTimeDays())
                : command.expectedAt();
        PurchaseOrder order = PurchaseOrder.draft(purchaseOrders.nextPoNumber(today), command.supplierId(),
                command.warehouseId(), currency, lines, today, expectedAt,
                note, supplier.paymentTermDays(), checked.leadTimeDays());

        boolean possibleDuplicate = isPossibleDuplicate(order);
        purchaseOrders.save(order);
        purchaseOrders.recordEvent(order.id(), null, "CREATED", actor(), null, PurchaseOrderStatus.DRAFT, null);
        return summaryOf(order, possibleDuplicate);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PurchaseOrderSummary> findById(UUID purchaseOrderId) {
        Optional<PurchaseOrderSummary> found = search.summary(new PurchaseOrderId(purchaseOrderId), false);
        found.ifPresent(order -> WarehouseScope.requireWarehouse(order.warehouseId()));
        return found;
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

    // ------------------------------------------------------------------ editing a draft (SCRUM-462)

    /**
     * {@inheritDoc}
     *
     * <p>The draft is locked, then checked the way {@link #createPurchaseOrder} checks a new one:
     * the receiving warehouse exists, is the caller's (BR-SEC-002) and is ACTIVE; each SKU has an
     * inventory item (BR-01) and every line, kept or new, follows the supplier's catalog as it is now
     * (BR-PO-001), so a MOQ raised since the draft was written applies to it. The lead time is
     * recomputed from the lines as edited; the delivery date is the request's, which the edit requires.
     * The caller must be allowed both the old warehouse and the new one. What changed goes on the
     * order's timeline as REVISED; the revision history is untouched until the draft is submitted
     * again.</p>
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary reviseDraft(UUID purchaseOrderId, RevisePurchaseOrderCommand command) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        if (order.status() != PurchaseOrderStatus.DRAFT || order.type() != PurchaseOrder.Type.STANDARD) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_EDITABLE,
                    "Purchase order %s is %s %s".formatted(order.poNumber(), order.status(), order.type()));
        }
        if (command.version() != order.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Purchase order " + order.poNumber() + " changed since it was read; reload it");
        }
        requireReceivingWarehouse(command.warehouseId());
        String note = note(command.note());
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A purchase order must have at least one line");
        }
        CheckedLines checked = checkLines(command.lines().stream()
                .map(l -> l == null ? null : new LineInput(l.sku(), l.description(), l.quantityOrdered(),
                        l.unitPrice(), l.taxRate()))
                .toList(), order.currency(), order.supplierId(), supplierLeadTimeDays(order.supplierId()));
        List<PurchaseOrder.DraftLine> wanted = new ArrayList<>();
        for (int i = 0; i < checked.lines().size(); i++) {
            CheckedLine line = checked.lines().get(i);
            wanted.add(new PurchaseOrder.DraftLine(command.lines().get(i).lineId(), line.inventoryItemId(), line.sku(),
                    line.uom(), line.description(), line.quantity(), line.unitPrice(), line.taxRate()));
        }

        Map<String, String> before = timelineFacts(order);
        order.reviseDraft(command.warehouseId(), command.expectedAt(), note, wanted,
                purchaseOrders.nextLineNo(order.id()), checked.leadTimeDays());
        boolean possibleDuplicate = isPossibleDuplicate(order);
        purchaseOrders.save(order);
        purchaseOrders.recordEvent(order.id(), null, "REVISED", actor(), PurchaseOrderStatus.DRAFT,
                PurchaseOrderStatus.DRAFT, null, changes(before, timelineFacts(order)));
        return summaryOf(order, possibleDuplicate);
    }

    /** What the timeline says about a draft edit: the facts that changed, before and after. */
    private static Map<String, String> timelineFacts(PurchaseOrder order) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("warehouseId", order.warehouseId().toString());
        facts.put("expectedAt", String.valueOf(order.expectedAt()));
        facts.put("lineCount", String.valueOf(order.lines().size()));
        facts.put("totalAmount", order.totalAmount().amount().toPlainString());
        facts.put("lines", order.lines().stream()
                .map(l -> l.sku().code() + "x" + l.quantityOrdered() + "@" + l.unitPrice().amount().toPlainString())
                .reduce((a, b) -> a + "," + b).orElse(""));
        return facts;
    }

    private static Map<String, String> changes(Map<String, String> before, Map<String, String> after) {
        Map<String, String> changed = new LinkedHashMap<>();
        after.forEach((key, value) -> {
            if (!value.equals(before.get(key))) {
                changed.put(key + "Before", truncate(before.get(key)));
                changed.put(key + "After", truncate(value));
            }
        });
        return changed;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 1000) + "…";
    }

    /**
     * SCRUM-390: the warehouse the goods are received into exists (404), is one the caller works for
     * (BR-SEC-002, 403), and is ACTIVE (409 WAREHOUSE_INACTIVE).
     */
    private void requireReceivingWarehouse(UUID warehouseId) {
        var warehouse = warehouses.findWarehouse(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND,
                        "No warehouse with id " + warehouseId));
        WarehouseScope.requireWarehouse(warehouseId);
        if (!warehouse.active()) {
            throw new BusinessException(ErrorCode.WAREHOUSE_INACTIVE, "Warehouse " + warehouse.prefix() + " is not active");
        }
    }

    private static String note(String note) {
        if (note != null && note.length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "note must be at most 2000 characters");
        }
        return note == null || note.isBlank() ? null : note.trim();
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
        requireLinesPurchasable(order);
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
        if (order.status() == PurchaseOrderStatus.PENDING_APPROVAL) {
            requireLinesPurchasable(order);
        }
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
        PurchaseOrderSummary order = search.summary(new PurchaseOrderId(id), false)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        WarehouseScope.requireWarehouse(order.warehouseId());
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
        refuseWarehouseBound();
        return reports.statusDashboard();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SupplierSpendSummary> supplierSpend(SupplierSpendReportQuery query) {
        refuseWarehouseBound();
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

    /**
     * The reports add up every warehouse. Warehouse staff read purchase orders to prepare receiving
     * (kltn-docs 02 §2), not the company's spend, so a warehouse-bound caller is refused rather than
     * shown totals that include warehouses they are not assigned to (BR-SEC-002).
     */
    private static void refuseWarehouseBound() {
        if (WarehouseScope.restriction().isPresent()) {
            throw new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE,
                    "Purchasing reports cover every warehouse");
        }
    }

    /** Locked, and only within the caller's warehouses when they are warehouse-bound (BR-SEC-002). */
    private PurchaseOrder loadForUpdate(UUID purchaseOrderId) {
        PurchaseOrder order = purchaseOrders.findByIdForUpdate(new PurchaseOrderId(purchaseOrderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND,
                        "No purchase order with id " + purchaseOrderId));
        WarehouseScope.requireWarehouse(order.warehouseId());
        return order;
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
                .filter(other -> !other.id().equals(order.id()))
                .anyMatch(other -> other.lines().stream().map(PoLine::inventoryItemId).anyMatch(items::contains));
    }

    private static List<PoLine> toLines(List<CheckedLine> checked) {
        List<PoLine> lines = new ArrayList<>();
        for (CheckedLine c : checked) {
            lines.add(PurchaseOrder.line(lines.size() + 1, c.inventoryItemId(), c.sku(), c.uom(), c.description(),
                    c.quantity(), c.unitPrice(), c.taxRate()));
        }
        return lines;
    }

    /**
     * The supplier's own lead time for a draft edit, which has the order but not the supplier: the catalog's
     * slice of the supplier row is one cheap read, where {@link SupplierDetails} would also load its contact.
     * Create already holds the supplier and uses its details. Both agree on a NULL column, which means the
     * system default ({@link SupplierItem#effectiveLeadTimeDays}, {@code SupplierJpaEntity.getLeadTimeDays}).
     */
    private Integer supplierLeadTimeDays(UUID supplierId) {
        return supplierItems.findSupplier(supplierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "No supplier with id " + supplierId))
                .leadTimeDays();
    }

    /**
     * The lines of a standard order that is being submitted or approved: a SKU discontinued or suspended since
     * the line was written is refused on its own line ({@code lines[i].sku}), and the buyer removes the line.
     * A subcontract order is never asked (BR-10).
     */
    private void requireLinesPurchasable(PurchaseOrder order) {
        if (order.type() != PurchaseOrder.Type.STANDARD) {
            return;
        }
        requirePurchasable(order.lines().stream().map(line -> line.sku().code()).toList());
    }

    /**
     * kltn-docs 02 BR-01, 01 BR-04: only an active SKU goes on a new line. One call for the whole request,
     * and one refusal naming every line at fault, each with why ({@code PRODUCT_INACTIVE},
     * {@code PRODUCT_DISCONTINUED}, {@code PRODUCT_NOT_APPROVED}, {@code VARIANT_NOT_ACTIVE}) so the form
     * can put it next to the SKU.
     */
    private void requirePurchasable(List<String> skus) {
        Map<String, Purchasability> verdicts = products.purchasability(skus);
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < skus.size(); i++) {
            Purchasability verdict = verdicts.get(skus.get(i));
            if (verdict != null && !verdict.isPurchasable()) {
                errors.add(new FieldError("lines[" + i + "].sku", "SKU " + skus.get(i) + " is not active for purchasing",
                        verdict.name()));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.SKU_NOT_PURCHASABLE,
                    errors.size() + " line(s) hold a SKU that is not active for purchasing; remove them", errors);
        }
    }

    /** One requested line, as created or as edited; {@code unitPrice} null takes the catalog price. */
    private record LineInput(String sku, String description, int quantity, BigDecimal unitPrice, BigDecimal taxRate) {
    }

    /** A requested line once its SKU is resolved to an inventory item (BR-01), before the catalog is asked. */
    private record ResolvedLine(UUID inventoryItemId, Sku sku, String uom, String description, LineInput input) {
    }

    /** A requested line once its SKU is resolved, its catalog terms met and its amounts checked. */
    private record CheckedLine(UUID inventoryItemId, Sku sku, String uom, String description, int quantity,
                               Money unitPrice, BigDecimal taxRate) {
    }

    /** The lines as they will be written, and the order's lead time: the slowest line's. */
    private record CheckedLines(List<CheckedLine> lines, int leadTimeDays) {
    }

    /**
     * The single choke point for the lines of a standard order, on create and on draft edit alike; a
     * subcontract order never comes here (BR-10). In this order:
     * <ol>
     *   <li>each SKU resolves to an inventory item, once per order, with a description;</li>
     *   <li>the supplier's catalog rows for all of them, in one query, {@code FOR SHARE} so a concurrent
     *       catalog delete waits for this order instead of stranding its line;</li>
     *   <li>the catalog terms (BR-PO-001), every line at fault in one 409 {@code SUPPLIER_TERMS_VIOLATED}:
     *       an unlisted SKU on {@code lines[i].sku} and nothing else for that line, since there are no terms
     *       to hold it to; the MOQ and whole packs on {@code lines[i].quantityOrdered}; a missing price
     *       takes the catalog's when the currencies match, and is required on {@code lines[i].unitPrice}
     *       when they do not. A typed price wins: it is a negotiated one;</li>
     *   <li>the amounts, only now that every price is known, held to {@link PurchasePrices} like the
     *       catalog's own;</li>
     *   <li>purchasability (BR-01), one call for the whole order.</li>
     * </ol>
     * The lead time is the largest {@link SupplierItem#effectiveLeadTimeDays} among the lines. A draft is
     * checked against the catalog when it is written; a catalog edit afterwards reaches it at its next edit.
     */
    private CheckedLines checkLines(List<LineInput> inputs, Currency currency, UUID supplierId,
                                    Integer supplierLeadTimeDays) {
        // PurchasePrices alone would accept a whole amount in a pseudo-currency such as XXX.
        if (currency.getDefaultFractionDigits() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unsupported purchase order currency");
        }
        List<ResolvedLine> resolved = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (var c : inputs) {
            if (c == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A purchase order line is missing");
            }
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
            resolved.add(new ResolvedLine(item.inventoryItemId(), sku, uom, description, c));
        }

        Map<UUID, SupplierItem> listed = supplierItems.listedForShare(supplierId,
                resolved.stream().map(ResolvedLine::inventoryItemId).toList());
        List<FieldError> errors = new ArrayList<>();
        List<BigDecimal> prices = new ArrayList<>();
        int linesAtFault = 0;
        int leadTimeDays = 0;
        for (int i = 0; i < resolved.size(); i++) {
            ResolvedLine line = resolved.get(i);
            SupplierItem item = listed.get(line.inventoryItemId());
            if (item == null) {
                errors.add(new FieldError("lines[" + i + "].sku",
                        "SKU " + line.sku().code() + " is not listed by this supplier",
                        ErrorCode.SUPPLIER_ITEM_NOT_LISTED.name()));
                linesAtFault++;
                prices.add(null);
                continue;
            }
            int before = errors.size();
            int quantity = line.input().quantity();
            for (var violation : item.orderQuantityViolations(quantity)) {
                ErrorCode code = switch (violation) {
                    case BELOW_MOQ -> ErrorCode.PO_LINE_BELOW_MOQ;
                    case NOT_PACK_MULTIPLE -> ErrorCode.PO_LINE_NOT_PACK_MULTIPLE;
                };
                errors.add(new FieldError("lines[" + i + "].quantityOrdered", "Quantity " + quantity
                        + " does not meet the supplier's minimum " + item.terms().moq() + " in packs of "
                        + item.terms().packSize(), code.name()));
            }
            BigDecimal price = line.input().unitPrice();
            if (price == null) {
                Money catalogPrice = item.terms().price();
                if (catalogPrice.currency().equals(currency)) {
                    price = catalogPrice.amount();
                } else {
                    errors.add(new FieldError("lines[" + i + "].unitPrice", "The catalog price is in "
                            + catalogPrice.currency().getCurrencyCode() + "; enter a price in "
                            + currency.getCurrencyCode(), ErrorCode.PO_LINE_PRICE_REQUIRED.name()));
                }
            }
            if (errors.size() > before) {
                linesAtFault++;
            }
            prices.add(price);
            leadTimeDays = Math.max(leadTimeDays, item.effectiveLeadTimeDays(supplierLeadTimeDays));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.SUPPLIER_TERMS_VIOLATED,
                    linesAtFault + " line(s) do not meet the supplier's catalog terms", errors);
        }

        BigDecimal total = BigDecimal.ZERO;
        List<CheckedLine> lines = new ArrayList<>();
        for (int i = 0; i < resolved.size(); i++) {
            ResolvedLine line = resolved.get(i);
            LineInput c = line.input();
            BigDecimal price = prices.get(i);
            if (c.quantity() <= 0 || c.quantity() > 1_000_000 || !PurchasePrices.acceptable(price, currency)
                    || c.taxRate() != null && (c.taxRate().signum() < 0
                    || c.taxRate().compareTo(BigDecimal.valueOf(100)) > 0)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Invalid purchase order quantity, unit price or tax rate");
            }
            total = total.add(price.multiply(BigDecimal.valueOf(c.quantity())));
            lines.add(new CheckedLine(line.inventoryItemId(), line.sku(), line.uom(), line.description(),
                    c.quantity(), new Money(price, currency), c.taxRate()));
        }
        if (total.compareTo(PurchasePrices.MAX) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Purchase order total exceeds the supported limit");
        }
        requirePurchasable(lines.stream().map(line -> line.sku().code()).toList());
        return new CheckedLines(lines, leadTimeDays);
    }
}
