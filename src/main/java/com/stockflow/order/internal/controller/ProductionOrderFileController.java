package com.stockflow.order.internal.controller;

import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.order.internal.service.ProductionOrderDispatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * The links a producer is e-mailed with its production order (4A-3, kltn-docs 17 §4.3 step 7: the slip, and the print
 * files "with a checksum checked on download"). A producer holds no account: the link itself - signed, time-limited
 * ({@code SignedLinks}) - is the credential, so these routes are open to anyone (permitAll, GET and HEAD) and everything is
 * checked by the service on each use: the signature, the production order still open, the file's recorded digest
 * still the approved one (the object store's digest metadata, not a fresh hash of the bytes).
 * Then the answer is a redirect to a presigned URL of minutes, so the bytes never pass through the application.
 *
 * <p>The redirect's target is never logged, and the request log keeps no {@code signature} parameter. The responses are
 * not cached ({@code no-store}) and send no referrer on.</p>
 */
@RestController
@RequestMapping("/api/v1/production-order-files/{id}")
@Tag(name = "Production order files", description = "A producer's signed links; no sign-in")
class ProductionOrderFileController {

    private final ProductionOrderDispatch dispatch;

    ProductionOrderFileController(ProductionOrderDispatch dispatch) {
        this.dispatch = dispatch;
    }

    @GetMapping("/slip")
    @RateLimit(limit = 60, perSeconds = 60, key = RateLimit.Key.IP, bucket = "production-order-files")
    @Operation(summary = "The production order slip, by the signed link its producer was e-mailed: 302 to a presigned "
            + "URL. 403 PRODUCTION_ORDER_LINK_INVALID, 410 PRODUCTION_ORDER_LINK_EXPIRED, 409 "
            + "PRODUCTION_ORDER_SLIP_MISMATCH")
    public ResponseEntity<Void> slip(@PathVariable UUID id, @RequestParam long expires,
                                     @RequestParam String signature) {
        return redirect(dispatch.openSlip(id, expires, signature));
    }

    @GetMapping("/print-files/{designSnapshotId}")
    @RateLimit(limit = 60, perSeconds = 60, key = RateLimit.Key.IP, bucket = "production-order-files")
    @Operation(summary = "A print file of the production order, by the signed link its producer was e-mailed, after "
            + "checking it against the approved design: 302 to a presigned URL. 403 PRODUCTION_ORDER_LINK_INVALID, "
            + "410 PRODUCTION_ORDER_LINK_EXPIRED, 409 PRINT_FILE_MISMATCH")
    public ResponseEntity<Void> printFile(@PathVariable UUID id, @PathVariable UUID designSnapshotId,
                                          @RequestParam long expires, @RequestParam String signature) {
        return redirect(dispatch.openPrintFile(id, designSnapshotId, expires, signature));
    }

    private static ResponseEntity<Void> redirect(DownloadLink link) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.url()))
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").build();
    }
}
