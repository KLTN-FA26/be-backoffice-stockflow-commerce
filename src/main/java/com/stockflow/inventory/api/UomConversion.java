package com.stockflow.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One unit of measure of a SKU's inventory item (docs 01 §3 "Đơn vị quy đổi"): the base unit, or a
 * packing unit with how many base units it holds ({@code factor}, BR-09) and its own size and weight.
 * For the base unit, factor is 1 and the size and weight are the item's logistics.
 *
 * <p>{@code defaultPurchase}: the unit a purchase order line starts in (glossary "Purchase UoM"); one
 * per item, the base unit until another is marked. {@code active} false: retired (BR-10), kept for the
 * documents that used it, never offered for new ones.</p>
 */
public record UomConversion(
        UUID id,
        String sku,
        String unitOfMeasure,
        int factor,
        boolean base,
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        boolean defaultPurchase,
        boolean active,
        long version
) {
}
