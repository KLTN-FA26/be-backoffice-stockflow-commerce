package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.ReturnedCartonOwnership;
import com.stockflow.inventory.internal.domain.StockPackageId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ReturnedCartonOwnership} over the columns V20261223000300 added to {@code inventory.stock_package}. Writes go
 * through the entity manager, flushed first: the carton's own status change (made through JPA in the same transaction)
 * must be in the database before the ownership that the CHECKs tie to it. Reads of the custody list are plain JDBC.
 */
@Repository
class ReturnedCartonOwnershipAdapter implements ReturnedCartonOwnership {

    private static final String CUSTODY_COLUMNS = """
            p.id, p.lpn, i.sku, (SELECT s.lot_number FROM inventory.stock_item s WHERE s.stock_package_id = p.id
                                  ORDER BY s.id LIMIT 1) AS lot_number,
            COALESCE(p.received_qty, 0) AS quantity, p.location_code, p.return_request_id, p.return_ownership,
            p.custody_held_at, p.custody_choice, p.custody_ended_at, p.custody_ended_by, p.custody_note""";

    private final NamedParameterJdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    ReturnedCartonOwnershipAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void cameBack(Collection<StockPackageId> ids, UUID returnRequestId) {
        List<UUID> wanted = ids(ids);
        if (wanted.isEmpty()) {
            return;
        }
        entityManager.flush();
        // Two statements rather than one with a null parameter: PostgreSQL cannot type a bare null in a CASE.
        if (returnRequestId == null) {
            entityManager.createNativeQuery("""
                            update inventory.stock_package
                               set return_request_id = null, return_ownership = null, custody_held_at = null,
                                   custody_choice = null, custody_ended_at = null, custody_ended_by = null,
                                   custody_note = null
                             where id in (:ids)""")
                    .setParameter("ids", wanted).executeUpdate();
            return;
        }
        entityManager.createNativeQuery("""
                        update inventory.stock_package
                           set return_request_id = :request, return_ownership = 'PENDING', custody_held_at = null,
                               custody_choice = null, custody_ended_at = null, custody_ended_by = null,
                               custody_note = null
                         where id in (:ids)""")
                .setParameter("request", returnRequestId).setParameter("ids", wanted).executeUpdate();
    }

    @Override
    public Optional<Ownership> of(StockPackageId id) {
        List<?> found = entityManager.createNativeQuery("""
                        select return_ownership from inventory.stock_package
                         where id = :id and return_ownership is not null""")
                .setParameter("id", id.value()).getResultList();
        return found.isEmpty() ? Optional.empty() : Optional.of(Ownership.valueOf((String) found.getFirst()));
    }

    @Override
    public Map<String, Ownership> ofLpns(Collection<String> lpns) {
        Map<String, Ownership> owned = new HashMap<>();
        if (lpns.isEmpty()) {
            return owned;
        }
        jdbc.query("""
                SELECT lpn, return_ownership FROM inventory.stock_package
                 WHERE lpn IN (:lpns) AND return_ownership IS NOT NULL""",
                new MapSqlParameterSource("lpns", List.copyOf(lpns)),
                rs -> {
                    owned.put(rs.getString("lpn"), Ownership.valueOf(rs.getString("return_ownership")));
                });
        return owned;
    }

    @Override
    public List<StockPackageId> waitingFor(UUID returnRequestId) {
        @SuppressWarnings("unchecked")
        List<Object> found = entityManager.createNativeQuery("""
                        select id from inventory.stock_package
                         where return_request_id = :request and return_ownership = 'PENDING' order by id""")
                .setParameter("request", returnRequestId).getResultList();
        return found.stream().map(id -> new StockPackageId((UUID) id)).toList();
    }

    @Override
    public void settle(Collection<StockPackageId> ids, Ownership decided, Instant at) {
        if (decided != Ownership.COMPANY && decided != Ownership.CUSTOMER_HELD) {
            throw new IllegalArgumentException("A refund decision makes a carton the company's or the customer's");
        }
        List<UUID> wanted = ids(ids);
        if (wanted.isEmpty()) {
            return;
        }
        entityManager.flush();
        int changed = decided == Ownership.COMPANY
                ? entityManager.createNativeQuery("""
                        update inventory.stock_package set return_ownership = 'COMPANY'
                         where id in (:ids) and return_ownership = 'PENDING'""")
                        .setParameter("ids", wanted).executeUpdate()
                : entityManager.createNativeQuery("""
                        update inventory.stock_package set return_ownership = 'CUSTOMER_HELD', custody_held_at = :at
                         where id in (:ids) and return_ownership = 'PENDING'""")
                        .setParameter("at", OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC))
                        .setParameter("ids", wanted).executeUpdate();
        if (changed != wanted.size()) {
            throw new IllegalStateException("%d of %d carton(s) no longer waited for their refund decision"
                    .formatted(wanted.size() - changed, wanted.size()));
        }
    }

    @Override
    public List<Custody> custodyOf(UUID returnRequestId) {
        entityManager.flush();
        return jdbc.query("SELECT " + CUSTODY_COLUMNS + """
                 FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
                WHERE p.return_request_id = :request AND p.return_ownership LIKE 'CUSTOMER\\_%'
                ORDER BY p.lpn""", new MapSqlParameterSource("request", returnRequestId),
                ReturnedCartonOwnershipAdapter::custody);
    }

    @Override
    public void instruct(UUID returnRequestId, String choice) {
        entityManager.flush();
        entityManager.createNativeQuery("""
                        update inventory.stock_package set custody_choice = :choice
                         where return_request_id = :request and return_ownership = 'CUSTOMER_HELD'""")
                .setParameter("choice", choice).setParameter("request", returnRequestId).executeUpdate();
    }

    @Override
    public Optional<Custody> custody(StockPackageId id) {
        entityManager.flush();
        return jdbc.query("SELECT " + CUSTODY_COLUMNS + """
                 FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
                WHERE p.id = :id AND p.return_ownership LIKE 'CUSTOMER\\_%'""",
                new MapSqlParameterSource("id", id.value()), ReturnedCartonOwnershipAdapter::custody)
                .stream().findFirst();
    }

    @Override
    public void end(StockPackageId id, Ownership ended, UUID by, Instant at, String note) {
        if (ended != Ownership.CUSTOMER_SENT_BACK && ended != Ownership.CUSTOMER_DESTROYED) {
            throw new IllegalArgumentException("Custody ends sent back or destroyed, not " + ended);
        }
        entityManager.flush();
        int changed = entityManager.createNativeQuery("""
                        update inventory.stock_package
                           set return_ownership = :ended, custody_ended_at = :at, custody_ended_by = :by,
                               custody_note = :note
                         where id = :id and return_ownership = 'CUSTOMER_HELD'""")
                .setParameter("ended", ended.name())
                .setParameter("at", OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC))
                .setParameter("by", by).setParameter("note", note).setParameter("id", id.value()).executeUpdate();
        if (changed != 1) {
            throw new IllegalStateException("Carton %s is no longer held for its customer".formatted(id.value()));
        }
    }

    @Override
    public List<Custody> held(Collection<String> warehousePrefixes, UUID returnRequestId, boolean ended, int offset,
                              int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("offset", offset)
                .addValue("limit", limit);
        return jdbc.query("SELECT " + CUSTODY_COLUMNS + """
                 FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
                WHERE %s
                ORDER BY p.custody_held_at, p.lpn
                OFFSET :offset LIMIT :limit""".formatted(where(warehousePrefixes, returnRequestId, ended, params)),
                params, ReturnedCartonOwnershipAdapter::custody);
    }

    @Override
    public long countHeld(Collection<String> warehousePrefixes, UUID returnRequestId, boolean ended) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long count = jdbc.queryForObject("SELECT count(*) FROM inventory.stock_package p WHERE "
                + where(warehousePrefixes, returnRequestId, ended, params), params, Long.class);
        return count == null ? 0 : count;
    }

    private static String where(Collection<String> warehousePrefixes, UUID returnRequestId, boolean ended,
                                MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(ended
                ? "p.return_ownership IN ('CUSTOMER_SENT_BACK', 'CUSTOMER_DESTROYED')"
                : "p.return_ownership = 'CUSTOMER_HELD'");
        if (returnRequestId != null) {
            where.append(" AND p.return_request_id = :request");
            params.addValue("request", returnRequestId);
        }
        if (warehousePrefixes != null) {
            where.append(" AND ").append(com.stockflow.common.security.WarehouseScope.locationsInSql(
                    "p.location_code", warehousePrefixes, "prefix", params));
        }
        return where.toString();
    }

    private static List<UUID> ids(Collection<StockPackageId> ids) {
        return ids.stream().map(StockPackageId::value).distinct().toList();
    }

    private static Custody custody(ResultSet rs, int row) throws SQLException {
        return new Custody(rs.getObject("id", UUID.class), rs.getString("lpn"), rs.getString("sku"),
                rs.getString("lot_number"), rs.getInt("quantity"), rs.getString("location_code"),
                rs.getObject("return_request_id", UUID.class), Ownership.valueOf(rs.getString("return_ownership")),
                instant(rs.getTimestamp("custody_held_at")), rs.getString("custody_choice"),
                instant(rs.getTimestamp("custody_ended_at")), rs.getObject("custody_ended_by", UUID.class),
                rs.getString("custody_note"));
    }

    private static Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }
}
