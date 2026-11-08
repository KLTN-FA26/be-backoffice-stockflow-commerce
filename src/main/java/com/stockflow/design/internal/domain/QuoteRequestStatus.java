package com.stockflow.design.internal.domain;

/**
 * Whether the sales staff still have a customer's quote request on a design to answer (kltn-docs 16 §4.2, B40). Mapped
 * {@code EnumType.STRING}; {@code ck_design_quote_request_status} lists the same two.
 *
 * <ul>
 *   <li>{@code OPEN} - asked and not yet answered. Asking again changes what it asks. A design returned after its
 *       request keeps it open, and the request follows the design when it is submitted again;</li>
 *   <li>{@code CLOSED} - the sales staff answered it (quoted it, or settled it with the customer), or the customer
 *       deleted the draft it was on. The design asks again, for its next order, with a new request.</li>
 * </ul>
 */
public enum QuoteRequestStatus { OPEN, CLOSED }
