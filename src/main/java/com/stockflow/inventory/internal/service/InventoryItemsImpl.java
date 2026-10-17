package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.contracts.SkuInventoryControlChanged;
import com.stockflow.inventory.api.InventoryControlItem;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.StockThresholdEvaluation;
import com.stockflow.inventory.api.TrackingMode;
import com.stockflow.inventory.internal.domain.ItemLogisticsRules;
import com.stockflow.inventory.internal.domain.StockPolicy;
import com.stockflow.inventory.internal.domain.StockThresholds;
import com.stockflow.inventory.internal.repository.InventoryItemSearch;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * The only place an inventory item is edited. It resolves the item's SKU once, then applies the rules: the version
 * check (a stale or shared version is {@code OPTIMISTIC_LOCK} for both groups), stock compatibility, active counts and
 * barcode uniqueness. A policy save publishes {@link SkuInventoryControlChanged} so stock alerts are re-evaluated. The
 * audit row names the item id: the same item is reached from any number of variants or none, so a variant id would
 * make its trail depend on the route taken. No product history is written and no product status is consulted; a
 * product under review does not freeze its warehouse data.
 */
@Service
@Transactional
class InventoryItemsImpl implements InventoryItems {
    private final InventoryPolicyRepository policies;
    private final InventoryControlService control;
    private final InventoryItemSearch search;
    private final Clock clock;
    private final CurrentUserProvider users;
    private final ApplicationEventPublisher events;

    InventoryItemsImpl(InventoryPolicyRepository policies, InventoryControlService control,
                       InventoryItemSearch search, Clock clock, CurrentUserProvider users,
                       ApplicationEventPublisher events) {
        this.policies = policies;
        this.control = control;
        this.search = search;
        this.clock = clock;
        this.users = users;
        this.events = events;
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
        saveLogistics(id, sku, expectedVersion, logistics);
        return detail(id, sku);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "inventory-item", resourceId = "#id")
    public Detail configure(UUID id, long expectedVersion, InventoryPolicy policy) {
        Sku sku = sku(id);
        savePolicy(id, sku, expectedVersion, policy);
        return detail(id, sku);
    }

    private Sku sku(UUID id) {
        return policies.findSku(id)
                .map(Sku::new)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + id));
    }

    /**
     * The id was resolved to a SKU before the SKU-keyed lock, so a DRAFT SKU renamed in between would otherwise surface
     * as a generic NOT_FOUND from the SKU lookup: once locked, the id must still map to the same SKU.
     */
    private void lockItem(UUID id, Sku sku) {
        policies.lock(sku.code());
        if (!policies.findSku(id).filter(sku.code()::equals).isPresent())
            throw new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + id);
    }

    private Detail detail(UUID id, Sku sku) {
        var item = control.item(sku);
        return new Detail(id, item.sku(), item.version(), control.logistics(sku).logistics(), item.policy(),
                evaluate(sku));
    }

    private void savePolicy(UUID id, Sku sku, long expectedVersion, InventoryPolicy policy) {
        StockPolicy.validate(policy);
        lockItem(id, sku);
        var current = control.item(sku);
        if (current.version() != expectedVersion) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
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
        if (!policies.save(sku.code(), expectedVersion, policy, actor()))
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        InventoryControlItem updated = control.item(sku);
        events.publishEvent(new SkuInventoryControlChanged(sku.code(), updated.version(), clock.instant()));
    }

    private void saveLogistics(UUID id, Sku sku, long expectedVersion, ItemLogistics logistics) {
        ItemLogisticsRules.validate(logistics);
        lockItem(id, sku);
        var current = control.logistics(sku);
        if (current.version() != expectedVersion) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        if (!current.logistics().unitOfMeasure().equals(logistics.unitOfMeasure()) && policies.holdsStock(sku.code())) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT,
                    "The unit of measure of %s cannot change while stock of it is held".formatted(sku));
        }
        try {
            if (!policies.saveLogistics(sku.code(), expectedVersion, logistics, actor()))
                throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Another inventory item already has barcode " + logistics.barcode());
        }
    }

    private StockThresholdEvaluation evaluate(Sku sku) {
        InventoryPolicy p = control.policy(sku);
        long quantity = policies.usableOnHand(sku.code(), BusinessCalendar.date(clock.instant()));
        return new StockThresholdEvaluation(
                quantity,
                p.reorderPoint(),
                p.safetyStock(),
                StockThresholds.reorderRequired(quantity, p.reorderPoint()),
                StockThresholds.belowSafetyStock(quantity, p.safetyStock()));
    }

    private String actor() {
        return users.current().map(u -> u.userId().toString()).orElse("system");
    }
}
