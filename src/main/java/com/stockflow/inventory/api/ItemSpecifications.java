package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

/**
 * Copies what one SKU's inventory item knows about how it is packed and handled onto another's: what a customer's own
 * SKU inherits from the variant its design was drawn on (kltn-docs 01 §4.8, open question B20 - "carton spec,
 * logistics and stock policy copied from the variant, editable separately afterwards").
 */
public interface ItemSpecifications {

    /**
     * Gives {@code target}'s item the logistics (weights, measures, handling), the stock policy and
     * every active packing unit - the carton spec among them - of {@code source}'s item, in the caller's transaction.
     * The barcode is not copied: it names the source's goods. Meant for an item just created with its variant, which has
     * no packing unit of its own yet.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INVENTORY_ITEM_NOT_FOUND} when either SKU has no item
     */
    void copy(Sku source, Sku target);
}
