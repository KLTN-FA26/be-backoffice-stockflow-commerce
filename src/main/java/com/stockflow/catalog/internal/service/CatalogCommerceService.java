package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.Listing;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.CatalogListingChanged;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.PublishedProductImage;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * A product's storefront listing: its SEO content, publication and the pages a signed-in customer reads.
 *
 * <p>Nothing here carries a price or a stock figure (kltn-docs 13 BR-04, BR-11): a variant (quy cách) is only looked at
 * and designed on, and the internal price of a customer's own SKU is staff-only ({@link CustomerPriceService}). A
 * customer's stock indicator is {@link StorefrontAvailabilityService}'s, for its own SKUs alone.</p>
 */
@Service
@Transactional
public class CatalogCommerceService {
    private final ProductPublication products;
    private final ListingRepository listings;
    private final ApplicationEventPublisher events;

    public CatalogCommerceService(
            ProductPublication products,
            ListingRepository listings,
            ApplicationEventPublisher events) {
        this.products = products;
        this.listings = listings;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Listing get(UUID id) {
        products.read(id);
        var source = products.ecommerce(id);
        var snapshot =
                listings.find(id)
                        .orElse(
                                new Listing(
                                        id, null, null, null, 0, -1, false, false, null, null, null,
                                        null));
        return new Listing(
                id,
                source.slug(),
                source.seoTitle(),
                source.seoDescription(),
                source.version(),
                snapshot.projectedRevision(),
                snapshot.enabled(),
                source.everPublished(),
                snapshot.publishedTitle(),
                snapshot.publishedDescription(),
                snapshot.publishedSeoTitle(),
                snapshot.publishedSeoDescription());
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "catalog-listing", resourceId = "#id")
    public Listing edit(UUID id, long revision, String slug, String title, String description) {
        var product = products.lock(id);
        if (product.status() == ProductStatus.DISCONTINUED)
            throw new BusinessException(ErrorCode.CONFLICT);
        var updated = get(id).edit(slug, title, description, revision);
        products.editEcommerce(id, updated.slug(), updated.seoTitle(), updated.seoDescription());
        listings.save(updated);
        events.publishEvent(new CatalogListingChanged(id));
        return updated;
    }

    @Auditable(action = AuditAction.APPROVE, resourceType = "catalog-listing", resourceId = "#id")
    public void publish(UUID id) {
        var product = products.lock(id);
        var listing =
                listings.find(id)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.CATALOG_LISTING_REQUIRED));
        if (product.status() != ProductStatus.APPROVED
                && product.status() != ProductStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_APPROVED);
        }
        if (product.categoryId() == null) {
            throw new BusinessException(ErrorCode.PRODUCT_CATEGORY_REQUIRED);
        }
        if (product.skus().isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_SKU_REQUIRED);
        }
        // No price is asked for: a variant has none, so publishing has no price condition (kltn-docs 13 §4.1 step 2).
        products.publish(
                id); // Same transaction; product validates the approved gallery and category.
        if (!listing.enabled()) listings.enable(id, true);
        listings.sourceRevision(id, products.ecommerce(id).version());
        events.publishEvent(new CatalogListingChanged(id));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "catalog-listing", resourceId = "#id")
    public void unpublish(UUID id) {
        products.lock(id);
        products.unpublish(id);
        if (listings.find(id).map(Listing::enabled).orElse(false)) listings.enable(id, false);
        listings.sourceRevision(id, products.ecommerce(id).version());
        events.publishEvent(new CatalogListingChanged(id));
    }

    /**
     * A variant (quy cách) on the product page: what a customer looks at and designs on - no price, no stock indicator
     * (kltn-docs 13 BR-04, BR-11).
     */
    public record Variant(String sku) {}

    public record PublicView(
            UUID productId,
            String slug,
            String title,
            String description,
            String seoTitle,
            String seoDescription,
            List<Variant> variants,
            ProductKind kind) {}

    /**
     * One storefront list card. {@code primaryImageUrl} and {@code skus} spare the page a detail call and a
     * gallery call per card; neither carries a price (the storefront shows none, kltn-docs 13 BR-04) or a stock
     * figure.
     */
    public record Card(UUID productId, String slug, String title, String seoTitle, String primaryImageUrl,
                       List<CardSku> skus, ProductKind kind) {}

    /** A variant of a card: its SKU code and name. */
    public record CardSku(String sku, String name) {}

    @Transactional(readOnly = true)
    public PublicView detail(String slug) {
        var listing =
                listings.bySlug(slug).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var product = products.read(listing.productId());
        if (product.status() != ProductStatus.PUBLISHED)
            throw new BusinessException(ErrorCode.NOT_FOUND);
        var variants = product.skus().stream().map(s -> new Variant(s.sku())).toList();
        return new PublicView(
                listing.productId(),
                listing.slug(),
                listing.publishedTitle(),
                listing.publishedDescription(),
                listing.publishedSeoTitle(),
                listing.publishedSeoDescription(),
                variants,
                product.kind());
    }

    @Transactional(readOnly = true)
    public List<PublishedProductImage> gallery(String slug) {
        var listing =
                listings.bySlug(slug).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return products.publishedImages(listing.productId());
    }
}
