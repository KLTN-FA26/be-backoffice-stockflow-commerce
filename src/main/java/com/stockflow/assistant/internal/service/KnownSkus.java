package com.stockflow.assistant.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.inventory.api.InventoryService;

import java.util.List;

/**
 * The check a tool makes before it reads stock for a SKU the model typed: an inventory item must have it.
 *
 * <p>The stock reads answer an unknown code exactly as they answer a real SKU with nothing in stock: nothing to
 * promise, no warehouses, no rows. On a screen nobody notices, because the SKU is picked from a list. The model types
 * it, from a label read with a zero for an O or from a product name it turned into a code, and the answer reads "out
 * of stock in every warehouse" for a SKU that does not exist while the real one has stock. An unknown code is
 * therefore NOT_FOUND, which the model explains as a code to check.</p>
 *
 * <p>This never hides stock: every stock row and every lot names an existing item
 * ({@code fk_stock_item_inventory_item}, {@code fk_stock_lot_item}). An item belongs to no warehouse, so a user limited
 * to some warehouses learns nothing about the others from it. The tool's permission is checked before this runs, so a
 * user who may not read stock does not learn which codes exist either.</p>
 */
final class KnownSkus {

    /**
     * The {@code sku} parameter of the stock tools. It names no example: the one it had ({@code CUP-12OZ-WHT}) is no
     * SKU of this business, and a model asked about "white 12oz cups" can copy an example as it stands.
     */
    static final String PARAMETER = "The SKU code exactly as the user gave it, or as a tool or an earlier answer in "
            + "this conversation stated it. Never compose one from a product name: when the user names a product, "
            + "find its SKU with searchCatalog first, or ask for the code";

    private KnownSkus() {
    }

    /**
     * {@code sku}, once an inventory item is known to have it.
     *
     * @throws NotFoundException when none has it
     */
    static Sku require(InventoryService inventory, Sku sku) {
        if (!inventory.itemIdsOf(List.of(sku.code())).containsKey(sku.code())) {
            throw new NotFoundException("SKU", sku.code());
        }
        return sku;
    }
}
