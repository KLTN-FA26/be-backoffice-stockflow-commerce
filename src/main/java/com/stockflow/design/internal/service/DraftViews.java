package com.stockflow.design.internal.service;

import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.repository.DesignArtifactJpaRepository;
import com.stockflow.design.internal.repository.DesignDocumentJpaRepository;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Designs as the API shows them, for both the staff workflow and a customer's own designs. A page costs one read of
 * the layouts' save times, one of its previews' storage keys, one of its variants' names, and one product read per
 * distinct product, whatever its size.
 *
 * <p>A preview's link is a view link ({@link FileTransfers#viewLink}): signed locally, with no existence check and no
 * export audit, because a page of twenty designs must not cost twenty round trips to the store to draw its thumbnails.
 * Saving the file under its name stays the artifact's own download link, checked and audited.</p>
 */
@Component
class DraftViews {

    private final ProductService products;
    private final DesignDocumentJpaRepository documents;
    private final DesignArtifactJpaRepository artifacts;
    private final FileTransfers files;

    DraftViews(ProductService products, DesignDocumentJpaRepository documents, DesignArtifactJpaRepository artifacts,
               FileTransfers files) {
        this.products = products;
        this.documents = documents;
        this.artifacts = artifacts;
        this.files = files;
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
        Map<UUID, Optional<ProductSummary>> productsById = new HashMap<>();
        for (DesignDraftJpaEntity draft : drafts) {
            productsById.computeIfAbsent(draft.getProductId(), products::findById);
        }
        List<String> skus = drafts.stream().map(DesignDraftJpaEntity::getSku).filter(Objects::nonNull).distinct()
                .toList();
        Map<String, String> variantNames = skus.isEmpty() ? Map.of() : products.variantNamesOf(skus);
        Map<UUID, String> previews = previewLinks(drafts);
        return drafts.stream().map(d -> {
            Optional<ProductSummary> product = productsById.get(d.getProductId());
            return view(d, product.map(ProductSummary::slug).orElse(null),
                    product.map(ProductSummary::name).orElse(null), saved.get(d.getId()),
                    d.getCurrentArtifactId() == null ? null : previews.get(d.getCurrentArtifactId()),
                    variantName(d.getSku(), variantNames));
        }).toList();
    }

    /** A link showing each current preview, by artifact id; none for a store that signs no browser links. */
    private Map<UUID, String> previewLinks(List<DesignDraftJpaEntity> drafts) {
        List<UUID> ids = drafts.stream().map(DesignDraftJpaEntity::getCurrentArtifactId).filter(Objects::nonNull)
                .distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> links = new HashMap<>();
        for (DesignArtifactJpaRepository.StoredAt stored : artifacts.storedAt(ids, DesignArtifactRole.CUSTOMER_PREVIEW)) {
            files.tryViewLink(stored.getStorageKey()).map(DownloadLink::url)
                    .ifPresent(url -> links.put(stored.getId(), url));
        }
        return links;
    }

    /** The variant's name (Q-4), or its SKU when the variant has no name to show; none for a design on no variant. */
    private static String variantName(String sku, Map<String, String> names) {
        if (sku == null) {
            return null;
        }
        String name = names.get(sku);
        return name == null || name.isBlank() ? sku : name;
    }

    /**
     * When the design last changed: the row itself, or its layout, whichever is later. Saving a layout moves the
     * design's version without rewriting its row, so the row's own timestamp alone would not show it.
     */
    private static DesignWorkflowService.DraftView view(DesignDraftJpaEntity d, String slug, String productName,
                                                        Instant layoutSaved, String previewUrl, String variantName) {
        Instant rowChanged = d.getLastModifiedAt() != null ? d.getLastModifiedAt() : d.getCreatedAt();
        Instant updatedAt = layoutSaved != null && (rowChanged == null || layoutSaved.isAfter(rowChanged))
                ? layoutSaved : rowChanged;
        return new DesignWorkflowService.DraftView(d.getId(), d.getCustomerId(), d.getProductId(), d.getOwnerUserId(),
                d.getName(), d.getSpec(), d.getStatus().name(), d.getCurrentArtifactId(), d.getReviewedBy(),
                d.getReviewNotes(), d.getVersion(), d.getParentSnapshotId(), slug, d.getSku(), d.getTemplateCode(),
                d.getReviewRequestedAt(), d.getReviewedAt(), updatedAt, d.getProjectId(), previewUrl, variantName,
                productName);
    }
}
