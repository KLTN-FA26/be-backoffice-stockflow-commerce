package com.stockflow.identity.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.AuditEntry;
import com.stockflow.common.audit.AuditHistory;
import com.stockflow.common.audit.AuditRecord;
import com.stockflow.common.audit.AuditSearch;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.identity.internal.controller.dto.AuditLogResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * The audit trail, read (kltn-docs 00 §2.5): every audited action of the platform - who, what, on which record, when,
 * from where, how it ended - written by {@code @Auditable} and kept 180 days, or 730 for security-relevant ones
 * ({@code AuditRetention}). The documents say changes are audited (13 §4.2 internal prices, 06 §4.1 step 8 the
 * warehouse map: who, when, what); this is where someone reads them.
 *
 * <p>Here, beside the other administration screens, though the trail is the shared base layer's: a controller in
 * {@code common} would belong to no module. The System Admin alone holds {@code platform-audit-logs} - an entry's
 * details and client address may name a person. Nothing here writes: entries are never edited, and only the retention
 * purge removes them.</p>
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit log", description = "The platform's audit trail: search it and open an entry (System Admin)")
class AuditLogController {

    private final AuditHistory history;

    AuditLogController(AuditHistory history) {
        this.history = history;
    }

    @GetMapping
    @Operation(summary = "Audit entries, newest first; every filter optional: actorId, resourceType and resourceId "
            + "(exact), action, outcome (SUCCESS / FAILURE), from (inclusive) and to (exclusive) as ISO instants, "
            + "correlationId (every entry of one request), securityRelevant. size at most 200")
    @RequiresPermission(resource = IdentityResources.AUDIT_LOGS, action = Action.READ)
    public ApiResponse<PageResponse<AuditLogResponse>> search(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditEntry.Outcome outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) Boolean securityRelevant,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        AuditSearch filter = new AuditSearch(actorId, resourceType, resourceId, action, outcome, from, to,
                correlationId, securityRelevant);
        return ApiResponse.ok(history.search(filter, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(AuditLogController::toResponse));
    }

    @GetMapping("/{auditLogId}")
    @Operation(summary = "One audit entry; 404 NOT_FOUND when there is none (or it was purged)")
    @RequiresPermission(resource = IdentityResources.AUDIT_LOGS, action = Action.READ)
    public ApiResponse<AuditLogResponse> get(@PathVariable UUID auditLogId) {
        return ApiResponse.ok(history.find(auditLogId).map(AuditLogController::toResponse)
                .orElseThrow(() -> new NotFoundException("audit-log", auditLogId)));
    }

    private static AuditLogResponse toResponse(AuditRecord record) {
        AuditEntry e = record.entry();
        return new AuditLogResponse(record.id(), e.occurredAt(), e.actorId(), e.actorName(), e.action().name(),
                e.resourceType(), e.resourceId(), e.outcome().name(), e.correlationId(), e.clientAddress(),
                e.details(), record.securityRelevant());
    }
}
