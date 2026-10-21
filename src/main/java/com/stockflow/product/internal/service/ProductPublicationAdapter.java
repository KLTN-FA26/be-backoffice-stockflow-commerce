package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.storage.StorageKeys;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.product.api.ProductEcommerce;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.PublicationProduct;
import com.stockflow.product.api.PublishedProductCard;
import com.stockflow.product.api.PublishedProductImage;
import com.stockflow.product.internal.repository.ProductCommerceRepository;

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
    private final IdentityService identity;

    ProductPublicationAdapter(
            ProductCommerceRepository products,
            CurrentUserProvider users,
            ProductMediaProperties media,
            IdentityService identity) {
        this.products = products;
        this.users = users;
        this.media = media;
        this.identity = identity;
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
     * One query for the published products among {@code ids}, one for their ACTIVE variants and one for their
     * approved images, whatever {@code ids.size()} is: a list page costs the same as a single card. The image
     * is chosen as the product page leads its gallery: across the whole product (the primary flag is unique per
     * variant, so a product can have several primaries), primary first, then {@code sort_order}, then id; it is
     * given the very URL the gallery returns.
     */
    @Transactional(readOnly = true)
    public Map<UUID, PublishedProductCard> cards(Set<UUID> ids) {
        Set<UUID> published = products.published(ids);
        if (published.isEmpty()) return Map.of();
        Map<UUID, List<PublishedProductCard.Sku>> skus = new HashMap<>();
        products.activeVariants(published).forEach(v ->
                skus.computeIfAbsent(v.productId(), k -> new java.util.ArrayList<>())
                        .add(new PublishedProductCard.Sku(v.sku(), v.name())));
        Map<UUID, ProductCommerceRepository.PublishedMedia> lead = new HashMap<>();
        for (var m : approvedMedia(published)) lead.merge(m.productId(), m, (a, b) -> LEAD_FIRST.compare(a, b) <= 0 ? a : b);
        Map<UUID, PublishedProductCard> cards = new LinkedHashMap<>();
        for (UUID id : published) {
            var first = lead.get(id);
            cards.put(id, new PublishedProductCard(first == null ? null : image(first).url(),
                    List.copyOf(skus.getOrDefault(id, List.of()))));
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

    public void advanceCommerceRevision(UUID id) {
        lock(id);
        products.advanceCommerceRevision(id, actor());
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
        if (p.status() != ProductStatus.PUBLISHED) products.publication(id, true, actor());
    }

    public void unpublish(UUID id) {
        var p = lock(id);
        if (p.status() == ProductStatus.APPROVED) return;
        if (p.status() != ProductStatus.PUBLISHED) throw new BusinessException(ErrorCode.CONFLICT);
        products.publication(id, false, actor());
    }

    private List<ProductCommerceRepository.PublishedMedia> approvedMedia(Collection<UUID> ids) {
        // Auditing can record either UUID or username; comparing only UUID text permits
        // self-approval.
        var usernames = new HashMap<UUID, String>();
        return products.publishedMedia(ids).stream()
                .filter(
                        m -> {
                            String approver =
                                    usernames.computeIfAbsent(
                                            m.publishedBy(),
                                            key -> identity.profile(key).username());
                            return !m.publishedBy().toString().equals(m.createdBy())
                                    && !m.createdBy().equals(approver);
                        })
                .toList();
    }

    private String actor() {
        return users.current().map(u -> u.userId().toString()).orElse("system");
    }
}
