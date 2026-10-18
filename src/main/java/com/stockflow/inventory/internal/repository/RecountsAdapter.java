package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Recounts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link Recounts} in SQL. The count number comes from {@code platform.document_sequence} (type {@code CC}), as an
 * adjustment's does: the upsert's row lock serialises two requests on one day.
 */
@Repository
class RecountsAdapter implements Recounts {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final NamedParameterJdbcTemplate jdbc;

    RecountsAdapter(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Optional<Recount> findOpenFor(LocationId location, Sku sku, String lotNumber) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT c.id FROM inventory.cycle_count c JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
                 WHERE c.status IN ('PLANNED', 'IN_PROGRESS') AND l.location_code = :location AND l.sku = :sku
                   AND COALESCE(l.lot_number, '') = COALESCE(:lot, '')
                 ORDER BY c.created_at, c.id LIMIT 1""",
                new MapSqlParameterSource().addValue("location", location.code()).addValue("sku", sku.code())
                        .addValue("lot", lotNumber, java.sql.Types.VARCHAR), UUID.class);
        return ids.stream().findFirst().map(id -> load(List.of(id)).get(0));
    }

    @Override
    public Recount openShortPick(UUID warehouseId, LocationId location, Sku sku, String lotNumber, int expectedQty,
                                 String note, LocalDate day) {
        Number sequence = jdbc.queryForObject("""
                INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                VALUES ('CC', :day, 1)
                ON CONFLICT (document_type, sequence_date)
                DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                RETURNING last_value""", new MapSqlParameterSource("day", day), Number.class);
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, is_blind,
                                                   planned_date, note, created_by)
                VALUES (:id, :number, :warehouse, 'SHORT_PICK', 'PLANNED', TRUE, :day, :note, 'system')""",
                new MapSqlParameterSource().addValue("id", id)
                        .addValue("number", "CC-%s-%04d".formatted(day.format(DAY), sequence.longValue()))
                        .addValue("warehouse", warehouseId).addValue("day", day).addValue("note", note));
        jdbc.update("""
                INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, lot_number, expected_qty,
                                                        created_by)
                VALUES (:id, :count, :location, :sku, :lot, :expected, 'system')""",
                new MapSqlParameterSource().addValue("id", Identifiers.newId()).addValue("count", id)
                        .addValue("location", location.code()).addValue("sku", sku.code())
                        .addValue("lot", lotNumber, java.sql.Types.VARCHAR).addValue("expected", expectedQty));
        return load(List.of(id)).get(0);
    }

    @Override
    public List<Recount> find(String status, Set<UUID> warehouses, long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("offset", offset).addValue("limit", limit);
        String where = where(status, warehouses, params);
        if (where == null) {
            return List.of();
        }
        List<UUID> ids = jdbc.queryForList("SELECT c.id FROM inventory.cycle_count c WHERE " + where
                + " ORDER BY c.created_at DESC, c.id DESC LIMIT :limit OFFSET :offset", params, UUID.class);
        return ids.isEmpty() ? List.of() : load(ids);
    }

    @Override
    public long count(String status, Set<UUID> warehouses) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(status, warehouses, params);
        if (where == null) {
            return 0;
        }
        Long count = jdbc.queryForObject("SELECT count(*) FROM inventory.cycle_count c WHERE " + where, params,
                Long.class);
        return count == null ? 0 : count;
    }

    /** {@code warehouses} null for every warehouse; empty for none, answered without a query (null). */
    private static String where(String status, Set<UUID> warehouses, MapSqlParameterSource params) {
        if (warehouses != null && warehouses.isEmpty()) {
            return null;
        }
        List<String> conditions = new ArrayList<>(List.of("TRUE"));
        if (status != null) {
            conditions.add("c.status = :status");
            params.addValue("status", status);
        }
        if (warehouses != null) {
            conditions.add("c.warehouse_id IN (:warehouses)");
            params.addValue("warehouses", warehouses);
        }
        return String.join(" AND ", conditions);
    }

    /** The recounts with these ids, in this order, each with its lines. */
    private List<Recount> load(List<UUID> ids) {
        Map<UUID, List<Line>> lines = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, cycle_count_id, location_code, sku, lot_number, expected_qty, counted_qty
                  FROM inventory.cycle_count_line WHERE cycle_count_id IN (:ids) ORDER BY location_code, sku, id""",
                new MapSqlParameterSource("ids", ids), (ResultSet rs) -> {
                    lines.computeIfAbsent(rs.getObject("cycle_count_id", UUID.class), id -> new ArrayList<>())
                            .add(new Line(rs.getObject("id", UUID.class), rs.getString("location_code"),
                                    rs.getString("sku"), rs.getString("lot_number"), rs.getInt("expected_qty"),
                                    (Integer) rs.getObject("counted_qty")));
                });
        Map<UUID, Recount> byId = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, count_number, warehouse_id, trigger_type, status, is_blind, planned_date, note, created_at
                  FROM inventory.cycle_count WHERE id IN (:ids)""",
                new MapSqlParameterSource("ids", ids), (ResultSet rs) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    byId.put(id, recount(rs, lines.getOrDefault(id, List.of())));
                });
        return ids.stream().map(byId::get).toList();
    }

    private static Recount recount(ResultSet rs, List<Line> lines) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new Recount(rs.getObject("id", UUID.class), rs.getString("count_number"),
                rs.getObject("warehouse_id", UUID.class), rs.getString("trigger_type"), rs.getString("status"),
                rs.getBoolean("is_blind"), rs.getObject("planned_date", LocalDate.class), rs.getString("note"),
                created == null ? null : created.toInstant(), lines);
    }
}
