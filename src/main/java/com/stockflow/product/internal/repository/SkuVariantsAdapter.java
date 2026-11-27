package com.stockflow.product.internal.repository;

import com.stockflow.inventory.spi.SkuVariants;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inventory's question of {@code inventory :: spi}: the catalogue variant each SKU is packed as (owner decision Q1).
 * Two projections for a whole read, one per table: a code is either a catalogue variant's or a customer's SKU's, never
 * both ({@code tg_customer_skus_code_free}, {@code tg_variants_code_free}).
 */
@Repository
class SkuVariantsAdapter implements SkuVariants {

    private final VariantJpaRepository variants;
    private final CustomerSkuJpaRepository customerSkus;

    SkuVariantsAdapter(VariantJpaRepository variants, CustomerSkuJpaRepository customerSkus) {
        this.variants = variants;
        this.customerSkus = customerSkus;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<String, UUID> variantsOf(Collection<String> skus) {
        List<String> wanted = skus.stream().distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> found = new HashMap<>();
        for (Object[] row : variants.idsBySku(wanted)) {
            found.put((String) row[0], (UUID) row[1]);
        }
        if (found.size() < wanted.size()) {
            for (Object[] row : customerSkus.variantIdsBySku(wanted.stream().filter(s -> !found.containsKey(s))
                    .toList())) {
                found.put((String) row[0], (UUID) row[1]);
            }
        }
        return found;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean isCatalogueVariant(String sku) {
        return sku != null && variants.existsBySku(sku);
    }
}
