package com.stockflow.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The purchasing information of one SKU's inventory item (docs 01 §3 "Thông tin mua hàng": default
 * supplier, reference purchase price, lead time, MOQ; recommended, never required). These are the
 * item's own reference values, which a purchase order starts from (02 §7); what each supplier offers
 * is the supplier catalog, kept by procurement. BR-06: the reference purchase price belongs to the
 * item and is independent of the selling price; it is stored, never compared with it.
 *
 * <p>Every field may be null ("not known"). The price comes with its currency, both or neither.</p>
 */
public record ItemPurchasing(
        UUID defaultSupplierId,
        BigDecimal referencePurchasePrice,
        String referencePurchaseCurrency,
        Integer leadTimeDays,
        Integer moq
) {
}
