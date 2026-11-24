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
import com.stockflow.common.storage.AttachUploadRequest;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.internal.controller.dto.CustodyNoteResponse;
import com.stockflow.order.internal.controller.dto.CustomerReturnResponse;
import com.stockflow.order.internal.controller.dto.EvidenceLinkResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequestResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequestRowResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequests;
import com.stockflow.order.internal.controller.dto.ReturnView;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.service.ReturnCustodyNotes;
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
 * Return requests (RMA, "đổi trả"; SCRUM-243, kltn-docs 17 §4.10): opening, photos of the claim, withdrawal and Sales'
 * review. The refund has its own resource and controller ({@link ReturnResolutionController}). The goods come back on
 * a return receipt of the warehouse ({@code /api/v1/goods-receipts/returns}, team plan 4D-2): there is no receipt or
 * inspection step here any more (4D-3).
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
    private final ReturnCustodyNotes custodyNotes;

    ReturnRequestController(ReturnRequestService returns, ReturnRequestQueries queries, CustomerService customers,
                            ReturnCustodyNotes custodyNotes) {
        this.returns = returns;
        this.queries = queries;
        this.customers = customers;
        this.custodyNotes = custodyNotes;
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
    @Operation(summary = "Add a photo of the problem (JPEG, PNG, WebP, at most 15 MB) while the claim is open: the "
            + "customer's, or Sales' for them. Photos of the goods as they came back are the return receipt's")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<ReturnView> addEvidence(@PathVariable UUID returnId,
                                                          @RequestPart("file") MultipartFile file,
                                                          @RequestParam(required = false) ReturnEvidence.Stage stage,
                                                          @AuthenticatedUser CurrentUser user) throws IOException {
        // Only the claim's photos are the return's (team plan 4D-3): the goods as they came back are photographed on
        // their return receipt, and nobody inspects them.
        if (stage != null && stage != ReturnEvidence.Stage.CLAIM) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("A %s photo is not kept on a return: photograph "
                    + "the goods on their return receipt").formatted(stage));
        }
        return upload(returnId, file, ReturnEvidence.Stage.CLAIM, user);
    }

    // -------------------------------------------------------------- uploads straight to the object store (preferred)

    @PostMapping(value = "/returns/{returnId}/evidence/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Step one of a claim photo uploaded straight to storage: a signed PUT for exactly the declared "
            + "file (JPEG, PNG, WebP, at most 15 MB). Then name its uploadId in POST /returns/{id}/evidence")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.CREATE, scope = DataScope.OWN)
    public ResponseEntity<ApiResponse<UploadSlot>> beginEvidenceUpload(@PathVariable UUID returnId,
            @Valid @RequestBody UploadSlotRequest request, @AuthenticatedUser CurrentUser user) {
        return slot(returns.beginEvidenceUpload(returnId, ownCustomerIdOrNull(user), ReturnEvidence.Stage.CLAIM,
                request, user.userId()));
    }

    @PostMapping(value = "/returns/{returnId}/evidence", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a claim photo uploaded through /evidence/uploads; the same uploadId again is answered "
            + "with the return it was kept on")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<ReturnView> addUploadedEvidence(@PathVariable UUID returnId,
            @Valid @RequestBody AttachUploadRequest body, @AuthenticatedUser CurrentUser user) {
        UUID own = ownCustomerIdOrNull(user);
        return ApiResponse.ok(view(returns.addUploadedEvidence(returnId, own, ReturnEvidence.Stage.CLAIM,
                body.uploadId(), user.userId()), own == null));
    }

    // -------------------------------------------------------------- custody (WF-05 Q-11), staff only

    @PostMapping(value = "/returns/{returnId}/custody", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record goods kept in custody for the customer (WF-05 Q-11): goods that came back and that no "
            + "return receipt takes - a SKU the return did not approve (409 RETURN_CARTON_NOT_CLAIMED on the receipt), "
            + "more than approved, a parcel after the claim was settled or refused. A photo, the SKU or the LPN, how "
            + "many, a note; whatever the return's status. Not stock, not refunded, no invoice adjusted")
    @RequiresPermission(resource = OrderResources.RMA_CUSTODY, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<ReturnView> addCustody(@PathVariable UUID returnId, @RequestPart("file") MultipartFile file,
                                              @RequestParam(required = false) String sku,
                                              @RequestParam(required = false) String lpn,
                                              @RequestParam int quantity,
                                              @RequestParam(required = false) String note,
                                              @AuthenticatedUser CurrentUser user) throws IOException {
        // Audit round 2 M4: a custody note is the warehouse's (or Sales'), on any return - sales-rma-custody:CREATE,
        // which no customer holds; refused for one all the same, as it always was.
        ScopeGuards.requireBackOffice(user);
        ReturnEvidence.Custody custody = custody(sku, lpn, quantity, note);
        try (InputStream content = file.getInputStream()) {
            FileUpload upload = new FileUpload(file.getOriginalFilename() == null ? "photo" : file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(view(returns.addEvidence(returnId, null, ReturnEvidence.Stage.CUSTODY, custody,
                    upload, user.userId()), true));
        }
    }

    @PostMapping(value = "/returns/{returnId}/custody/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Step one of a custody photo uploaded straight to storage (WF-05 Q-11); then name its uploadId "
            + "in POST /returns/{id}/custody")
    @RequiresPermission(resource = OrderResources.RMA_CUSTODY, action = Action.CREATE, scope = DataScope.ALL)
    public ResponseEntity<ApiResponse<UploadSlot>> beginCustodyUpload(@PathVariable UUID returnId,
            @Valid @RequestBody UploadSlotRequest request, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return slot(returns.beginEvidenceUpload(returnId, null, ReturnEvidence.Stage.CUSTODY, request,
                user.userId()));
    }

    @PostMapping(value = "/returns/{returnId}/custody", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a custody note whose photo was uploaded through /custody/uploads (WF-05 Q-11)")
    @RequiresPermission(resource = OrderResources.RMA_CUSTODY, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<ReturnView> addUploadedCustody(@PathVariable UUID returnId,
            @Valid @RequestBody ReturnRequests.Custody body, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        ReturnEvidence.Custody custody = custody(body.sku(), body.lpn(), body.quantity(), body.note());
        return ApiResponse.ok(view(returns.addUploadedEvidence(returnId, null, ReturnEvidence.Stage.CUSTODY, custody,
                body.uploadId(), user.userId()), true));
    }

    @PostMapping("/returns/{returnId}/custody/{evidenceId}/choice")
    @Operation(summary = "Record what the customer chose for the goods of a custody note (WF-05 Q-11), Sales",
            description = "Audit r3 RAD-3: SEND_BACK (at the customer's cost), DESTROY_ON_BEHALF (note names their "
                    + "written consent - required) or TAKEN_BY_RETURN (takenByReturnId: a supplementary return of the "
                    + "same customer, not rejected, withdrawn or expired). Whatever the return's status. Recorded again, "
                    + "it replaces the choice while the goods are kept. The warehouse manager then records that they "
                    + "left (POST .../end), from GET /returns/custody-notes. Answers the return; the note's custody "
                    + "carries outcome. 404 NOT_FOUND (no custody note of the return by that id), "
                    + "RETURN_REQUEST_NOT_FOUND; 403 OUT_OF_DATA_SCOPE; 409 RETURN_INVALID_TRANSITION (the goods left "
                    + "already, or the supplementary return takes nothing); 400 VALIDATION_FAILED.")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ReturnView> chooseForCustodyNote(@PathVariable UUID returnId, @PathVariable UUID evidenceId,
                                                        @Valid @RequestBody ReturnRequests.CustodyNoteChoice body,
                                                        @AuthenticatedUser CurrentUser user) {
        // The customer holds sales-rmas:UPDATE for withdrawing their own return; what they chose is told to Sales, who
        // record it - a destruction on their behalf rests on a signed record, not a click.
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(view(custodyNotes.choose(returnId, evidenceId, body.choice(), body.note(),
                body.takenByReturnId(), user.userId()), true));
    }

    @PostMapping("/returns/{returnId}/custody/{evidenceId}/end")
    @Operation(summary = "Record that the goods of a custody note left as the customer chose (warehouse manager)",
            description = "Audit r3 RAD-3 (Q-11, TH-6): handed back, destroyed on the customer's behalf, or received on "
                    + "the supplementary return - note is the record of it (hand-over note, destruction record, the "
                    + "receipt). Final. No stock moves: custody goods never entered stock. 404 NOT_FOUND, "
                    + "RETURN_REQUEST_NOT_FOUND; 403 OUT_OF_DATA_SCOPE (a return of another warehouse); 409 "
                    + "RETURN_CUSTODY_CHOICE_REQUIRED (Sales have not recorded the choice), RETURN_INVALID_TRANSITION "
                    + "(ended already, or the supplementary return was rejected, withdrawn or expired - Sales choose "
                    + "again); 400 VALIDATION_FAILED.")
    @RequiresPermission(resource = OrderResources.RMA_CUSTODY, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse.CustodyOutcome> endCustodyNote(@PathVariable UUID returnId,
            @PathVariable UUID evidenceId, @Valid @RequestBody ReturnRequests.EndCustodyNote body,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ReturnWebMapper.toOutcome(custodyNotes.end(returnId, evidenceId, body.note(),
                user.userId())));
    }

    @GetMapping("/returns/custody-notes")
    @Operation(summary = "Goods kept on custody notes for customers (WF-05 Q-11, TH-6), oldest first",
            description = "Audit r3 RAD-3: every custody note whose goods the warehouse still keeps - no choice yet "
                    + "(outcome null: wait for Sales), or chosen and not carried out - in the caller's warehouses; "
                    + "ended=true lists those that left instead, latest first; returnRequestId narrows to one return. "
                    + "The other half of the TH-6 list beside GET /inventory/stock-packages/customer-custody (cartons "
                    + "held after a refund did not take them back).")
    @RequiresPermission(resource = OrderResources.RMA_CUSTODY, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<CustodyNoteResponse>> custodyNotes(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(required = false) UUID returnRequestId,
            @RequestParam(defaultValue = "false") boolean ended,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(custodyNotes.kept(returnRequestId, ended, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(CustodyNoteResponse::of));
    }

    private static ReturnEvidence.Custody custody(String sku, String lpn, int quantity, String note) {
        try {
            return new ReturnEvidence.Custody(sku, lpn, quantity, note);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    /** A slot carries a signed URL: a credential, never cached. */
    private static ResponseEntity<ApiResponse<UploadSlot>> slot(UploadSlot slot) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse.ok(slot));
    }

    private ApiResponse<ReturnView> upload(UUID returnId, MultipartFile file, ReturnEvidence.Stage stage,
                                           CurrentUser user) throws IOException {
        UUID own = ownCustomerIdOrNull(user);
        try (InputStream content = file.getInputStream()) {
            FileUpload upload = new FileUpload(file.getOriginalFilename() == null ? "photo" : file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), content);
            ReturnRequest request = returns.addEvidence(returnId, own, stage, upload, user.userId());
            return ApiResponse.ok(view(request, own == null));
        }
    }

    @GetMapping("/returns/{returnId}/evidence/{evidenceId}/download-url")
    @Operation(summary = "A link to one photo, valid for five minutes, served by the object store: its display copy "
            + "(no location metadata); original=true saves the photo exactly as uploaded, and is audited")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<ApiResponse<EvidenceLinkResponse>> evidenceLink(@PathVariable UUID returnId,
            @PathVariable UUID evidenceId, @RequestParam(defaultValue = "false") boolean original,
            @AuthenticatedUser CurrentUser user) {
        UUID own = ownCustomerIdOrNull(user);
        String url = original ? queries.evidenceOriginalLink(returnId, evidenceId, own)
                : queries.evidenceLink(returnId, evidenceId, own);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(new EvidenceLinkResponse(url)));
    }

    @PostMapping("/returns/{returnId}/custody-choice")
    @Operation(summary = "Record what the customer chose for goods held for them (WF-05 Q-4), Sales",
            description = "For a RESOLVED return with a carton held for the customer (custodyCartons on GET "
                    + "/returns/{id} lists them): sealed cartons the refund did not take back - every carton of a SKU "
                    + "refunded 0, and whole cartons beyond the units refunded (audit r3 RAD-1) - are out of stock, held "
                    + "for the customer in the quarantine area. SEND_BACK (at the customer's cost) or DESTROY_ON_BEHALF "
                    + "(note names their written consent - required). The warehouse manager carries it out from GET "
                    + "/inventory/stock-packages/customer-custody. Recorded again, it replaces the choice while nothing "
                    + "was carried out. Goods kept on a custody note have their own choice (POST "
                    + "/returns/{id}/custody/{evidenceId}/choice). 409 RETURN_INVALID_TRANSITION (not RESOLVED, or no "
                    + "carton of it held), INVALID_STOCK_PACKAGE_TRANSITION (already carried out), OPTIMISTIC_LOCK; 400 "
                    + "VALIDATION_FAILED.")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ReturnView> recordCustodyChoice(@PathVariable UUID returnId,
                                                       @Valid @RequestBody ReturnRequests.CustodyChoice body,
                                                       @AuthenticatedUser CurrentUser user) {
        // The customer holds sales-rmas:UPDATE for withdrawing their own return; their choice is told to Sales, who
        // record it - a destruction on their behalf rests on a signed record, not a click.
        ScopeGuards.requireBackOffice(user);
        ReturnRequest recorded = returns.recordCustodyChoice(returnId, body.choice(), body.note(), body.version(),
                user.userId());
        return ApiResponse.ok(ReturnWebMapper.toResponse(recorded, List.of(), key -> null,
                queries.custodyCartons(returnId)));
    }

    @PostMapping("/returns/{returnId}/cancellation")
    @Operation(summary = "Withdraw a return before any of its goods is back (REQUESTED or AWAITING_RETURN → "
            + "CANCELLED); the customer their own, Sales any",
            description = "409 RETURN_INVALID_TRANSITION past AWAITING_RETURN. 409 RETURN_GOODS_PARTLY_RECEIVED once a "
                    + "parcel of its goods came back (WF-05 TH-3): withdrawing it would leave those goods in the "
                    + "warehouse with nothing refunded - Sales mark the goods complete instead "
                    + "(POST /returns/{id}/goods-complete) and decide the refund on what came. 409 "
                    + "RETURN_RECEIPT_DRAFT_OPEN while the warehouse counts a parcel of it on a draft receipt "
                    + "(receiptDraftOpen on every return response, for the customer too): withdrawn then, the order "
                    + "would go back to DELIVERED before the parcel's receipt reopened the return.")
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
        return ApiResponse.ok(ReturnWebMapper.toCustomerResponse(detail.request(), detail.history(), queries::thumbnail));
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
    @Operation(summary = "One return with every step, its photos, what came back on its return receipt and its history")
    @RequiresPermission(resource = OrderResources.RMAS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> one(@PathVariable UUID returnId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        ReturnRequestQueries.Detail detail = queries.detail(returnId, null);
        return ApiResponse.ok(ReturnWebMapper.toResponse(detail.request(), detail.history(), queries::thumbnail,
                detail.custodyCartons()));
    }

    @PostMapping("/returns/{returnId}/approval")
    @Operation(summary = "Approve (Sales): in full, or fewer units per line; the goods come back to the order's "
            + "warehouse on a return receipt by the deadline (17 BR-18). Needs a photo of the problem")
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
