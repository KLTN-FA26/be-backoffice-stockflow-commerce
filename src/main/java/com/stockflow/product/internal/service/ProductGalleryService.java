package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.StorageKeys;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.GalleryItem;
import com.stockflow.product.internal.entity.ProductGalleryJpaEntity;
import com.stockflow.product.internal.entity.ProductJpaEntity;
import com.stockflow.product.internal.repository.ProductGalleryJpaRepository;
import com.stockflow.product.internal.repository.ProductJpaRepository;
import com.stockflow.product.internal.repository.SkuJpaRepository;
import com.stockflow.product.internal.repository.VariantGalleryJpaRepository;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ProductGalleryService {
    private final ProductJpaRepository products;
    private final ProductGalleryJpaRepository galleries;
    private final FileTransfers files;
    private final VariantGalleryJpaRepository variantGalleries;
    private final VariantJpaRepository variants;
    private final SkuJpaRepository skus;
    private final ProductMediaProperties media;
    public ProductGalleryService(ProductJpaRepository products, ProductGalleryJpaRepository galleries, FileTransfers files,
            VariantGalleryJpaRepository variantGalleries, VariantJpaRepository variants,
            SkuJpaRepository skus, ProductMediaProperties media) {
        this.products = products; this.galleries = galleries; this.files = files;
        this.variantGalleries = variantGalleries; this.variants = variants; this.skus = skus; this.media = media;
    }
    public record GalleryView(long revision, List<GalleryItem> working, List<GalleryItem> published,
                              UUID editedBy, UUID approvedBy) { }
    public record PublicRendition(int edge, int width, int height, String contentType, String url) { }
    public record PublicImage(UUID imageId, String caption, List<PublicRendition> renditions) { }
    public record PublicGallery(UUID productId, UUID variantId, List<PublicImage> images) { }

    @Transactional(readOnly = true)
    public GalleryView get(UUID productId) {
        products.findById(productId).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        return view(galleries.findById(productId).orElseGet(() -> new ProductGalleryJpaEntity(productId)));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product-gallery", resourceId = "#productId")
    public GalleryView edit(UUID productId, UUID actor, long expectedRevision, List<GalleryItem> items) {
        var product = lock(productId, actor);
        var gallery = galleries.findById(productId).orElseGet(() -> new ProductGalleryJpaEntity(productId));
        version(gallery, expectedRevision);
        if (items == null || items.size() > 20 || new HashSet<>(items.stream().map(GalleryItem::imageId).toList()).size() != items.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A gallery accepts at most 20 distinct images");
        }
        // Legacy external URLs remain readable via the old API, but are not inspected managed assets.
        var available = product.getImages().stream().filter(i -> i.storedFile() != null)
                .map(i -> i.getId()).collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(items.stream().map(GalleryItem::imageId).toList())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Upload each gallery image through this product's media endpoint first");
        }
        gallery.edit(items, actor);
        return view(galleries.saveAndFlush(gallery));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product-gallery", resourceId = "#productId")
    public GalleryView approve(UUID productId, UUID actor, long expectedRevision) {
        var product = lock(productId, actor);
        var gallery = galleries.findById(productId).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        version(gallery, expectedRevision);
        if (actor.equals(gallery.getEditedBy())) { throw new BusinessException(ErrorCode.SELF_APPROVAL_NOT_ALLOWED); }
        if (gallery.getWorkingItems().isEmpty()) { throw new BusinessException(ErrorCode.CONFLICT, "A published gallery needs a cover image"); }
        if (product.getStatus() != ProductStatus.APPROVED && product.getStatus() != ProductStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Approve the product before its gallery");
        }
        // Only managed, optimized assets can enter the approved gallery.
        for (var item : gallery.getWorkingItems()) {
            var image = product.getImages().stream().filter(i -> i.getId().equals(item.imageId())).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "Gallery contains a removed image"));
            if (image.storedFile() == null || image.getRenditions().isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT, "Re-upload legacy stored images to generate display renditions");
            }
        }
        publishRenditions(product, gallery.getWorkingItems());
        gallery.publish(actor);
        return view(galleries.saveAndFlush(gallery));
    }

    @Transactional(readOnly = true)
    public DownloadLink rendition(UUID productId, UUID imageId, int edge) {
        var product = products.findByIdWithImages(productId).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        var image = product.getImages().stream().filter(i -> i.getId().equals(imageId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var rendition = image.getRenditions().stream().filter(r -> r.edge() == edge).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return files.downloadLink(rendition.file().key());
    }

    public GalleryView getVariant(UUID productId, UUID variantId) {
        requireVariant(productId, variantId);
        return variantView(variantGalleries.findById(variantId)
                .orElseGet(() -> new com.stockflow.product.internal.entity.VariantGalleryJpaEntity(variantId, productId)));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product-variant-gallery", resourceId = "#variantId")
    public GalleryView editVariant(UUID productId, UUID variantId, UUID actor, long expectedRevision,
                                   List<GalleryItem> items) {
        var product = lock(productId, actor);
        requireVariant(productId, variantId);
        validateItems(product, items);
        var gallery = variantGalleries.findById(variantId)
                .orElseGet(() -> new com.stockflow.product.internal.entity.VariantGalleryJpaEntity(variantId, productId));
        variantVersion(gallery, expectedRevision);
        gallery.edit(items, actor);
        return variantView(variantGalleries.saveAndFlush(gallery));
    }

    @Auditable(action = AuditAction.APPROVE, resourceType = "product-variant-gallery", resourceId = "#variantId")
    public GalleryView approveVariant(UUID productId, UUID variantId, UUID actor, long expectedRevision) {
        var product = lock(productId, actor);
        requireVariant(productId, variantId);
        var gallery = variantGalleries.findById(variantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        variantVersion(gallery, expectedRevision);
        if (actor.equals(gallery.getEditedBy())) { throw new BusinessException(ErrorCode.SELF_APPROVAL_NOT_ALLOWED); }
        if (gallery.getWorkingItems().isEmpty()) { throw new BusinessException(ErrorCode.CONFLICT, "A variant override cannot be empty"); }
        if (product.getStatus() != ProductStatus.APPROVED && product.getStatus() != ProductStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Approve the product before its variant gallery");
        }
        validateManagedAssets(product, gallery.getWorkingItems());
        publishRenditions(product, gallery.getWorkingItems());
        gallery.publish(actor);
        return variantView(variantGalleries.saveAndFlush(gallery));
    }

    /** Anonymous storefront view. A variant without an override inherits the approved product gallery. */
    @Transactional(readOnly = true)
    public PublicGallery publicGallery(UUID productId, String sku) {
        var product = products.findByIdWithImages(productId)
                .filter(value -> value.getStatus() == ProductStatus.PUBLISHED)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        UUID variantId = null;
        List<GalleryItem> selected;
        if (sku != null && !sku.isBlank()) {
            var skuRow = skus.findByCode(sku.strip()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            variantId = skuRow.getVariantId();
            requireVariant(productId, variantId);
            selected = variantGalleries.findById(variantId).map(value -> value.getPublishedItems())
                    .filter(value -> !value.isEmpty()).orElse(null);
        } else {
            selected = null;
        }
        if (selected == null) {
            selected = galleries.findById(productId).map(value -> value.getPublishedItems())
                    .filter(value -> !value.isEmpty()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        }
        var images = new java.util.ArrayList<PublicImage>();
        for (var item : selected) {
            var image = product.getImages().stream().filter(value -> value.getId().equals(item.imageId())).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "Published gallery references a missing image"));
            var renditions = image.getRenditions().stream().map(value -> new PublicRendition(value.edge(), value.width(),
                    value.height(), value.file().contentType(),
                    media.publicUrl(StorageKeys.publishedKeyOf(value.file().key())))).toList();
            if (renditions.isEmpty()) { throw new BusinessException(ErrorCode.CONFLICT, "Published image has no display rendition"); }
            images.add(new PublicImage(item.imageId(), item.caption(), renditions));
        }
        return new PublicGallery(productId, variantId, List.copyOf(images));
    }

    /**
     * Copies the renditions of every image being approved to the CDN-served prefix. Until this runs
     * an image exists only under a private prefix; approval is what makes it reachable.
     */
    private void publishRenditions(ProductJpaEntity product, List<GalleryItem> items) {
        for (var item : items) {
            product.getImages().stream().filter(image -> image.getId().equals(item.imageId())).findFirst()
                    .ifPresent(image -> image.getRenditions().forEach(rendition -> files.publish(rendition.file().key())));
        }
    }

    private ProductJpaEntity lock(UUID id, UUID actor) {
        if (actor == null) { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
        var product = products.lockById(id).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (product.getStatus() == ProductStatus.DISCONTINUED || product.getStatus() == ProductStatus.PENDING_APPROVAL) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product media is locked in this state");
        }
        return product;
    }
    private void validateItems(ProductJpaEntity product, List<GalleryItem> items) {
        if (items == null || items.size() > 20 || new HashSet<>(items.stream().map(GalleryItem::imageId).toList()).size() != items.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A gallery accepts at most 20 distinct images");
        }
        validateManagedAssets(product, items);
    }
    private static void validateManagedAssets(ProductJpaEntity product, List<GalleryItem> items) {
        var available = product.getImages().stream().filter(i -> i.storedFile() != null && !i.getRenditions().isEmpty())
                .map(i -> i.getId()).collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(items.stream().map(GalleryItem::imageId).toList())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Gallery images must be optimized assets of this product");
        }
    }
    private void requireVariant(UUID productId, UUID variantId) {
        if (!variants.existsByIdAndProductId(variantId, productId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Variant does not belong to this product");
        }
    }
    private static void version(ProductGalleryJpaEntity g, long expected) {
        if (g.getRevision() != expected) { throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK); }
    }
    private static GalleryView view(ProductGalleryJpaEntity g) {
        return new GalleryView(g.getRevision(), g.getWorkingItems(), g.getPublishedItems(), g.getEditedBy(), g.getApprovedBy());
    }
    private static void variantVersion(com.stockflow.product.internal.entity.VariantGalleryJpaEntity g, long expected) {
        if (g.getRevision() != expected) { throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK); }
    }
    private static GalleryView variantView(com.stockflow.product.internal.entity.VariantGalleryJpaEntity g) {
        return new GalleryView(g.getRevision(), g.getWorkingItems(), g.getPublishedItems(), g.getEditedBy(), g.getApprovedBy());
    }
}
