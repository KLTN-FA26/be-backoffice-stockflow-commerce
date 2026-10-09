package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.contracts.SkuInventoryControlChanged;
import com.stockflow.inventory.api.InventoryControlItem;
import com.stockflow.inventory.api.InventoryItemLogistics;
import com.stockflow.inventory.api.ItemLogistics;
import org.springframework.dao.DuplicateKeyException;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.StockThresholdEvaluation;
import com.stockflow.inventory.api.TrackingMode;
import com.stockflow.inventory.internal.domain.ItemLogisticsRules;
import com.stockflow.inventory.internal.domain.StockPolicy;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

@Service
@Transactional
class InventoryControlServiceImpl implements InventoryControlService {
    private final InventoryPolicyRepository policies;
    private final Clock clock;
    private final CurrentUserProvider users;
    private final ApplicationEventPublisher events;

    InventoryControlServiceImpl(
            InventoryPolicyRepository policies,
            Clock clock,
            CurrentUserProvider users,
            ApplicationEventPublisher events) {
        this.policies = policies;
        this.clock = clock;
        this.users = users;
        this.events = events;
    }

    @Override
    public InventoryControlItem configure(Sku sku, long expectedVersion, InventoryPolicy policy) {
        StockPolicy.validate(policy);
        policies.lock(sku.code());
        var current = item(sku);
        if (current.version() != expectedVersion) throw new BusinessException(ErrorCode.CONFLICT);
        var previous = current.policy();
        boolean operationalChange =
                previous.removalStrategy() != policy.removalStrategy()
                        || previous.trackingMode() != policy.trackingMode()
                        || previous.expiryTracked() != policy.expiryTracked()
                        || !Objects.equals(previous.maxShelfLifeDays(), policy.maxShelfLifeDays());
        if (operationalChange && policies.hasActiveCount(sku.code()))
            throw new BusinessException(ErrorCode.COUNT_POLICY_CONFLICT);
        if (policies.incompatible(sku.code(), policy)) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }
        String actor = users.current().map(u -> u.userId().toString()).orElse("system");
        if (!policies.save(sku.code(), expectedVersion, policy, actor))
            throw new BusinessException(ErrorCode.CONFLICT);
        var updated = item(sku);
        events.publishEvent(
                new SkuInventoryControlChanged(sku.code(), updated.version(), clock.instant()));
        return updated;
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
                                null, null, RemovalStrategy.FEFO, TrackingMode.NONE, false, null));
    }

    @Override
    @Transactional(readOnly = true)
    public StockThresholdEvaluation evaluate(Sku sku) {
        InventoryPolicy p = policy(sku);
        long quantity = policies.usableOnHand(sku.code(), BusinessCalendar.date(clock.instant()));
        return new StockThresholdEvaluation(
                quantity,
                p.reorderPoint(),
                p.safetyStock(),
                p.reorderPoint() == null ? null : quantity <= p.reorderPoint(),
                p.safetyStock() == null ? null : quantity < p.safetyStock());
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryItemLogistics logistics(Sku sku) {
        return policies.findLogistics(sku.code())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + sku));
    }

    @Override
    public InventoryItemLogistics describe(Sku sku, long expectedVersion, ItemLogistics logistics) {
        ItemLogisticsRules.validate(logistics);
        policies.lock(sku.code());
        var current = logistics(sku);
        if (current.version() != expectedVersion) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        if (!current.logistics().unitOfMeasure().equals(logistics.unitOfMeasure()) && policies.holdsStock(sku.code())) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT,
                    "The unit of measure of %s cannot change while stock of it is held".formatted(sku));
        }
        String actor = users.current().map(u -> u.userId().toString()).orElse("system");
        try {
            if (!policies.saveLogistics(sku.code(), expectedVersion, logistics, actor))
                throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "Another inventory item already has barcode " + logistics.barcode());
        }
        return logistics(sku);
    }
}
