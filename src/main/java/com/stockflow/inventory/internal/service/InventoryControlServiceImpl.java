package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryControlItem;
import com.stockflow.inventory.api.InventoryItemLogistics;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class InventoryControlServiceImpl implements InventoryControlService {
    private final InventoryPolicyRepository policies;

    InventoryControlServiceImpl(InventoryPolicyRepository policies) {
        this.policies = policies;
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryControlItem item(Sku sku) {
        return policies.findItem(sku.code())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryItemLogistics logistics(Sku sku) {
        return policies.findLogistics(sku.code())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + sku));
    }
}
