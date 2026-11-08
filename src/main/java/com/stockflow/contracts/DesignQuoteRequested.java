package com.stockflow.contracts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by the <b>design</b> module when a customer asks for a quote on their design (kltn-docs 12 §4.8, 16 §4.2
 * step 5): on an approved design, or with a draft's submission. {@code notification} tells the sales staff, until the
 * chat module carries the request as a card. Facts only: no design name, which the customer typed.
 *
 * @param requestId           the request
 * @param designId            the design it is on
 * @param customerId          whose design it is
 * @param designStatus        {@code APPROVED}, or {@code PENDING_REVIEW} when asked with a submission: quoted only once
 *                            approved (12 BR-15)
 * @param sku                 the variant the design is drawn on
 * @param customerSku         the customer's own SKU of an approved design; null while it waits for review
 * @param cartons             the cartons the customer would like - a suggestion (14 BR-22)
 * @param desiredDeliveryDate the delivery date they would like, or null
 * @param requestedAt         when they asked
 */
public record DesignQuoteRequested(UUID requestId, UUID designId, UUID customerId, String designStatus, String sku,
                                   String customerSku, int cartons, LocalDate desiredDeliveryDate,
                                   Instant requestedAt) {
}
