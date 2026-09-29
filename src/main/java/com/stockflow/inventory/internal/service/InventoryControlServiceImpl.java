package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.StockThresholdEvaluation;
import com.stockflow.inventory.api.TrackingMode;
import com.stockflow.inventory.internal.domain.StockPolicy;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import com.stockflow.common.domain.BusinessCalendar;

@Service
@Transactional
class InventoryControlServiceImpl implements InventoryControlService {
    private final InventoryPolicyRepository policies;
    private final Clock clock;
    InventoryControlServiceImpl(InventoryPolicyRepository policies, Clock clock) {
        this.policies = policies; this.clock = clock;
    }
    @Override
    public void configure(Sku sku, InventoryPolicy policy) {
        StockPolicy.validate(policy);
        policies.lock(sku.code());
        var previous=policy(sku);
        boolean operationalChange=previous.removalStrategy()!=policy.removalStrategy()
                || previous.trackingMode()!=policy.trackingMode() || previous.expiryTracked()!=policy.expiryTracked()
                || !java.util.Objects.equals(previous.maxShelfLifeDays(),policy.maxShelfLifeDays());
        if(operationalChange && policies.hasActiveCount(sku.code()))
            throw new BusinessException(ErrorCode.COUNT_POLICY_CONFLICT);
        if (policies.incompatible(sku.code(), policy)) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }
        policies.save(sku.code(), policy);
    }
    @Override
    @Transactional(readOnly = true)
    public InventoryPolicy policy(Sku sku) {
        return policies.find(sku.code()).orElse(new InventoryPolicy(null, null, RemovalStrategy.FEFO,
                TrackingMode.NONE, false, null));
    }
    @Override
    @Transactional(readOnly = true)
    public StockThresholdEvaluation evaluate(Sku sku) {
        InventoryPolicy p = policy(sku);
        long quantity = policies.usableOnHand(sku.code(), BusinessCalendar.date(clock.instant()));
        return new StockThresholdEvaluation(quantity, p.reorderPoint(), p.safetyStock(),
                p.reorderPoint() == null ? null : quantity <= p.reorderPoint(),
                p.safetyStock() == null ? null : quantity < p.safetyStock());
    }
}
