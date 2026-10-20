package com.stockflow.product.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What the product module asks the supplier module when a SKU's default supplier is set (docs 01 §3
 * "Thông tin mua hàng"): does this supplier exist, and may new purchase orders go to it?
 *
 * <p>An interface in {@code product :: api} implemented in {@code procurement}, for the same reason as
 * {@link SkuReferenceProbe}: procurement depends on product, so product calling procurement would close
 * a cycle that {@code ModularityTest} rejects.</p>
 */
public interface SupplierProbe {

    /** Empty when no supplier has this id. */
    Optional<Standing> supplier(UUID supplierId);

    /** @param active the supplier may receive new purchase orders (status ACTIVE) */
    record Standing(UUID supplierId, String code, boolean active) {
    }
}
