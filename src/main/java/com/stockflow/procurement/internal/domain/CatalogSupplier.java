package com.stockflow.procurement.internal.domain;

import java.util.Currency;
import java.util.UUID;

/**
 * The slice of a supplier the catalog needs: enough to refuse an inactive supplier, to fix the
 * currency of every price it quotes, and to fall back to its lead time. A separate record so the
 * catalog never loads, or locks the aggregate for, the whole {@code Supplier} profile.
 */
public record CatalogSupplier(
        UUID id, String code, String name, SupplierStatus status, Currency currency, Integer leadTimeDays) {}
