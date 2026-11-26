package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.spi.SkuVariants;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalogue variant each SKU is packed as, whose cartons are the SKU's (owner decision Q1). An inventory item
 * records it ({@code inventory_items.variant_id}, never changes), so a SKU with an item is answered here without asking
 * anyone; the rest - a catalogue variant, which never has an item, and a customer's SKU no order has named yet (Q5) -
 * are asked of the catalogue ({@link SkuVariants}), once per read. No cross-schema JOIN. Without the answering module
 * (a module test) only SKUs with an item are known.
 */
@Component
public class SkuVariantLookup {

    private final JdbcTemplate jdbc;
    private final ObjectProvider<SkuVariants> catalogue;

    public SkuVariantLookup(JdbcTemplate jdbc, ObjectProvider<SkuVariants> catalogue) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
    }

    /** Each of {@code skus} the item table or the catalogue knows, mapped to its catalogue variant; nulls skipped. */
    public Map<String, UUID> of(Collection<String> skus) {
        List<String> wanted = skus.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> found = new HashMap<>();
        jdbc.query("select sku, variant_id from inventory.inventory_items where sku = any (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", wanted.toArray())),
                rs -> {
                    found.put(rs.getString("sku"), rs.getObject("variant_id", UUID.class));
                });
        if (found.size() < wanted.size()) {
            SkuVariants answer = catalogue.getIfAvailable();
            if (answer != null) {
                answer.variantsOf(wanted.stream().filter(s -> !found.containsKey(s)).toList()).forEach(found::put);
            }
        }
        return found;
    }

    /**
     * Whether {@code sku} is a customer's SKU rather than a catalogue variant's own code: it has an item (only a
     * customer's SKU has one), or the catalogue says it is no variant of its own. Without the answering module (a
     * module test) only an item tells.
     */
    public boolean isCustomerSku(String sku) {
        Boolean item = jdbc.queryForObject("select exists(select 1 from inventory.inventory_items where sku = ?)",
                Boolean.class, sku);
        if (Boolean.TRUE.equals(item)) {
            return true;
        }
        SkuVariants answer = catalogue.getIfAvailable();
        return answer != null && of(sku).isPresent() && !answer.isCatalogueVariant(sku);
    }

    /** The catalogue variant of one SKU, empty when neither the item table nor the catalogue knows it. */
    public Optional<UUID> of(String sku) {
        return sku == null ? Optional.empty() : Optional.ofNullable(of(List.of(sku)).get(sku));
    }
}
