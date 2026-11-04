package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.Listing;
import com.stockflow.catalog.internal.domain.SellingPrice;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.CatalogListingChanged;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductKind;
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

    /**
     * Sets the base price of one SKU of the product. {@code sku} is normalised like every SKU in the system
     * ({@link Sku}: trimmed, upper case), so {@code cup-12oz} names {@code CUP-12OZ}; a value that cannot be a SKU is
     * {@code VALIDATION_FAILED}, one the product does not sell is {@code NOT_FOUND}.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "catalog-price", resourceId = "#id")
    public Listing price(UUID id, String rawSku, long revision, SellingPrice price, String actor) {
        String sku = skuCode(rawSku);
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

    /** The SKU of one variant the product sells (an ACTIVE one), for the routes that name the variant by its id. */
    @Transactional(readOnly = true)
    public String skuOfVariant(UUID id, UUID variantId) {
        return products.read(id).skus().stream().filter(s -> s.skuId().equals(variantId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).sku();
    }

    /** The base and effective price of one SKU of the product; {@code sku} is normalised as in the setter. */
    @Transactional(readOnly = true)
    public PriceView price(UUID id, String rawSku) {
        String sku = skuCode(rawSku);
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
            List<Variant> variants,
            ProductKind kind) {}

    /**
     * One storefront list card. {@code primaryImageUrl} and {@code skus} spare the page a detail call and a
     * gallery call per card; neither carries a price or a stock figure (those stay on the CUSTOMER-only
     * prices and availability endpoints).
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
                variants,
                product.kind());
    }

    @Transactional(readOnly = true)
    public List<PublishedProductImage> gallery(String slug) {
        var listing =
                listings.bySlug(slug).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return products.publishedImages(listing.productId());
    }

    private static String skuCode(String raw) {
        try {
            return new Sku(raw).code();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "sku must be 1-64 letters, digits, '.', '_' or '-', starting with a letter or digit");
        }
    }
}
