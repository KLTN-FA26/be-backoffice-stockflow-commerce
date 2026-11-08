package com.stockflow.design.internal.domain;

/**
 * Whether the sales staff still have a customer's quote request on a design to answer (kltn-docs 16 §4.2, B40). Mapped
 * {@code EnumType.STRING}; {@code ck_design_quote_request_status} lists the same two.
 *
 * <ul>
 *   <li>{@code OPEN} - asked and not yet closed. A design returned after its request keeps it open, and the request
 *       follows the design when it is submitted again;</li>
 *   <li>{@code CLOSED} - the customer deleted the draft the request was on.</li>
 * </ul>
 */
public enum QuoteRequestStatus { OPEN, CLOSED }
