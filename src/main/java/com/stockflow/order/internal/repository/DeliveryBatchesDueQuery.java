package com.stockflow.order.internal.repository;

import com.stockflow.common.api.PageResponse;
import com.stockflow.order.api.SalesQueues;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The delivery batches due on a day ({@link SalesQueues#batchesDue}), in one count and one page statement over this
 * module's own tables: a batch of a contract whose window includes the day, of an order still owing deliveries, with
 * no shipment handed over for it. The warehouse rule is the order list's (orders shipping from or released to one of
 * the warehouses), applied by id here as the order search applies it.
 *
 * <p><b>Only the live contract's batches.</b> A contract cancelled before signing stays beside the one drawn up again
 * ({@code uk_sales_contract_order_live} allows one that is not CANCELLED per order), and its delivery batches stay with
 * it: without the filter the batch is listed twice, and a cancelled window ending today would make the queue's section
 * critical for a batch nobody owes.</p>
 *
 * <p>Every value is bound; the only text built is the fixed predicates.</p>
 */
@Repository
public class DeliveryBatchesDueQuery {

    private static final String FROM = """
              FROM ordering.delivery_batch b
              JOIN ordering.sales_contract k ON k.id = b.contract_id AND k.status <> 'CANCELLED'
              JOIN ordering.customer_order o ON o.id = k.order_id
             WHERE b.window_start <= :day AND b.window_end >= :day
               AND (o.status IN (:followed) OR (o.status = 'ON_HOLD' AND o.status_before_hold IN (:followed)))
               AND NOT EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.delivery_batch_id = b.id)""";

    private final NamedParameterJdbcTemplate jdbc;

    public DeliveryBatchesDueQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param followed     the order statuses that still owe deliveries
     * @param warehouseIds null for no limit; empty for none
     */
    public PageResponse<SalesQueues.BatchDue> due(LocalDate day, Collection<String> followed, Set<UUID> warehouseIds,
                                                 int limit) {
        if (warehouseIds != null && warehouseIds.isEmpty()) {
            return PageResponse.of(List.of(), 0, limit, 0);
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource("day", day)
                .addValue("followed", List.copyOf(followed)).addValue("limit", limit);
        List<String> extra = new ArrayList<>();
        if (warehouseIds != null) {
            extra.add(" AND (o.shipping_warehouse_id IN (:warehouses) OR o.warehouse_id IN (:warehouses))");
            parameters.addValue("warehouses", warehouseIds);
        }
        String where = FROM + String.join("", extra);
        Long total = jdbc.queryForObject("SELECT count(*)" + where, parameters, Long.class);
        List<SalesQueues.BatchDue> rows = jdbc.query("""
                        SELECT o.id AS order_id, o.order_number, o.customer_id, o.status, b.id AS batch_id, b.seq,
                               b.window_start, b.window_end, o.shipping_warehouse_id""" + where
                        + " ORDER BY b.window_end, o.order_number, b.seq LIMIT :limit", parameters,
                (rs, n) -> new SalesQueues.BatchDue(rs.getObject("order_id", UUID.class), rs.getString("order_number"),
                        rs.getObject("customer_id", UUID.class), rs.getString("status"),
                        rs.getObject("batch_id", UUID.class), rs.getInt("seq"),
                        rs.getObject("window_start", LocalDate.class), rs.getObject("window_end", LocalDate.class),
                        rs.getObject("shipping_warehouse_id", UUID.class)));
        return PageResponse.of(rows, 0, limit, total == null ? 0 : total);
    }
}
