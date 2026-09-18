package com.stockflow.procurement.internal.service;

import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.POLineSummary;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderStatusCount;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.ReceiveGoodsCommand;
import com.stockflow.procurement.api.RecordSupplierConfirmationCommand;
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
import com.stockflow.procurement.internal.repository.SupplierJpaRepository;
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
    private final SupplierJpaRepository suppliers;
    private final ApplicationEventPublisher events;

    ProcurementServiceImpl(PurchaseOrderRepository purchaseOrders, PurchaseOrderSearchRepository search,
                           ProcurementReportRepository reports, Clock clock, SupplierJpaRepository suppliers,
                           ApplicationEventPublisher events) {
        this.purchaseOrders = purchaseOrders;
        this.search = search;
        this.reports = reports;
        this.clock = clock;
        this.suppliers = suppliers;
        this.events = events;
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
        SupplierStatus supplierStatus = purchaseOrders.supplierStatus(command.supplierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "No supplier with id " + command.supplierId()));
        if (supplierStatus != SupplierStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE,
                    "Supplier " + command.supplierId() + " is " + supplierStatus);
        }

        Currency currency = Currency.getInstance(command.currency());
        List<PoLine> lines = toLines(command.lines(), currency);

        LocalDate today = clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        String poNumber = purchaseOrders.nextPoNumber(today);

        var supplier = suppliers.findById(command.supplierId()).orElseThrow(() ->
                new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND, "No supplier with id " + command.supplierId()));
        LocalDate expectedAt = command.expectedAt() == null ? today.plusDays(supplier.getLeadTimeDays()) : command.expectedAt();
        PurchaseOrder order = PurchaseOrder.draft(poNumber, command.supplierId(), currency, lines,
                expectedAt, supplier.getPaymentTermDays(), supplier.getLeadTimeDays());

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
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        if (order.status() == PurchaseOrderStatus.SENT) {
            return toSummary(order, false);
        }
        order.send(clock.instant());
        PurchaseOrder saved = purchaseOrders.save(order);
        var supplier = suppliers.findById(saved.supplierId()).orElseThrow(() ->
                new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND, "No supplier with id " + saved.supplierId()));
        String recipient = supplier.getCommunicationChannel() == SupplierCommunicationChannel.EMAIL
                ? supplier.getEmail() : supplier.getApiEndpoint();
        events.publishEvent(new PurchaseOrderSent(saved.id().value(), saved.poNumber(), saved.supplierId(),
                supplier.getCommunicationChannel().name(), recipient, saved.totalAmount().amount(),
                saved.currency().getCurrencyCode(), saved.expectedAt(), saved.paymentTermDays()));
        return toSummary(saved, false);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary recordSupplierConfirmation(UUID purchaseOrderId,
                                                            RecordSupplierConfirmationCommand command) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        order.recordSupplierConfirmation(SupplierConfirmationStatus.valueOf(command.status()),
                command.supplierReference(), command.note(), clock.instant());
        return toSummary(purchaseOrders.save(order), false);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary cancel(UUID purchaseOrderId, String reason) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        order.cancel(reason, clock.instant());
        return toSummary(purchaseOrders.save(order), false);
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
        var criteria = new SupplierSpendCriteria(query.supplierId(), query.expectedAtFrom(), query.expectedAtTo());
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

    private static List<PoLine> toLines(List<CreatePOLineCommand> commands, Currency currency) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("A purchase order must have at least one line");
        }
        return commands.stream()
                .map(c -> new PoLine(Identifiers.newId(), new Sku(c.sku()), c.description(),
                        c.quantityOrdered(), 0, new Money(c.unitPrice(), currency)))
                .toList();
    }

    private static PurchaseOrderSummary toSummary(PurchaseOrder order, boolean possibleDuplicate) {
        List<POLineSummary> lines = order.lines().stream()
                .map(line -> new POLineSummary(line.id(), line.sku().code(), line.description(),
                        line.quantityOrdered(), line.quantityReceived(), line.unitPrice().amount()))
                .toList();
        return new PurchaseOrderSummary(
                order.id().value(),
                order.poNumber(),
                order.supplierId(),
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
