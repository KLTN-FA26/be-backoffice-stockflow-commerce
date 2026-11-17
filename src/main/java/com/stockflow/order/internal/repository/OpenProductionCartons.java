package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.ProductionOrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The cartons production orders still have to bring to each warehouse, by SKU (kltn-docs 06 §4.6 "Sắp về" and "Chờ
 * duyệt"; BE1): one grouped read of this module's own tables, {@code ordering.production_order} and its lines - no
 * other schema joined. A line's outstanding cartons are {@code cartons - received_cartons}.
 *
 * <ul>
 *   <li><b>incoming</b>: production orders sent to their producer and not finished - what
 *       {@code ProductionOrder.sentAndOpen()} answers true for;</li>
 *   <li><b>pending</b>: not sent yet - DRAFT and PENDING_APPROVAL.</li>
 * </ul>
 *
 * <p>Received, cancelled and closed ones bring nothing more, so a production order closed short leaves the board the
 * moment it is closed (06 edge cases).</p>
 */
@Repository
public class OpenProductionCartons {

    /** Not sent yet: the "Chờ duyệt" column. */
    static final List<ProductionOrderStatus> PENDING = List.of(ProductionOrderStatus.DRAFT,
            ProductionOrderStatus.PENDING_APPROVAL);

    /** Sent and open: the rule of {@code ProductionOrder.sentAndOpen()}, as a list of statuses. */
    static final List<ProductionOrderStatus> INCOMING = Arrays.stream(ProductionOrderStatus.values())
            .filter(status -> status.open() && !PENDING.contains(status)).toList();

    /** What one SKU is still to bring to one warehouse. */
    public record Outstanding(UUID warehouseId, String sku, int incomingCartons, int pendingCartons) {
    }

    private final JdbcTemplate jdbc;

    public OpenProductionCartons(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The transaction-scoped advisory lock {@code warehouse-capacity:<warehouseId>} (BE1; 06 §4.6 step 8, C42). Two
     * approvals sending production orders to one warehouse at once would each see its projected free room before the
     * other's cartons, both find it zero or more, and neither tell the manager it went below; taken after the sales
     * order and the production order (lock order: customer_order, production_order, this), the second reads the
     * first's once it commits. Wrapped in a sub-select because {@code pg_advisory_xact_lock} returns void.
     */
    public void lockWarehouseCapacity(UUID warehouseId) {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) AS locked",
                Integer.class, "warehouse-capacity:" + warehouseId);
    }

    /** Every warehouse of {@code warehouseIds}, every SKU with something outstanding; nothing for an empty list. */
    public List<Outstanding> outstanding(Collection<UUID> warehouseIds) {
        if (warehouseIds.isEmpty()) {
            return List.of();
        }
        String incoming = String.join(",", INCOMING.stream().map(s -> "'" + s.name() + "'").toList());
        String pending = String.join(",", PENDING.stream().map(s -> "'" + s.name() + "'").toList());
        return jdbc.query("""
                select po.receiving_warehouse_id, l.sku,
                       sum(case when po.status::text in (%1$s) then l.cartons - l.received_cartons else 0 end)
                           as incoming,
                       sum(case when po.status::text in (%2$s) then l.cartons - l.received_cartons else 0 end)
                           as pending
                  from ordering.production_order po
                  join ordering.production_order_line l on l.production_order_id = po.id
                 where po.receiving_warehouse_id = any (?)
                   and po.status::text in (%1$s, %2$s)
                 group by po.receiving_warehouse_id, l.sku
                 order by po.receiving_warehouse_id, l.sku""".formatted(incoming, pending),
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", warehouseIds.toArray())),
                (rs, n) -> new Outstanding(rs.getObject("receiving_warehouse_id", UUID.class), rs.getString("sku"),
                        rs.getInt("incoming"), rs.getInt("pending")));
    }
}
