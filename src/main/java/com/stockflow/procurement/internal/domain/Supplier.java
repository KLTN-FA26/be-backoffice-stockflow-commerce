package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Owns profile validation, immutable code and supplier status transitions. */
public final class Supplier extends AggregateRoot {
    private final UUID id;
    private SupplierDetails details;
    private final Instant createdAt;
    private final Instant lastModifiedAt;
    public Supplier(UUID id, SupplierDetails details, Instant createdAt, Instant lastModifiedAt) {
        this.id = id; this.details = details; this.createdAt = createdAt; this.lastModifiedAt = lastModifiedAt;
    }
    public static Supplier create(SupplierDetails details) {
        return new Supplier(Identifiers.newId(), validated(details), null, null);
    }
    public void update(SupplierDetails replacement, boolean hasOpenOrders) {
        replacement = validated(replacement);
        if (!details.code().equalsIgnoreCase(replacement.code())) throw new BusinessException(ErrorCode.CONFLICT, "Supplier code is immutable");
        if (replacement.status() == SupplierStatus.INACTIVE) requireNoOpenOrders(hasOpenOrders);
        details = replacement;
    }
    public void deactivate(boolean hasOpenOrders) {
        requireNoOpenOrders(hasOpenOrders);
        var d = details;
        details = new SupplierDetails(d.code(), d.name(), d.contactName(), d.email(), d.phone(), d.taxCode(),
                SupplierStatus.INACTIVE, d.paymentTermDays(), d.leadTimeDays(), d.communicationChannel(), d.apiEndpoint());
    }
    private static void requireNoOpenOrders(boolean open) {
        if (open) throw new BusinessException(ErrorCode.SUPPLIER_HAS_OPEN_PURCHASE_ORDERS);
    }
    private static SupplierDetails validated(SupplierDetails d) {
        new SupplierProfile(d.code(), d.name(), d.email(), d.phone(), d.taxCode(), d.paymentTermDays(),
                d.leadTimeDays(), d.communicationChannel().name(), d.apiEndpoint());
        if (d.status() == null || d.contactName() != null && d.contactName().length() > 200)
            throw new IllegalArgumentException("Invalid supplier status or contact name");
        return new SupplierDetails(d.code().toUpperCase(Locale.ROOT), d.name().trim(), clean(d.contactName()),
                clean(d.email()), clean(d.phone()), clean(d.taxCode()), d.status(), d.paymentTermDays(),
                d.leadTimeDays(), d.communicationChannel(), clean(d.apiEndpoint()));
    }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    public UUID id() { return id; }
    public SupplierDetails details() { return details; }
    public Instant createdAt() { return createdAt; }
    public Instant lastModifiedAt() { return lastModifiedAt; }
}
