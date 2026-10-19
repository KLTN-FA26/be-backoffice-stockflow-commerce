package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.contracts.SkuInventoryControlChanged;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.SkuRetirement;
import com.stockflow.inventory.api.StockThresholdEvaluation;
import com.stockflow.inventory.api.TrackingMode;
import com.stockflow.inventory.internal.domain.ItemLogisticsRules;
import com.stockflow.inventory.internal.domain.StockPolicy;
import com.stockflow.inventory.internal.domain.StockThresholds;
import com.stockflow.inventory.internal.repository.InventoryItemSearch;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ItemRow;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * The only place an inventory item is edited. It locks the item and reads it once, then applies the rules: the version
 * check (a stale or shared version is {@code OPTIMISTIC_LOCK} for both groups), stock compatibility, active counts and
 * barcode uniqueness. A policy save publishes {@link SkuInventoryControlChanged} so stock alerts are re-evaluated. The
 * audit row names the item id: the same item is reached from any number of variants or none, so a variant id would
 * make its trail depend on the route taken. No product history is written and no product status is consulted; a
 * product under review does not freeze its warehouse data.
 *
 * <p>A retired SKU does ({@link SkuRetirement}, #120): an {@code OBSOLETE} variant keeps its stock only to ship it or
 * adjust it away, so its item is still read like any other but an edit of either group is {@code VARIANT_OBSOLETE},
 * not a 404 that would say the item does not exist.</p>
 */
@Service
@Transactional
class InventoryItemsImpl implements InventoryItems {
    private final InventoryPolicyRepository policies;
    private final InventoryItemSearch search;
    private final Clock clock;
    private final CurrentUserProvider users;
    private final ApplicationEventPublisher events;
    /** Absent when the module owning SKUs is not loaded (an inventory-only module test): nothing is retired then. */
    private final ObjectProvider<SkuRetirement> retirement;

    InventoryItemsImpl(InventoryPolicyRepository policies, InventoryItemSearch search, Clock clock,
                       CurrentUserProvider users, ApplicationEventPublisher events,
                       ObjectProvider<SkuRetirement> retirement) {
        this.policies = policies;
        this.search = search;
        this.clock = clock;
        this.users = users;
        this.events = events;
        this.retirement = retirement;
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
        return detail(row(id));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "inventory-item", resourceId = "#id")
    public Detail describe(UUID id, long expectedVersion, ItemLogistics logistics) {
        saveLogistics(id, expectedVersion, logistics);
        return detail(row(id));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "inventory-item", resourceId = "#id")
    public Detail configure(UUID id, long expectedVersion, InventoryPolicy policy) {
        savePolicy(id, expectedVersion, policy);
        return detail(row(id));
    }

    private ItemRow row(UUID id) {
        return policies.findById(id).orElseThrow(() -> notFound(id));
    }

    private static BusinessException notFound(UUID id) {
        return new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + id);
    }

    /**
     * Takes the item's advisory lock and reads the item under it. The lock is keyed by SKU and the request by id, so
     * the SKU is read before the lock, and a DRAFT variant renamed in between leaves the lock on the old SKU. The row
     * read under the lock then shows the new SKU, and the answer is {@code OPTIMISTIC_LOCK} rather than this method
     * re-locking on the new SKU: the rename trigger bumps the version, and it committed after this request began, so
     * the version the client quotes is stale whatever it is. Like every stale version here, the client retries after
     * a fresh read, which is what {@code OPTIMISTIC_LOCK.isRetryable()} means; resending the same body cannot pass.
     * A 404 would be wrong, since the item still exists.
     */
    private ItemRow lockItem(UUID id) {
        String sku = policies.findSku(id).orElseThrow(() -> notFound(id));
        policies.lock(sku);
        ItemRow locked = row(id);
        if (!locked.sku().equals(sku)) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        return locked;
    }

    /** The item under its lock, refused when its SKU is retired; asked before the version, as #120 did. */
    private ItemRow lockEditable(UUID id) {
        ItemRow locked = lockItem(id);
        SkuRetirement owner = retirement.getIfAvailable();
        if (owner != null && owner.retired(locked.sku())) {
            throw new BusinessException(ErrorCode.VARIANT_OBSOLETE,
                    "SKU %s is obsolete: its inventory settings are kept as they are".formatted(locked.sku()));
        }
        return locked;
    }

    /** One read of the row, so the version, the logistics and the policy shown are those of the same save. */
    private Detail detail(ItemRow row) {
        return new Detail(row.id(), row.sku(), row.version(), row.logistics(), row.policy(),
                evaluate(row.sku(), row.policy()));
    }

    private void savePolicy(UUID id, long expectedVersion, InventoryPolicy policy) {
        StockPolicy.validate(policy);
        ItemRow current = lockEditable(id);
        if (current.version() != expectedVersion) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        String sku = current.sku();
        var previous = current.policy();
        boolean operationalChange =
                previous.removalStrategy() != policy.removalStrategy()
                        || previous.trackingMode() != policy.trackingMode()
                        || previous.expiryTracked() != policy.expiryTracked()
                        || !Objects.equals(previous.maxShelfLifeDays(), policy.maxShelfLifeDays());
        if (operationalChange && policies.hasActiveCount(sku))
            throw new BusinessException(ErrorCode.COUNT_POLICY_CONFLICT);
        if (policies.incompatible(sku, policy)) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }
        if (!policies.save(sku, expectedVersion, policy, actor()))
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        // The UPDATE matched version = expectedVersion and set version + 1, under the item's lock.
        events.publishEvent(new SkuInventoryControlChanged(sku, expectedVersion + 1, clock.instant()));
    }

    private void saveLogistics(UUID id, long expectedVersion, ItemLogistics logistics) {
        ItemLogisticsRules.validate(logistics);
        ItemRow current = lockEditable(id);
        if (current.version() != expectedVersion) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        String sku = current.sku();
        if (!current.logistics().unitOfMeasure().equals(logistics.unitOfMeasure()) && policies.holdsStock(sku)) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT,
                    "The unit of measure of %s cannot change while stock of it is held".formatted(sku));
        }
        try {
            if (!policies.saveLogistics(sku, expectedVersion, logistics, actor()))
                throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Another inventory item already has barcode " + logistics.barcode());
        }
    }

    private StockThresholdEvaluation evaluate(String sku, InventoryPolicy p) {
        long quantity = policies.usableOnHand(sku, BusinessCalendar.date(clock.instant()));
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
