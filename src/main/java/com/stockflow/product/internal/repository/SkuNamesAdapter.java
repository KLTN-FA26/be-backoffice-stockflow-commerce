package com.stockflow.product.internal.repository;

import com.stockflow.inventory.spi.SkuNames;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inventory's question of {@code inventory :: spi} for the carton label (SCRUM-473) and the carton list (#454): what
 * each SKU is called. The
 * variant's name, the one a customer's SKU is quoted and ordered by. One query for a whole label sheet.
 */
@Repository
class SkuNamesAdapter implements SkuNames {

    private final VariantJpaRepository variants;

    SkuNamesAdapter(VariantJpaRepository variants) {
        this.variants = variants;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<String, String> namesOf(Collection<String> skus) {
        List<String> wanted = skus.stream().distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (Object[] row : variants.namesBySku(wanted)) {
            names.put((String) row[0], (String) row[1]);
        }
        return names;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<String, ItemNames> itemNamesOf(Collection<String> skus) {
        List<String> wanted = skus.stream().distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, ItemNames> names = new HashMap<>();
        for (Object[] row : variants.itemNamesBySku(wanted)) {
            names.put((String) row[0], new ItemNames((String) row[1], (String) row[2]));
        }
        return names;
    }
}
