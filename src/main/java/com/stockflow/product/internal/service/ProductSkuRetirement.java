package com.stockflow.product.internal.service;

import com.stockflow.inventory.api.SkuRetirement;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory's question "has this SKU been retired?" answered from the variant's status: an {@code OBSOLETE} variant
 * (one retired on its own, or every variant of a discontinued product) freezes its item's inventory settings.
 */
@Component
class ProductSkuRetirement implements SkuRetirement {

    private final VariantJpaRepository variants;

    ProductSkuRetirement(VariantJpaRepository variants) {
        this.variants = variants;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean retired(String sku) {
        return variants.existsBySkuAndStatus(sku, VariantStatus.OBSOLETE);
    }
}
