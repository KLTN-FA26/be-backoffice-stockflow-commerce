package com.stockflow.order.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The back-office order list (SCRUM-443). Every filter is optional.
 *
 * @param search     order number, contact or recipient name, contact or recipient phone —
 *                   case-insensitive, contains
 * @param statuses   any of these; empty means every status
 * @param placedFrom first day included, in the business's time zone
 * @param placedTo   last day included
 * @param sort       {@code field,asc|desc}; fields: orderNumber, placedAt, totalAmount, status
 */
public record ListOrdersQuery(
        int page,
        int size,
        String search,
        List<OrderStatus> statuses,
        UUID customerId,
        LocalDate placedFrom,
        LocalDate placedTo,
        String sort
) {

    public ListOrdersQuery {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }
}
