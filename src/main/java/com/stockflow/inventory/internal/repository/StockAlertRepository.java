package com.stockflow.inventory.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.domain.StockAlert.Key;
import com.stockflow.inventory.internal.domain.StockAlert.Kind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code inventory.stock_alert}, in SQL rather than JPA: the hot operations are set-based and the identity of an
 * open episode is an expression index ({@link StockAlert.Key}) that a mapped entity would only get in the way of.
 *
 * <p><b>Concurrency.</b> The partial unique index {@code uk_stock_alert_open} decides who opens an episode:
 * {@link #insertIfAbsent} is {@code ON CONFLICT DO NOTHING}, so the drain job and the policy-change listener can
 * both try and exactly one row appears. Updates carry the version they read and fail with
 * {@code OPTIMISTIC_LOCK} when somebody else moved the row first.</p>
 */
@Repository
public class StockAlertRepository {

    private static final String COLUMNS = "id, kind, sku, warehouse_id, lot_number, expiry_date, observed_qty,"
            + " threshold_qty, reservation_impaired, status, opened_at, last_observed_at, acknowledged_by,"
            + " acknowledged_at, ack_note, resolved_at, resolution, version";

    /** The filters of the alert list; empty collections and nulls mean "any". */
    public record Criteria(Set<Kind> kinds, Set<StockAlert.Status> statuses, String sku, UUID warehouseId,
                           Set<UUID> allowedWarehouses) {
        /** {@code allowedWarehouses} null = not limited; otherwise only alerts of these warehouses (so no SKU-wide ones). */
        public Criteria {
            kinds = kinds == null ? Set.of() : kinds;
            statuses = statuses == null ? Set.of() : statuses;
        }
    }

    private final JdbcTemplate jdbc;

    public StockAlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ reads

    public Optional<StockAlert> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.stock_alert WHERE id = ?", (rs, n) -> map(rs), id)
                .stream().findFirst();
    }

    public Optional<StockAlert> findByIdForUpdate(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.stock_alert WHERE id = ? FOR UPDATE", (rs, n) -> map(rs), id)
                .stream().findFirst();
    }

    /**
     * The open (OPEN or ACKNOWLEDGED) episode of this condition, if there is one. Locked: whoever reads an open
     * alert here is about to change it, and a person acknowledging it meanwhile waits instead of losing the race.
     */
    public Optional<StockAlert> findOpen(Key key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.stock_alert WHERE status <> 'RESOLVED' AND kind = ?"
                        + " AND sku = ? AND COALESCE(warehouse_id, '00000000-0000-0000-0000-000000000000'::uuid) = ?"
                        + " AND COALESCE(lot_number, '') = ? AND COALESCE(expiry_date, DATE '1900-01-01') = ? FOR UPDATE",
                (rs, n) -> map(rs), key.kind().name(), key.sku(),
                key.warehouseId() == null ? new UUID(0, 0) : key.warehouseId(),
                key.lotNumber() == null ? "" : key.lotNumber(),
                key.expiryDate() == null ? LocalDate.of(1900, 1, 1) : key.expiryDate()).stream().findFirst();
    }

    /** Open REORDER and SAFETY alerts of these SKUs, locked for the evaluation that follows. */
    public List<StockAlert> findOpenStockLevel(Collection<String> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.stock_alert WHERE status <> 'RESOLVED'"
                        + " AND kind IN ('REORDER', 'SAFETY') AND sku = ANY (?) FOR UPDATE",
                ps -> ps.setArray(1, varchars(ps.getConnection(), skus)), (rs, n) -> map(rs));
    }

    /** Every open alert of these kinds, locked. Expiry alerts are few: one row per lot of a warehouse that is near or past expiry. */
    public List<StockAlert> findOpenOfKinds(Kind... kinds) {
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.stock_alert WHERE status <> 'RESOLVED'"
                        + " AND kind = ANY (?) FOR UPDATE",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("varchar",
                        java.util.Arrays.stream(kinds).map(Kind::name).toArray())), (rs, n) -> map(rs));
    }

    /** SKUs that have an open REORDER or SAFETY alert: the reconcile re-checks them even when they are no longer low. */
    public List<String> skusWithOpenStockLevelAlerts() {
        return jdbc.queryForList("SELECT DISTINCT sku FROM inventory.stock_alert WHERE status <> 'RESOLVED'"
                + " AND kind IN ('REORDER', 'SAFETY') ORDER BY sku", String.class);
    }

    /**
     * Serialises evaluations of one SKU (transaction-scoped). A namespace of its own (702): the stock-write lock
     * (701, {@code InventoryPolicyRepository.lock}) is never taken here, so evaluating cannot hold up a receipt or a move.
     * Callers take these in SKU order.
     */
    public void lockEvaluation(String sku) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 702))", (org.springframework.jdbc.core.RowCallbackHandler) rs -> { }, sku);
    }

    /** When the latest resolved episode of this condition ended; the start of the re-touch window. */
    public Optional<Instant> lastResolvedAt(Key key) {
        Timestamp latest = jdbc.queryForObject("SELECT max(resolved_at) FROM inventory.stock_alert WHERE status = 'RESOLVED'"
                        + " AND kind = ? AND sku = ? AND COALESCE(warehouse_id, '00000000-0000-0000-0000-000000000000'::uuid) = ?"
                        + " AND COALESCE(lot_number, '') = ? AND COALESCE(expiry_date, DATE '1900-01-01') = ?",
                Timestamp.class, key.kind().name(), key.sku(),
                key.warehouseId() == null ? new UUID(0, 0) : key.warehouseId(),
                key.lotNumber() == null ? "" : key.lotNumber(),
                key.expiryDate() == null ? LocalDate.of(1900, 1, 1) : key.expiryDate());
        return Optional.ofNullable(latest).map(Timestamp::toInstant);
    }

    /** One page of the list. {@code orderBy} is a whitelisted SQL fragment built by the service, never request text. */
    public List<StockAlert> search(Criteria criteria, String orderBy, int limit, long offset) {
        List<Object> args = new ArrayList<>();
        String where = where(criteria, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.stock_alert" + where + " ORDER BY " + orderBy
                + " LIMIT ? OFFSET ?", (rs, n) -> map(rs), args.toArray());
    }

    public long count(Criteria criteria) {
        List<Object> args = new ArrayList<>();
        Long total = jdbc.queryForObject("SELECT count(*) FROM inventory.stock_alert" + where(criteria, args), Long.class,
                args.toArray());
        return total == null ? 0 : total;
    }

    /** Open (not resolved) alerts per kind, inside the caller's warehouses. */
    public Map<Kind, Long> openCountsByKind(Set<UUID> allowedWarehouses) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT kind, count(*) FROM inventory.stock_alert WHERE status <> 'RESOLVED'");
        if (allowedWarehouses != null) {
            sql.append(" AND warehouse_id = ANY (?)");
        }
        sql.append(" GROUP BY kind");
        Map<Kind, Long> counts = new EnumMap<>(Kind.class);
        jdbc.query(sql.toString(), ps -> {
            if (allowedWarehouses != null) {
                ps.setArray(1, ps.getConnection().createArrayOf("uuid", allowedWarehouses.toArray()));
            }
        }, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                counts.put(Kind.valueOf(rs.getString(1)), rs.getLong(2)));
        return counts;
    }

    private static String where(Criteria c, List<Object> args) {
        List<String> parts = new ArrayList<>();
        if (!c.kinds().isEmpty()) {
            parts.add("kind IN (" + placeholders(c.kinds().size()) + ")");
            c.kinds().forEach(k -> args.add(k.name()));
        }
        if (!c.statuses().isEmpty()) {
            parts.add("status IN (" + placeholders(c.statuses().size()) + ")");
            c.statuses().forEach(s -> args.add(s.name()));
        }
        if (c.sku() != null && !c.sku().isBlank()) {
            parts.add("sku = ?");
            args.add(c.sku().trim().toUpperCase());
        }
        if (c.warehouseId() != null) {
            parts.add("warehouse_id = ?");
            args.add(c.warehouseId());
        }
        if (c.allowedWarehouses() != null) {
            if (c.allowedWarehouses().isEmpty()) {
                parts.add("FALSE");
            } else {
                parts.add("warehouse_id IN (" + placeholders(c.allowedWarehouses().size()) + ")");
                args.addAll(c.allowedWarehouses());
            }
        }
        return parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts);
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    // ------------------------------------------------------------------ writes

    /**
     * Inserts a new episode unless the same condition is already open.
     *
     * @return whether the row was created; false means another writer opened the condition first
     */
    public boolean insertIfAbsent(StockAlert a) {
        return jdbc.update("""
                INSERT INTO inventory.stock_alert (id, kind, sku, warehouse_id, lot_number, expiry_date, observed_qty,
                    threshold_qty, reservation_impaired, status, opened_at, last_observed_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (kind, sku, COALESCE(warehouse_id, '00000000-0000-0000-0000-000000000000'::uuid),
                             COALESCE(lot_number, ''), COALESCE(expiry_date, DATE '1900-01-01'))
                WHERE status <> 'RESOLVED' DO NOTHING""",
                a.id(), a.kind().name(), a.sku(), a.warehouseId(), a.lotNumber(), a.expiryDate(), a.observedQty(),
                a.thresholdQty(), a.reservationImpaired(), a.status().name(), Timestamp.from(a.openedAt()),
                Timestamp.from(a.lastObservedAt())) == 1;
    }

    /**
     * Writes the changes of {@code a} (numbers, status, acknowledgement, resolution).
     *
     * @throws BusinessException {@code OPTIMISTIC_LOCK} when the row changed since it was read
     */
    public void update(StockAlert a) {
        int updated = jdbc.update("""
                UPDATE inventory.stock_alert SET observed_qty = ?, threshold_qty = ?, reservation_impaired = ?,
                    status = ?, last_observed_at = ?, acknowledged_by = ?, acknowledged_at = ?, ack_note = ?,
                    resolved_at = ?, resolution = ?, version = version + 1
                WHERE id = ? AND version = ?""",
                a.observedQty(), a.thresholdQty(), a.reservationImpaired(), a.status().name(),
                Timestamp.from(a.lastObservedAt()), a.acknowledgedBy(), ts(a.acknowledgedAt()), a.ackNote(),
                ts(a.resolvedAt()), a.resolution() == null ? null : a.resolution().name(), a.id(), a.version());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "Stock alert " + a.id() + " changed meanwhile");
        }
    }

    // ------------------------------------------------------------------ mapping

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Array varchars(java.sql.Connection connection, Collection<String> values) throws SQLException {
        return connection.createArrayOf("varchar", values.toArray());
    }

    private static StockAlert map(ResultSet rs) throws SQLException {
        Kind kind = Kind.valueOf(rs.getString("kind"));
        Key key = new Key(kind, rs.getString("sku"), rs.getObject("warehouse_id", UUID.class), rs.getString("lot_number"),
                rs.getObject("expiry_date", LocalDate.class));
        Timestamp acknowledgedAt = rs.getTimestamp("acknowledged_at");
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        String resolution = rs.getString("resolution");
        return new StockAlert(rs.getObject("id", UUID.class), key, rs.getInt("observed_qty"),
                rs.getObject("threshold_qty", Integer.class), rs.getBoolean("reservation_impaired"),
                StockAlert.Status.valueOf(rs.getString("status")), rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("last_observed_at").toInstant(), rs.getObject("acknowledged_by", UUID.class),
                acknowledgedAt == null ? null : acknowledgedAt.toInstant(), rs.getString("ack_note"),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                resolution == null ? null : StockAlert.Resolution.valueOf(resolution), rs.getLong("version"));
    }
}
