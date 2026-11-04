package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;

import java.math.BigDecimal;

/**
 * Body of {@code POST} and {@code PUT} on a supplier's catalog. The {@code sku} names the row on create
 * and is ignored on update (a row never moves to another SKU); {@code version} is required on update
 * only. Whether the price fits the supplier's currency is the service's call, since the supplier decides
 * the currency.
 */
public record SaveSupplierItemRequest(
        @NotBlank(groups = Create.class) String sku,
        @Size(max = 50) String supplierSkuCode,
        @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal price,
        @NotNull @Min(1) Integer moq,
        @NotNull @Min(1) Integer packSize,
        @Min(0) @Max(365) Integer leadTimeDays,
        Boolean preferred,
        @NotNull(groups = Update.class) Long version) {

    /** POST names the SKU. */
    public interface Create extends Default {}

    /** PUT must say which version of the row it was edited from. */
    public interface Update extends Default {}
}
