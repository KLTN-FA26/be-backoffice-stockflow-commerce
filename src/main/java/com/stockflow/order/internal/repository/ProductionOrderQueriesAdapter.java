package com.stockflow.order.internal.repository;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.order.api.ProductionOrderQueries;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.WarehouseDirectory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link ProductionOrderQueries} over {@code ordering.production_order}: one statement for a page of production orders
 * with their sales order number, batch number, producer and latest print sample round, one for their lines, and the
 * warehouse codes from {@link WarehouseDirectory} (another module's table: no cross-schema join).
 *
 * <p><b>Why not the screen's own list.</b> {@code ProductionOrderService.list} filters by one status and by ids; the
 * questions this answers ask for every production order still to bring goods, by the order number people read, and by
 * part of a code. The warehouse rule is the screen's, applied the same way: the ids of the caller's warehouses from
 * {@link WarehouseScope#restriction()}, matched against the receiving warehouse
 * ({@code ProductionOrderSearch.search}'s {@code warehouses}). A caller limited to warehouses but assigned none sees
 * nothing, as on the screen.</p>
 *
 * <p>Every value a caller gives is bound, never concatenated; the only text built here is the fixed predicates.</p>
 */
@Component
class ProductionOrderQueriesAdapter implements ProductionOrderQueries {

    private static final String SELECT = """
            SELECT p.id, p.code, p.status, p.sales_order_id, o.order_number, o.customer_id, b.seq AS batch_seq,
                   b.window_start AS batch_window_start, b.window_end AS batch_window_end,
                   pr.name AS producer_name, pr.standard_lead_days, p.receiving_warehouse_id, p.need_by_date,
                   p.expected_completion_date, p.sent_at, p.production_started_at, p.sample_approval_required,
                   (SELECT count(*) FROM ordering.production_order_sample_round c
                     WHERE c.production_order_id = p.id) AS sample_rounds,
                   s.round_no AS sample_round_no, s.result AS sample_result, s.opened_at AS sample_opened_at,
                   s.answered_at AS sample_answered_at
              FROM ordering.production_order p
              JOIN ordering.customer_order o ON o.id = p.sales_order_id
              JOIN ordering.producer pr ON pr.id = p.producer_id
              LEFT JOIN ordering.delivery_batch b ON b.id = p.delivery_batch_id
              LEFT JOIN LATERAL (SELECT r.round_no, r.result, r.opened_at, r.answered_at
                                   FROM ordering.production_order_sample_round r
                                  WHERE r.production_order_id = p.id
                                  ORDER BY r.round_no DESC
                                  LIMIT 1) s ON TRUE""";

    private static final String COUNT = """
            SELECT count(*)
              FROM ordering.production_order p
              JOIN ordering.customer_order o ON o.id = p.sales_order_id""";

    private final NamedParameterJdbcTemplate jdbc;
    private final WarehouseDirectory warehouses;

    ProductionOrderQueriesAdapter(NamedParameterJdbcTemplate jdbc, WarehouseDirectory warehouses) {
        // The api's vocabulary is a copy of the domain's, so another module can name a status without importing it. A
        // status added to one and not the other would be refused or shown wrongly; it stops the start instead.
        if (!Set.copyOf(STATUSES).equals(Arrays.stream(ProductionOrderStatus.values()).map(Enum::name)
                .collect(Collectors.toSet()))) {
            throw new IllegalStateException("ProductionOrderQueries.STATUSES no longer matches ProductionOrderStatus");
        }
        if (!Set.copyOf(OPEN_STATUSES).equals(Arrays.stream(ProductionOrderStatus.values())
                .filter(ProductionOrderStatus::open).map(Enum::name).collect(Collectors.toSet()))) {
            throw new IllegalStateException("ProductionOrderQueries.OPEN_STATUSES no longer matches "
                    + "ProductionOrderStatus.open");
        }
        // The follow-up list reads the delay alerts' rules on today's dates: their statuses must stay the same.
        if (!Set.copyOf(WITH_PRODUCER_STATUSES).equals(com.stockflow.order.internal.domain.DelayAlertEvaluator
                .LATE_STATUSES.stream().map(Enum::name).collect(Collectors.toSet()))
                || !Set.copyOf(AT_RISK_STATUSES).equals(com.stockflow.order.internal.domain.DelayAlertEvaluator
                .AT_RISK_STATUSES.stream().map(Enum::name).collect(Collectors.toSet()))) {
            throw new IllegalStateException("The follow-up statuses no longer match DelayAlertEvaluator's");
        }
        this.jdbc = jdbc;
        this.warehouses = warehouses;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductionOrderRow> productionOrders(Collection<String> statuses, String salesOrderNumber,
                                                             String code, int limit) {
        if (limit < 1 || limit > MAX_ORDERS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_ORDERS + ", not " + limit);
        }
        List<String> wanted = statuses == null ? List.of() : statuses.stream().map(ProductionOrderQueriesAdapter::status)
                .distinct().toList();
        Optional<Set<UUID>> allowed = WarehouseScope.restriction().map(AssignedWarehouses::ids);
        if (allowed.isPresent() && allowed.get().isEmpty()) {
            return PageResponse.of(List.of(), 0, limit, 0);
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();
        if (!wanted.isEmpty()) {
            predicates.add("p.status IN (:statuses)");
            parameters.addValue("statuses", wanted);
        }
        if (salesOrderNumber != null && !salesOrderNumber.isBlank()) {
            predicates.add("upper(o.order_number) = :orderNumber");
            parameters.addValue("orderNumber", salesOrderNumber.strip().toUpperCase(Locale.ROOT));
        }
        if (code != null && !code.isBlank()) {
            // strpos, not LIKE: a '%' or '_' typed into the code is text, never a wildcard.
            predicates.add("strpos(upper(p.code), :code) > 0");
            parameters.addValue("code", code.strip().toUpperCase(Locale.ROOT));
        }
        allowed.ifPresent(ids -> {
            predicates.add("p.receiving_warehouse_id IN (:warehouses)");
            parameters.addValue("warehouses", ids);
        });
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        Long total = jdbc.queryForObject(COUNT + where, parameters, Long.class);
        parameters.addValue("limit", limit);
        List<ProductionOrderRow> rows = rows(SELECT + where + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit",
                parameters);
        return PageResponse.of(rows, 0, limit, total == null ? 0 : total);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionOrderRow> ofSalesOrder(UUID salesOrderId) {
        if (salesOrderId == null) {
            return List.of();
        }
        Optional<Set<UUID>> allowed = WarehouseScope.restriction().map(AssignedWarehouses::ids);
        if (allowed.isPresent() && allowed.get().isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource("salesOrder", salesOrderId)
                .addValue("limit", MAX_ORDERS);
        String where = " WHERE p.sales_order_id = :salesOrder";
        if (allowed.isPresent()) {
            where += " AND p.receiving_warehouse_id IN (:warehouses)";
            parameters.addValue("warehouses", allowed.get());
        }
        return rows(SELECT + where + " ORDER BY p.created_at, p.id LIMIT :limit", parameters);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<FollowUp> followUps(LocalDate today, Integer dueWithinDays, UUID warehouseId, int limit) {
        if (limit < 1 || limit > MAX_ORDERS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_ORDERS + ", not " + limit);
        }
        if (dueWithinDays != null && dueWithinDays < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "dueWithinDays must not be negative");
        }
        Optional<Set<UUID>> allowed = WarehouseScope.restriction().map(AssignedWarehouses::ids);
        if (allowed.isPresent() && (allowed.get().isEmpty()
                || (warehouseId != null && !allowed.get().contains(warehouseId)))) {
            return PageResponse.of(List.of(), 0, limit, 0);
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource("withProducer", WITH_PRODUCER_STATUSES)
                .addValue("atRisk", AT_RISK_STATUSES).addValue("today", today);
        List<String> predicates = new ArrayList<>(List.of("p.status IN (:withProducer)"));
        String due = "";
        if (dueWithinDays != null) {
            due = " OR COALESCE(p.expected_completion_date, p.need_by_date) <= :dueBy";
            parameters.addValue("dueBy", today.plusDays(dueWithinDays));
        }
        predicates.add("(p.need_by_date < :today OR p.expected_completion_date < :today OR (" + AT_RISK + ")" + due
                + ")");
        if (warehouseId != null) {
            predicates.add("p.receiving_warehouse_id = :warehouse");
            parameters.addValue("warehouse", warehouseId);
        }
        allowed.ifPresent(ids -> {
            predicates.add("p.receiving_warehouse_id IN (:warehouses)");
            parameters.addValue("warehouses", ids);
        });
        String where = " WHERE " + String.join(" AND ", predicates);
        Long total = jdbc.queryForObject(COUNT + where, parameters, Long.class);
        parameters.addValue("limit", limit);
        String urgency = " ORDER BY CASE WHEN p.need_by_date < :today THEN 0 WHEN p.expected_completion_date < :today "
                + "THEN 1 WHEN " + AT_RISK + " THEN 2 ELSE 3 END, p.need_by_date, p.code, p.id LIMIT :limit";
        List<FollowUp> rows = followUpRows(SELECT + where + urgency, parameters, today, dueWithinDays);
        return PageResponse.of(rows, 0, limit, total == null ? 0 : total);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FollowUp> followUp(UUID productionOrderId, LocalDate today) {
        if (productionOrderId == null) {
            return Optional.empty();
        }
        Optional<Set<UUID>> allowed = WarehouseScope.restriction().map(AssignedWarehouses::ids);
        if (allowed.isPresent() && allowed.get().isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource("id", productionOrderId);
        String where = " WHERE p.id = :id";
        if (allowed.isPresent()) {
            where += " AND p.receiving_warehouse_id IN (:warehouses)";
            parameters.addValue("warehouses", allowed.get());
        }
        return followUpRows(SELECT + where, parameters, today, null).stream().findFirst();
    }

    /** At risk, in SQL: the producer's day is after the need-by day, for an order the producer accepted. */
    private static final String AT_RISK = "p.status IN (:atRisk) AND p.expected_completion_date > p.need_by_date";

    /** {@code DelayAlertEvaluator.AT_RISK_STATUSES}, by name. */
    static final List<String> AT_RISK_STATUSES = List.of("ACCEPTED", "SAMPLE_PENDING", "IN_PRODUCTION",
            "PARTIALLY_RECEIVED");

    /**
     * Why a production order is to be chased on {@code today}, the most urgent reason first - the same order as the
     * list's, so a row's reason is the one it was sorted by.
     */
    static FollowUpReason reasonOf(String status, LocalDate needBy, LocalDate expected, LocalDate today,
                                   Integer dueWithinDays) {
        if (!WITH_PRODUCER_STATUSES.contains(status)) {
            return FollowUpReason.NONE;
        }
        if (needBy != null && needBy.isBefore(today)) {
            return FollowUpReason.LATE;
        }
        if (expected != null && expected.isBefore(today)) {
            return FollowUpReason.PRODUCER_OVERDUE;
        }
        if (AT_RISK_STATUSES.contains(status) && expected != null && needBy != null && expected.isAfter(needBy)) {
            return FollowUpReason.AT_RISK;
        }
        LocalDate due = expected != null ? expected : needBy;
        if (dueWithinDays != null && due != null && !due.isAfter(today.plusDays(dueWithinDays))) {
            return FollowUpReason.DUE_SOON;
        }
        return FollowUpReason.NONE;
    }

    /** Days past the day a LATE or PRODUCER_OVERDUE reason counts from; null for any other reason. */
    static Integer daysLate(FollowUpReason reason, LocalDate needBy, LocalDate expected, LocalDate today) {
        return switch (reason) {
            case LATE -> (int) java.time.temporal.ChronoUnit.DAYS.between(needBy, today);
            case PRODUCER_OVERDUE -> (int) java.time.temporal.ChronoUnit.DAYS.between(expected, today);
            default -> null;
        };
    }

    private List<FollowUp> followUpRows(String sql, MapSqlParameterSource parameters, LocalDate today,
                                        Integer dueWithinDays) {
        return rowsWithHeads(sql, parameters).stream().map(read -> {
            Head head = read.head();
            FollowUpReason reason = reasonOf(head.status(), head.needByDate(), head.expectedCompletionDate(), today,
                    dueWithinDays);
            return new FollowUp(read.row(), head.customerId(), head.windowStart(), head.windowEnd(), reason,
                    daysLate(reason, head.needByDate(), head.expectedCompletionDate(), today));
        }).toList();
    }

    private static String status(String name) {
        if (name == null || !STATUSES.contains(name)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A production order status is one of " + STATUSES + ", not " + name);
        }
        return name;
    }

    /** The production orders {@code sql} selects, with their lines and warehouse codes. */
    private List<ProductionOrderRow> rows(String sql, MapSqlParameterSource parameters) {
        return rowsWithHeads(sql, parameters).stream().map(Read::row).toList();
    }

    /** One production order read: the row and the first statement's columns it was built from. */
    private record Read(Head head, ProductionOrderRow row) {
    }

    private List<Read> rowsWithHeads(String sql, MapSqlParameterSource parameters) {
        List<Head> heads = jdbc.query(sql, parameters, (rs, n) -> Head.of(rs));
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Line>> lines = new HashMap<>();
        jdbc.query("""
                SELECT production_order_id, sku, cartons, units_per_carton, received_cartons
                  FROM ordering.production_order_line
                 WHERE production_order_id IN (:ids)
                 ORDER BY production_order_id, sku, id""",
                new MapSqlParameterSource("ids", heads.stream().map(Head::id).toList()),
                (ResultSet rs) -> {
                    lines.computeIfAbsent(rs.getObject("production_order_id", UUID.class), id -> new ArrayList<>())
                            .add(new Line(rs.getString("sku"), rs.getInt("cartons"), rs.getInt("units_per_carton"),
                                    rs.getInt("received_cartons")));
                });
        // A handful of warehouses at most: each code is asked for once per call.
        Map<UUID, String> codes = new LinkedHashMap<>();
        heads.stream().map(Head::warehouseId).distinct()
                .forEach(id -> codes.put(id, warehouses.codeOf(id).orElse(null)));
        return heads.stream().map(head -> new Read(head, head.row(codes.get(head.warehouseId()),
                lines.getOrDefault(head.id(), List.of())))).toList();
    }

    /** One production order as the first statement reads it, before its lines and warehouse code are added. */
    private record Head(UUID id, String code, String status, UUID salesOrderId, String salesOrderNumber,
                        Integer batchSeq, String producerName, int producerLeadDays, UUID warehouseId,
                        LocalDate needByDate, LocalDate expectedCompletionDate, Instant sentAt,
                        Instant productionStartedAt, boolean sampleApprovalRequired, int sampleRounds,
                        SampleRound latest, UUID customerId, LocalDate windowStart, LocalDate windowEnd) {

        static Head of(ResultSet rs) throws SQLException {
            Integer roundNo = rs.getObject("sample_round_no", Integer.class);
            return new Head(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("status"),
                    rs.getObject("sales_order_id", UUID.class), rs.getString("order_number"),
                    rs.getObject("batch_seq", Integer.class), rs.getString("producer_name"),
                    rs.getInt("standard_lead_days"), rs.getObject("receiving_warehouse_id", UUID.class),
                    rs.getObject("need_by_date", LocalDate.class),
                    rs.getObject("expected_completion_date", LocalDate.class), instant(rs.getTimestamp("sent_at")),
                    instant(rs.getTimestamp("production_started_at")), rs.getBoolean("sample_approval_required"),
                    rs.getInt("sample_rounds"),
                    roundNo == null ? null : new SampleRound(roundNo, rs.getString("sample_result"),
                            instant(rs.getTimestamp("sample_opened_at")),
                            instant(rs.getTimestamp("sample_answered_at"))),
                    rs.getObject("customer_id", UUID.class), rs.getObject("batch_window_start", LocalDate.class),
                    rs.getObject("batch_window_end", LocalDate.class));
        }

        ProductionOrderRow row(String warehouseCode, List<Line> lines) {
            return new ProductionOrderRow(id, code, status, salesOrderId, salesOrderNumber, batchSeq, producerName,
                    producerLeadDays, warehouseCode, needByDate, expectedCompletionDate, sentAt, productionStartedAt,
                    sampleApprovalRequired, sampleRounds, latest, lines);
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
