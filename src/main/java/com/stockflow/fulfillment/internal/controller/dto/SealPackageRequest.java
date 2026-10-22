package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Weighed, measured, sealed (kltn-docs 08 step 5-7, BR-03): the real weight and size are required.
 *
 * @param rotatable null: from the SKUs inside, the strictest wins (01, E13); set: the packer's override
 * @param fragile   null: from the SKUs inside (storage class FRAGILE); set: the packer's override
 * @param kitOverrideReason KIT-FORCE: an ordinary package holding an item that ships as several boxes is sealed only
 *                  with a reason - its own (given when opened or scanned) or this one
 */
public record SealPackageRequest(@NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal weightKg,
                                 @NotNull @Positive @Digits(integer = 8, fraction = 2) @DecimalMax("100000")
                                 BigDecimal lengthCm,
                                 @NotNull @Positive @Digits(integer = 8, fraction = 2) @DecimalMax("100000")
                                 BigDecimal widthCm,
                                 @NotNull @Positive @Digits(integer = 8, fraction = 2) @DecimalMax("100000")
                                 BigDecimal heightCm,
                                 @Size(max = 16) String packageType,
                                 Boolean rotatable,
                                 Boolean fragile,
                                 @Size(max = 500) String kitOverrideReason) {
}
