package com.stockflow.product.api;

import java.util.List;

/**
 * The outcome of discontinuing a product (SCRUM-461): the product as it is now, plus what the person
 * should know. The discontinuation happened; the warnings are not refusals (kltn-docs 01 step 12).
 *
 * @param product    the product, {@link ProductStatus#DISCONTINUED}
 * @param warnings   codes: {@code OPEN_PURCHASE_ORDER_LINES} (goods still arriving are received as usual),
 *                   {@code UNSHIPPED_ORDER_LINES} (orders already placed carry on)
 * @param references the open documents behind the warnings
 */
public record ProductDiscontinuation(ProductSummary product, List<String> warnings, OpenReferences references) {

    public static final String OPEN_PURCHASE_ORDER_LINES = "OPEN_PURCHASE_ORDER_LINES";
    public static final String UNSHIPPED_ORDER_LINES = "UNSHIPPED_ORDER_LINES";

    public ProductDiscontinuation {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
