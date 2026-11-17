package com.stockflow.chat.api;

/**
 * The cards the system puts into a conversation (kltn-docs 16 §3 Output). Same names as the chat's own; each kind's
 * payload shape is agreed between the module that sends it and the clients that draw it.
 */
public enum ChatCardKind {
    QUOTE_REQUEST, QUOTE, CONTRACT, PAYMENT_INFO, PAYMENT_REMINDER, SAMPLE_APPROVAL, INVOICE, ORDER_STATUS,
    PRODUCTION_PROGRESS, CSAT_SURVEY
}
