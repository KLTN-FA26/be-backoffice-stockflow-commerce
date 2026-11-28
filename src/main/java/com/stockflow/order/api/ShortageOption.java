package com.stockflow.order.api;

/**
 * The four ways on a shortage choice card offers (kltn-docs 17 §4.14 step 56, BR-33). Stored as text in
 * {@code ordering.shortage_card.options_offered} / {@code chosen_option}, whose CHECKs list the same names.
 */
public enum ShortageOption {
    /** Wait for production of the missing cartons; carries an expected delivery date (D48). */
    WAIT_FOR_PRODUCTION,
    /** "Giảm số thùng": drop only the missing cartons of the batch, no contract annex (D45). Always offered (D51). */
    REDUCE_CARTONS,
    /** Ship what is there, then close the rest of the order (17 §4.8 step 33). */
    SHIP_PART_CLOSE_REST,
    /** Cancel the whole order (17 §4.8 step 30). */
    CANCEL_ORDER
}
