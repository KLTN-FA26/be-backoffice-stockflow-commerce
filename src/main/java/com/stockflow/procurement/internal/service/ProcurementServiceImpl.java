package com.stockflow.procurement.internal.service;

import com.stockflow.notification.api.NotificationService;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.product.api.ProductService;

import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.POLineSummary;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderStatusCount;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.ReceiveGoodsCommand;
import com.stockflow.procurement.api.RecordSupplierConfirmationCommand;
import com.stockflow.procurement.api.SendPurchaseOrderCommand;
import com.stockflow.procurement.api.RecoverPurchaseOrderDeliveryCommand;
import com.stockflow.procurement.api.PurchaseOrderDeliveryDecision;
import com.stockflow.procurement.internal.repository.PoDeliveryDecisionRepository;
import com.stockflow.procurement.api.SupplierSpendReportQuery;
import com.stockflow.procurement.api.SupplierSpendSummary;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.domain.SupplierConfirmationStatus;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.procurement.internal.repository.ProcurementReportRepository;
import com.stockflow.procurement.internal.repository.PurchaseOrderSearchCriteria;
import com.stockflow.procurement.internal.repository.PurchaseOrderSearchRepository;
import com.stockflow.procurement.internal.repository.SupplierSpendCriteria;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The only implementation of {@link ProcurementService}, and the module's transaction boundary.
 *
 * <p>Package-private class, public interface: Spring injects it by the interface, so nobody can
 * bypass the port by autowiring the concrete class.</p>
 */
@Service
@Transactional
class ProcurementServiceImpl implements ProcurementService {

    private static final SortWhitelist SORT =
            SortWhitelist.of("poNumber", "expectedAt", "createdAt", "lastModifiedAt", "status")
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

    ProcurementServiceImpl(PurchaseOrderRepository purchaseOrders, PurchaseOrderSearchRepository search,
                           ProcurementReportRepository reports, Clock clock, SupplierRepository suppliers,
                           ApplicationEventPublisher events, NotificationService notifications, PoDeliveryDecisionRepository deliveryDecisions,
                           PoCommunicationProfile communication, ProductService products) {
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
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>BR-PO-001 (MOQ) and BR-PO-002 (approval limits) are not enforced</b> — {@code
     * SupplierJpaEntity} has no per-SKU MOQ column and there is no approver-limit data model in
     * this codebase yet. Flagged here rather than silently skipped, same treatment SCRUM-57 gave
     * BR-PRD-001's fuller form.</p>
     */
    @Override
    public PurchaseOrderSummary createPurchaseOrder(CreatePurchaseOrderCommand command) {
        var supplier = suppliers.findByIdForUpdate(command.supplierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "No supplier with id " + command.supplierId())).details();
        SupplierStatus supplierStatus = supplier.status();
        if (supplierStatus != SupplierStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE,
                    "Supplier " + command.supplierId() + " is " + supplierStatus);
        }

        Currency currency;
        try {
            currency = Currency.getInstance(command.currency());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unsupported purchase order currency");
        }
        List<PoLine> lines = toLines(command.lines(), currency);

        // Purchasing calendar dates must use the same Vietnam zone as supplier delivery KPIs.
        LocalDate today = BusinessCalendar.date(clock.instant());
        String poNumber = purchaseOrders.nextPoNumber(today);

        LocalDate expectedAt = command.expectedAt() == null ? today.plusDays(supplier.leadTimeDays()) : command.expectedAt();
        PurchaseOrder order = PurchaseOrder.draft(poNumber, command.supplierId(), currency, lines,
                expectedAt, supplier.paymentTermDays(), supplier.leadTimeDays());

        boolean possibleDuplicate = isPossibleDuplicate(order);
        PurchaseOrder saved = purchaseOrders.save(order);
        return toSummary(saved, possibleDuplicate);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PurchaseOrderSummary> findById(UUID purchaseOrderId) {
        return purchaseOrders.findById(new PurchaseOrderId(purchaseOrderId))
                .map(order -> toSummary(order, false));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderSummary> list(ListPurchaseOrdersQuery query) {
        var pageable = Pages.of(query.page(), query.size(), SORT.parse(query.sort()));
        // null means "no filter" to Specs.in (matches everything); an empty list means "match
        // nothing" - converting an absent filter to List.of() here would silently turn every
        // unfiltered list call into an empty result. See Specs.in's own javadoc.
        var statuses = query.statuses() == null ? null
                : query.statuses().stream().map(PurchaseOrderStatus::valueOf).toList();
        var criteria = new PurchaseOrderSearchCriteria(query.supplierId(), statuses);
        return Pages.toResponse(search.search(criteria, pageable));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary approve(UUID purchaseOrderId) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        order.approve(clock.instant());
        return toSummary(purchaseOrders.save(order), false);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary send(UUID purchaseOrderId) {
        return send(purchaseOrderId, new SendPurchaseOrderCommand(null, null));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary send(UUID purchaseOrderId, SendPurchaseOrderCommand command) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        LocalDate previousDate = order.expectedAt();
        var sendTime = clock.instant();
        order.confirmDeliveryDate(BusinessCalendar.date(sendTime), command.expectedAt(), command.reason());
        order.send(sendTime);
        PurchaseOrder saved = purchaseOrders.save(order);
        publishDelivery(saved, false, previousDate, command.reason(), false, false);
        return toSummary(saved, false);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary recoverDelivery(UUID purchaseOrderId, RecoverPurchaseOrderDeliveryCommand command) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        order.requireDeliveryRecovery(BusinessCalendar.date(clock.instant()), command.reason(),
                command.reconciled(), command.acknowledgePastDue());
        publishDelivery(order, true, order.expectedAt(), command.reason(), command.reconciled(), command.acknowledgePastDue());
        return toSummary(order, false);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderDeliveryDecision> deliveryDecisions(UUID id, int page, int size) {
        purchaseOrders.findById(new PurchaseOrderId(id)).orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        return deliveryDecisions.list(id, page, size);
    }

    private void publishDelivery(PurchaseOrder saved, boolean recovery, LocalDate previousDate, String reason,
            boolean reconciled, boolean acknowledgePastDue) {
        var supplier = suppliers.findById(saved.supplierId()).orElseThrow(() ->
                new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND, "No supplier with id " + saved.supplierId())).details();
        String recipient = supplier.communicationChannel() == SupplierCommunicationChannel.EMAIL
                ? supplier.email() : supplier.apiEndpoint();
        if (supplier.status() != SupplierStatus.ACTIVE || recipient == null || recipient.isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT, "An active supplier with a delivery contact is required");
        }
        notifications.validateSupplierDelivery(supplier.communicationChannel().name(), recipient);
        int generation = notifications.prepareSupplierDelivery(saved.id().value(), recovery);
        deliveryDecisions.record(saved.id().value(), generation, previousDate, saved.expectedAt(), reason,
                reconciled, acknowledgePastDue, supplier.communicationChannel().name(), recipient);
        var previous = deliveryDecisions.latestSnapshot(saved.id().value());
        var buyer = previous.map(PurchaseOrderSent::buyer).orElseGet(communication::requireBuyer);
        var lines = previous.map(PurchaseOrderSent::lines).orElseGet(() -> saved.lines().stream()
                .map(line -> new PurchaseOrderSent.Line(line.sku().code(), description(line),
                        line.quantityOrdered(), line.unitPrice().amount())).toList());
        var event = new PurchaseOrderSent(saved.id().value(), saved.poNumber(), saved.supplierId(),
                supplier.communicationChannel().name(), recipient, saved.totalAmount().amount(),
                saved.currency().getCurrencyCode(), saved.expectedAt(), saved.paymentTermDays(),
                lines, generation, buyer, false, null);
        deliveryDecisions.snapshot(event);
        events.publishEvent(event);
    }

    private String description(PoLine line) {
        if (line.description() != null && !line.description().isBlank()) return line.description().trim();
        return products.nameForSku(line.sku().code()).filter(name -> !name.isBlank())
                .orElseThrow(() -> new BusinessException(ErrorCode.PO_LINE_DESCRIPTION_REQUIRED,
                        "Missing product description for SKU " + line.sku().code()));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary recordSupplierConfirmation(UUID purchaseOrderId,
                                                            RecordSupplierConfirmationCommand command) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        SupplierConfirmationStatus response;
        try { response = SupplierConfirmationStatus.valueOf(command.status()); }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(ErrorCode.PO_SUPPLIER_RESPONSE_INVALID);
        }
        order.recordSupplierConfirmation(response,
                command.supplierReference(), command.note(), clock.instant());
        notifications.suppressSupplierDelivery(purchaseOrderId);
        return toSummary(purchaseOrders.save(order), false);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary cancel(UUID purchaseOrderId, String reason) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        boolean wasSent = order.status() == PurchaseOrderStatus.SENT;
        order.cancel(reason, clock.instant());
        notifications.suppressSupplierDelivery(purchaseOrderId);
        PurchaseOrder saved = purchaseOrders.save(order);
        if (wasSent) {
            notifications.prepareSupplierCancellation(purchaseOrderId);
            var previous = deliveryDecisions.latestSnapshot(purchaseOrderId);
            var supplier = suppliers.findById(order.supplierId()).orElseThrow(() ->
                    new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND)).details();
            String channel = previous.map(PurchaseOrderSent::channel).orElse(supplier.communicationChannel().name());
            String recipient = previous.map(PurchaseOrderSent::recipient).orElse(
                    supplier.communicationChannel() == SupplierCommunicationChannel.EMAIL ? supplier.email() : supplier.apiEndpoint());
            // Cancellation must reach the original destination even if the supplier master has changed.
            events.publishEvent(new PurchaseOrderSent(purchaseOrderId, order.poNumber(), order.supplierId(),
                    channel, recipient, order.totalAmount().amount(), order.currency().getCurrencyCode(),
                    order.expectedAt(), order.paymentTermDays(), previous.map(PurchaseOrderSent::lines).orElse(List.of()),
                    0, previous.map(PurchaseOrderSent::buyer).orElse(null), true, reason));
        }
        return toSummary(saved, false);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary receiveGoods(UUID purchaseOrderId, ReceiveGoodsCommand command) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        Map<UUID, Integer> receivedByLineId = command.lines().stream()
                .collect(Collectors.toMap(line -> line.lineId(), line -> line.quantity()));
        order.receiveGoods(receivedByLineId, clock.instant());
        return toSummary(purchaseOrders.save(order), false);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary closeShort(UUID purchaseOrderId, String reason) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        order.closeShort(reason, clock.instant());
        return toSummary(purchaseOrders.save(order), false);
    }

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
                query.currency() == null || query.currency().isBlank()
                        ? null : query.currency().trim().toUpperCase(java.util.Locale.ROOT),
                query.expectedAtFrom(), query.expectedAtTo());
        return Pages.toResponse(reports.supplierSpend(criteria, pageable));
    }

    private PurchaseOrder loadForUpdate(UUID purchaseOrderId) {
        return purchaseOrders.findByIdForUpdate(new PurchaseOrderId(purchaseOrderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND,
                        "No purchase order with id " + purchaseOrderId));
    }

    /**
     * BR-PO-003: "two POs to the same supplier, same SKU, same delivery date are flagged as
     * possible duplicates" — a warning surfaced on the create response, not a rejection.
     */
    private boolean isPossibleDuplicate(PurchaseOrder order) {
        if (order.expectedAt() == null) {
            return false;
        }
        List<Sku> skus = order.lines().stream().map(PoLine::sku).toList();
        return purchaseOrders.findOpenBySupplierAndExpectedAt(order.supplierId(), order.expectedAt())
                .stream()
                .anyMatch(other -> other.lines().stream().map(PoLine::sku).anyMatch(skus::contains));
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
            if (c == null || c.quantityOrdered() <= 0 || c.quantityOrdered() > 1_000_000
                    || c.unitPrice() == null || c.unitPrice().signum() < 0
                    || c.unitPrice().compareTo(new BigDecimal("9999999999999999.99")) > 0
                    || c.unitPrice().stripTrailingZeros().scale() > Math.min(2, currency.getDefaultFractionDigits())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid purchase order quantity or unit price");
            }
            total = total.add(c.unitPrice().multiply(BigDecimal.valueOf(c.quantityOrdered())));
        }
        if (total.compareTo(new BigDecimal("9999999999999999.99")) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Purchase order total exceeds the supported limit");
        }
        return commands.stream().map(c -> {
            var sku = new Sku(c.sku());
            String description = c.description() != null && !c.description().isBlank()
                    ? c.description().trim()
                    : products.nameForSku(sku.code()).filter(name -> !name.isBlank()).map(String::trim)
                            .orElseThrow(() -> new BusinessException(ErrorCode.PO_LINE_DESCRIPTION_REQUIRED,
                                    "Missing product description for SKU " + sku.code()));
            if (description.length() > 300) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Product description must not exceed 300 characters");
            }
            return new PoLine(Identifiers.newId(), sku, description, c.quantityOrdered(), 0,
                    new Money(c.unitPrice(), currency));
        }).toList();
    }

    private PurchaseOrderSummary toSummary(PurchaseOrder order, boolean possibleDuplicate) {
        var supplier = suppliers.findById(order.supplierId()).orElseThrow(() ->
                new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND)).details();
        List<POLineSummary> lines = order.lines().stream()
                .map(line -> new POLineSummary(line.id(), line.sku().code(), line.description(),
                        line.quantityOrdered(), line.quantityReceived(), line.unitPrice().amount()))
                .toList();
        return new PurchaseOrderSummary(
                order.id().value(),
                order.poNumber(),
                order.supplierId(),
                supplier.code(), supplier.name(),
                order.status().name(),
                order.currency().getCurrencyCode(),
                order.totalAmount().amount(),
                order.expectedAt(),
                lines,
                order.createdAt(),
                order.createdBy(),
                order.lastModifiedAt(),
                order.lastModifiedBy(),
                possibleDuplicate,
                order.cancellationReason(),
                order.closeShortReason(), order.paymentTermDays(), order.leadTimeDays(), order.sentAt(),
                order.supplierConfirmationStatus().name(), order.supplierRespondedAt(),
                order.supplierReference(), order.supplierResponseNote());
    }
}
