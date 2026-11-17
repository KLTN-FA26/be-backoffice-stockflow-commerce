package com.stockflow.chat.internal.domain;

/**
 * The cards the system draws into a conversation from a document (kltn-docs 16 §3 Output, BR-06, BR-10, BR-11). Each
 * card's data is the message's jsonb payload, shaped by its kind; package 3 writes and acts on them. Mapped
 * {@code EnumType.STRING}; {@code ck_message_card_kind} holds the same list.
 */
public enum CardKind {
    /** A quote request from the customer's design or design project (BR-11). */
    QUOTE_REQUEST,
    /** A quote the customer accepts or asks to change on the card (16 §4.3 step 7). */
    QUOTE,
    /** The sales contract of an order, with its instalment schedule (16 §4.3 step 8). */
    CONTRACT,
    /** Transfer details of a due instalment, with its VietQR (BR-06). */
    PAYMENT_INFO,
    /** A reminder of an instalment or receivable that is due or overdue (16 §4.4 step 10). */
    PAYMENT_REMINDER,
    /** A print sample round the customer approves or asks to reprint (BR-10). */
    SAMPLE_APPROVAL,
    /** An issued e-invoice, to view or download (16 §4.6 step 15). */
    INVOICE,
    /** Where an order stands (16 §4.6 step 15). */
    ORDER_STATUS,
    /** Where a production order stands (16 §4.5 step 14). */
    PRODUCTION_PROGRESS,
    /** The satisfaction survey sent when a conversation is resolved (16 §4.6 step 18). */
    CSAT_SURVEY
}
