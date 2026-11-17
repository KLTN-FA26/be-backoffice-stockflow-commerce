package com.stockflow.order.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
 * @param warehouseIds null: every order; otherwise only orders shipping from (or released to) one of these warehouses,
 *                   and none at all when it is empty - the list of a caller limited to warehouses (team plan 5-7 review
 *                   M1: the warehouse manager reads the orders of their warehouses). Empty never means "all"
 */
public record ListOrdersQuery(
        int page,
        int size,
        String search,
        List<OrderStatus> statuses,
        UUID customerId,
        LocalDate placedFrom,
        LocalDate placedTo,
        String sort,
        Set<UUID> warehouseIds
) {

    public ListOrdersQuery {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        warehouseIds = warehouseIds == null ? null : Set.copyOf(warehouseIds);
    }

    /** Every order the other filters match: no warehouse limit. */
    public ListOrdersQuery(int page, int size, String search, List<OrderStatus> statuses, UUID customerId,
                           LocalDate placedFrom, LocalDate placedTo, String sort) {
        this(page, size, search, statuses, customerId, placedFrom, placedTo, sort, null);
    }
}
