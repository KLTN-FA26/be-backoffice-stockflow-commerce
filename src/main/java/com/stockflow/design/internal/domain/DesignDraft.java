package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.UUID;

/**
 * Login ids are explicit: CRM customer ids must never be assumed to be identity user ids. {@code templateId} is the
 * template a customer's design is drawn on, null for a design staff started.
 */
public record DesignDraft(UUID id, UUID ownerUserId, UUID assignedUserId, DesignStatus status,
                          UUID currentArtifactId, UUID reviewerUserId, UUID templateId) {
    public DesignDraft(UUID id, UUID ownerUserId, UUID assignedUserId, DesignStatus status, UUID currentArtifactId) {
        this(id, ownerUserId, assignedUserId, status, currentArtifactId, null, null);
    }
    public DesignDraft(UUID id, UUID ownerUserId, UUID assignedUserId, DesignStatus status, UUID currentArtifactId,
                       UUID reviewerUserId) {
        this(id, ownerUserId, assignedUserId, status, currentArtifactId, reviewerUserId, null);
    }
    public void requireAccess(UUID userId) {
        if (userId == null || (!userId.equals(ownerUserId) && !userId.equals(assignedUserId) && !userId.equals(reviewerUserId))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    /**
     * Files are added by whoever makes the design - its owner or the designer assigned to it - never by its independent
     * reviewer, who may read it. Checked before an upload is read, so a reviewer is not told no only after the file
     * was downloaded, scanned and stored.
     */
    public void requireEditor(UUID userId) {
        if (userId == null || (!userId.equals(ownerUserId) && !userId.equals(assignedUserId))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the owner or the assigned designer adds files");
        }
    }

    public void requireEditable() {
        if (status != DesignStatus.DRAFT) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only draft designs accept artifact changes");
        }
    }
}
