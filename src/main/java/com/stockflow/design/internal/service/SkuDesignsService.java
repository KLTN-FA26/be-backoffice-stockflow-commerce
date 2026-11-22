package com.stockflow.design.internal.service;

import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.api.SkuDesign;
import com.stockflow.design.api.SkuDesigns;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.design.internal.repository.DesignArtifactJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The design of each customer SKU, in four statements whatever the count: the SKUs (product), their snapshots, the
 * snapshots' designs, the designs' preview files. The SKU is issued to one approved snapshot (12 BR-09) and a design is
 * frozen once approved, so the design's current preview is the one approved.
 *
 * <p>The preview's link is a view link ({@link FileTransfers#viewLink}), as {@code DraftViews} draws a page of designs:
 * signed locally, with no existence check and no export audit, so a page of lines does not cost a round trip to the
 * store per thumbnail.</p>
 */
@Service
@Transactional(readOnly = true)
class SkuDesignsService implements SkuDesigns {

    private final CustomerSkus customerSkus;
    private final DesignSnapshotJpaRepository snapshots;
    private final DesignDraftJpaRepository drafts;
    private final DesignArtifactJpaRepository artifacts;
    private final FileTransfers files;

    SkuDesignsService(CustomerSkus customerSkus, DesignSnapshotJpaRepository snapshots, DesignDraftJpaRepository drafts,
                      DesignArtifactJpaRepository artifacts, FileTransfers files) {
        this.customerSkus = customerSkus;
        this.snapshots = snapshots;
        this.drafts = drafts;
        this.artifacts = artifacts;
        this.files = files;
    }

    @Override
    public Map<String, SkuDesign> ofSkus(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return Map.of();
        }
        Map<String, CustomerSku> owned = customerSkus.findAll(skus.stream().filter(Objects::nonNull).distinct().toList());
        if (owned.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> draftOfSnapshot = snapshots.findAllById(owned.values().stream()
                        .map(CustomerSku::designSnapshotId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(DesignSnapshotJpaEntity::getId, DesignSnapshotJpaEntity::getDraftId));
        Map<UUID, DesignDraftJpaEntity> designs = drafts.findAllById(draftOfSnapshot.values().stream().distinct()
                .toList()).stream().collect(Collectors.toMap(DesignDraftJpaEntity::getId, Function.identity()));
        Map<UUID, String> previews = previews(designs.values());
        Map<String, SkuDesign> found = new HashMap<>();
        owned.forEach((code, sku) -> {
            DesignDraftJpaEntity design = designs.get(draftOfSnapshot.get(sku.designSnapshotId()));
            if (design != null) {
                String name = design.getName() == null || design.getName().isBlank() ? sku.name() : design.getName();
                found.put(code, new SkuDesign(code, design.getId(), name,
                        design.getCurrentArtifactId() == null ? null : previews.get(design.getCurrentArtifactId())));
            }
        });
        return found;
    }

    /** A link showing each design's preview, by artifact id; none for a store that signs no browser links. */
    private Map<UUID, String> previews(Collection<DesignDraftJpaEntity> designs) {
        var ids = designs.stream().map(DesignDraftJpaEntity::getCurrentArtifactId).filter(Objects::nonNull).distinct()
                .toList();
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
}
