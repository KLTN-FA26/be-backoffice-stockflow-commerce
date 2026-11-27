package com.stockflow.payment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.storage.AttachUploadRequest;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.payment.internal.controller.dto.CreditRequestRequests;
import com.stockflow.payment.internal.controller.dto.CreditRequestResponse;
import com.stockflow.payment.internal.domain.CreditRequest;
import com.stockflow.payment.internal.domain.CreditRequestStatus;
import com.stockflow.payment.internal.service.CreditRequestFiles;
import com.stockflow.payment.internal.service.CreditRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Credit requests (owner decision QĐ-A, 2026-11-26; kltn-docs 15 §4.3, 18 §4 step 5): the customer asks from the portal,
 * or Sales ask for them; Sales give their opinion; the accountant approves - the customer's credit set in the same
 * transaction - or rejects with a reason; whoever filed it may withdraw it. The credit profile form
 * ({@code PUT /customers/{id}/credit-profile}) stays, for changes no request asked for.
 *
 * <p><b>Data scope.</b> The CUSTOMER role holds {@code customer-credit-requests} READ and CREATE for their own requests,
 * and its data scope filters nothing, so every handler a customer may reach does its own owner check: a portal account
 * files for its own customer only, whatever the body names, and reads, withdraws and attaches to its own customer's
 * requests only - anyone else's is 404. The back-office list and Sales' and the accountant's steps call
 * {@link ScopeGuards#requireBackOffice}. Handlers are public: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/credit-requests")
@Tag(name = "Credit requests", description = "Ask for a credit limit; Sales' opinion; the accountant's decision")
@PermissionResource(code = PaymentResources.CREDIT_REQUESTS, group = "Payment", label = "Credit requests",
        route = "/payment/credit-requests", apiPath = "/api/v1/credit-requests",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class CreditRequestController {

    private final CreditRequestService credits;
    private final CreditRequestFiles files;
    private final CreditRequestWebMapper mapper;

    CreditRequestController(CreditRequestService credits, CreditRequestFiles files, CreditRequestWebMapper mapper) {
        this.credits = credits;
        this.files = files;
        this.mapper = mapper;
    }

    // ---- filing: the customer for themselves, or Sales for a customer ----------------------------------------------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask for a credit limit",
            description = "From the portal the customer asks for themselves (SUBMITTED: Sales give their opinion next); "
                    + "customerId, salesNote and recommendedLimit are ignored. Sales ask for customerId with their "
                    + "opinion (salesNote required; PENDING_APPROVAL). 409 CREDIT_REQUEST_ALREADY_OPEN while the "
                    + "customer has one open; 404 CUSTOMER_NOT_FOUND")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<CreditRequestResponse> file(@Valid @RequestBody CreditRequestRequests.File body,
                                                   @AuthenticatedUser CurrentUser user) {
        var filing = new CreditRequestService.Filing(body.requestedLimit(), body.requestedTermDays(), body.reason(),
                body.salesNote(), body.recommendedLimit());
        boolean backOffice = ScopeGuards.isBackOffice(user);
        CreditRequest filed = backOffice ? credits.fileForCustomer(body.customerId(), filing, user.userId())
                : credits.fileOwn(user.userId(), filing);
        return ApiResponse.ok(detail(filed, backOffice));
    }

    @GetMapping("/mine")
    @Operation(summary = "The signed-in customer's own credit requests, newest first")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<PageResponse<CreditRequestResponse>> mine(@RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(required = false) Integer size,
                                                                 @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.rows(credits.mine(user.userId(), page, sizeOf(size)), false));
    }

    // ---- reading -----------------------------------------------------------------------------------------------------

    @GetMapping
    @Operation(summary = "Credit requests, newest first (back office); filter by status and customer")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<CreditRequestResponse>> list(
            @RequestParam(required = false) CreditRequestStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(mapper.rows(credits.list(customerId, status, page, sizeOf(size)), true));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One credit request with its documents; for the back office with the review figures (how the "
            + "customer has paid, what they owe, their credit now, the warnings). A customer reads their own only")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<CreditRequestResponse> get(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(detail(credits.find(id, ownCustomerIdOrNull(user)), ScopeGuards.isBackOffice(user)));
    }

    // ---- Sales' opinion and the accountant's decision -------------------------------------------------------------

    @PostMapping("/{id}/recommendation")
    @Operation(summary = "Sales' opinion on a request the customer filed: it then waits for the accountant",
            description = "409 CREDIT_REQUEST_INVALID_TRANSITION unless SUBMITTED; 409 OPTIMISTIC_LOCK for a stale "
                    + "version")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<CreditRequestResponse> recommend(@PathVariable UUID id,
                                                        @Valid @RequestBody CreditRequestRequests.Recommend body,
                                                        @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(detail(credits.recommend(id, body.version(), body.note(), body.recommendedLimit(),
                user.userId()), true));
    }

    @PostMapping("/{id}/approval")
    @Operation(summary = "Approve: grant a limit (at most the one asked) and the days to pay; the customer's credit is "
            + "set to them at once",
            description = "409 CREDIT_REQUEST_ABOVE_REQUESTED; 409 CREDIT_REQUEST_SELF_DECISION for whoever filed it "
                    + "or gave Sales' opinion; 409 OPTIMISTIC_LOCK when the request or the customer's credit changed "
                    + "since read (version, creditTermsVersion)")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<CreditRequestResponse> approve(@PathVariable UUID id,
                                                      @Valid @RequestBody CreditRequestRequests.Approve body,
                                                      @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(detail(credits.approve(id, body.version(), body.approvedLimit(),
                body.approvedTermDays(), body.note(), body.creditTermsVersion(), user.userId()), true));
    }

    @PostMapping("/{id}/rejection")
    @Operation(summary = "Reject, with the reason; the customer's credit is unchanged")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<CreditRequestResponse> reject(@PathVariable UUID id,
                                                     @Valid @RequestBody CreditRequestRequests.Reject body,
                                                     @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(detail(credits.reject(id, body.version(), body.reason(), user.userId()), true));
    }

    @PostMapping("/{id}/withdrawal")
    @Operation(summary = "Withdraw one's own open request: the customer the one they filed, Sales the one they filed",
            description = "403 CREDIT_REQUEST_NOT_FILER for anyone else's; 409 CREDIT_REQUEST_INVALID_TRANSITION once "
                    + "decided")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<CreditRequestResponse> withdraw(@PathVariable UUID id,
                                                       @Valid @RequestBody CreditRequestRequests.Withdraw body,
                                                       @AuthenticatedUser CurrentUser user) {
        UUID own = ownCustomerIdOrNull(user);
        return ApiResponse.ok(detail(credits.withdraw(id, body.version(), user.userId(), own), own == null));
    }

    // ---- documents ---------------------------------------------------------------------------------------------------

    @PostMapping(value = "/{id}/files/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Step one of a document uploaded straight to storage (PDF, Word, Excel, JPEG, PNG; 25 MB): a "
            + "signed PUT for exactly the declared file. Then name its uploadId in POST /credit-requests/{id}/files")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.CREATE, scope = DataScope.OWN)
    public ResponseEntity<ApiResponse<UploadSlot>> beginUpload(@PathVariable UUID id,
                                                               @Valid @RequestBody UploadSlotRequest body,
                                                               @AuthenticatedUser CurrentUser user) {
        UploadSlot slot = files.beginUpload(id, ownCustomerIdOrNull(user), body, user.userId());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(slot));
    }

    @PostMapping(value = "/{id}/files", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a document uploaded through /files/uploads while the request is open (at most 10); the "
            + "same uploadId again answers with the file it made")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<CreditRequestResponse.File> attachUploaded(@PathVariable UUID id,
                                                                  @Valid @RequestBody AttachUploadRequest body,
                                                                  @AuthenticatedUser CurrentUser user) {
        var kept = files.attach(id, ownCustomerIdOrNull(user),
                IncomingFiles.uploaded(user.userId(), List.of(body.uploadId())), user.userId());
        return ApiResponse.ok(CreditRequestWebMapper.file(kept));
    }

    @PostMapping(value = "/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a document sent as a multipart part while the request is open (at most 10)")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<CreditRequestResponse.File> attach(@PathVariable UUID id,
                                                          @RequestPart("file") MultipartFile file,
                                                          @AuthenticatedUser CurrentUser user) throws IOException {
        UUID own = ownCustomerIdOrNull(user);
        try (InputStream content = file.getInputStream()) {
            FileUpload upload = new FileUpload(file.getOriginalFilename() == null ? "document"
                    : file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(CreditRequestWebMapper.file(files.attach(id, own, IncomingFiles.sent(List.of(upload)),
                    user.userId())));
        }
    }

    @GetMapping("/{id}/files/{fileId}/download-url")
    @Operation(summary = "A short link saving a document of the request under its name")
    @RequiresPermission(resource = PaymentResources.CREDIT_REQUESTS, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<ApiResponse<DownloadLink>> downloadUrl(@PathVariable UUID id, @PathVariable UUID fileId,
                                                                 @AuthenticatedUser CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(files.downloadLink(id, fileId, ownCustomerIdOrNull(user))));
    }

    private CreditRequestResponse detail(CreditRequest request, boolean backOffice) {
        return mapper.detail(request, files.list(request.id()), backOffice ? credits.review(request) : null,
                backOffice);
    }

    /** The caller's own customer when they are a portal customer; null for the back office. */
    private UUID ownCustomerIdOrNull(CurrentUser user) {
        return ScopeGuards.isBackOffice(user) ? null : credits.ownCustomerId(user.userId());
    }

    private static int sizeOf(Integer size) {
        return size == null ? Pages.DEFAULT_PAGE_SIZE : size;
    }
}
