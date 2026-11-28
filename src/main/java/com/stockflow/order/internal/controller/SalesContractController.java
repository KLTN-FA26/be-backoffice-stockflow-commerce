package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.order.internal.controller.dto.SalesContractRequests;
import com.stockflow.order.internal.service.SalesContracts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The sales contract of an order (4B-4; kltn-docs 14 §4.3 steps 11-17, §5.2). Sales draws it up, edits and sends it
 * ({@code sales-contracts:CREATE / UPDATE}) and records it signed ({@code :APPROVE}); accounting and the Sales Manager
 * read it. A change locks the order as the caller's data scope sees it, and a read reads it under the same scope - out
 * of scope is 404 either way. Handlers are public so the permission proxy advises them.
 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}/contract")
@Tag(name = "Orders")
@PermissionResource(code = OrderResources.SALES_CONTRACTS, group = "Sales", label = "Sales contracts",
        route = "/orders", apiPath = "/api/v1/orders/{orderId}/contract",
        actions = {Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class SalesContractController {

    private final SalesContracts contracts;

    SalesContractController(SalesContracts contracts) {
        this.contracts = contracts;
    }

    @GetMapping
    @Operation(summary = "The order's contract in force: parties, totals, delivery schedule, payment plan, status. A "
            + "DRAFT contract carries creditWarning (OVERDUE / OVER_LIMIT) when its part paid after delivery would not "
            + "fit the customer's credit now, so Sales decide before sending (14 §4.3 step 13, 15 BR-11)")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ApiResponse<SalesContracts.ContractView> get(@PathVariable UUID orderId,
                                                        @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.get(orderId));
    }

    @GetMapping("/history")
    @Operation(summary = "Every contract the order had, the cancelled ones too, newest first")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ApiResponse<List<SalesContracts.ContractView>> history(@PathVariable UUID orderId,
                                                                  @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.history(orderId));
    }

    @PostMapping
    @Operation(summary = "Draw up the contract of a draft order (14 §4.3 step 11). Left out: 2 free sample rounds (as "
            + "configured), one delivery batch of every carton in the window the customer asked for, the customer's "
            + "default payment schedule. 409 SALES_CONTRACT_EXISTS / SALES_CONTRACT_ORDER_NOT_DRAFT / "
            + "CUSTOMER_LEGAL_PROFILE_MISSING / DELIVERY_SCHEDULE_INVALID / PAYMENT_PLAN_INVALID")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.CREATE)
    public ApiResponse<SalesContracts.ContractView> create(@PathVariable UUID orderId,
                                                           @Valid @RequestBody(required = false)
                                                           SalesContractRequests.Draft request,
                                                           @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.create(user, orderId, request == null ? null : draft(request)));
    }

    @PutMapping
    @Operation(summary = "Edit a draft contract as read: free sample rounds; a delivery schedule or payment plan sent "
            + "replaces the old one whole, one left out is kept; likewise the special terms (blank clears), the "
            + "penalty / tolerance / warranty overrides and the two signers. Any edit takes an approval of the terms "
            + "back (it is asked for again when the terms still deviate)")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.UPDATE)
    public ApiResponse<SalesContracts.ContractView> edit(@PathVariable UUID orderId,
                                                         @Valid @RequestBody SalesContractRequests.Draft request,
                                                         @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        if (request.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "version: the version read");
        }
        return ApiResponse.ok(contracts.edit(user, orderId, request.version(), draft(request)));
    }

    @GetMapping("/payment-plan-suggestion")
    @Operation(summary = "The draft's payment plan with every installment tied to a delivery batch suggested again from "
            + "the batches as they are now (14 BR-13, DS-02b): the rest of the total after the untied installments, "
            + "shared as the batches' goods are worth with tax; the last tied one takes the rounding. Adds up to "
            + "totalAmount. Nothing is saved: send it back with PUT at the version given. 409 "
            + "SALES_CONTRACT_STATUS_INVALID once sent; 409 PAYMENT_PLAN_INVALID when the untied installments take the "
            + "whole total")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ApiResponse<SalesContracts.PlanSuggestion> paymentPlanSuggestion(@PathVariable UUID orderId,
                                                                           @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.suggestPaymentPlan(orderId));
    }

    @PostMapping("/sending")
    @Operation(summary = "Send the draft to the customer (14 §4.3 step 13): every delivery window set and not in the "
            + "past; the PDF sent is kept. A plan paying after delivery is checked against the customer's credit: "
            + "OVERDUE or OVER_LIMIT and it is NOT sent but waits for accounting - the answer is the contract still "
            + "DRAFT with creditReview PENDING (15 BR-11, 2026-11-26). 409 CONTRACT_CREDIT_REVIEW_PENDING when it "
            + "waits already")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.UPDATE)
    public ApiResponse<SalesContracts.ContractView> send(@PathVariable UUID orderId,
                                                         @Valid @RequestBody SalesContractRequests.Version request,
                                                         @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.send(user, orderId, request.version()));
    }

    @PostMapping("/terms-review")
    @Operation(summary = "Put a draft whose terms deviate from the standard ones (special terms, or a penalty, tolerance "
            + "or warranty other than the configured) in front of a Sales Manager (QĐ-4). It stays a DRAFT, locked "
            + "against edits, and is not sent until approved. 409 CONTRACT_TERMS_REVIEW_NOT_NEEDED for a standard "
            + "contract; 409 CONTRACT_TERMS_REVIEW_PENDING when it waits already")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.UPDATE)
    public ApiResponse<SalesContracts.ContractView> submitForTermsReview(@PathVariable UUID orderId,
                                                                         @Valid @RequestBody
                                                                         SalesContractRequests.Version request,
                                                                         @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.submitForTermsReview(user, orderId, request.version()));
    }

    @PostMapping("/terms-review/approval")
    @Operation(summary = "A Sales Manager approves the terms as written (QĐ-4); the contract may now be sent. The note is "
            + "optional. Editing the draft afterwards takes the approval back. 409 CONTRACT_TERMS_REVIEW_NOT_PENDING")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACT_TERMS, action = Action.APPROVE)
    public ApiResponse<SalesContracts.ContractView> approveTerms(@PathVariable UUID orderId,
                                                                 @Valid @RequestBody
                                                                 SalesContractRequests.TermsDecision request,
                                                                 @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.approveTerms(user, orderId, request.version(), request.note()));
    }

    @PostMapping("/terms-review/rejection")
    @Operation(summary = "A Sales Manager rejects the terms, with the reason (required, at most 500 characters): the draft "
            + "goes back to Sales to change and send for review again. 409 CONTRACT_TERMS_REVIEW_NOT_PENDING")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACT_TERMS, action = Action.APPROVE)
    public ApiResponse<SalesContracts.ContractView> rejectTerms(@PathVariable UUID orderId,
                                                                @Valid @RequestBody
                                                                SalesContractRequests.TermsDecision request,
                                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.rejectTerms(user, orderId, request.version(), request.note()));
    }

    @PostMapping("/terms-review/withdrawal")
    @Operation(summary = "Take a draft waiting for a Sales Manager back, to change it (QĐ-4): terms review NONE. 409 "
            + "CONTRACT_TERMS_REVIEW_NOT_PENDING when it waits for nothing")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.UPDATE)
    public ApiResponse<SalesContracts.ContractView> withdrawTermsReview(@PathVariable UUID orderId,
                                                                        @Valid @RequestBody
                                                                        SalesContractRequests.Version request,
                                                                        @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.withdrawTermsReview(user, orderId, request.version()));
    }

    @PutMapping("/sign-by")
    @Operation(summary = "Move the day the customer should have signed by while the contract is SENT: today or later, at "
            + "most 60 days ahead. 409 SALES_CONTRACT_STATUS_INVALID otherwise")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.UPDATE)
    public ApiResponse<SalesContracts.ContractView> extendSignBy(@PathVariable UUID orderId,
                                                                 @Valid @RequestBody SalesContractRequests.SignBy request,
                                                                 @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.extendSignBy(user, orderId, request.version(), request.signBy()));
    }

    @GetMapping("/revisions")
    @Operation(summary = "The revisions of the contract that were sent, oldest first: when, by whom, what the customer "
            + "asked for after reading it, and whether the PDF is there")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ApiResponse<List<SalesContracts.RevisionView>> revisions(@PathVariable UUID orderId,
                                                                    @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.revisions(orderId));
    }

    @GetMapping("/revisions/{revision}/document")
    @Operation(summary = "A short-lived link to the PDF the revision was sent as")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ApiResponse<DownloadLink> revisionDocument(@PathVariable UUID orderId, @PathVariable int revision,
                                                      @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.revisionDocument(orderId, revision));
    }

    @PostMapping("/credit-review/withdrawal")
    @Operation(summary = "Take a contract waiting for accounting back, to change it (15 BR-11): creditReview NONE, "
            + "the review WITHDRAWN. 409 CONTRACT_CREDIT_REVIEW_NOT_PENDING when it waits for nothing")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.UPDATE)
    public ApiResponse<SalesContracts.ContractView> withdrawCreditReview(@PathVariable UUID orderId,
                                                                         @Valid @RequestBody
                                                                         SalesContractRequests.Version request,
                                                                         @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.withdrawCreditReview(user, orderId, request.version()));
    }

    @PostMapping("/signed-file/uploads")
    @Operation(summary = "Where the browser uploads the signed contract (a scan or a signed PDF); the contract is sent")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.APPROVE)
    public ApiResponse<UploadSlot> beginSignedFileUpload(@PathVariable UUID orderId,
                                                         @Valid @RequestBody UploadSlotRequest request,
                                                         @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.beginSignedFileUpload(user, orderId, request));
    }

    @PostMapping(path = "/signature", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Record the contract signed (14 §4.3 step 14, BR-14): the signed file uploaded through "
            + "/signed-file/uploads and the day it was signed, not before it was sent nor after today. Immutable from "
            + "then on; the same uploadId again answers the same contract")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.APPROVE)
    public ApiResponse<SalesContracts.ContractView> sign(@PathVariable UUID orderId,
                                                         @Valid @RequestBody SalesContractRequests.Signature request,
                                                         @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.sign(user, orderId, request.version(),
                IncomingFiles.uploaded(user.userId(), List.of(request.uploadId())), request.signedOn()));
    }

    @PostMapping(path = "/signature", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Record the contract signed with the signed file sent here (multipart: file, version, "
            + "signedOn)")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.APPROVE)
    public ApiResponse<SalesContracts.ContractView> signWithFile(@PathVariable UUID orderId,
                                                                 @RequestPart("file") MultipartFile file,
                                                                 @RequestParam long version,
                                                                 @RequestParam LocalDate signedOn,
                                                                 @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "signed-contract"
                : file.getOriginalFilename();
        try (InputStream content = file.getInputStream()) {
            return ApiResponse.ok(contracts.sign(user, orderId, version, IncomingFiles.sent(List.of(
                    new FileUpload(name, file.getContentType(), file.getSize(), content))), signedOn));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    @PostMapping("/cancellation")
    @Operation(summary = "Cancel a draft or sent contract with a reason (14 §4.3 step 16); the order may get a new one. "
            + "A signed contract is never cancelled here")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.UPDATE)
    public ApiResponse<SalesContracts.ContractView> cancel(@PathVariable UUID orderId,
                                                           @Valid @RequestBody SalesContractRequests.Cancellation request,
                                                           @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.cancel(user, orderId, request.version(), request.reason()));
    }

    @GetMapping(value = "/preview", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The contract drawn as a PDF now: what a draft would be sent as")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ResponseEntity<byte[]> preview(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"contract-preview.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(contracts.preview(orderId));
    }

    @GetMapping("/document")
    @Operation(summary = "A short-lived link to the PDF the contract was sent as")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ApiResponse<DownloadLink> document(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.sentDocument(orderId));
    }

    @GetMapping("/signed-file")
    @Operation(summary = "A short-lived link to the signed contract")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ApiResponse<DownloadLink> signedFile(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.signedFile(orderId));
    }

    private static SalesContracts.Draft draft(SalesContractRequests.Draft request) {
        // A null element is passed on as null: the service names it with the rule it breaks, by field.
        return new SalesContracts.Draft(request.freeSampleRounds(),
                request.deliverySchedule() == null ? null : request.deliverySchedule().stream()
                        .map(batch -> batch == null ? null : new SalesContracts.Batch(batch.windowStart(),
                                batch.windowEnd(), batch.lines() == null ? List.of() : batch.lines().stream()
                                        .map(line -> line == null ? null
                                                : new SalesContracts.BatchLine(line.orderLineId(), line.cartons()))
                                        .toList()))
                        .toList(),
                request.paymentPlan() == null ? null : request.paymentPlan().stream()
                        .map(i -> i == null ? null : new SalesContracts.Installment(i.name(), i.milestone(),
                                i.amount(), i.percent(), i.dueDays(), i.deliveryBatchSeq()))
                        .toList(),
                request.returnWindowDays(), agreement(request));
    }

    /** Null when the request says nothing of it, so an edit keeps what the contract has. */
    private static SalesContracts.AgreementInput agreement(SalesContractRequests.Draft request) {
        if (request.specialTerms() == null && request.penaltyPercent() == null
                && request.quantityTolerancePercent() == null && request.warrantyMonths() == null
                && request.sellerSigner() == null && request.buyerSigner() == null) {
            return null;
        }
        return new SalesContracts.AgreementInput(request.specialTerms(), request.penaltyPercent(),
                request.quantityTolerancePercent(), request.warrantyMonths(),
                request.sellerSigner() == null ? null : new SalesContracts.SellerSigner(request.sellerSigner().name(),
                        request.sellerSigner().title(), request.sellerSigner().authorizationRef()),
                request.buyerSigner() == null ? null : new SalesContracts.BuyerSigner(request.buyerSigner().name(),
                        request.buyerSigner().title()));
    }
}
