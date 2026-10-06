package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.warehouse.internal.entity.WarehouseJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

/** Spring Data repository for {@link WarehouseJpaEntity}. */
interface WarehouseJpaRepository extends BaseJpaRepository<WarehouseJpaEntity> {

    boolean existsByPrefix(String prefix);

    /**
     * The right-most and bottom-most edge of everything on the warehouse's map, in one query: every
     * shelf and area still on the layout (not {@code INACTIVE}, issue #18 D3) and every boundary,
     * which has no status.
     *
     * <p>A shelf or area turned a quarter turn has its sides swapped - the same rule as
     * {@code Footprint.effectiveWidth}. Native SQL because JPQL has no {@code GREATEST} and no
     * {@code UNION ALL}; three JPQL queries would answer the same in three round trips.</p>
     */
    @Query(nativeQuery = true, value = """
            SELECT max(e.right_edge) AS "rightEdge", max(e.bottom_edge) AS "bottomEdge"
            FROM (
                SELECT s.x + CASE WHEN s.rotation IN (90, 270) THEN s.length ELSE s.width END,
                       s.y + CASE WHEN s.rotation IN (90, 270) THEN s.width ELSE s.length END
                FROM warehouse.shelf s
                WHERE s.warehouse_id = :warehouseId AND s.status <> 'INACTIVE'
                UNION ALL
                SELECT a.x + CASE WHEN a.rotation IN (90, 270) THEN a.length ELSE a.width END,
                       a.y + CASE WHEN a.rotation IN (90, 270) THEN a.width ELSE a.length END
                FROM warehouse.area a
                WHERE a.warehouse_id = :warehouseId AND a.status <> 'INACTIVE'
                UNION ALL
                SELECT greatest(b.start_x, b.end_x), greatest(b.start_y, b.end_y)
                FROM warehouse.boundary b
                WHERE b.warehouse_id = :warehouseId
            ) AS e(right_edge, bottom_edge)
            """)
    ExtentRow findOccupiedExtent(@Param("warehouseId") UUID warehouseId);

    /** Both {@code null} when nothing is on the map. */
    interface ExtentRow {
        BigDecimal getRightEdge();

        BigDecimal getBottomEdge();
    }
}
