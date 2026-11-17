package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * A design as its files see it. Login ids are explicit: CRM customer ids must never be assumed to be identity user ids.
 *
 * @param templateId the template a customer's design is drawn on; null for a design staff started
 */
public record DesignDraft(UUID id, UUID ownerUserId, DesignStatus status, UUID currentArtifactId, UUID templateId) {

    /** Whether a customer started it on a template. */
    public boolean customerDesign() {
        return templateId != null;
    }

    /** Its files are read by whoever reads the design ({@link CustomerDesignRules#requireReader}). */
    public void requireAccess(UUID userId, DesignReadRights rights, BooleanSupplier quoteRequestOpen) {
        CustomerDesignRules.requireReader(userId, ownerUserId, status, customerDesign(), rights, quoteRequestOpen);
    }

    /**
     * Files are added by the design's owner only (BR-01); a reviewer reads them. Checked before an upload is read, so
     * nobody else is told no only after the file was downloaded, scanned and stored.
     */
    public void requireEditor(UUID userId) {
        if (userId == null || !userId.equals(ownerUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the design's owner adds files");
        }
    }

    public void requireEditable() {
        if (status != DesignStatus.DRAFT) {
            throw new BusinessException(ErrorCode.DESIGN_NOT_DRAFT, "Only a draft design takes files");
        }
    }
}
