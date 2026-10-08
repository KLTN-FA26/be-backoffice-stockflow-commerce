package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.Listing;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CatalogProjectionService {
    private final ListingRepository listings;
    private final ProductPublication products;
    private final Clock clock;

    public CatalogProjectionService(
            ListingRepository listings, ProductPublication products, Clock clock) {
        this.listings = listings;
        this.products = products;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attempt(UUID id) {
        listings.attempted(id, clock.instant());
    }

    /** Current-source rebuild is idempotent and cannot resurrect a stale publish event. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void project(UUID id) {
        var product = products.lock(id);
        var listing = listings.find(id).orElse(null);
        if (listing == null && product.status() != ProductStatus.PUBLISHED) return;
        var source = products.ecommerce(id);
        if (listing == null) {
            // Backfill the snapshot, never the source publication or its selling prices.
            if (!Listing.normalizeSlug(source.slug()).equals(source.slug()))
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            listings.save(new Listing(id, source.slug(), source.seoTitle(), source.seoDescription(),
                    source.version(), -1, false, source.everPublished(), null, null, null, null));
            listing = listings.find(id).orElseThrow();
        }
        if (listing.revision() > source.version())
            throw new BusinessException(ErrorCode.CONFLICT);
        if (listing.revision() != source.version()
                || !Objects.equals(listing.slug(), source.slug())
                || !Objects.equals(listing.seoTitle(), source.seoTitle())
                || !Objects.equals(listing.seoDescription(), source.seoDescription())) {
            listings.save(
                    new Listing(
                            id,
                            source.slug(),
                            source.seoTitle(),
                            source.seoDescription(),
                            source.version(),
                            listing.projectedRevision(),
                            listing.enabled(),
                            source.everPublished(),
                            listing.publishedTitle(),
                            listing.publishedDescription(),
                            listing.publishedSeoTitle(),
                            listing.publishedSeoDescription()));
            listing = listings.find(id).orElseThrow();
        }
        if (product.status() == ProductStatus.PUBLISHED) {
            if (!listing.enabled()) {
                listings.enable(id, true);
                listing = listings.find(id).orElseThrow();
            }
            if (listing.publishedTitle() != null && listing.revision() == listing.projectedRevision()
                    && listings.projectedSkus(id, source.version()).equals(product.skus().stream()
                            .map(s -> s.sku()).collect(Collectors.toSet()))) return;
            if (product.categoryId() == null)
                throw new BusinessException(ErrorCode.PRODUCT_CATEGORY_REQUIRED);
            if (products.publishedImages(id).isEmpty())
                throw new BusinessException(ErrorCode.PRODUCT_GALLERY_REQUIRED);
        }
        listings.hideEntries(id);
        if (listing.enabled() && product.status() == ProductStatus.PUBLISHED) {
            if (product.skus().isEmpty())
                throw new BusinessException(ErrorCode.PRODUCT_SKU_REQUIRED);
            for (var sku : product.skus()) {
                var price =
                        listings.price(sku.sku())
                                .orElseThrow(
                                        () -> new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE));
                listings.projectSku(
                        id,
                        sku.skuId(),
                        sku.sku(),
                        product.name(),
                        product.description(),
                        listing.seoTitle(),
                        listing.seoDescription(),
                        price,
                        listing.revision(),
                        clock.instant());
            }
            listings.projected(id, product.name(), product.description());
        } else {
            if (listing.enabled()) listings.enable(id, false);
            listings.acknowledgeHidden(id);
        }
    }
}
