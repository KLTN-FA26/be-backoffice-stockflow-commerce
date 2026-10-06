package com.stockflow.design.internal.controller;

import com.stockflow.design.internal.controller.dto.DesignDecisionResponse;
import com.stockflow.design.internal.controller.dto.DesignDraftResponse;
import com.stockflow.design.internal.controller.dto.DesignSnapshotResponse;
import com.stockflow.design.internal.service.DesignWorkflowService;
import org.springframework.stereotype.Component;

@Component
class DesignWorkflowWebMapper {
    DesignDraftResponse toResponse(DesignWorkflowService.DraftView d) {
        return new DesignDraftResponse(d.id(), d.customerId(), d.productId(), d.ownerUserId(),
                d.assignedUserId(), d.reviewerUserId(), d.name(), d.spec(), d.status(),
                d.currentArtifactId(), d.technicalReviewPassed(), d.reviewNotes(), d.version(),
                d.parentSnapshotId());
    }

    DesignSnapshotResponse toResponse(DesignWorkflowService.SnapshotView s) {
        return new DesignSnapshotResponse(s.id(), s.draftId(), s.artifactId(), s.checksum(), s.spec(),
                s.confirmedBy(), s.confirmedAt(), s.artifacts().stream()
                .map(a -> new DesignSnapshotResponse.Artifact(a.artifactId(), a.role(), a.originalName(),
                        a.contentType(), a.sizeBytes(), a.checksum()))
                .toList());
    }

    DesignDecisionResponse toResponse(DesignWorkflowService.DecisionView d) {
        return new DesignDecisionResponse(d.actorId(), d.artifactId(), d.decision(), d.notes(), d.version(), d.at());
    }
}
