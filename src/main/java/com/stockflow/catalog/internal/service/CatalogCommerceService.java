package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.Listing;
import com.stockflow.catalog.internal.domain.SellingPrice;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.contracts.CatalogListingChanged;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.PublishedProductImage;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class CatalogCommerceService {
    private final ProductPublication products;
    private final ListingRepository listings;
    private final InventoryService inventory;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CatalogCommerceService(
            ProductPublication products,
            ListingRepository listings,
            InventoryService inventory,
            ApplicationEventPublisher events,
            Clock clock) {
        this.products = products;
        this.listings = listings;
        this.inventory = inventory;
        this.events = events;
        this.clock = clock;
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

    @Auditable(action = AuditAction.UPDATE, resourceType = "catalog-price", resourceId = "#id")
    public Listing price(UUID id, String sku, long revision, SellingPrice price, String actor) {
        var product = products.lock(id);
        if (product.status() == ProductStatus.DISCONTINUED)
            throw new BusinessException(ErrorCode.CONFLICT);
        if (product.skus().stream().noneMatch(s -> s.sku().equals(sku)))
            throw new BusinessException(ErrorCode.NOT_FOUND);
        var listing =
                listings.find(id)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.CATALOG_LISTING_REQUIRED));
        if (products.ecommerce(id).version() != revision)
            throw new BusinessException(ErrorCode.CONFLICT);
        listings.basePrice(sku, price, actor, clock.instant());
        // Resolve now: do not commit a new base rule which makes the public price ambiguous.
        listings.price(sku).orElseThrow(() -> new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE));
        products.advanceCommerceRevision(id);
        listings.sourceRevision(id, products.ecommerce(id).version());
        events.publishEvent(new CatalogListingChanged(id));
        return get(id);
    }

    public record PriceView(
            String sku, long revision, SellingPrice basePrice, SellingPrice effectivePrice) {}

    @Transactional(readOnly = true)
    public PriceView price(UUID id, String sku) {
        if (products.read(id).skus().stream().noneMatch(s -> s.sku().equals(sku)))
            throw new BusinessException(ErrorCode.NOT_FOUND);
        return new PriceView(
                sku,
                products.ecommerce(id).version(),
                listings.basePrice(sku).orElse(null),
                listings.price(sku).orElse(null));
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
        for (var sku : product.skus())
            listings.price(sku.sku())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE));
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

    public record Variant(String sku, SellingPrice price, String availability) {}

    public record PublicView(
            UUID productId,
            String slug,
            String title,
            String description,
            String seoTitle,
            String seoDescription,
            List<Variant> variants) {}

    public record Card(UUID productId, String slug, String title, String seoTitle) {}

    @Transactional(readOnly = true)
    public PublicView detail(String slug) {
        var listing =
                listings.bySlug(slug).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var product = products.read(listing.productId());
        if (product.status() != ProductStatus.PUBLISHED)
            throw new BusinessException(ErrorCode.NOT_FOUND);
        var available =
                inventory.availableQuantities(
                        product.skus().stream().map(s -> s.sku()).collect(Collectors.toSet()));
        var variants =
                product.skus().stream()
                        .map(
                                s -> {
                                    var price =
                                            listings.price(s.sku())
                                                    .orElseThrow(
                                                            () ->
                                                                    new BusinessException(
                                                                            ErrorCode
                                                                                    .PRICE_NOT_AVAILABLE));
                                    return new Variant(
                                            s.sku(),
                                            price,
                                            available.getOrDefault(s.sku(), 0L) > 0
                                                    ? "IN_STOCK"
                                                    : "OUT_OF_STOCK");
                                })
                        .toList();
        return new PublicView(
                listing.productId(),
                listing.slug(),
                listing.publishedTitle(),
                listing.publishedDescription(),
                listing.publishedSeoTitle(),
                listing.publishedSeoDescription(),
                variants);
    }

    @Transactional(readOnly = true)
    public List<PublishedProductImage> gallery(String slug) {
        var listing =
                listings.bySlug(slug).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return products.publishedImages(listing.productId());
    }

    /**
     * Counts are projection counts and may briefly lag a discontinuation; live guards never expose
     * it.
     */
    @Transactional(readOnly = true)
    public PageResponse<Card> list(int page, int size) {
        var paging = Pages.of(page, size);
        var rows = listings.page(paging.getOffset(), paging.getPageSize());
        var visible =
                products.published(
                        rows.stream().map(Listing::productId).collect(Collectors.toSet()));
        return PageResponse.of(
                rows.stream()
                        .filter(l -> visible.contains(l.productId()))
                        .map(
                                l ->
                                        new Card(
                                                l.productId(),
                                                l.slug(),
                                                l.publishedTitle(),
                                                l.publishedSeoTitle()))
                        .toList(),
                page,
                size,
                listings.count());
    }
}
