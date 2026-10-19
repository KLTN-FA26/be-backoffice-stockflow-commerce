package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.CommercialTerms;
import com.stockflow.common.id.Identifiers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One supplier's offer for one inventory item: who sells it, on what terms, and whether this is
 * the supplier we normally buy it from.
 *
 * <p>Terms a caller supplies validate themselves ({@link SupplierItemTerms#of}); a row read back from
 * the database is rebuilt unchecked, so it can still be listed, corrected or removed. This
 * aggregate owns what depends on history and on other rows: whether an update changed the price,
 * and what a given order quantity is allowed to be. Uniqueness of the preferred flag per item is a cross-row rule, so the service
 * enforces it under {@link SupplierItemRepository#lockCatalogOf(UUID)}.</p>
 */
public final class SupplierItem extends AggregateRoot {
    private final UUID id;
    private final UUID supplierId;
    private final UUID inventoryItemId;
    private SupplierItemTerms terms;
    private boolean preferred;
    private final long version;
    private final Instant createdAt;
    private final Instant lastModifiedAt;

    public SupplierItem(
            UUID id,
            UUID supplierId,
            UUID inventoryItemId,
            SupplierItemTerms terms,
            boolean preferred,
            long version,
            Instant createdAt,
            Instant lastModifiedAt) {
        this.id = id;
        this.supplierId = supplierId;
        this.inventoryItemId = inventoryItemId;
        this.terms = terms;
        this.preferred = preferred;
        this.version = version;
        this.createdAt = createdAt;
        this.lastModifiedAt = lastModifiedAt;
    }

    public static SupplierItem create(
            UUID supplierId, UUID inventoryItemId, SupplierItemTerms terms, boolean preferred) {
        return new SupplierItem(
                Identifiers.newId(), supplierId, inventoryItemId, terms, preferred, 0L, null, null);
    }

    /**
     * Replaces the terms and the preferred flag. The price history is not this aggregate's to
     * write, so the change is returned for the repository to record.
     *
     * <p>The replacement is priced in the supplier's currency. A row priced in another one (nothing in
     * the schema ties {@code supplier_items.currency} to the supplier's, and carried rows may differ) is
     * repriced into it by this edit: always a price change, whatever the amounts, so the old period
     * closes in its own currency and the new one opens in the supplier's. Refusing it instead would
     * leave the row uneditable, and delete-and-relist would lose its price history.</p>
     *
     * @return the price movement, empty when currency and amount are unchanged ({@code 10.00} to
     *         {@code 10.0} is not a change, hence {@code compareTo} and not {@code equals})
     */
    public Optional<PriceChange> update(SupplierItemTerms replacement, boolean preferred) {
        var current = terms.price();
        var next = replacement.price();
        Optional<PriceChange> change =
                current.currency().equals(next.currency()) && current.compareTo(next) == 0
                        ? Optional.empty()
                        : Optional.of(new PriceChange(current, next));
        this.terms = replacement;
        this.preferred = preferred;
        return change;
    }

    /** Never throws: the caller decides whether a violation is a refusal or only a warning. */
    public List<OrderQuantityViolation> orderQuantityViolations(int quantity) {
        var violations = new ArrayList<OrderQuantityViolation>(2);
        if (quantity < terms.moq()) violations.add(OrderQuantityViolation.BELOW_MOQ);
        if (quantity % terms.packSize() != 0) violations.add(OrderQuantityViolation.NOT_PACK_MULTIPLE);
        return List.copyOf(violations);
    }

    /** Item lead time, else the supplier's, else the system default. */
    public int effectiveLeadTimeDays(Integer supplierLeadTimeDays) {
        if (terms.leadTimeDays() != null) return terms.leadTimeDays();
        return supplierLeadTimeDays != null ? supplierLeadTimeDays : CommercialTerms.LEAD_DAYS;
    }

    public UUID id() {
        return id;
    }

    public UUID supplierId() {
        return supplierId;
    }

    public UUID inventoryItemId() {
        return inventoryItemId;
    }

    public SupplierItemTerms terms() {
        return terms;
    }

    public boolean preferred() {
        return preferred;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastModifiedAt() {
        return lastModifiedAt;
    }
}
