package com.stockflow.product.internal.service;

import com.stockflow.inventory.api.SkuRetirement;
import com.stockflow.product.internal.domain.CustomerSkuStatus;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.repository.CustomerSkuJpaRepository;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory's question "has this SKU been retired?" answered from the SKU's status: an {@code OBSOLETE} customer's SKU
 * (one retired on its own, or every SKU of a discontinued product) freezes its item's inventory settings. Only a
 * customer's SKU has an item since V20270105000100, so {@code product.customer_skus} answers; a catalogue variant's
 * code is answered from its own status for completeness.
 */
@Component
class ProductSkuRetirement implements SkuRetirement {

    private final CustomerSkuJpaRepository customerSkus;
    private final VariantJpaRepository variants;

    ProductSkuRetirement(CustomerSkuJpaRepository customerSkus, VariantJpaRepository variants) {
        this.customerSkus = customerSkus;
        this.variants = variants;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean retired(String sku) {
        return customerSkus.existsBySkuAndStatus(sku, CustomerSkuStatus.OBSOLETE)
                || variants.existsBySkuAndStatus(sku, VariantStatus.OBSOLETE);
    }
}
