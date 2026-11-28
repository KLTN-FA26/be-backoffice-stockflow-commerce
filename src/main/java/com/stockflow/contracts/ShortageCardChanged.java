package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a shortage choice card changes: sent, chosen by the customer, replaced or
 * ended (kltn-docs 17 §4.14, §5.6, BR-33; 16 BR-15). The chat keeps the card in step wherever it was posted and the
 * notification module tells Sales that the customer chose. Published after commit; delivered at least once, so a
 * listener must make a repeat harmless.
 *
 * <p>Facts only: the card's lines, figures and notes stay in the order module, read through its {@code api}. Consumers
 * receive it in-process; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param orderNumber      the order's number, for the notice's words (notification cannot read the order)
 * @param missingCartons   the cartons the card is short of, for the same
 * @param status           the card's status after the change: {@code SENT}, {@code ANSWERED}, {@code EXECUTED},
 *                         {@code SUPERSEDED} or {@code EXPIRED}
 * @param chosenOption     the option the customer chose; null while they have not
 * @param executionStarted whether Sales started carrying the choice out in steps
 * @param change           what happened: {@code SENT}, {@code CHOSEN}, {@code EXECUTION_STARTED}, {@code EXECUTED},
 *                         {@code SUPERSEDED}, {@code EXPIRED} or {@code CLOSE_REST_DUE}
 */
public record ShortageCardChanged(UUID cardId, UUID orderId, String orderNumber, UUID customerId, String status,
                                  String chosenOption, int missingCartons, boolean executionStarted, String change,
                                  Instant occurredAt) {
}
