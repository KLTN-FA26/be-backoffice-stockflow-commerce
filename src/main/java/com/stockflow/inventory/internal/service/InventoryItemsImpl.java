package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.TrackingMode;
import com.stockflow.inventory.internal.repository.InventoryItemSearch;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves the item's SKU once and delegates to {@link InventoryControlService}, which owns the rules (version check,
 * stock compatibility, active counts, barcode uniqueness). The audit row names the item id: the same item is reached
 * from any number of variants or none, so a variant id would make its trail depend on the route taken. No product
 * history is written and no product status is consulted; a product under review does not freeze its warehouse data.
 */
@Service
@Transactional
class InventoryItemsImpl implements InventoryItems {
    private final InventoryPolicyRepository policies;
    private final InventoryControlService control;
    private final InventoryItemSearch search;

    InventoryItemsImpl(InventoryPolicyRepository policies, InventoryControlService control,
                       InventoryItemSearch search) {
        this.policies = policies;
        this.control = control;
        this.search = search;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Summary> search(ItemQuery query) {
        var criteria = new InventoryItemSearch.Criteria(blankToNull(query.sku()), blankToNull(query.q()),
                query.storageClass(), query.policyConfigured());
        return Pages.toResponse(search.items(criteria, Pages.of(query.page(), query.size()))
                .map(r -> new Summary(r.id(), r.sku(), r.unitOfMeasure(), r.barcode(), r.storageClass(),
                        TrackingMode.of(r.lotTracked(), r.serialTracked()), r.policyConfigured())));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    @Transactional(readOnly = true)
    public Detail get(UUID id) {
        return detail(id, sku(id));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "inventory-item", resourceId = "#id")
    public Detail describe(UUID id, long expectedVersion, ItemLogistics logistics) {
        Sku sku = sku(id);
        control.describe(sku, expectedVersion, logistics);
        return detail(id, sku);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "inventory-item", resourceId = "#id")
    public Detail configure(UUID id, long expectedVersion, InventoryPolicy policy) {
        Sku sku = sku(id);
        control.configure(sku, expectedVersion, policy);
        return detail(id, sku);
    }

    private Sku sku(UUID id) {
        return policies.findSku(id)
                .map(Sku::new)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + id));
    }

    private Detail detail(UUID id, Sku sku) {
        var item = control.item(sku);
        return new Detail(id, item.sku(), item.version(), control.logistics(sku).logistics(), item.policy(),
                control.evaluate(sku));
    }
}
