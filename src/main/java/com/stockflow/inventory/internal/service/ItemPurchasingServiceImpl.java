package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.api.InventoryItemPurchasing;
import com.stockflow.inventory.api.ItemPurchasing;
import com.stockflow.inventory.api.ItemPurchasingService;
import com.stockflow.inventory.internal.domain.ItemPurchasingRules;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.ItemPurchasingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** See {@link ItemPurchasingService}. Takes the same per-SKU lock as the logistics and policy edits. */
@Service
@Transactional
class ItemPurchasingServiceImpl implements ItemPurchasingService {

    private final ItemPurchasingRepository purchasing;
    private final InventoryPolicyRepository items;
    private final CurrentUserProvider users;

    ItemPurchasingServiceImpl(ItemPurchasingRepository purchasing, InventoryPolicyRepository items,
                              CurrentUserProvider users) {
        this.purchasing = purchasing;
        this.items = items;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryItemPurchasing purchasing(Sku sku) {
        return purchasing.find(sku.code()).orElseThrow(
                () -> new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + sku));
    }

    @Override
    public InventoryItemPurchasing describe(Sku sku, long expectedVersion, ItemPurchasing p) {
        ItemPurchasingRules.validate(p);
        items.lock(sku.code());
        if (purchasing(sku).version() != expectedVersion) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        String actor = users.current().map(u -> u.userId().toString()).orElse("system");
        if (!purchasing.save(sku.code(), expectedVersion, p, actor)) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        }
        return purchasing(sku);
    }
}
