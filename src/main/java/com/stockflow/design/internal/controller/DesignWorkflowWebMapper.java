package com.stockflow.design.internal.controller;

import com.stockflow.design.internal.controller.dto.DesignDecisionResponse;
import com.stockflow.design.internal.controller.dto.DesignDraftResponse;
import com.stockflow.design.internal.controller.dto.DesignSnapshotResponse;
import com.stockflow.design.internal.controller.dto.DesignProjectEventResponse;
import com.stockflow.design.internal.service.DesignProjectService;
import com.stockflow.design.internal.service.DesignWorkflowService;
import com.stockflow.identity.api.IdentityService;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * Designs, their history and their projects' history as the API shows them. Who took each step is named here, from the
 * ids on a whole page in one read ({@link IdentityService#displayNames}), never one read per row.
 */
@Component
class DesignWorkflowWebMapper {

    private final IdentityService identities;

    DesignWorkflowWebMapper(IdentityService identities) {
        this.identities = identities;
    }

    /** The display name of each user among {@code ids}, in one read; absent for a null or unknown id. */
    Map<UUID, String> names(Collection<UUID> ids) {
        var known = ids.stream().filter(Objects::nonNull).distinct().toList();
        return known.isEmpty() ? Map.of() : identities.displayNames(known);
    }

    DesignDraftResponse toResponse(DesignWorkflowService.DraftView d) {
        return new DesignDraftResponse(d.id(), d.customerId(), d.productId(), d.ownerUserId(), d.name(), d.spec(),
                d.status(), d.currentArtifactId(), d.reviewedBy(), d.reviewNotes(), d.version(), d.parentSnapshotId(),
                d.productSlug(), d.sku(), d.templateCode(), d.reviewRequestedAt(), d.reviewedAt(), d.updatedAt(),
                d.currentArtifactId(), d.projectId(), d.previewUrl(), d.variantName(), d.productName(),
                d.customerSku(), d.customerName(), d.unitsPerCarton(), d.projectName(), d.projectStatus(),
                d.reviewedByName());
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
        return toResponse(d, names(java.util.Collections.singletonList(d.actorId())));
    }

    /** A page of a design's history, its actors named in one read. */
    Page<DesignDecisionResponse> decisions(Page<DesignWorkflowService.DecisionView> page) {
        Map<UUID, String> names = names(page.getContent().stream().map(DesignWorkflowService.DecisionView::actorId)
                .toList());
        return page.map(d -> toResponse(d, names));
    }

    private static DesignDecisionResponse toResponse(DesignWorkflowService.DecisionView d, Map<UUID, String> names) {
        return new DesignDecisionResponse(d.actorId(), d.artifactId(), d.decision(), d.notes(), d.version(), d.at(),
                d.actorId() == null ? null : names.get(d.actorId()));
    }

    DesignProjectEventResponse toResponse(DesignProjectService.EventView e) {
        return toResponse(e, names(java.util.Collections.singletonList(e.actorId())));
    }

    /** A page of a project's history, its actors named in one read. */
    Page<DesignProjectEventResponse> events(Page<DesignProjectService.EventView> page) {
        Map<UUID, String> names = names(page.getContent().stream().map(DesignProjectService.EventView::actorId)
                .toList());
        return page.map(e -> toResponse(e, names));
    }

    private static DesignProjectEventResponse toResponse(DesignProjectService.EventView e, Map<UUID, String> names) {
        return new DesignProjectEventResponse(e.actorId(), e.event(), e.notes(), e.createdAt(),
                e.actorId() == null ? null : names.get(e.actorId()));
    }
}
