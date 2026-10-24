package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.internal.controller.dto.CustomerReturnResponse;
import com.stockflow.order.internal.controller.dto.EvidenceLinkResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequestResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequestRowResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequests;
import com.stockflow.order.internal.controller.dto.ReturnView;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.service.ReturnRequestQueries;
import com.stockflow.order.internal.service.ReturnRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Return requests (RMA, "đổi trả"; SCRUM-243, 03-state-machines §12): opening, photos, withdrawal and Sales' review.
 * The warehouse's receipt, QC's inspection and the resolution have their own resources and controllers
 * ({@link ReturnReceiptController}, {@link ReturnInspectionController}, {@link ReturnResolutionController}), so each
 * role is granted exactly its step.
 *
 * <p><b>Data scope.</b> The CUSTOMER role holds {@code sales-rmas} VIEW_PAGE / READ / CREATE / UPDATE for their own
 * returns and its data scope filters nothing, so every handler a customer may reach does its own owner check: a portal
 * account acts on its own customer's orders and returns only, and anyone else's - or none - is 404 either way. The
 * back-office lists call {@link ScopeGuards#requireBackOffice}. Handlers are public: the permission aspect needs a
 * proxied public method, and a skipped guard is an endpoint with no authorisation at all.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Returns", description = "Return requests (RMA): open, photos, withdraw, review")
@PermissionResource(
        code = OrderResources.RMAS,
        group = "Sales",
        label = "Returns (RMA)",
        route = "/sales/returns",
        apiPath = "/api/v1/returns",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class ReturnRequestController {

    private final ReturnRequestService returns;
    private final ReturnRequestQueries queries;
    private final CustomerService customers;

    ReturnRequestController(ReturnRequestService returns, ReturnRequestQueries queries, CustomerService customers) {
        this.returns = returns;
        this.queries = queries;
        this.customers = customers;
    }

    // ---- the customer, or Sales for them ------------------------------------------------------------------------------

    @PostMapping("/orders/{orderId}/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a return on a delivered order, within the return window (BR-RMA-001): lines and units, "
            + "reason, description, the resolution wanted. Custom-printed goods only when defective (BR-RMA-002). "
            + "Idempotent on requestId. The customer on their own order (portal); Sales on any")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<ReturnView> open(@PathVariable UUID orderId,
                                        @Valid @RequestBody ReturnRequests.Open request,
                                        @AuthenticatedUser CurrentUser user) {
        var command = ReturnWebMapper.toCommand(orderId, request);
        boolean backOffice = ScopeGuards.isBackOffice(user);
        ReturnRequest opened = backOffice ? returns.openForCustomer(command, user.userId())
                : returns.openOwn(ownCustomerId(user), command, user.userId());
        return ApiResponse.ok(view(opened, backOffice));
    }

    @PostMapping(value = "/returns/{returnId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a photo (JPEG, PNG, WebP, at most 15 MB): the customer's of the problem while the "
            + "claim is open; staff may add stage RECEIPT or INSPECTION photos")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<ReturnView> addEvidence(@PathVariable UUID returnId,
                                                          @RequestPart("file") MultipartFile file,
                                                          @RequestParam(required = false) ReturnEvidence.Stage stage,
                                                          @AuthenticatedUser CurrentUser user) throws IOException {
        UUID own = ownCustomerIdOrNull(user);
        try (InputStream content = file.getInputStream()) {
            FileUpload upload = new FileUpload(file.getOriginalFilename() == null ? "photo" : file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), content);
            ReturnRequest request = returns.addEvidence(returnId, own, stage, upload, user.userId());
            return ApiResponse.ok(view(request, own == null));
        }
    }

    @GetMapping("/returns/{returnId}/evidence/{evidenceId}/download-url")
    @Operation(summary = "A link to one photo, valid for five minutes, served by the object store")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<EvidenceLinkResponse> evidenceLink(@PathVariable UUID returnId, @PathVariable UUID evidenceId,
                                                          @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(new EvidenceLinkResponse(queries.evidenceLink(returnId, evidenceId,
                ownCustomerIdOrNull(user))));
    }

    @PostMapping("/returns/{returnId}/cancellation")
    @Operation(summary = "Withdraw a return before its goods are back (REQUESTED or AWAITING_RETURN → CANCELLED); "
            + "the customer their own, Sales any")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.UPDATE, scope = DataScope.OWN)
    public ApiResponse<ReturnView> cancel(@PathVariable UUID returnId,
                                          @Valid @RequestBody(required = false) ReturnRequests.Cancel request,
                                          @AuthenticatedUser CurrentUser user) {
        UUID own = ownCustomerIdOrNull(user);
        return ApiResponse.ok(view(returns.cancel(returnId, own, request == null ? null : request.reason(),
                user.userId()), own == null));
    }

    /** The caller's own view: staff see every step and note, a customer only what is theirs to see. */
    private static ReturnView view(ReturnRequest request, boolean backOffice) {
        return backOffice ? ReturnWebMapper.toResponse(request, List.of())
                : ReturnWebMapper.toCustomerResponse(request, List.of());
    }

    @GetMapping("/returns/me")
    @Operation(summary = "The signed-in customer's own returns, newest first; status may be repeated")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<PageResponse<ReturnRequestRowResponse>> mine(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(name = "status", required = false) List<ReturnStatus> statuses,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(queries.mine(ownCustomerId(user), statuses, orderId, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(ReturnWebMapper::toCustomerRow));
    }

    @GetMapping("/returns/me/{returnId}")
    @Operation(summary = "One of the signed-in customer's own returns: units at every step, deadline, outcome")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<CustomerReturnResponse> myReturn(@PathVariable UUID returnId,
                                                        @AuthenticatedUser CurrentUser user) {
        ReturnRequestQueries.Detail detail = queries.detail(returnId, ownCustomerId(user));
        return ApiResponse.ok(ReturnWebMapper.toCustomerResponse(detail.request(), detail.history()));
    }

    // ---- the back office -----------------------------------------------------------------------------------------------

    @GetMapping("/returns")
    @Operation(summary = "Every customer's returns: status (repeat), customer, order, RMA or order number, "
            + "vatAdjustment=true for resolved returns needing an adjustment invoice")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<ReturnRequestRowResponse>> list(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(name = "status", required = false) List<ReturnStatus> statuses,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean vatAdjustment,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        // A customer holds sales-rmas:READ for their own returns; this list is everyone's.
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(queries.list(statuses, customerId, orderId, search, vatAdjustment, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(ReturnWebMapper::toRow));
    }

    @GetMapping("/returns/{returnId}")
    @Operation(summary = "One return with every step, its photos, what the parcel held and its history")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> one(@PathVariable UUID returnId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        ReturnRequestQueries.Detail detail = queries.detail(returnId, null);
        return ApiResponse.ok(ReturnWebMapper.toResponse(detail.request(), detail.history()));
    }

    @PostMapping("/returns/{returnId}/approval")
    @Operation(summary = "Approve (Sales): in full, or fewer units per line; the goods come back by the deadline, or "
            + "stay with the customer when worth at most the no-return value (or a short delivery). Needs a photo of "
            + "the problem")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> approve(@PathVariable UUID returnId,
                                                      @Valid @RequestBody(required = false) ReturnRequests.Approve request,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.approve(returnId, ReturnWebMapper.toCommand(request),
                user.userId()), List.of()));
    }

    @PostMapping("/returns/{returnId}/rejection")
    @Operation(summary = "Reject (Sales), saying why; the order goes back to waiting for its window and money")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> reject(@PathVariable UUID returnId,
                                                     @Valid @RequestBody ReturnRequests.Reason request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.reject(returnId, request.reason(), request.version(),
                user.userId()), List.of()));
    }

    // ---- who the caller is -----------------------------------------------------------------------------------------------

    /** The back office acts on any customer's returns: null. A portal account on its own customer's only. */
    private UUID ownCustomerIdOrNull(CurrentUser user) {
        return ScopeGuards.isBackOffice(user) ? null : ownCustomerId(user);
    }

    /** Returns are stored under the customer profile, the token names the sign-in: go through the profile. */
    private UUID ownCustomerId(CurrentUser user) {
        return customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
    }
}
