package com.stockflow.order.internal.domain;

import com.stockflow.order.api.OrderStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One status change the aggregate made and has not been saved yet: the repository writes one history row for each,
 * with who made it and why (SCRUM-247), and moves the row through each in turn, so the database's transition trigger
 * sees every edge.
 *
 * @param actorId who made it; null when the system did
 * @param at      when; null to let the repository stamp it with its clock
 * @param reason  a code and, for a hold or a failed delivery, the note - what the history shows staff
 */
public record OrderTransition(OrderStatus from, OrderStatus to, UUID actorId, String reason, Instant at) {
}
