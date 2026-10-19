package com.stockflow.procurement.api;

import java.math.BigDecimal;

/**
 * What a buyer enters for one SKU in a supplier's catalog. The price is in the supplier's currency,
 * which is why the command carries no currency of its own.
 *
 * @param sku          read on create only: a row never moves to another SKU, it is removed and listed again
 * @param leadTimeDays {@code null} means "use the supplier's lead time"
 * @param version      required on update, ignored on create
 */
public record SaveSupplierItemCommand(
        String sku,
        String supplierSkuCode,
        BigDecimal price,
        int moq,
        int packSize,
        Integer leadTimeDays,
        boolean preferred,
        Long version) {}
