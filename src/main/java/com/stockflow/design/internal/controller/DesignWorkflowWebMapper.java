package com.stockflow.design.internal.controller;

import com.stockflow.design.internal.controller.dto.DesignDecisionResponse;
import com.stockflow.design.internal.controller.dto.DesignDraftResponse;
import com.stockflow.design.internal.controller.dto.DesignSnapshotResponse;
import com.stockflow.design.internal.service.DesignWorkflowService;
import org.springframework.stereotype.Component;

@Component
class DesignWorkflowWebMapper {
    DesignDraftResponse toResponse(DesignWorkflowService.DraftView d) {
        return new DesignDraftResponse(d.id(), d.customerId(), d.productId(), d.ownerUserId(), d.name(), d.spec(),
                d.status(), d.currentArtifactId(), d.reviewedBy(), d.reviewNotes(), d.version(), d.parentSnapshotId(),
                d.productSlug(), d.sku(), d.templateCode(), d.reviewRequestedAt(), d.reviewedAt(), d.updatedAt(),
                d.currentArtifactId(), d.projectId(), d.previewUrl(), d.variantName(), d.productName());
    }

    DesignSnapshotResponse toResponse(DesignWorkflowService.SnapshotView s) {
        return new DesignSnapshotResponse(s.id(), s.draftId(), s.artifactId(), s.checksum(), s.spec(),
                s.confirmedBy(), s.confirmedAt(), s.artifacts().stream()
                .map(a -> new DesignSnapshotResponse.Artifact(a.artifactId(), a.role(), a.originalName(),
                        a.contentType(), a.sizeBytes(), a.checksum()))
                .toList(), s.document(), s.documentChecksum(), s.assets() == null ? null : s.assets().stream()
                .map(a -> new DesignSnapshotResponse.Asset(a.assetId(), a.originalName(), a.contentType(),
                        a.sizeBytes(), a.checksum(), a.widthPx(), a.heightPx()))
                .toList(), s.customerSku());
    }

    DesignDecisionResponse toResponse(DesignWorkflowService.DecisionView d) {
        return new DesignDecisionResponse(d.actorId(), d.artifactId(), d.decision(), d.notes(), d.version(), d.at());
    }
}
