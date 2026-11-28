package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.domain.OwnerChangeRequest;
import com.stockflow.customer.internal.domain.OwnerChangeRequestRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link OwnerChangeRequestRepository} in SQL: a request is one row, read whole and written whole. */
@Repository
class OwnerChangeRequestRepositoryAdapter implements OwnerChangeRequestRepository {

    private static final String COLUMNS = """
            id, customer_id, requested_by, requested_at, current_owner_id, reason, note, preferred_sales_user_id,
            status, decided_by, decided_at, decision_note, new_sales_owner_id, version""";

    private static final RowMapper<OwnerChangeRequest> REQUEST = OwnerChangeRequestRepositoryAdapter::request;

    private final NamedParameterJdbcTemplate jdbc;

    OwnerChangeRequestRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OwnerChangeRequest> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM customer.sales_owner_change_request WHERE id = :id",
                new MapSqlParameterSource("id", id), REQUEST).stream().findFirst();
    }

    @Override
    public Optional<OwnerChangeRequest> findForUpdate(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM customer.sales_owner_change_request WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), REQUEST).stream().findFirst();
    }

    @Override
    public Optional<OwnerChangeRequest> latestOf(UUID customerId) {
        return of(customerId, 1).stream().findFirst();
    }

    @Override
    public List<OwnerChangeRequest> of(UUID customerId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM customer.sales_owner_change_request WHERE customer_id = :customer"
                        + " ORDER BY requested_at DESC, id DESC LIMIT :limit",
                new MapSqlParameterSource("customer", customerId).addValue("limit", limit), REQUEST);
    }

    @Override
    public List<OwnerChangeRequest> list(OwnerChangeRequest.Status status, int page, int size) {
        return jdbc.query("SELECT " + COLUMNS + " FROM customer.sales_owner_change_request"
                        + " WHERE (CAST(:status AS varchar) IS NULL OR status = :status)"
                        + " ORDER BY requested_at DESC, id DESC LIMIT :size OFFSET :offset",
                new MapSqlParameterSource("status", status == null ? null : status.name()).addValue("size", size)
                        .addValue("offset", (long) page * size), REQUEST);
    }

    @Override
    public long count(OwnerChangeRequest.Status status) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM customer.sales_owner_change_request"
                        + " WHERE (CAST(:status AS varchar) IS NULL OR status = :status)",
                new MapSqlParameterSource("status", status == null ? null : status.name()), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public void insert(OwnerChangeRequest request) {
        jdbc.update("""
                INSERT INTO customer.sales_owner_change_request (id, customer_id, requested_by, requested_at,
                        current_owner_id, reason, note, preferred_sales_user_id, status, version, created_at, created_by)
                VALUES (:id, :customer, :by, :at, :owner, :reason, :note, :preferred, 'PENDING', 0, :at, :creator)""",
                new MapSqlParameterSource("id", request.id()).addValue("customer", request.customerId())
                        .addValue("by", request.requestedBy()).addValue("at", Timestamp.from(request.requestedAt()))
                        .addValue("owner", request.currentOwnerId()).addValue("reason", request.reason().name())
                        .addValue("note", request.note()).addValue("preferred", request.preferredSalesUserId())
                        .addValue("creator", String.valueOf(request.requestedBy())));
    }

    @Override
    public void update(OwnerChangeRequest request) {
        jdbc.update("""
                UPDATE customer.sales_owner_change_request
                   SET status = :status, decided_by = :decidedBy, decided_at = :decidedAt, decision_note = :note,
                       new_sales_owner_id = :newOwner, version = version + 1, last_modified_at = :decidedAt,
                       last_modified_by = :modifier
                 WHERE id = :id""",
                new MapSqlParameterSource("id", request.id()).addValue("status", request.status().name())
                        .addValue("decidedBy", request.decidedBy())
                        .addValue("decidedAt", request.decidedAt() == null ? null : Timestamp.from(request.decidedAt()))
                        .addValue("note", request.decisionNote()).addValue("newOwner", request.newSalesOwnerId())
                        .addValue("modifier", String.valueOf(request.decidedBy())));
    }

    private static OwnerChangeRequest request(ResultSet rs, int row) throws SQLException {
        return new OwnerChangeRequest(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("requested_by", UUID.class), instant(rs, "requested_at"),
                rs.getObject("current_owner_id", UUID.class),
                OwnerChangeRequest.Reason.valueOf(rs.getString("reason")), rs.getString("note"),
                rs.getObject("preferred_sales_user_id", UUID.class),
                OwnerChangeRequest.Status.valueOf(rs.getString("status")), rs.getObject("decided_by", UUID.class),
                instant(rs, "decided_at"), rs.getString("decision_note"),
                rs.getObject("new_sales_owner_id", UUID.class), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
