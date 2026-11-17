package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.ProductionOrderSlip;
import com.stockflow.order.internal.domain.ProductionOrderSlipRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code ordering.production_order_slip} by plain SQL: a row written once and never changed (the table's trigger refuses
 * both), so there is no entity state to manage, no version and nothing for Hibernate to flush late. The insert runs at
 * once, in the caller's transaction; the table checks at commit that the production order was sent.
 */
@Repository
class ProductionOrderSlipRepositoryAdapter implements ProductionOrderSlipRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    ProductionOrderSlipRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public void record(ProductionOrderSlip slip) {
        jdbc.update("""
                INSERT INTO ordering.production_order_slip (production_order_id, storage_key, sha256, size_bytes,
                                                            created_at)
                VALUES (?, ?, ?, ?, ?)""", slip.productionOrderId(), slip.storageKey(), slip.sha256(),
                slip.sizeBytes(), Timestamp.from(slip.createdAt()));
    }

    @Override
    public Optional<ProductionOrderSlip> find(UUID productionOrderId) {
        return jdbc.query("""
                SELECT production_order_id, storage_key, sha256, size_bytes, created_at
                  FROM ordering.production_order_slip WHERE production_order_id = ?""",
                (row, index) -> new ProductionOrderSlip(row.getObject(1, UUID.class), row.getString(2),
                        row.getString(3), row.getLong(4), row.getTimestamp(5).toInstant()),
                productionOrderId).stream().findFirst();
    }

    @Override
    public Set<String> referencedKeys(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(named.queryForList(
                "SELECT storage_key FROM ordering.production_order_slip WHERE storage_key IN (:keys)",
                Map.of("keys", keys), String.class));
    }
}
