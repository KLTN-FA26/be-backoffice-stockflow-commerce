package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.api.SaveSupplierItemCommand;
import com.stockflow.procurement.api.SkuSupplierSummary;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.procurement.api.SupplierItemSummary;
import com.stockflow.procurement.internal.domain.CatalogSupplier;
import com.stockflow.procurement.internal.domain.PurchasePrices;
import com.stockflow.procurement.internal.domain.SupplierItem;
import com.stockflow.procurement.internal.domain.SupplierItemRepository;
import com.stockflow.procurement.internal.domain.SupplierItemTerms;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.Purchasability;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
 * <p>"At most one preferred supplier per SKU" spans rows no single row lock covers, so making a row
 * preferred takes the item's catalog lock first and clears the previous preferred row, flushed,
 * before the new one is written; {@code uk_supplier_items_preferred} never sees two at once. Two
 * buyers preferring different suppliers at the same moment therefore both succeed, and the later
 * one wins.</p>
 */
@Service
@Transactional
class SupplierItemServiceImpl implements SupplierItemService {

    /** The largest amount {@code NUMERIC(18,2)} holds. */

    private final SupplierItemRepository items;
    private final InventoryService inventory;
    private final ProductService products;
    private final Clock clock;

    SupplierItemServiceImpl(
            SupplierItemRepository items, InventoryService inventory, ProductService products, Clock clock) {
        this.items = items;
        this.inventory = inventory;
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
        var item = SupplierItem.create(supplierId, policy.inventoryItemId(), terms(command, supplier.currency()),
                command.preferred());
        Instant now = clock.instant();
        if (item.preferred()) unpreferOthers(item, now);
        return summary(items.save(item, Optional.empty(), now), policy.sku(), supplier);
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
        var terms = terms(command, supplier.currency());
        Instant now = clock.instant();
        if (command.preferred() && !item.preferred()) unpreferOthers(item, now);
        var priceChange = item.update(terms, command.preferred());
        var saved = items.save(item, priceChange, now);
        return summary(saved, skuOf(saved.inventoryItemId()), supplier);
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
        var policies = inventory.itemPolicies(rows.items().stream().map(SupplierItem::inventoryItemId).toList());
        return rows.map(item -> summary(item, policies.get(item.inventoryItemId()).sku(), supplier));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkuSupplierSummary> suppliersOf(String sku) {
        var policy = inventoryItem(sku(sku));
        return items.suppliersOf(policy.inventoryItemId()).stream()
                .map(row -> {
                    var item = row.item();
                    var supplier = row.supplier();
                    var price = item.terms().price();
                    return new SkuSupplierSummary(supplier.id(), supplier.code(), supplier.name(),
                            supplier.status().name(), item.id(), item.terms().supplierSkuCode(), price.amount(),
                            price.currency().getCurrencyCode(), item.terms().moq(), item.terms().packSize(),
                            item.effectiveLeadTimeDays(supplier.leadTimeDays()), item.preferred());
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

    /**
     * Clears the SKU's current preferred row, if it is another one, under the catalog lock. Saved
     * (and flushed) before the caller saves {@code item}, so the partial unique index never holds two.
     */
    private void unpreferOthers(SupplierItem item, Instant now) {
        items.lockCatalogOf(item.inventoryItemId());
        items.findPreferred(item.inventoryItemId())
                .filter(current -> !current.id().equals(item.id()))
                .ifPresent(current -> {
                    current.update(current.terms(), false);
                    items.save(current, Optional.empty(), now);
                });
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

    private static SupplierItemTerms terms(SaveSupplierItemCommand command, Currency currency) {
        return SupplierItemTerms.of(command.supplierSkuCode(), price(command.price(), currency), command.moq(),
                command.packSize(), command.leadTimeDays());
    }

    /**
     * Checks the amount as entered, before {@link Money} rounds it to the currency's scale: a VND price
     * of {@code 1500.50} would otherwise be stored as {@code 1501} without a word. Same rule as a
     * purchase order line's unit price.
     */
    private static Money price(BigDecimal amount, Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) throw invalidPrice("Unsupported supplier currency " + currency.getCurrencyCode());
        if (!PurchasePrices.acceptable(amount, currency))
            throw invalidPrice("Price must be above zero, at most " + PurchasePrices.MAX.toPlainString()
                    + ", and have no more decimals than " + currency.getCurrencyCode() + " allows");
        return new Money(amount, currency);
    }

    private static BusinessException invalidPrice(String message) {
        return invalid("price", ErrorCode.SUPPLIER_ITEM_PRICE_INVALID.name(), message);
    }

    /**
     * A 400 naming one field. The field code is what the exception handler translates beside the input,
     * so it is never {@code VALIDATION_FAILED}: that would put "invalid data" there instead of the rule.
     */
    private static BusinessException invalid(String field, String fieldCode, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldError(field, message, fieldCode)));
    }

    private static SupplierItemSummary summary(SupplierItem item, String sku, CatalogSupplier supplier) {
        var terms = item.terms();
        return new SupplierItemSummary(item.id(), item.supplierId(), item.inventoryItemId(), sku,
                terms.supplierSkuCode(), terms.price().amount(), terms.price().currency().getCurrencyCode(),
                terms.moq(), terms.packSize(), terms.leadTimeDays(),
                item.effectiveLeadTimeDays(supplier.leadTimeDays()), item.preferred(), item.version(),
                item.createdAt(), item.lastModifiedAt());
    }
}
