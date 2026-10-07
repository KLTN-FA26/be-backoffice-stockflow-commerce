package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryControlItem;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.StockThresholdEvaluation;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Compatibility route; skuId now identifies the canonical variant, policy version the inventory
 * item.
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
