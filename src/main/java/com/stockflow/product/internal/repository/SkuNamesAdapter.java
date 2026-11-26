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
 * each SKU is called - the name a customer's SKU is quoted and ordered by. Stock is a customer's SKU's, a row of
 * {@code product.customer_skus} since V20270105000100, so that table is read first; a catalogue variant's code is
 * answered from {@code product.variants} for completeness (a code is in one table only). One query per table for a
 * whole label sheet, the second only for codes the first did not know.
 */
@Repository
class SkuNamesAdapter implements SkuNames {

    private final CustomerSkuJpaRepository customerSkus;
    private final VariantJpaRepository variants;

    SkuNamesAdapter(CustomerSkuJpaRepository customerSkus, VariantJpaRepository variants) {
        this.customerSkus = customerSkus;
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
        for (Object[] row : customerSkus.namesBySku(wanted)) {
            names.put((String) row[0], (String) row[1]);
        }
        List<String> rest = wanted.stream().filter(sku -> !names.containsKey(sku)).toList();
        if (!rest.isEmpty()) {
            for (Object[] row : variants.namesBySku(rest)) {
                names.put((String) row[0], (String) row[1]);
            }
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
        for (Object[] row : customerSkus.itemNamesBySku(wanted)) {
            names.put((String) row[0], new ItemNames((String) row[1], (String) row[2]));
        }
        List<String> rest = wanted.stream().filter(sku -> !names.containsKey(sku)).toList();
        if (!rest.isEmpty()) {
            for (Object[] row : variants.itemNamesBySku(rest)) {
                names.put((String) row[0], new ItemNames((String) row[1], (String) row[2]));
            }
        }
        return names;
    }
}
