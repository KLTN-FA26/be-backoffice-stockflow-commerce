package com.stockflow.chat.internal.domain;

/**
 * What a conversation can be about (kltn-docs 16 §1 "chat gắn ngữ cảnh", §3 Input). Each kind is a foreign key of
 * {@code chat.conversation_context}. Mapped {@code EnumType.STRING}.
 */
public enum ContextKind {
    /** A product of the catalogue - anyone's to ask about. */
    PRODUCT,
    /** The customer's own SKU (a variant made to their approved design). */
    CUSTOMER_SKU,
    /** A design of the customer. */
    DESIGN,
    /** A design project of the customer. */
    DESIGN_PROJECT,
    QUOTE,
    ORDER,
    /** A sales contract. */
    CONTRACT,
    PRODUCTION_ORDER,
    /** An issued e-invoice. */
    EINVOICE;

    /** Whether the document belongs to one customer, who alone may talk about it (16 BR-02). */
    public boolean belongsToACustomer() {
        return this != PRODUCT;
    }
}
