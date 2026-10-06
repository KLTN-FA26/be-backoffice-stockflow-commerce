package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.UUID;

/** Review decisions are bound to one artifact and one immutable specification revision. */
public record DesignReview(UUID artifactId, UUID reviewerId, boolean passed, String notes) {
    public DesignReview {
        if (artifactId == null || reviewerId == null || notes == null || notes.isBlank() || notes.length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A review needs an artifact, reviewer and evidence");
        }
    }
}
