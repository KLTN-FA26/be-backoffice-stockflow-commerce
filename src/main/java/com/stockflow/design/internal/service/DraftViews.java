package com.stockflow.design.internal.service;

import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.repository.DesignDocumentJpaRepository;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Designs as the API shows them, for both the staff workflow and a customer's own designs. A page costs one read of
 * the layouts' save times and one product read per distinct product, whatever its size.
 */
@Component
class DraftViews {

    private final ProductService products;
    private final DesignDocumentJpaRepository documents;

    DraftViews(ProductService products, DesignDocumentJpaRepository documents) {
        this.products = products;
        this.documents = documents;
    }

    DesignWorkflowService.DraftView of(DesignDraftJpaEntity draft) {
        return of(List.of(draft)).get(0);
    }

    List<DesignWorkflowService.DraftView> of(List<DesignDraftJpaEntity> drafts) {
        if (drafts.isEmpty()) {
            return List.of();
        }
        Map<UUID, Instant> saved = documents.savedAt(drafts.stream().map(DesignDraftJpaEntity::getId).toList())
                .stream().collect(Collectors.toMap(DesignDocumentJpaRepository.SavedAt::getDraftId,
                        DesignDocumentJpaRepository.SavedAt::getUpdatedAt));
        Map<UUID, String> slugs = new HashMap<>();
        for (DesignDraftJpaEntity draft : drafts) {
            slugs.computeIfAbsent(draft.getProductId(),
                    id -> products.findById(id).map(ProductSummary::slug).orElse(null));
        }
        return drafts.stream().map(d -> view(d, slugs.get(d.getProductId()), saved.get(d.getId()))).toList();
    }

    /**
     * When the design last changed: the row itself, or its layout, whichever is later. Saving a layout moves the
     * design's version without rewriting its row, so the row's own timestamp alone would not show it.
     */
    private static DesignWorkflowService.DraftView view(DesignDraftJpaEntity d, String slug, Instant layoutSaved) {
        Instant rowChanged = d.getLastModifiedAt() != null ? d.getLastModifiedAt() : d.getCreatedAt();
        Instant updatedAt = layoutSaved != null && (rowChanged == null || layoutSaved.isAfter(rowChanged))
                ? layoutSaved : rowChanged;
        return new DesignWorkflowService.DraftView(d.getId(), d.getCustomerId(), d.getProductId(), d.getOwnerUserId(),
                d.getName(), d.getSpec(), d.getStatus().name(), d.getCurrentArtifactId(), d.getReviewedBy(),
                d.getReviewNotes(), d.getVersion(), d.getParentSnapshotId(), slug, d.getSku(), d.getTemplateCode(),
                d.getReviewRequestedAt(), d.getReviewedAt(), updatedAt, d.getProjectId());
    }
}
