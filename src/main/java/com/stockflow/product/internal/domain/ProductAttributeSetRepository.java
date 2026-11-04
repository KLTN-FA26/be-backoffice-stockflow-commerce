package com.stockflow.product.internal.domain;

import com.stockflow.product.api.ProductStatus;

import java.util.Set;
import java.util.UUID;

/** Persistence port for {@link ProductAttributeSet}. */
public interface ProductAttributeSetRepository {

    /** The product's axes and descriptive attributes; {@code status} is the product's current one, which the set restates. */
    ProductAttributeSet load(UUID productId, ProductStatus status);

    ProductAttributeSet save(ProductAttributeSet set);

    /** Dictionary values that some variant of the product uses; an option for one of them cannot be removed. */
    Set<UUID> valueIdsUsedByVariants(UUID productId);
}
