package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.order.internal.service.ContractSigning;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.signing.ContractSignOtps;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.stockflow.order.internal.controller.dto.SalesContractRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The contract of one of the signed-in customer's own orders, once Sales has sent it (4B-4; kltn-docs 14 §4.3 step 13:
 * the customer reads the contract on the portal). The customer is resolved from the sign-in, never from the request;
 * someone else's order, a draft or no contract is 404 either way. Handlers are public so the permission proxy advises
 * them.
 */
@RestController
@RequestMapping("/api/v1/orders/me/{orderId}/contract")
@Tag(name = "Orders")
class CustomerSalesContractController {

    private final SalesContracts contracts;
    private final ContractSigning signing;
    private final CustomerService customers;

    CustomerSalesContractController(SalesContracts contracts, ContractSigning signing, CustomerService customers) {
        this.contracts = contracts;
        this.signing = signing;
        this.customers = customers;
    }

    @GetMapping
    @Operation(summary = "The contract of one of the signed-in customer's own orders, once sent")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<SalesContracts.ContractView> get(@PathVariable UUID orderId,
                                                        @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(contracts.customerContract(ownCustomerId(user), orderId));
    }

    @GetMapping("/document")
    @Operation(summary = "A short-lived link to the contract as signed once it is, else as sent; copy=sent asks for the "
            + "PDF as sent even after signing")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<DownloadLink> document(@PathVariable UUID orderId,
                                              @RequestParam(name = "copy", required = false) String copy,
                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(contracts.customerDocument(ownCustomerId(user), orderId, "sent".equalsIgnoreCase(copy)));
    }

    @GetMapping("/revisions")
    @Operation(summary = "The revisions of the contract that were sent to the signed-in customer, with what they asked "
            + "for after reading each; no sender is named")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<java.util.List<SalesContracts.RevisionView>> revisions(@PathVariable UUID orderId,
                                                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(contracts.customerRevisions(ownCustomerId(user), orderId));
    }

    @GetMapping("/revisions/{revision}/document")
    @Operation(summary = "A short-lived link to the PDF that revision was sent as")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<DownloadLink> revisionDocument(@PathVariable UUID orderId, @PathVariable int revision,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(contracts.customerRevisionDocument(ownCustomerId(user), orderId, revision));
    }

    @PostMapping("/change-request")
    @Operation(summary = "Send the contract back for changes: what to change, 10 to 2000 characters. SENT -> DRAFT as the "
            + "next revision (the sent PDF is kept as the revision's), and the salesperson who looks after the "
            + "customer is told. 409 SALES_CONTRACT_STATUS_INVALID unless the contract is sent and not yet signed by "
            + "the customer; 409 OPTIMISTIC_LOCK for a stale version. The answer is the contract as the portal shows it: "
            + "status CHANGES_REQUESTED")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.UPDATE, scope = DataScope.OWN)
    public ApiResponse<SalesContracts.ContractView> requestChanges(@PathVariable UUID orderId,
                                                                   @Valid @RequestBody
                                                                   SalesContractRequests.ChangeRequest request,
                                                                   @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(contracts.requestChanges(ownCustomerId(user), user.userId(), orderId, request.version(),
                request.reason()));
    }

    @PostMapping("/sign/otp")
    @Operation(summary = "Mail a one-time code to the signed-in customer's own e-mail address, to sign the contract with "
            + "(QD-3). The answer says where it went (masked), when it stops working and when another may be asked for. "
            + "409 CONTRACT_NOT_SIGNABLE unless the contract is sent; 409 CONTRACT_SIGN_DEADLINE_PASSED past the day to "
            + "sign by; 429 CONTRACT_OTP_TOO_MANY when asked for again too soon or too often")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.UPDATE, scope = DataScope.OWN)
    @RateLimit(limit = 10, perSeconds = 3600, key = RateLimit.Key.USER, bucket = "contract-sign-otp")
    public ApiResponse<ContractSignOtps.Issued> requestOtp(@PathVariable UUID orderId,
                                                           @AuthenticatedUser CurrentUser user,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(signing.requestOtp(user, orderId, request.getRemoteAddr()));
    }

    @PostMapping("/sign")
    @Operation(summary = "Sign the contract with the code: SENT -> CUSTOMER_SIGNED. Signing is agreeing to the contract as "
            + "it reads, so there is no separate step. The version read, the six digits, the signer's name and title and "
            + "consent:true to sign electronically. The address and browser of the request and the SHA-256 of the PDF "
            + "that was sent are kept as evidence. 422 CONTRACT_OTP_INVALID (details.attemptsLeft) / "
            + "CONTRACT_OTP_EXPIRED; 409 CONTRACT_NOT_SIGNABLE / CONTRACT_SIGN_DEADLINE_PASSED / OPTIMISTIC_LOCK")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.UPDATE, scope = DataScope.OWN)
    @RateLimit(limit = 10, perSeconds = 60, key = RateLimit.Key.USER, bucket = "contract-sign")
    public ApiResponse<SalesContracts.ContractView> sign(@PathVariable UUID orderId,
                                                         @Valid @RequestBody SalesContractRequests.CustomerSign request,
                                                         @AuthenticatedUser CurrentUser user,
                                                         HttpServletRequest http) {
        return ApiResponse.ok(signing.customerSignWithOtp(user, orderId, request.version(), request.otp(),
                request.signerName(), request.signerTitle(), Boolean.TRUE.equals(request.consent()), origin(http)));
    }

    @PostMapping(path = "/sign/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Sign the contract by sending back a signed PDF (multipart: file, version, signerName, "
            + "signerTitle): a scan of the paper signed by hand is accepted and marked as having no digital signature; a "
            + "digital signature must hold and be over exactly the contract that was sent. 422 "
            + "CONTRACT_SIGNED_FILE_REJECTED (details.reason) for a file that cannot be read or a signature that does not "
            + "hold")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.UPDATE, scope = DataScope.OWN)
    @RateLimit(limit = 5, perSeconds = 60, key = RateLimit.Key.USER, bucket = "contract-sign-upload")
    public ApiResponse<SalesContracts.ContractView> signWithFile(@PathVariable UUID orderId,
                                                                 @RequestPart("file") MultipartFile file,
                                                                 @RequestParam long version,
                                                                 @RequestParam String signerName,
                                                                 @RequestParam String signerTitle,
                                                                 @AuthenticatedUser CurrentUser user,
                                                                 HttpServletRequest http) {
        return ApiResponse.ok(signing.customerSignWithFile(user, orderId, version, signerName, signerTitle,
                SignedFileUploads.name(file), SignedFileUploads.read(file), origin(http)));
    }

    @GetMapping("/signature-page")
    @Operation(summary = "A short-lived link to the one-page certificate of the signed contract: who signed, how and when, "
            + "the SHA-256 of the sent PDF, the verification code. 404 until the company has signed")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<DownloadLink> signaturePage(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(signing.customerCertificatePage(user, orderId));
    }

    /** Where the request came from, as the request logging resolves it: the address the connector reports. */
    private static ContractSigning.Origin origin(HttpServletRequest request) {
        return new ContractSigning.Origin(request.getRemoteAddr(), request.getHeader("User-Agent"));
    }

    private UUID ownCustomerId(CurrentUser user) {
        return customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
    }
}
