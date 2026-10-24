package com.stockflow.order.internal.domain;

import java.time.Instant;

/**
 * One status change of a return, with who made it and why: a row of {@code ordering.return_request_status_history},
 * written by the repository from {@link ReturnRequest#pullTransitions()} so no transition can forget its history.
 *
 * @param from  null for the opening
 * @param actor null when the system made the change (an expiry)
 */
public record ReturnTransition(ReturnStatus from, ReturnStatus to, java.util.UUID actor, String reason, Instant at) {
}
