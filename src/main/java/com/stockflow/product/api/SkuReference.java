package com.stockflow.product.api;

import java.util.UUID;

/**
 * One open document that still refers to a SKU of a product: what a person is shown when the product
 * is discontinued or deleted (SCRUM-461). Produced by whichever module owns the document, through
 * {@link SkuReferenceProbe}.
 *
 * @param kind           which kind of document
 * @param documentNumber the number a person recognises it by ({@code PRD-20261014-0001}, {@code ORD-…})
 * @param documentId     its id, for a link
 * @param sku            the SKU it refers to
 */
public record SkuReference(Kind kind, String documentNumber, UUID documentId, String sku) {

    /** Where the reference lives. Closed on purpose: each value is a warning a screen words differently. */
    public enum Kind {
        /**
         * A line of an open production order with cartons still to come (kltn-docs 01 BR-14). It replaced the open
         * purchase order line when purchasing went (4A-8): goods arrive only against production orders now.
         */
        PRODUCTION_ORDER_LINE,
        /** A line of an order that has been placed and not yet shipped. */
        SALES_ORDER_LINE
    }
}
