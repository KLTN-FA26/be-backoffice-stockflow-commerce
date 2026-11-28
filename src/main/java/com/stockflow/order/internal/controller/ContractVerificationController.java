package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.order.internal.service.ContractSigning;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The public page behind the QR code on a signed contract's certificate page: anyone holding the code - nobody signs in -
 * may read who the contract is between and when it was signed, and ask whether a file is the signed contract
 * ({@code permitAll} in the security configuration, limited per address).
 *
 * <p>The code is the only credential and it is unguessable (about 59 bits). A code that is not one, and one nobody holds,
 * answer the same 404, so the endpoint is no oracle beyond "this code is a signed contract's". No amounts and no person
 * are returned, only the two companies' names, the status, the two dates and the file's SHA-256.</p>
 */
@RestController
@RequestMapping("/api/v1/public/contract-verifications")
@Tag(name = "Orders")
class ContractVerificationController {

    /** Whether the file sent is the signed contract. */
    record Match(boolean matches) { }

    private final ContractSigning signing;

    ContractVerificationController(ContractSigning signing) {
        this.signing = signing;
    }

    @GetMapping("/{code}")
    @Operation(summary = "What is known of the signed contract behind a verification code: number, revision, the two "
            + "companies, status, when each side signed, the SHA-256 of the signed file and whether the certificate is the "
            + "system's own trial one. 404 for any code that is not one")
    @RateLimit(limit = 30, perSeconds = 60, key = RateLimit.Key.IP, bucket = "contract-verification")
    public ApiResponse<ContractSigning.Verification> verify(@PathVariable String code) {
        return ApiResponse.ok(signing.verification(code).orElseThrow(ContractVerificationController::unknown));
    }

    @PostMapping(path = "/{code}/check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Whether the file sent is, byte for byte, the signed contract behind the code (its SHA-256 equals "
            + "the signed file's). 404 for any code that is not one")
    @RateLimit(limit = 10, perSeconds = 60, key = RateLimit.Key.IP, bucket = "contract-verification-check")
    public ApiResponse<Match> check(@PathVariable String code, @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(new Match(signing.matches(code, SignedFileUploads.read(file))
                .orElseThrow(ContractVerificationController::unknown)));
    }

    private static BusinessException unknown() {
        return new BusinessException(ErrorCode.NOT_FOUND, "No signed contract has that verification code");
    }
}
