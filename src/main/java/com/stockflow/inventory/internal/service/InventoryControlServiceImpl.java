package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryControlItem;
import com.stockflow.inventory.api.InventoryItemLogistics;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.TrackingMode;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


@Service
@Transactional
class InventoryControlServiceImpl implements InventoryControlService {
    private final InventoryPolicyRepository policies;
    private final int expiryWarningDays;

    InventoryControlServiceImpl(
            InventoryPolicyRepository policies,
            @Value("${stockflow.inventory.expiry-warning-days:30}") int expiryWarningDays) {
        if (expiryWarningDays < 1) {
            throw new IllegalArgumentException("stockflow.inventory.expiry-warning-days must be at least 1");
        }
        this.policies = policies;
        this.expiryWarningDays = expiryWarningDays;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public int expiryWarningDays() {
        return expiryWarningDays;
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryControlItem item(Sku sku) {
        return policies.findItem(sku.code())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryPolicy policy(Sku sku) {
        return policies.find(sku.code())
                .orElse(
                        new InventoryPolicy(
                                null, null, null, RemovalStrategy.FEFO, TrackingMode.NONE, false, null));
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryItemLogistics logistics(Sku sku) {
        return policies.findLogistics(sku.code())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + sku));
    }
}
