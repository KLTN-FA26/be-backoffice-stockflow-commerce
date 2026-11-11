package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.PrintConfigs;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * What a customer may design on a published product (kltn-docs 13 BR-14, 12 BR-02; the storefront's P2): the active
 * customization template of each ACTIVE variant, with the print areas computed from its model. A product that is not
 * published reads as absent, like its detail page; one that cannot be customized is refused, so the studio is never
 * opened on a product it cannot print on.
 */
@Service
public class CatalogCustomizationService {

    private final ListingRepository listings;
    private final ProductPublication products;
    private final PrintConfigs printConfigs;

    public CatalogCustomizationService(ListingRepository listings, ProductPublication products,
                                       PrintConfigs printConfigs) {
        this.listings = listings;
        this.products = products;
        this.printConfigs = printConfigs;
    }

    /** A published product's templates, by variant position. */
    public record Customization(UUID productId, ProductKind kind, List<PrintConfig> templates) {
    }

    @Transactional(readOnly = true)
    public Customization customization(String slug) {
        var listing = listings.bySlug(slug).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var product = products.read(listing.productId());
        if (product.status() != ProductStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (product.kind() != ProductKind.CUSTOMIZABLE) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_CUSTOMIZABLE,
                    "This product cannot be designed on");
        }
        return new Customization(listing.productId(), product.kind(),
                printConfigs.activeOfProduct(listing.productId()));
    }
}
