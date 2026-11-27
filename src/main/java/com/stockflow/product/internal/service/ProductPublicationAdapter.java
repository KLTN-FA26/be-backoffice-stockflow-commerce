package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.storage.StorageKeys;
import com.stockflow.product.api.ProductEcommerce;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.PublicationProduct;
import com.stockflow.product.api.PublishedProductCard;
import com.stockflow.product.api.PublishedProductImage;
import com.stockflow.product.internal.repository.ProductCommerceRepository;
import com.stockflow.product.internal.repository.ProductImageRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class ProductPublicationAdapter implements ProductPublication {
    private final ProductCommerceRepository products;
    private final CurrentUserProvider users;
    private final ProductMediaProperties media;
    private final ProductImageRepository productImages;
    private final ProductImageService productImageFiles;

    ProductPublicationAdapter(
            ProductCommerceRepository products,
            CurrentUserProvider users,
            ProductMediaProperties media,
            ProductImageRepository productImages,
            ProductImageService productImageFiles) {
        this.products = products;
        this.users = users;
        this.media = media;
        this.productImages = productImages;
        this.productImageFiles = productImageFiles;
    }

    public PublicationProduct lock(UUID id) {
        return products.read(id, true);
    }

    @Transactional(readOnly = true)
    public PublicationProduct read(UUID id) {
        return products.read(id, false);
    }

    @Transactional(readOnly = true)
    public Set<UUID> published(Set<UUID> ids) {
        return products.published(ids);
    }

    @Transactional(readOnly = true)
    public List<UUID> publishedPage(UUID after, int size) {
        return products.publishedPage(after, size);
    }

    @Transactional(readOnly = true)
    public ProductEcommerce ecommerce(UUID id) {
        return products.ecommerce(id);
    }

    @Transactional(readOnly = true)
    public List<PublishedProductImage> publishedImages(UUID id) {
        if (read(id).status() != ProductStatus.PUBLISHED)
            throw new BusinessException(ErrorCode.NOT_FOUND);
        return approvedMedia(List.of(id)).stream().map(this::image).toList();
    }

    /**
     * One query for the published products among {@code ids}, one for their own images, one for their ACTIVE
     * variants and one for their approved variant images, whatever {@code ids.size()} is: a list page costs the same
     * as a single card. The card shows the product's own image (ADR-0012: the product's face in a grouping), by its
     * largest rendition. A product without one shows its variants' lead image, chosen as the product page leads its
     * gallery: across the whole product (the primary flag is unique per variant, so a product can have several
     * primaries), primary first, then {@code sort_order}, then id; it is given the very URL the gallery returns.
     */
    @Transactional(readOnly = true)
    public Map<UUID, PublishedProductCard> cards(Set<UUID> ids) {
        Map<UUID, com.stockflow.product.api.ProductKind> kinds = products.publishedKinds(ids);
        Set<UUID> published = kinds.keySet();
        if (published.isEmpty()) return Map.of();
        Map<UUID, List<PublishedProductCard.Sku>> skus = new HashMap<>();
        products.activeVariants(published).forEach(v ->
                skus.computeIfAbsent(v.productId(), k -> new java.util.ArrayList<>())
                        .add(new PublishedProductCard.Sku(v.sku(), v.name())));
        Map<UUID, String> own = productImages.leadRenditionKeys(published);
        Map<UUID, ProductCommerceRepository.PublishedMedia> lead = new HashMap<>();
        for (var m : approvedMedia(published)) lead.merge(m.productId(), m, (a, b) -> LEAD_FIRST.compare(a, b) <= 0 ? a : b);
        Map<UUID, PublishedProductCard> cards = new LinkedHashMap<>();
        for (UUID id : published) {
            var first = lead.get(id);
            String ownKey = own.get(id);
            String url = ownKey != null ? media.publicUrl(StorageKeys.publishedKeyOf(ownKey))
                    : first == null ? null : image(first).url();
            cards.put(id, new PublishedProductCard(url,
                    List.copyOf(skus.getOrDefault(id, List.of())), kinds.get(id)));
        }
        return cards;
    }

    /** The product page's gallery order: the primary image leads, then {@code sort_order}, then id. */
    private static final java.util.Comparator<ProductCommerceRepository.PublishedMedia> LEAD_FIRST =
            java.util.Comparator
                    .comparing((ProductCommerceRepository.PublishedMedia m) -> !m.primary())
                    .thenComparingInt(ProductCommerceRepository.PublishedMedia::sortOrder)
                    .thenComparing(ProductCommerceRepository.PublishedMedia::id);

    private PublishedProductImage image(ProductCommerceRepository.PublishedMedia m) {
        return new PublishedProductImage(
                m.id(),
                m.variantId(),
                m.sku(),
                m.renditionKey() == null
                        ? m.url()
                        : media.publicUrl(StorageKeys.publishedKeyOf(m.renditionKey())),
                m.altText(),
                m.sortOrder(),
                m.primary());
    }

    public void editEcommerce(UUID id, String slug, String title, String description) {
        var product = lock(id);
        if (product.status() == ProductStatus.DISCONTINUED)
            throw new BusinessException(ErrorCode.CONFLICT);
        var source = ecommerce(id);
        if (source.everPublished() && !source.slug().equals(slug))
            throw new BusinessException(ErrorCode.CONFLICT);
        products.edit(id, slug, title, description, actor());
    }

    public void publish(UUID id) {
        var p = lock(id);
        if (p.status() != ProductStatus.APPROVED && p.status() != ProductStatus.PUBLISHED)
            throw new BusinessException(ErrorCode.PRODUCT_NOT_APPROVED);
        if (p.categoryId() == null)
            throw new BusinessException(ErrorCode.PRODUCT_CATEGORY_REQUIRED);
        if (p.skus().isEmpty()) throw new BusinessException(ErrorCode.PRODUCT_SKU_REQUIRED);
        if (approvedMedia(List.of(id)).isEmpty())
            throw new BusinessException(ErrorCode.PRODUCT_GALLERY_REQUIRED);
        // The product's own image has no approval of its own: it goes public with the product (ADR-0012).
        productImageFiles.publishFor(id);
        if (p.status() != ProductStatus.PUBLISHED) products.publication(id, true, actor());
    }

    public void unpublish(UUID id) {
        var p = lock(id);
        if (p.status() == ProductStatus.APPROVED) return;
        if (p.status() != ProductStatus.PUBLISHED) throw new BusinessException(ErrorCode.CONFLICT);
        products.publication(id, false, actor());
    }

    /** The published images: whoever published one, its uploader included (ADR-0012 dropped four-eyes). */
    private List<ProductCommerceRepository.PublishedMedia> approvedMedia(Collection<UUID> ids) {
        return products.publishedMedia(ids);
    }

    private String actor() {
        return users.current().map(u -> u.userId().toString()).orElse("system");
    }
}
