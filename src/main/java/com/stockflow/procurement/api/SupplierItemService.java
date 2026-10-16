package com.stockflow.procurement.api;

import com.stockflow.common.api.PageResponse;

import java.util.List;
import java.util.UUID;

/**
 * The supplier catalog (SCRUM-77): which supplier sells which SKU, at what price and terms, and
 * which one is preferred. At most one supplier is preferred per SKU; preferring another moves the
 * flag. Every price change is kept as a dated period.
 */
public interface SupplierItemService {

    /**
     * Lists a SKU for an {@code ACTIVE} supplier.
     *
     * @throws com.stockflow.common.error.BusinessException {@code SUPPLIER_NOT_FOUND}, {@code SUPPLIER_INACTIVE},
     *         {@code VALIDATION_FAILED} (field named), {@code INVENTORY_ITEM_NOT_FOUND},
     *         {@code SKU_NOT_PURCHASABLE}, {@code SUPPLIER_ITEM_DUPLICATE}
     */
    SupplierItemSummary create(UUID supplierId, SaveSupplierItemCommand command);

    /**
     * Replaces the terms of one of an {@code ACTIVE} supplier's rows; the SKU in the command is ignored.
     *
     * @throws com.stockflow.common.error.BusinessException {@code SUPPLIER_NOT_FOUND}, {@code SUPPLIER_INACTIVE},
     *         {@code SUPPLIER_ITEM_NOT_FOUND}, {@code OPTIMISTIC_LOCK}, {@code VALIDATION_FAILED}
     */
    SupplierItemSummary update(UUID supplierId, UUID supplierItemId, SaveSupplierItemCommand command);

    /**
     * Removes a row, whatever the supplier's status, unless an open purchase order still buys the SKU
     * from that supplier.
     *
     * @throws com.stockflow.common.error.BusinessException {@code SUPPLIER_NOT_FOUND}, {@code SUPPLIER_ITEM_NOT_FOUND},
     *         {@code OPTIMISTIC_LOCK}, {@code SUPPLIER_ITEM_IN_USE}
     */
    void delete(UUID supplierId, UUID supplierItemId, long version);

    /** @throws com.stockflow.common.error.BusinessException {@code SUPPLIER_NOT_FOUND} */
    PageResponse<SupplierItemSummary> list(UUID supplierId, int page, int size, String sort);

    /**
     * Every supplier of a SKU, preferred first, then by supplier name.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED}, {@code INVENTORY_ITEM_NOT_FOUND}
     */
    List<SkuSupplierSummary> suppliersOf(String sku);
}
