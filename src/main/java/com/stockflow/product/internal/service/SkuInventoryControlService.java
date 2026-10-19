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
import com.stockflow.product.internal.domain.ProductChangeType;
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
import com.stockflow.product.internal.repository.ProductCommerceRepository;
import com.stockflow.product.internal.repository.ProductCommerceRepository.VariantSku;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The inventory side of a product's SKUs, reached through the product: its stock policy and its
 * logistics. {@code skuId} is the variant's id; the version an edit quotes back is the inventory
 * item's. Both live in the inventory module, which is called through {@code inventory :: api} in
 * this transaction. What an edit changed is written to the product's history in the same transaction
 * ({@code product_change_log}, SCRUM-461). A suspended product is still maintained: only a product under
 * review or discontinued is refused.
 *
 * <p>An OBSOLETE variant is read like any other — its remaining stock is still shipped or adjusted away (kltn-docs
 * 01 §5.2), and that needs its logistics and policy — but its settings are frozen: an edit is
 * {@code VARIANT_OBSOLETE}, not a 404 that would say the SKU does not exist.</p>
 */
@Service
@Transactional
public class SkuInventoryControlService {
    private final ProductPublication products;
    private final ProductCommerceRepository variants;
    private final InventoryControlService inventory;
    private final ProductChangeLogRepository history;

    public SkuInventoryControlService(ProductPublication products, ProductCommerceRepository variants,
                                      InventoryControlService inventory, ProductChangeLogRepository history) {
        this.products = products;
        this.variants = variants;
        this.inventory = inventory;
        this.history = history;
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
        var sku = variants.variantSku(productId, skuId);
        return view(skuId, inventory.item(new Sku(sku.sku())));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "inventory-item", resourceId = "#skuId")
    public View update(UUID productId, UUID skuId, long version, InventoryPolicy policy) {
        var product = products.lock(productId);
        if (product.status() == ProductStatus.DISCONTINUED
                || product.status() == ProductStatus.PENDING_APPROVAL)
            throw new BusinessException(ErrorCode.CONFLICT);
        var sku = editable(productId, skuId);
        var before = inventory.item(new Sku(sku.sku())).policy();
        var updated = inventory.configure(new Sku(sku.sku()), version, policy);
        var changed = RecordDiff.changedFields(before, updated.policy());
        if (!changed.isEmpty()) {
            history.record(productId, ProductChangeType.INVENTORY_CONTROL, skuId, null, null, null, changed);
        }
        return view(skuId, updated);
    }

    /** The SKU's logistics (weight, dimensions, package, storage class), kept on its inventory item. */
    @Transactional(readOnly = true)
    public InventoryItemLogistics logistics(UUID productId, UUID skuId) {
        products.read(productId);
        return inventory.logistics(new Sku(variants.variantSku(productId, skuId).sku()));
    }

    /** Same guard as the policy: not while the product is under review, never once discontinued. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "inventory-item", resourceId = "#skuId")
    public InventoryItemLogistics describe(UUID productId, UUID skuId, long version, ItemLogistics logistics) {
        var product = products.lock(productId);
        if (product.status() == ProductStatus.DISCONTINUED
                || product.status() == ProductStatus.PENDING_APPROVAL)
            throw new BusinessException(ErrorCode.CONFLICT);
        var sku = editable(productId, skuId);
        var before = inventory.logistics(new Sku(sku.sku())).logistics();
        var described = inventory.describe(new Sku(sku.sku()), version, logistics);
        var changed = RecordDiff.changedFields(before, described.logistics());
        if (!changed.isEmpty()) {
            history.record(productId, ProductChangeType.LOGISTICS, skuId, null, null, null, changed);
        }
        return described;
    }

    private VariantSku editable(UUID productId, UUID skuId) {
        var sku = variants.variantSku(productId, skuId);
        if (sku.obsolete()) {
            throw new BusinessException(ErrorCode.VARIANT_OBSOLETE,
                    "SKU %s is obsolete: its inventory settings are kept as they are".formatted(sku.sku()));
        }
        return sku;
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
