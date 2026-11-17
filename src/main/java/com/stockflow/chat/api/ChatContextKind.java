package com.stockflow.chat.api;

/** What a conversation can be about (kltn-docs 16 §1), as other modules name it. Same names as the chat's own. */
public enum ChatContextKind {
    PRODUCT, CUSTOMER_SKU, DESIGN, DESIGN_PROJECT, QUOTE, ORDER, CONTRACT, PRODUCTION_ORDER, EINVOICE
}
