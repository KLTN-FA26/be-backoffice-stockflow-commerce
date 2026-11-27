package com.stockflow.inventory.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Asks the module that owns the catalogue which catalogue variant each SKU is packed as, for a SKU that has no
 * inventory item (owner decisions Q1, Q5 of 2026-11-26): a carton is its variant's ({@code uom_conversions.variant_id}),
 * and an item records its variant ({@code inventory_items.variant_id}), but only a customer's SKU that an order named
 * has an item. A catalogue variant never has one, and neither does a customer's SKU still being quoted - yet a design
 * on the variant, a quote of the SKU and the variant's own carton screen all ask for its cartons. Implemented by
 * {@code product}; see the package documentation for why the interface lives here.
 */
public interface SkuVariants {

    /**
     * Runs in the caller's transaction. One question per read, never one per SKU.
     *
     * @param skus SKU codes as stored, catalogue variants' and customers' alike
     * @return each of {@code skus} the catalogue knows, mapped to its catalogue variant: a variant's own code to the
     *         variant itself, a customer's SKU to the variant its design was drawn on; an unknown code is absent
     */
    Map<String, UUID> variantsOf(Collection<String> skus);

    /**
     * Whether {@code sku} is a catalogue variant's own code - not a customer's SKU drawn on one, not unknown: what a
     * carton write is named by, since a carton is the variant's and a write named by a customer's SKU would change the
     * carton of every customer of the variant. Runs in the caller's transaction.
     */
    boolean isCatalogueVariant(String sku);
}
