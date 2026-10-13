package com.stockflow.procurement.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.internal.repository.PoLineReferences;
import com.stockflow.product.api.SkuReference;
import com.stockflow.product.api.SkuReferenceProbe;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Procurement's answer to "what is still open against these SKUs" (SCRUM-461, BR-PRD-005): the purchase
 * order lines still to be received. Handed to the product service by Spring, never called from the other
 * direction: see {@link SkuReferenceProbe} for why.
 *
 * <p>A SKU is resolved to its inventory item through {@code inventory :: api}, which is what a purchase
 * order line points at. A SKU the shared {@link Sku} format does not accept cannot be on a purchase order
 * line either, and is left out.</p>
 */
@Component
class ProcurementSkuReferenceProbe implements SkuReferenceProbe {

    private final InventoryService inventory;
    private final PoLineReferences lines;

    ProcurementSkuReferenceProbe(InventoryService inventory, PoLineReferences lines) {
        this.inventory = inventory;
        this.lines = lines;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkuReference> openReferences(Collection<String> skus, int limit) {
        Map<UUID, String> skuOfItem = new HashMap<>();
        for (String code : skus) {
            Sku sku;
            try {
                sku = new Sku(code);
            } catch (IllegalArgumentException notAPurchasableFormat) {
                continue;
            }
            inventory.itemPolicy(sku).map(InventoryItemPolicy::inventoryItemId)
                    .ifPresent(itemId -> skuOfItem.put(itemId, sku.code()));
        }
        return lines.openLines(skuOfItem.keySet(), limit).stream()
                .map(row -> new SkuReference(SkuReference.Kind.PURCHASE_ORDER_LINE, row.poNumber(),
                        row.purchaseOrderId(), skuOfItem.get(row.inventoryItemId())))
                .toList();
    }
}
