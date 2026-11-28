package com.stockflow.order.internal.domain;

/**
 * Where a draft contract stands with the Sales Manager's approval of its terms (QĐ-4 of the contract e-signing plan). Only
 * a contract that deviates from the standard terms - a special-terms text, or a penalty, tolerance or warranty that is
 * not the configured default - is ever put in front of a manager; every other contract stays NONE and is sent as it is.
 */
public enum TermsReview {

    /** Not asked for: the contract is standard, or Sales has not sent it for review yet. */
    NONE,
    /** Waiting for a Sales Manager; the draft is locked against edits meanwhile. */
    PENDING,
    /** Approved: the contract may be sent as it is. Any edit of the draft takes the approval back (NONE). */
    APPROVED,
    /** Refused, with the manager's reason; the draft is Sales' to change and send for review again. */
    REJECTED
}
