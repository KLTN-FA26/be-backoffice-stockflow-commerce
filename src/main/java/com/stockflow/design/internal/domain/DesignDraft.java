package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.UUID;

/** Login ids are explicit: CRM customer ids must never be assumed to be identity user ids. */
public record DesignDraft(UUID id, UUID ownerUserId, UUID assignedUserId, DesignStatus status,
                          UUID currentArtifactId, UUID reviewerUserId) {
    public DesignDraft(UUID id, UUID ownerUserId, UUID assignedUserId, DesignStatus status, UUID currentArtifactId) {
        this(id, ownerUserId, assignedUserId, status, currentArtifactId, null);
    }
    public void requireAccess(UUID userId) {
        if (userId == null || (!userId.equals(ownerUserId) && !userId.equals(assignedUserId) && !userId.equals(reviewerUserId))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    public void requireEditable() {
        if (status != DesignStatus.DRAFT) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only draft designs accept artifact changes");
        }
    }
}
