package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.design.internal.controller.dto.ConfirmedSnapshotResponse;
import com.stockflow.design.internal.controller.dto.DesignSnapshotResponse;
import com.stockflow.design.internal.controller.dto.SnapshotPreviewResponse;
import com.stockflow.design.internal.service.DesignSnapshotQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Confirmed design snapshots of a customer, for the back office choosing one (SCRUM-403/448). Drafts are not here:
 * they stay with their owner, designer and reviewer ({@code GET /designs}).
 */
@RestController
@RequestMapping("/api/v1/design-snapshots")
@Tag(name = "Design snapshots", description = "Customer-confirmed, immutable designs a quote, sample or order points at")
@PermissionResource(code = DesignResources.SNAPSHOTS, group = "Design", label = "Confirmed designs",
        apiPath = "/api/v1/design-snapshots", actions = {Action.VIEW_PAGE, Action.READ})
class DesignSnapshotController {

    private final DesignSnapshotQueryService snapshots;

    DesignSnapshotController(DesignSnapshotQueryService snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping
    @Operation(summary = "The confirmed design snapshots of one customer, newest first; productId or sku narrows to "
            + "designs of that product. Back office only; 404 CUSTOMER_NOT_FOUND for an unknown customer")
    @RequiresPermission(resource = DesignResources.SNAPSHOTS, action = Action.READ)
    public ApiResponse<PageResponse<ConfirmedSnapshotResponse>> list(@RequestParam UUID customerId,
            @RequestParam(required = false) UUID productId, @RequestParam(required = false) String sku,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        var found = snapshots.confirmedOf(user, customerId, productId, sku,
                Pages.of(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size));
        return ApiResponse.ok(Pages.toResponse(found, s -> new ConfirmedSnapshotResponse(s.snapshotId(), s.draftId(),
                s.designName(), s.customerId(), s.productId(), s.checksum(), s.spec(), s.confirmedAt(), s.confirmedBy(),
                s.artifacts().stream().map(a -> new DesignSnapshotResponse.Artifact(a.artifactId(), a.role(),
                        a.originalName(), a.contentType(), a.sizeBytes(), a.checksum())).toList())));
    }

    @GetMapping("/{snapshotId}/preview")
    @Operation(summary = "A short-lived link to the CUSTOMER_PREVIEW the customer confirmed; the snapshot must be the "
            + "named customer's (404 otherwise), and is verified against its checksum first (409 when it changed)")
    @RequiresPermission(resource = DesignResources.SNAPSHOTS, action = Action.READ)
    public ResponseEntity<ApiResponse<SnapshotPreviewResponse>> preview(@PathVariable UUID snapshotId,
            @RequestParam UUID customerId, @AuthenticatedUser CurrentUser user) {
        var p = snapshots.preview(user, snapshotId, customerId);
        // The body carries a bearer URL: never kept by a shared cache or the browser's.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(new SnapshotPreviewResponse(
                p.snapshotId(), p.artifactId(), p.originalName(), p.contentType(), p.sizeBytes(), p.checksum(), p.url(),
                p.expiresAt())));
    }
}
