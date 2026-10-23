package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.CycleCount;
import com.stockflow.inventory.internal.domain.CycleCount.CountLineDraft;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.CycleCountStatus;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.entity.CycleCountJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@link CycleCountRepository}: the aggregate through JPA, the queries that are not about one aggregate in SQL.
 *
 * <p>The count number comes from {@code platform.document_sequence} (type {@code CC}), as an adjustment's does: the
 * upsert's row lock serialises two requests on one day.</p>
 */
@Repository
class CycleCountRepositoryAdapter implements CycleCountRepository {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** Seed of the slot lock (701 is the SKU's, 702 the alerts'). */
    private static final int SLOT_LOCK_SEED = 703;

    private static final Map<String, Object> LOCK_TIMEOUT =
            Map.of("jakarta.persistence.lock.timeout", 3000);

    private final CycleCountJpaRepository jpa;
    private final EntityManager entityManager;
    private final NamedParameterJdbcTemplate jdbc;

    CycleCountRepositoryAdapter(CycleCountJpaRepository jpa, EntityManager entityManager, JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.entityManager = entityManager;
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Optional<CycleCount> findById(UUID id) {
        return jpa.findByIdWithLines(id).map(CycleCountPersistenceMapper::toDomain);
    }

    /**
     * The row is locked in SQL first and the entity then refreshed under the lock: returning the persistence-context
     * copy would make the lock decorative (see {@code StockItemRepositoryAdapter.findByIdForUpdate}).
     */
    @Override
    public Optional<CycleCount> findByIdForUpdate(UUID id) {
        if (jdbc.queryForList("SELECT id FROM inventory.cycle_count WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), UUID.class).isEmpty()) {
            return Optional.empty();
        }
        return jpa.findByIdWithLines(id).map(entity -> {
            // Header only: lines do not cascade REFRESH, they were read by the join fetch just above, after the lock.
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE, LOCK_TIMEOUT);
            return CycleCountPersistenceMapper.toDomain(entity);
        });
    }

    /**
     * Insert, or copy what the aggregate moved onto the stored rows. The domain never removes a line, so nothing is
     * deleted here.
     */
    @Override
    public CycleCount save(CycleCount count) {
        Optional<CycleCountJpaEntity> stored = jpa.findByIdWithLines(count.id());
        CycleCountJpaEntity entity;
        if (stored.isPresent()) {
            entity = stored.get();
            CycleCountPersistenceMapper.apply(count, entity);
        } else {
            entity = CycleCountPersistenceMapper.toNewEntity(count);
        }
        return CycleCountPersistenceMapper.toDomain(jpa.saveAndFlush(entity));
    }

    @Override
    public String nextNumber(LocalDate day) {
        Number sequence = jdbc.queryForObject("""
                INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                VALUES ('CC', :day, 1)
                ON CONFLICT (document_type, sequence_date)
                DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                RETURNING last_value""", new MapSqlParameterSource("day", day), Number.class);
        return "CC-%s-%04d".formatted(day.format(DAY), sequence.longValue());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Takes a transaction-scoped advisory lock on the slot first (seed 703; 701 is the SKU's, 702 the alerts').
     * No constraint says one open count per slot, so without it two short reports on one bin in different picks
     * would both find none and both open one. A loose slot matches only loose lines, a carton only its package.</p>
     */
    @Override
    public Optional<CycleCount> findOpenFor(CountSlot slot) {
        lock(slot.lockKey());
        MapSqlParameterSource params = new MapSqlParameterSource();
        String match = slotMatch(slot, "l", params);
        List<UUID> ids = jdbc.queryForList("""
                SELECT c.id FROM inventory.cycle_count c JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
                 WHERE c.status IN ('PLANNED', 'IN_PROGRESS') AND %s
                 ORDER BY c.created_at, c.id LIMIT 1""".formatted(match), params, UUID.class);
        return ids.stream().findFirst().flatMap(this::findById);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The locks are taken in sorted key order, so two claims over overlapping slots cannot deadlock. The one
     * query then over-fetches by bin and SKU (for loose lines) or package, and the exact slot is matched here.</p>
     */
    @Override
    public Map<CountSlot, String> claim(Collection<CountSlot> slots) {
        if (slots.isEmpty()) {
            return Map.of();
        }
        Map<String, CountSlot> byKey = new LinkedHashMap<>();
        slots.forEach(slot -> byKey.putIfAbsent(slot.lockKey(), slot));
        new TreeSet<>(byKey.keySet()).forEach(this::lock);

        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> any = new ArrayList<>();
        Set<UUID> packages = new TreeSet<>();
        Set<String> locations = new TreeSet<>();
        Set<String> skus = new TreeSet<>();
        for (CountSlot slot : byKey.values()) {
            if (slot.isCarton()) {
                packages.add(slot.stockPackageId().value());
            } else {
                locations.add(slot.location().code());
                skus.add(slot.sku().code());
            }
        }
        if (!packages.isEmpty()) {
            any.add("l.stock_package_id IN (:packages)");
            params.addValue("packages", packages);
        }
        if (!locations.isEmpty()) {
            any.add("(l.stock_package_id IS NULL AND l.location_code IN (:locations) AND l.sku IN (:skus))");
            params.addValue("locations", locations).addValue("skus", skus);
        }
        Map<CountSlot, String> busy = new HashMap<>();
        jdbc.query("""
                SELECT c.count_number, l.location_code, l.sku, l.lot_number, l.stock_package_id
                  FROM inventory.cycle_count c JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
                 WHERE c.status IN ('PLANNED', 'IN_PROGRESS') AND (%s)
                 ORDER BY c.created_at, c.id""".formatted(String.join(" OR ", any)), params, (RowCallbackHandler) rs -> {
            CountSlot held = slotOf(rs);
            CountSlot wanted = byKey.get(held.lockKey());
            if (wanted != null) {
                busy.putIfAbsent(wanted, rs.getString("count_number"));
            }
        });
        return busy;
    }

    @Override
    public List<CountLineDraft> stockIn(String warehousePrefix, Collection<String> locationCodes,
                                        Collection<String> locationPrefixes, Collection<String> skus) {
        List<CountLineDraft> drafts = new ArrayList<>();
        String loose = """
                SELECT s.location_code, s.sku, s.lot_number, sum(s.on_hand) AS qty
                  FROM inventory.stock_item s
                 WHERE s.stock_package_id IS NULL AND %s
                 GROUP BY s.location_code, s.sku, s.lot_number
                HAVING sum(s.on_hand) > 0
                 ORDER BY s.location_code, s.sku, s.lot_number""";
        MapSqlParameterSource looseParams = new MapSqlParameterSource();
        jdbc.query(loose.formatted(scope("s", warehousePrefix, locationCodes, locationPrefixes, skus, looseParams)),
                looseParams, (RowCallbackHandler) rs -> drafts.add(new CountLineDraft(
                        CountSlot.loose(new LocationId(rs.getString("location_code")), new Sku(rs.getString("sku")),
                                rs.getString("lot_number")), rs.getInt("qty"))));
        String cartons = """
                SELECT b.stock_package_id, b.location_code, b.sku, b.lot_number, b.on_hand AS qty
                  FROM inventory.stock_package_balance b
                 WHERE b.status = 'IN_STOCK' AND %s
                 ORDER BY b.location_code, b.sku, b.stock_package_id""";
        MapSqlParameterSource cartonParams = new MapSqlParameterSource();
        jdbc.query(cartons.formatted(scope("b", warehousePrefix, locationCodes, locationPrefixes, skus, cartonParams)),
                cartonParams, (RowCallbackHandler) rs -> drafts.add(new CountLineDraft(
                        CountSlot.carton(new LocationId(rs.getString("location_code")), new Sku(rs.getString("sku")),
                                rs.getString("lot_number"),
                                new StockPackageId(rs.getObject("stock_package_id", UUID.class))),
                        rs.getInt("qty"))));
        return drafts;
    }

    /** Location and SKU criteria on {@code alias}; with no location criterion, the whole warehouse by its prefix. */
    private static String scope(String alias, String warehousePrefix, Collection<String> locationCodes,
                                Collection<String> locationPrefixes, Collection<String> skus,
                                MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        List<String> locations = new ArrayList<>();
        if (!locationCodes.isEmpty()) {
            locations.add(alias + ".location_code IN (:codes)");
            params.addValue("codes", locationCodes);
        }
        int index = 0;
        for (String prefix : locationPrefixes) {
            String name = "prefix" + index++;
            locations.add(alias + ".location_code LIKE :" + name + " ESCAPE '\\'");
            params.addValue(name, escapeLike(prefix) + "%");
        }
        if (locations.isEmpty()) {
            conditions.add(alias + ".location_code LIKE :warehousePrefix ESCAPE '\\'");
            params.addValue("warehousePrefix", escapeLike(warehousePrefix) + "-%");
        } else {
            conditions.add("(" + String.join(" OR ", locations) + ")");
        }
        if (!skus.isEmpty()) {
            conditions.add(alias + ".sku IN (:skus)");
            params.addValue("skus", skus);
        }
        return String.join(" AND ", conditions);
    }

    private static String escapeLike(String literal) {
        return literal.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code FOR SHARE} cannot sit on an aggregate query in PostgreSQL, hence the inner select: the rows are
     * locked, then summed.</p>
     */
    @Override
    public int bookQuantity(CountSlot slot) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where;
        if (slot.isCarton()) {
            where = "s.stock_package_id = :package";
            params.addValue("package", slot.stockPackageId().value());
        } else {
            where = """
                    s.stock_package_id IS NULL AND s.location_code = :location AND s.sku = :sku
                    AND COALESCE(s.lot_number, '') = COALESCE(:lot, '')""";
            params.addValue("location", slot.location().code()).addValue("sku", slot.sku().code())
                    .addValue("lot", slot.lotNumber(), Types.VARCHAR);
        }
        Number total = jdbc.queryForObject("""
                SELECT COALESCE(sum(q.on_hand), 0)
                  FROM (SELECT s.on_hand FROM inventory.stock_item s WHERE %s FOR SHARE) q""".formatted(where),
                params, Number.class);
        return total == null ? 0 : total.intValue();
    }

    @Override
    public List<CycleCount> find(CycleCountStatus status, Set<UUID> warehouses, long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("offset", offset).addValue("limit", limit);
        String where = where(status, warehouses, params);
        if (where == null) {
            return List.of();
        }
        List<UUID> ids = jdbc.queryForList("SELECT c.id FROM inventory.cycle_count c WHERE " + where
                + " ORDER BY c.created_at DESC, c.id DESC LIMIT :limit OFFSET :offset", params, UUID.class);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, CycleCountJpaEntity> byId = new HashMap<>();
        jpa.findAllWithLines(ids).forEach(entity -> byId.put(entity.getId(), entity));
        return ids.stream().map(byId::get).map(CycleCountPersistenceMapper::toDomain).toList();
    }

    @Override
    public long count(CycleCountStatus status, Set<UUID> warehouses) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(status, warehouses, params);
        if (where == null) {
            return 0;
        }
        Long count = jdbc.queryForObject("SELECT count(*) FROM inventory.cycle_count c WHERE " + where, params,
                Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public Map<UUID, List<LineAdjustment>> adjustmentsOf(Collection<UUID> lineIds) {
        if (lineIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<LineAdjustment>> byLine = new HashMap<>();
        jdbc.query("""
                SELECT a.cycle_count_line_id, a.id, a.adjustment_number, a.quantity_delta, a.status
                  FROM inventory.stock_adjustment a WHERE a.cycle_count_line_id IN (:ids)
                 ORDER BY a.created_at, a.id""", new MapSqlParameterSource("ids", lineIds), (RowCallbackHandler) rs ->
                byLine.computeIfAbsent(rs.getObject("cycle_count_line_id", UUID.class), id -> new ArrayList<>())
                        .add(new LineAdjustment(rs.getObject("id", UUID.class), rs.getString("adjustment_number"),
                                rs.getInt("quantity_delta"), rs.getString("status"))));
        return byLine;
    }

    @Override
    public Map<UUID, String> lpnsOf(Collection<StockPackageId> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> lpns = new HashMap<>();
        jdbc.query("SELECT id, lpn FROM inventory.stock_package WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids.stream().map(StockPackageId::value).toList()),
                (RowCallbackHandler) rs -> lpns.put(rs.getObject("id", UUID.class), rs.getString("lpn")));
        return lpns;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A carton line freezes the stock rows of that carton ({@code stock_package_id} equal); a loose line only
     * loose rows of its bin, SKU and lot.</p>
     *
     * <p>The freeze lasts while the SHORT_PICK count is open, and (SCRUM-146 D7) after it completes until every
     * {@code COUNT_VARIANCE} adjustment of the line is decided: between submission and approval the books still hold
     * the units a picker could not find, and promising them again would repeat the short pick.</p>
     */
    @Override
    public List<Frozen> frozen(Collection<String> skus, LocalDate today) {
        if (skus.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT s.id, s.sku, s.location_code, s.on_hand - s.reserved AS free
                  FROM inventory.stock_item s
                 WHERE s.sku IN (:skus) AND s.status = 'AVAILABLE' AND s.on_hand > s.reserved
                   AND (s.expiry_date IS NULL OR s.expiry_date >= :today)
                   AND EXISTS (SELECT 1 FROM inventory.cycle_count c
                                 JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
                                WHERE c.trigger_type = 'SHORT_PICK'
                                  AND (c.status IN ('PLANNED', 'IN_PROGRESS')
                                       OR (c.status = 'COMPLETED'
                                           AND EXISTS (SELECT 1 FROM inventory.stock_adjustment a
                                                        WHERE a.cycle_count_line_id = l.id
                                                          AND a.reason_code = 'COUNT_VARIANCE'
                                                          AND a.status = 'PENDING_APPROVAL')))
                                  AND (l.stock_package_id = s.stock_package_id
                                       OR (l.stock_package_id IS NULL AND s.stock_package_id IS NULL
                                           AND l.location_code = s.location_code AND l.sku = s.sku
                                           AND COALESCE(l.lot_number, '') = COALESCE(s.lot_number, ''))))""",
                new MapSqlParameterSource().addValue("skus", skus).addValue("today", today),
                (rs, row) -> new Frozen(rs.getObject("id", UUID.class), rs.getString("sku"),
                        rs.getString("location_code"), rs.getLong("free")));
    }

    private void lock(String key) {
        jdbc.getJdbcOperations().query("select pg_advisory_xact_lock(hashtextextended(?, " + SLOT_LOCK_SEED + "))",
                (RowCallbackHandler) rs -> { }, key);
    }

    /** The condition that a line of alias {@code line} is this slot: its package, or the loose bin, SKU and lot. */
    private static String slotMatch(CountSlot slot, String line, MapSqlParameterSource params) {
        if (slot.isCarton()) {
            params.addValue("package", slot.stockPackageId().value());
            return line + ".stock_package_id = :package";
        }
        params.addValue("location", slot.location().code()).addValue("sku", slot.sku().code())
                .addValue("lot", slot.lotNumber(), Types.VARCHAR);
        return line + ".stock_package_id IS NULL AND " + line + ".location_code = :location AND " + line
                + ".sku = :sku AND COALESCE(" + line + ".lot_number, '') = COALESCE(:lot, '')";
    }

    private static CountSlot slotOf(ResultSet rs) throws java.sql.SQLException {
        LocationId location = new LocationId(rs.getString("location_code"));
        Sku sku = new Sku(rs.getString("sku"));
        UUID pkg = rs.getObject("stock_package_id", UUID.class);
        return pkg == null ? CountSlot.loose(location, sku, rs.getString("lot_number"))
                : CountSlot.carton(location, sku, rs.getString("lot_number"), new StockPackageId(pkg));
    }

    /** {@code warehouses} null for every warehouse; empty for none, answered without a query (null). */
    private static String where(CycleCountStatus status, Set<UUID> warehouses, MapSqlParameterSource params) {
        if (warehouses != null && warehouses.isEmpty()) {
            return null;
        }
        List<String> conditions = new ArrayList<>(List.of("TRUE"));
        if (status != null) {
            conditions.add("c.status = :status");
            params.addValue("status", status.name());
        }
        if (warehouses != null) {
            conditions.add("c.warehouse_id IN (:warehouses)");
            params.addValue("warehouses", warehouses);
        }
        return String.join(" AND ", conditions);
    }
}
