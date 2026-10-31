package com.stockflow.procurement.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.notification.api.AmendmentDeliveryStatus;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.RecordSupplierConfirmationCommand;
import com.stockflow.procurement.api.RecoverPurchaseOrderDeliveryCommand;
import com.stockflow.procurement.api.SendPurchaseOrderCommand;
import com.stockflow.procurement.api.SupplierSpendReportQuery;
import com.stockflow.procurement.internal.controller.dto.AmendPurchaseOrderRequest;
import com.stockflow.procurement.internal.controller.dto.CancelPurchaseOrderRequest;
import com.stockflow.procurement.internal.controller.dto.CloseShortRequest;
import com.stockflow.procurement.internal.controller.dto.CreatePurchaseOrderRequest;
import com.stockflow.procurement.internal.controller.dto.PendingAmendmentResponse;
import com.stockflow.procurement.internal.controller.dto.PurchaseOrderResponse;
import com.stockflow.procurement.internal.controller.dto.PurchaseOrderStatusCountResponse;
import com.stockflow.procurement.internal.controller.dto.RecoverPurchaseOrderDeliveryRequest;
import com.stockflow.procurement.internal.controller.dto.SendPurchaseOrderRequest;
import com.stockflow.procurement.internal.controller.dto.SupplierConfirmationRequest;
import com.stockflow.procurement.internal.controller.dto.SupplierSpendResponse;
import com.stockflow.procurement.internal.controller.dto.UpdatePurchaseOrderRequest;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.service.PurchaseOrderAmendments;
import com.stockflow.procurement.internal.service.PurchaseOrderHistory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP entry point for purchase orders (SCRUM-113/114/115/116/390/462): create, edit a draft, submit, approve or
 * reject, confirm (= send to the supplier), record the supplier's answer, cancel, close. Receiving is
 * {@code /api/v1/goods-receipts}.
 *
 * <p><b>Handler methods are public.</b> {@code @RequiresPermission} is applied by a Spring AOP
 * proxy, which advises a non-public method only when the generated proxy happens to land in the
 * same package and classloader — when that does not hold, the guard is skipped silently.
 */
@RestController
@RequestMapping("/api/v1/purchase-orders")
@Tag(name = "Purchase Orders", description = "Procurement purchase orders")
@PermissionResource(
        code = PurchaseOrderResources.PURCHASE_ORDERS,
        group = "Procurement",
        label = "Purchase Orders",
        route = "/admin/purchase-orders",
        apiPath = "/api/v1/purchase-orders",
        actions = {
            Action.VIEW_PAGE,
            Action.READ,
            Action.CREATE,
            Action.UPDATE,
            Action.APPROVE,
            Action.EXPORT
        })
class PurchaseOrderController {

    private final ProcurementService procurementService;
    private final PurchaseOrderWebMapper mapper;
    private final NotificationService notifications;
    private final Clock clock;
    private final com.stockflow.procurement.internal.service.SubcontractAmendments subcontractAmendments;
    private final com.stockflow.procurement.api.SubcontractPurchasing subcontracting;
    private final PurchaseOrderAmendments amendments;
    private final PurchaseOrderHistory history;

    PurchaseOrderController(
            ProcurementService procurementService,
            PurchaseOrderWebMapper mapper,
            NotificationService notifications,
            Clock clock,
            com.stockflow.procurement.internal.service.SubcontractAmendments subcontractAmendments,
            com.stockflow.procurement.api.SubcontractPurchasing subcontracting,
            PurchaseOrderAmendments amendments,
            PurchaseOrderHistory history) {
        this.procurementService = procurementService;
        this.mapper = mapper;
        this.notifications = notifications;
        this.clock = clock;
        this.subcontractAmendments = subcontractAmendments;
        this.subcontracting = subcontracting;
        this.amendments = amendments;
        this.history = history;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a purchase order")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.CREATE)
    public ApiResponse<PurchaseOrderResponse> create(
            @Valid @RequestBody CreatePurchaseOrderRequest request) {
        return ApiResponse.ok(
                toResponse(procurementService.createPurchaseOrder(mapper.toCommand(request))));
    }

    @PutMapping("/{purchaseOrderId}")
    @Operation(
            summary = "Edit a DRAFT standard purchase order: warehouse, delivery date, note, lines",
            description =
                    "Send the whole draft with the version you read. Lines not listed leave the order;"
                        + " a listed lineId keeps its SKU. 409 PURCHASE_ORDER_NOT_EDITABLE once it left"
                        + " DRAFT or for a SUBCONTRACT order; 409 OPTIMISTIC_LOCK when it changed since.")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> update(
            @PathVariable UUID purchaseOrderId, @Valid @RequestBody UpdatePurchaseOrderRequest request) {
        return ApiResponse.ok(
                toResponse(procurementService.reviseDraft(purchaseOrderId, mapper.toCommand(request))));
    }

    @GetMapping("/{purchaseOrderId}")
    @Operation(summary = "Look up one purchase order")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<PurchaseOrderResponse> findOne(@PathVariable UUID purchaseOrderId) {
        return procurementService
                .findById(purchaseOrderId)
                .map(this::toResponse)
                .map(ApiResponse::ok)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.PURCHASE_ORDER_NOT_FOUND,
                                        "No purchase order with id " + purchaseOrderId));
    }

    @GetMapping
    @Operation(summary = "List purchase orders, paginated and filterable")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<PageResponse<PurchaseOrderResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(required = false) String sort) {
        var query =
                new ListPurchaseOrdersQuery(
                        page,
                        size == null ? Pages.DEFAULT_PAGE_SIZE : size,
                        supplierId,
                        warehouseId,
                        status,
                        search,
                        sort);
        var result = procurementService.list(query);
        var statuses =
                notifications.purchaseOrderDeliveryStatuses(
                        result.items().stream()
                                .map(PurchaseOrderSummary::purchaseOrderId)
                                .toList());
        var cancellations =
                notifications.purchaseOrderCancellationStatuses(
                        result.items().stream()
                                .map(PurchaseOrderSummary::purchaseOrderId)
                                .toList());
        var ids = result.items().stream().map(PurchaseOrderSummary::purchaseOrderId).toList();
        var amendmentStatuses = notifications.purchaseOrderAmendmentStatuses(ids);
        var pending = pendingAmendments(ids);
        return ApiResponse.ok(
                result.map(po -> response(po, statuses, cancellations, amendmentStatuses, pending)));
    }

    @PostMapping("/{purchaseOrderId}/submission")
    @Operation(summary = "Submit a draft purchase order for approval; freezes it as a revision")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> submit(@PathVariable UUID purchaseOrderId,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(procurementService.submit(purchaseOrderId, user.userId())));
    }

    @PostMapping("/{purchaseOrderId}/approval")
    @Operation(summary = "Approve a submitted purchase order; the approver must not be who submitted it")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.APPROVE)
    public ApiResponse<PurchaseOrderResponse> approve(@PathVariable UUID purchaseOrderId,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(procurementService.approve(purchaseOrderId, user.userId())));
    }

    @PostMapping("/{purchaseOrderId}/rejection")
    @Operation(summary = "Send a submitted purchase order back to draft, with a reason")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.APPROVE)
    public ApiResponse<PurchaseOrderResponse> reject(@PathVariable UUID purchaseOrderId,
                                                     @AuthenticatedUser CurrentUser user,
                                                     @Valid @RequestBody CancelPurchaseOrderRequest request) {
        return ApiResponse.ok(toResponse(procurementService.reject(purchaseOrderId, user.userId(), request.reason())));
    }

    /**
     * BR-02, SCRUM-117: an APPROVED or CONFIRMED standard order changes through a revision approved again - within the
     * requester's own limit at once, otherwise by someone else. {@code lines} is every line kept.
     */
    @PostMapping("/{purchaseOrderId}/amendments")
    @Operation(
            summary = "Amend an approved or confirmed standard purchase order",
            description =
                    "Send the header as it should read and every line kept, with the version you read. Approved at"
                        + " once within your approval limit, otherwise it waits for someone else's decision. 409"
                        + " PO_AMENDMENT_NOT_ALLOWED while a goods receipt is in progress or once anything was"
                        + " received; 409 PO_AMENDMENT_ALREADY_PENDING while another waits; 400"
                        + " PO_AMENDMENT_NO_CHANGE.")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> amend(@PathVariable UUID purchaseOrderId,
                                                    @AuthenticatedUser CurrentUser user,
                                                    @Valid @RequestBody AmendPurchaseOrderRequest request) {
        var amendment = new PurchaseOrder.AmendmentRequest(request.expectedDate(), request.note(), request.reason(),
                request.lines().stream()
                        .map(line -> line == null ? null : new PurchaseOrder.AmendmentLine(line.lineId(),
                                line.quantityOrdered(), line.unitPrice(), line.taxRate(), line.description()))
                        .toList());
        return ApiResponse.ok(toResponse(
                amendments.request(purchaseOrderId, request.version(), amendment, user.userId())));
    }

    /**
     * The amendment waiting is approved by someone other than who asked for it, with the approval permission: a
     * standard order's amendment (SCRUM-117), or a SUBCONTRACT order's supplement for a resend after a QC shortfall
     * (BR-02, 03 §16 "PO supplement"). The two are stored differently, so the order's type picks the service.
     */
    @PostMapping("/{purchaseOrderId}/amendment/approval")
    @Operation(summary = "Approve the amendment waiting on an approved purchase order (four eyes)")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.APPROVE)
    public ApiResponse<PurchaseOrderResponse> approveAmendment(@PathVariable UUID purchaseOrderId,
                                                               @AuthenticatedUser CurrentUser user) {
        if (isSubcontract(purchaseOrderId)) {
            subcontractAmendments.approveAmendment(purchaseOrderId, user.userId());
            return ApiResponse.ok(toResponse(procurementService.findById(purchaseOrderId).orElseThrow()));
        }
        return ApiResponse.ok(toResponse(amendments.approve(purchaseOrderId, user.userId())));
    }

    @PostMapping("/{purchaseOrderId}/amendment/rejection")
    @Operation(summary = "Reject the amendment waiting on an approved purchase order, with a reason")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.APPROVE)
    public ApiResponse<PurchaseOrderResponse> rejectAmendment(@PathVariable UUID purchaseOrderId,
                                                              @AuthenticatedUser CurrentUser user,
                                                              @Valid @RequestBody CancelPurchaseOrderRequest request) {
        if (isSubcontract(purchaseOrderId)) {
            subcontractAmendments.rejectAmendment(purchaseOrderId, user.userId(), request.reason());
            return ApiResponse.ok(toResponse(procurementService.findById(purchaseOrderId).orElseThrow()));
        }
        return ApiResponse.ok(toResponse(amendments.reject(purchaseOrderId, user.userId(), request.reason())));
    }

    /** Anyone who may update the order withdraws its amendment, the requester included; a reason is required. */
    @PostMapping("/{purchaseOrderId}/amendment/withdrawal")
    @Operation(summary = "Withdraw the amendment waiting on an approved standard purchase order, with a reason")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> withdrawAmendment(@PathVariable UUID purchaseOrderId,
                                                                @AuthenticatedUser CurrentUser user,
                                                                @Valid @RequestBody CancelPurchaseOrderRequest request) {
        return ApiResponse.ok(toResponse(amendments.withdraw(purchaseOrderId, user.userId(), request.reason())));
    }

    /** Read within the caller's warehouses: another warehouse's order is refused here, before either service runs. */
    private boolean isSubcontract(UUID purchaseOrderId) {
        return "SUBCONTRACT".equals(procurementService.findById(purchaseOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND,
                        "No purchase order with id " + purchaseOrderId))
                .type());
    }

    /**
     * BR-PRD-17, 04c G10: what a SUBCONTRACT order owes once its production order ended - QC-passed units paid,
     * failed ones not, approved excess loss deducted, an agreed cancellation fee added. The basis of the invoice match.
     */
    @GetMapping("/{purchaseOrderId}/subcontract-settlement")
    @Operation(summary = "The settlement of a SUBCONTRACT purchase order (BR-PRD-17)")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<com.stockflow.procurement.api.SubcontractSettlement> subcontractSettlement(
            @PathVariable UUID purchaseOrderId) {
        return ApiResponse.ok(subcontracting.settlement(purchaseOrderId).orElseThrow(() ->
                new com.stockflow.common.error.NotFoundException("subcontract-settlement", purchaseOrderId)));
    }

    /** {@code /sending} is the old name, kept while the screens move to {@code /confirmation}. */
    @PostMapping({"/{purchaseOrderId}/confirmation", "/{purchaseOrderId}/sending"})
    @Operation(
            summary = "Confirm an approved PO: lock it and queue it for supplier delivery",
            description =
                    "Use the same Idempotency-Key on retries. A new request for an already CONFIRMED PO"
                        + " returns 409. Delivery is asynchronous; CONFIRMED does not mean supplier"
                        + " acceptance (see supplierConfirmationStatus).")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> confirm(
            @PathVariable UUID purchaseOrderId,
            @AuthenticatedUser CurrentUser user,
            @Valid @RequestBody(required = false) SendPurchaseOrderRequest request) {
        var command =
                request == null
                        ? new SendPurchaseOrderCommand(null, null)
                        : new SendPurchaseOrderCommand(request.expectedAt(), request.reason());
        return ApiResponse.ok(toResponse(procurementService.confirm(purchaseOrderId, user.userId(), command)));
    }

    @PostMapping("/{purchaseOrderId}/delivery-recovery")
    @Operation(
            summary =
                    "Recover a terminal failed delivery after reconciliation; never reset PO state",
            description =
                    "Requires APPROVE permission, a reason and reconciled=true. Reuse"
                        + " Idempotency-Key for HTTP retries. Original overdue dates require"
                        + " acknowledgePastDue=true.")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.APPROVE)
    public ApiResponse<PurchaseOrderResponse> recoverDelivery(
            @PathVariable UUID purchaseOrderId,
            @Valid @RequestBody RecoverPurchaseOrderDeliveryRequest request) {
        return ApiResponse.ok(
                toResponse(
                        procurementService.recoverDelivery(
                                purchaseOrderId,
                                new RecoverPurchaseOrderDeliveryCommand(
                                        request.reason(),
                                        request.reconciled(),
                                        request.acknowledgePastDue()))));
    }

    @PostMapping("/{purchaseOrderId}/supplier-confirmation")
    @Operation(summary = "Record a supplier's confirmation or rejection")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> recordSupplierConfirmation(
            @PathVariable UUID purchaseOrderId,
            @Valid @RequestBody SupplierConfirmationRequest request) {
        return ApiResponse.ok(
                toResponse(
                        procurementService.recordSupplierConfirmation(
                                purchaseOrderId,
                                new RecordSupplierConfirmationCommand(
                                        request.status(),
                                        request.supplierReference(),
                                        request.note()))));
    }

    @PostMapping("/{purchaseOrderId}/cancellation")
    @Operation(summary = "Cancel a purchase order")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> cancel(
            @PathVariable UUID purchaseOrderId,
            @AuthenticatedUser CurrentUser user,
            @Valid @RequestBody CancelPurchaseOrderRequest request) {
        return ApiResponse.ok(
                toResponse(procurementService.cancel(purchaseOrderId, user.userId(), request.reason())));
    }

    @PostMapping("/{purchaseOrderId}/closure-short")
    @Operation(summary = "Close a purchase order short, writing off the remaining open quantity")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> closeShort(
            @PathVariable UUID purchaseOrderId, @AuthenticatedUser CurrentUser user,
            @Valid @RequestBody CloseShortRequest request) {
        return ApiResponse.ok(
                toResponse(procurementService.closeShort(purchaseOrderId, user.userId(), request.reason())));
    }

    @PostMapping("/{purchaseOrderId}/closure")
    @Operation(summary = "Close a fully received purchase order")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> close(@PathVariable UUID purchaseOrderId,
                                                    @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(procurementService.close(purchaseOrderId, user.userId())));
    }

    private PurchaseOrderResponse toResponse(PurchaseOrderSummary po) {
        var statuses = notifications.purchaseOrderDeliveryStatuses(List.of(po.purchaseOrderId()));
        var ids = List.of(po.purchaseOrderId());
        return response(
                po,
                statuses,
                notifications.purchaseOrderCancellationStatuses(ids),
                notifications.purchaseOrderAmendmentStatuses(ids),
                pendingAmendments(ids));
    }

    /** One query for the page's waiting amendments and one for their requesters' names. */
    private Map<UUID, PendingAmendmentResponse> pendingAmendments(List<UUID> ids) {
        var result = new HashMap<UUID, PendingAmendmentResponse>();
        history.pendingAmendments(ids)
                .forEach(
                        (id, p) ->
                                result.put(
                                        id,
                                        new PendingAmendmentResponse(
                                                p.revisionNo(),
                                                p.requestedBy(),
                                                p.requestedByName(),
                                                p.requestedAt(),
                                                p.reason(),
                                                p.newTotalAmount())));
        return result;
    }

    private PurchaseOrderResponse response(
            PurchaseOrderSummary po,
            Map<UUID, String> statuses,
            Map<UUID, String> cancellations,
            Map<UUID, AmendmentDeliveryStatus> amendmentStatuses,
            Map<UUID, PendingAmendmentResponse> pending) {
        var today = BusinessCalendar.date(clock.instant());
        var warnings =
                po.expectedAt() != null
                                && po.expectedAt().isBefore(today)
                                && !List.of("CANCELLED", "CLOSED", "RECEIVED")
                                        .contains(po.status())
                        ? List.of("DELIVERY_DATE_IN_PAST")
                        : List.<String>of();
        String cancellation =
                cancellations.getOrDefault(
                        po.purchaseOrderId(),
                        "CANCELLED".equals(po.status())
                                        && !"NOT_SENT".equals(po.supplierConfirmationStatus())
                                ? "UNKNOWN"
                                : "NOT_REQUIRED");
        return mapper.toResponse(
                po,
                deliveryStatus(po, statuses),
                cancellation,
                amendmentDeliveryStatus(po, amendmentStatuses),
                pending.get(po.purchaseOrderId()),
                warnings);
    }

    /**
     * The state of the notice of the amendment now active, or null: the newest notice attempted may announce an earlier
     * revision when the latest approved one was not attempted yet, and its state is not this one's (SCRUM-117).
     */
    private static String amendmentDeliveryStatus(PurchaseOrderSummary po,
                                                  Map<UUID, AmendmentDeliveryStatus> statuses) {
        AmendmentDeliveryStatus status = statuses.get(po.purchaseOrderId());
        return status != null && status.revisionNo() == po.revisionNo() ? status.status() : null;
    }

    private static String deliveryStatus(PurchaseOrderSummary po, Map<UUID, String> statuses) {
        return statuses.getOrDefault(
                po.purchaseOrderId(),
                "NOT_SENT".equals(po.supplierConfirmationStatus())
                        ? "NOT_SENT"
                        : po.sentAt() == null ? "UNKNOWN" : "QUEUED");
    }

    @GetMapping("/reports/status-dashboard")
    @Operation(summary = "Count of purchase orders per status")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<List<PurchaseOrderStatusCountResponse>> statusDashboard() {
        return ApiResponse.ok(
                procurementService.statusDashboard().stream().map(mapper::toResponse).toList());
    }

    @GetMapping("/reports/supplier-spend")
    @Operation(summary = "Spend per supplier and currency, highest first within each currency")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<PageResponse<SupplierSpendResponse>> supplierSpend(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate expectedAtFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate expectedAtTo) {
        var query =
                new SupplierSpendReportQuery(
                        page,
                        size == null ? Pages.DEFAULT_PAGE_SIZE : size,
                        supplierId,
                        currency,
                        expectedAtFrom,
                        expectedAtTo);
        return ApiResponse.ok(procurementService.supplierSpend(query).map(mapper::toResponse));
    }
}
