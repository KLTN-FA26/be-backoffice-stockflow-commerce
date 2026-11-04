package com.stockflow.product.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.List;

/**
 * The SKU a variant gets when the caller supplies none: {@code PRODUCT_CODE-VALUE-VALUE}, value codes
 * in axis order. There is deliberately no automatic suffix on a collision or an overflow: a SKU that
 * the caller did not choose and cannot predict is worse than a refusal that says "supply one".
 */
public final class SkuCodeRule {

    private SkuCodeRule() {
    }

    public static Sku generate(String productCode, List<String> valueCodesInAxisOrder) {
        StringBuilder code = new StringBuilder(productCode);
        valueCodesInAxisOrder.forEach(v -> code.append('-').append(v));
        if (code.length() > Sku.MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The generated SKU %s is longer than %d characters; shorten the product or value codes, or supply a SKU by hand"
                            .formatted(code, Sku.MAX_LENGTH));
        }
        return new Sku(code.toString());
    }
}
