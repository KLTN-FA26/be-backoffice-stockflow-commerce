package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class CatalogProjectionService {
    private final ListingRepository listings;
    private final ProductPublication products;
    private final Clock clock;
    public CatalogProjectionService(ListingRepository listings,ProductPublication products,Clock clock) {
        this.listings=listings; this.products=products; this.clock=clock;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void attempt(UUID id) { listings.attempted(id,clock.instant()); }
    /** Current-source rebuild is idempotent and cannot resurrect a stale publish event. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void project(UUID id) {
        var product=products.lock(id);
        var listing=listings.find(id).orElse(null);
        if (listing==null) return;
        if (listing.enabled() && product.status()==ProductStatus.PUBLISHED
                && listing.revision()==listing.projectedRevision()) return;
        listings.hideEntries(id);
        if (listing.enabled() && product.status()==ProductStatus.PUBLISHED) {
            if (product.skus().isEmpty()) throw new BusinessException(ErrorCode.PRODUCT_SKU_REQUIRED);
            for (var sku : product.skus()) {
                var price=listings.price(sku.sku()).orElseThrow(() -> new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE));
                listings.projectSku(id,sku.skuId(),sku.sku(),product.name(),product.description(),
                        listing.seoTitle(),listing.seoDescription(),price,listing.revision(),clock.instant());
            }
            listings.projected(id,product.name(),product.description());
        } else {
            if (listing.enabled()) listings.enable(id,false);
            listings.acknowledgeHidden(id);
        }
    }
}
