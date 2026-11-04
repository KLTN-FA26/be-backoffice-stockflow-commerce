package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ItemPurchasingService;
import com.stockflow.procurement.api.SaveSupplierItemCommand;
import com.stockflow.procurement.api.SkuSupplierSummary;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.procurement.api.SupplierItemSummary;
import com.stockflow.procurement.internal.domain.CatalogSupplier;
import com.stockflow.procurement.internal.domain.SupplierItem;
import com.stockflow.procurement.internal.domain.SupplierItemRepository;
import com.stockflow.procurement.internal.domain.SupplierItemTerms;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.Purchasability;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The supplier catalog's transaction boundary.
 *
 * <p>Every write reads the supplier {@code FOR SHARE} first, so a concurrent deactivation cannot
 * slip in between the status check and the commit. An edit then locks its own row, and only that
 * lock makes the version comparison meaningful: the adapter does not apply the version on save.</p>
 *
 * <p>"Preferred" is not stored on the catalog row. The supplier we normally buy an item from is the
 * item's default supplier (kltn-docs 01 §3 "Nhà cung cấp mặc định", read by 02 §7), kept by inventory: a
 * row is preferred when its supplier is that default. Preferring a row sets the item's default supplier;
 * un-preferring the preferred row clears it; both go through {@code inventory :: api} under the item's
 * per-SKU lock, so two buyers preferring different suppliers at once both succeed and the later one wins.
 * Creating a row never clears a default: a new row is not yet anybody's choice to withdraw. Removing a
 * row leaves the item's default supplier alone: it is the item's master data, not the catalog's.</p>
 */
@Service
@Transactional
class SupplierItemServiceImpl implements SupplierItemService {

    private final SupplierItemRepository items;
    private final InventoryService inventory;
    private final ItemPurchasingService purchasing;
    private final ProductService products;
    private final Clock clock;

    SupplierItemServiceImpl(
            SupplierItemRepository items, InventoryService inventory, ItemPurchasingService purchasing,
            ProductService products, Clock clock) {
        this.items = items;
        this.inventory = inventory;
        this.purchasing = purchasing;
        this.products = products;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "supplier-item", resourceId = "#result?.supplierItemId()")
    public SupplierItemSummary create(UUID supplierId, SaveSupplierItemCommand command) {
        var supplier = activeSupplier(supplierId);
        var sku = sku(command.sku());
        var policy = inventoryItem(sku);
        requirePurchasable(sku);
        var item = SupplierItem.create(supplierId, policy.inventoryItemId(), terms(command, supplier.currency()));
        var saved = items.save(item, Optional.empty(), clock.instant());
        if (command.preferred()) purchasing.assignDefaultSupplier(saved.inventoryItemId(), supplierId);
        return summary(saved, policy.sku(), supplier, preferred(saved));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "supplier-item", resourceId = "#supplierItemId")
    public SupplierItemSummary update(UUID supplierId, UUID supplierItemId, SaveSupplierItemCommand command) {
        var supplier = activeSupplier(supplierId);
        // The request's own @NotNull catches this first; the code is the one that bean validation
        // would have reported for the same field.
        if (command.version() == null)
            throw invalid("version", "NotNull", "Version is required to edit a supplier item");
        var item = lockedItem(supplierId, supplierItemId, command.version());
        var priceChange = item.update(terms(command, supplier.currency()));
        var saved = items.save(item, priceChange, clock.instant());
        if (command.preferred()) purchasing.assignDefaultSupplier(saved.inventoryItemId(), supplierId);
        else purchasing.clearDefaultSupplier(saved.inventoryItemId(), supplierId);
        return summary(saved, skuOf(saved.inventoryItemId()), supplier, preferred(saved));
    }

    @Override
    @Auditable(action = AuditAction.DELETE, resourceType = "supplier-item", resourceId = "#supplierItemId")
    public void delete(UUID supplierId, UUID supplierItemId, long version) {
        supplier(supplierId);
        var item = lockedItem(supplierId, supplierItemId, version);
        if (items.usedByOpenPurchaseOrder(supplierId, item.inventoryItemId()))
            throw new BusinessException(ErrorCode.SUPPLIER_ITEM_IN_USE);
        items.delete(item);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SupplierItemSummary> list(UUID supplierId, int page, int size, String sort) {
        var supplier = items.findSupplier(supplierId).orElseThrow(() -> supplierNotFound(supplierId));
        var rows = items.listBySupplier(supplierId, page, size, sort);
        // One lookup for the page's SKUs, not one per row.
        var itemIds = rows.items().stream().map(SupplierItem::inventoryItemId).toList();
        var policies = inventory.itemPolicies(itemIds);
        var defaults = purchasing.defaultSuppliers(itemIds);
        return rows.map(item -> summary(item, policies.get(item.inventoryItemId()).sku(), supplier,
                supplierId.equals(defaults.get(item.inventoryItemId()))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkuSupplierSummary> suppliersOf(String sku) {
        var policy = inventoryItem(sku(sku));
        UUID defaultSupplier = purchasing.defaultSuppliers(List.of(policy.inventoryItemId()))
                .get(policy.inventoryItemId());
        // Stable: the default supplier first, the rest in the repository's supplier-name order.
        return items.suppliersOf(policy.inventoryItemId()).stream()
                .sorted(Comparator.comparing(row -> !row.supplier().id().equals(defaultSupplier)))
                .map(row -> {
                    var item = row.item();
                    var supplier = row.supplier();
                    var price = item.terms().price();
                    return new SkuSupplierSummary(supplier.id(), supplier.code(), supplier.name(),
                            supplier.status().name(), item.id(), item.terms().supplierSkuCode(), price.amount(),
                            price.currency().getCurrencyCode(), item.terms().moq(), item.terms().packSize(),
                            item.effectiveLeadTimeDays(supplier.leadTimeDays()),
                            supplier.id().equals(defaultSupplier));
                })
                .toList();
    }

    /** Read {@code FOR SHARE}: writes only, a read-only transaction refuses the lock. */
    private CatalogSupplier supplier(UUID supplierId) {
        return items.supplierForShare(supplierId).orElseThrow(() -> supplierNotFound(supplierId));
    }

    private static BusinessException supplierNotFound(UUID supplierId) {
        return new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND, "No supplier with id " + supplierId);
    }

    private CatalogSupplier activeSupplier(UUID supplierId) {
        var supplier = supplier(supplierId);
        if (supplier.status() != SupplierStatus.ACTIVE) throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE);
        return supplier;
    }

    /** Another supplier's row is reported as missing, not as forbidden: its existence is not this supplier's business. */
    private SupplierItem lockedItem(UUID supplierId, UUID supplierItemId, long version) {
        var item = items.findByIdForUpdate(supplierItemId)
                .filter(found -> found.supplierId().equals(supplierId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_ITEM_NOT_FOUND,
                        "No supplier item " + supplierItemId + " for supplier " + supplierId));
        if (item.version() != version) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        return item;
    }

    private static Sku sku(String code) {
        try {
            return new Sku(code);
        } catch (IllegalArgumentException malformed) {
            throw invalid("sku", ErrorCode.SUPPLIER_ITEM_SKU_INVALID.name(), malformed.getMessage());
        }
    }

    private InventoryItemPolicy inventoryItem(Sku sku) {
        return inventory.itemPolicy(sku)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND,
                        "SKU " + sku.code() + " has no inventory item"));
    }

    private String skuOf(UUID inventoryItemId) {
        return inventory.itemPolicy(inventoryItemId).map(InventoryItemPolicy::sku)
                .orElseThrow(() -> new IllegalStateException("Inventory item " + inventoryItemId + " vanished"));
    }

    /** kltn-docs 02 BR-01: a catalog row is an offer to buy, so it takes only a SKU a new order line may take. */
    private void requirePurchasable(Sku sku) {
        Purchasability verdict = products.purchasability(List.of(sku.code())).get(sku.code());
        if (verdict != null && !verdict.isPurchasable()) {
            String message = "SKU " + sku.code() + " is not active for purchasing";
            throw new BusinessException(ErrorCode.SKU_NOT_PURCHASABLE, message,
                    List.of(new FieldError("sku", message, verdict.name())));
        }
    }

    /** The price is checked as entered, in the supplier's currency, by {@link SupplierItemTerms#of}. */
    private static SupplierItemTerms terms(SaveSupplierItemCommand command, Currency currency) {
        return SupplierItemTerms.of(command.supplierSkuCode(), command.price(), currency, command.moq(),
                command.packSize(), command.leadTimeDays());
    }

    /**
     * A 400 naming one field. The field code is what the exception handler translates beside the input,
     * so it is never {@code VALIDATION_FAILED}: that would put "invalid data" there instead of the rule.
     */
    private static BusinessException invalid(String field, String fieldCode, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldError(field, message, fieldCode)));
    }

    /** Whether the row's supplier is its item's default supplier, read after this transaction's own write. */
    private boolean preferred(SupplierItem item) {
        return item.supplierId().equals(purchasing.defaultSuppliers(List.of(item.inventoryItemId()))
                .get(item.inventoryItemId()));
    }

    private static SupplierItemSummary summary(SupplierItem item, String sku, CatalogSupplier supplier,
                                               boolean preferred) {
        var terms = item.terms();
        return new SupplierItemSummary(item.id(), item.supplierId(), item.inventoryItemId(), sku,
                terms.supplierSkuCode(), terms.price().amount(), terms.price().currency().getCurrencyCode(),
                terms.moq(), terms.packSize(), terms.leadTimeDays(),
                item.effectiveLeadTimeDays(supplier.leadTimeDays()), preferred, item.version(),
                item.createdAt(), item.lastModifiedAt());
    }
}
