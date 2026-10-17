package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryItemPurchasing;
import com.stockflow.inventory.api.ItemPurchasing;
import com.stockflow.inventory.api.ItemPurchasingService;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.SupplierProbe;
import com.stockflow.product.internal.domain.ProductChangeType;
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * The purchasing information of a product's SKU (docs 01 §3 "Thông tin mua hàng": default supplier,
 * reference purchase price, lead time, MOQ), kept on its inventory item and reached through the product
 * like its logistics. A new default supplier must exist and be active, asked of procurement through
 * {@link SupplierProbe}; an unchanged one is not asked again, so a supplier deactivated later does not
 * block editing the price.
 */
@Service
@Transactional
public class SkuPurchasingService {

    private final ProductPublication products;
    private final ItemPurchasingService inventory;
    private final ObjectProvider<SupplierProbe> suppliers;
    private final ProductChangeLogRepository history;

    public SkuPurchasingService(ProductPublication products, ItemPurchasingService inventory,
                                ObjectProvider<SupplierProbe> suppliers, ProductChangeLogRepository history) {
        this.products = products;
        this.inventory = inventory;
        this.suppliers = suppliers;
        this.history = history;
    }

    @Transactional(readOnly = true)
    public InventoryItemPurchasing purchasing(UUID productId, UUID variantId) {
        products.read(productId);
        return inventory.purchasing(new Sku(products.inventorySku(productId, variantId).sku()));
    }

    /** Same guard as logistics: not while the product is under review, never once discontinued. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "inventory-item", resourceId = "#variantId")
    public InventoryItemPurchasing describe(UUID productId, UUID variantId, long version, ItemPurchasing purchasing) {
        var product = products.lock(productId);
        if (product.status() == ProductStatus.DISCONTINUED || product.status() == ProductStatus.PENDING_APPROVAL)
            throw new BusinessException(ErrorCode.CONFLICT);
        var sku = new Sku(products.inventorySku(productId, variantId).sku());
        var before = inventory.purchasing(sku).purchasing();
        if (purchasing.defaultSupplierId() != null
                && !Objects.equals(purchasing.defaultSupplierId(), before.defaultSupplierId())) {
            requireActiveSupplier(purchasing.defaultSupplierId());
        }
        var described = inventory.describe(sku, version, purchasing);
        var changed = RecordDiff.changedFields(before, described.purchasing());
        if (!changed.isEmpty()) {
            history.record(productId, ProductChangeType.PURCHASING, variantId, null, null, null, changed);
        }
        return described;
    }

    private void requireActiveSupplier(UUID supplierId) {
        SupplierProbe probe = suppliers.getIfAvailable();
        var standing = probe == null ? null : probe.supplier(supplierId).orElse(null);
        if (standing == null) {
            throw new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND, "No supplier " + supplierId);
        }
        if (!standing.active()) {
            throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE,
                    "Supplier %s is not active and cannot be a default supplier".formatted(standing.code()));
        }
    }
}
