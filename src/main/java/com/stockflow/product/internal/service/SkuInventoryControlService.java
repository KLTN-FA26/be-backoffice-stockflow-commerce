package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryControlItem;
import com.stockflow.inventory.api.InventoryItemLogistics;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.StockThresholdEvaluation;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The inventory side of a product's SKUs, reached through the product: its stock policy and its
 * logistics. {@code skuId} is the variant's id; the version an edit quotes back is the inventory
 * item's. Both live in the inventory module, which is called through {@code inventory :: api} in
 * this transaction.
 */
@Service
@Transactional
public class SkuInventoryControlService {
    private final ProductPublication products;
    private final InventoryControlService inventory;

    public SkuInventoryControlService(
            ProductPublication products, InventoryControlService inventory) {
        this.products = products;
        this.inventory = inventory;
    }

    public record View(
            UUID skuId,
            String sku,
            String unitOfMeasure,
            long version,
            InventoryPolicy policy,
            StockThresholdEvaluation evaluation) {}

    @Transactional(readOnly = true)
    public View get(UUID productId, UUID skuId) {
        products.read(productId);
        var sku = products.inventorySku(productId, skuId);
        return view(skuId, inventory.item(new Sku(sku.sku())));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "inventory-item", resourceId = "#skuId")
    public View update(UUID productId, UUID skuId, long version, InventoryPolicy policy) {
        var product = products.lock(productId);
        if (product.status() == ProductStatus.DISCONTINUED
                || product.status() == ProductStatus.PENDING_APPROVAL)
            throw new BusinessException(ErrorCode.CONFLICT);
        var sku = products.inventorySku(productId, skuId);
        return view(skuId, inventory.configure(new Sku(sku.sku()), version, policy));
    }

    /** The SKU's logistics (weight, dimensions, package, storage class), kept on its inventory item. */
    @Transactional(readOnly = true)
    public InventoryItemLogistics logistics(UUID productId, UUID skuId) {
        products.read(productId);
        return inventory.logistics(new Sku(products.inventorySku(productId, skuId).sku()));
    }

    /** Same guard as the policy: not while the product is under review, never once discontinued. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "inventory-item", resourceId = "#skuId")
    public InventoryItemLogistics describe(UUID productId, UUID skuId, long version, ItemLogistics logistics) {
        var product = products.lock(productId);
        if (product.status() == ProductStatus.DISCONTINUED
                || product.status() == ProductStatus.PENDING_APPROVAL)
            throw new BusinessException(ErrorCode.CONFLICT);
        var sku = products.inventorySku(productId, skuId);
        return inventory.describe(new Sku(sku.sku()), version, logistics);
    }

    private View view(UUID skuId, InventoryControlItem item) {
        return new View(
                skuId,
                item.sku(),
                item.unitOfMeasure(),
                item.version(),
                item.policy(),
                inventory.evaluate(new Sku(item.sku())));
    }
}
