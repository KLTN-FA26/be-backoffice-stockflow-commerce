package com.stockflow.design.internal.service;

import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.design.internal.repository.DesignArtifactJpaRepository;
import com.stockflow.design.internal.repository.DesignDocumentJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Designs as the API shows them, for both the staff workflow and a customer's own designs. A page costs one read of
 * the layouts' save times, one of its previews' storage keys, one of its variants' names, one of its customers' names,
 * one of its approved designs' snapshots and one of those snapshots' SKUs, one of its projects, one of its reviewers'
 * names, and one product read per distinct product, whatever its size.
 *
 * <p>The project's name and status and the reviewer's name ride on every design too: without them the studio read every
 * project of the customer, and one more per project it missed, each time it drew a list of designs, and could not say
 * who returned or approved a design at all (it held only the reviewer's id).</p>
 *
 * <p>The customer's own SKU of an approved design and the customer's name ride on every design (FE request BE-2): the
 * snapshot that carries the SKU is its owner's alone, and a reviewer no longer reads a design once it is decided, so
 * without them a list of approved designs would cost a snapshot read per row and the review queue could not say whose
 * each design is. Both are read for the whole page, as the quote requests read them.</p>
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
    private final DesignSnapshotJpaRepository snapshots;
    private final CustomerSkus customerSkus;
    private final CustomerService customers;
    private final com.stockflow.inventory.api.UomConversionService units;
    private final DesignProjectJpaRepository projects;
    private final IdentityService identities;

    DraftViews(ProductService products, DesignDocumentJpaRepository documents, DesignArtifactJpaRepository artifacts,
               FileTransfers files, DesignSnapshotJpaRepository snapshots, CustomerSkus customerSkus,
               CustomerService customers, com.stockflow.inventory.api.UomConversionService units,
               DesignProjectJpaRepository projects, IdentityService identities) {
        this.units = units;
        this.projects = projects;
        this.identities = identities;
        this.products = products;
        this.documents = documents;
        this.artifacts = artifacts;
        this.files = files;
        this.snapshots = snapshots;
        this.customerSkus = customerSkus;
        this.customers = customers;
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
        Map<UUID, String> customerSkuOf = customerSkusOf(drafts);
        Set<UUID> customerIds = drafts.stream().map(DesignDraftJpaEntity::getCustomerId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> customerNames = customers.fullNames(customerIds);
        Map<String, Integer> perCarton = piecesPerCarton(drafts);
        Map<UUID, DesignProjectJpaEntity> projectsById = projectsOf(drafts);
        Set<UUID> reviewers = drafts.stream().map(DesignDraftJpaEntity::getReviewedBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> reviewerNames = reviewers.isEmpty() ? Map.of() : identities.displayNames(reviewers);
        return drafts.stream().map(d -> {
            Optional<ProductSummary> product = productsById.get(d.getProductId());
            DesignProjectJpaEntity project = d.getProjectId() == null ? null : projectsById.get(d.getProjectId());
            return view(d, product.map(ProductSummary::slug).orElse(null),
                    product.map(ProductSummary::name).orElse(null), saved.get(d.getId()),
                    d.getCurrentArtifactId() == null ? null : previews.get(d.getCurrentArtifactId()),
                    variantName(d.getSku(), variantNames), customerSkuOf.get(d.getId()),
                    d.getCustomerId() == null ? null : customerNames.get(d.getCustomerId()),
                    d.getSku() == null ? null : perCarton.get(d.getSku()), project,
                    d.getReviewedBy() == null ? null : reviewerNames.get(d.getReviewedBy()));
        }).toList();
    }

    /** The projects the page's designs are in, by id, in one read. */
    private Map<UUID, DesignProjectJpaEntity> projectsOf(List<DesignDraftJpaEntity> drafts) {
        Set<UUID> ids = drafts.stream().map(DesignDraftJpaEntity::getProjectId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return projects.findAllById(ids).stream()
                .collect(Collectors.toMap(DesignProjectJpaEntity::getId, project -> project));
    }

    /**
     * R5-13: the pieces in one carton of each design, by the catalogue variant it is drawn on, in one read for the
     * page. The carton is the variant's (owner decision Q1): the customer's SKU an approval issues is packed in it and
     * has none of its own, so the variant answers for an approved design as for a draft. The carton is the variant's
     * one carton unit, as a quote line reads it ({@link com.stockflow.inventory.api.UomConversion#cartonOf}); a variant
     * without one is absent.
     */
    private Map<String, Integer> piecesPerCarton(List<DesignDraftJpaEntity> drafts) {
        List<String> skus = drafts.stream().map(DesignDraftJpaEntity::getSku)
                .filter(Objects::nonNull).distinct().toList();
        if (skus.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> perCarton = new HashMap<>();
        units.conversionsOf(skus.stream().map(com.stockflow.common.domain.Sku::new).toList())
                .forEach((sku, declared) -> com.stockflow.inventory.api.UomConversion.cartonOf(declared)
                        .ifPresent(carton -> perCarton.put(sku, carton.factor())));
        return perCarton;
    }

    /**
     * The customer's own SKU of each approved design, by design. It is issued with the design's snapshot at approval,
     * so only an approved design has one; read in the transaction of an approval, it is the SKU that approval issued.
     */
    private Map<UUID, String> customerSkusOf(List<DesignDraftJpaEntity> drafts) {
        List<UUID> approved = drafts.stream().filter(d -> d.getStatus() == DesignStatus.APPROVED)
                .map(DesignDraftJpaEntity::getId).toList();
        if (approved.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> snapshotOf = snapshots.snapshotsOf(approved).stream()
                .collect(Collectors.toMap(DesignSnapshotJpaRepository.SnapshotOf::getDraftId,
                        DesignSnapshotJpaRepository.SnapshotOf::getId));
        if (snapshotOf.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CustomerSku> bySnapshot = customerSkus.ofSnapshots(snapshotOf.values());
        Map<UUID, String> skus = new HashMap<>();
        snapshotOf.forEach((design, snapshot) -> {
            CustomerSku sku = bySnapshot.get(snapshot);
            if (sku != null) {
                skus.put(design, sku.sku());
            }
        });
        return skus;
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
                                                        Instant layoutSaved, String previewUrl, String variantName,
                                                        String customerSku, String customerName,
                                                        Integer unitsPerCarton, DesignProjectJpaEntity project,
                                                        String reviewedByName) {
        Instant rowChanged = d.getLastModifiedAt() != null ? d.getLastModifiedAt() : d.getCreatedAt();
        Instant updatedAt = layoutSaved != null && (rowChanged == null || layoutSaved.isAfter(rowChanged))
                ? layoutSaved : rowChanged;
        return new DesignWorkflowService.DraftView(d.getId(), d.getCustomerId(), d.getProductId(), d.getOwnerUserId(),
                d.getName(), d.getSpec(), d.getStatus().name(), d.getCurrentArtifactId(), d.getReviewedBy(),
                d.getReviewNotes(), d.getVersion(), d.getParentSnapshotId(), slug, d.getSku(), d.getTemplateCode(),
                d.getReviewRequestedAt(), d.getReviewedAt(), updatedAt, d.getProjectId(), previewUrl, variantName,
                productName, customerSku, customerName, unitsPerCarton,
                project == null ? null : project.getName(), project == null ? null : project.getStatus().name(),
                reviewedByName);
    }
}
