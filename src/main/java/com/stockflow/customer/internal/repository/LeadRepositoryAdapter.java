package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.domain.Lead;
import com.stockflow.customer.internal.domain.LeadChange;
import com.stockflow.customer.internal.domain.LeadRepository;
import com.stockflow.customer.internal.domain.LeadStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link LeadRepository} in SQL, like {@link OpenLeadsAdapter} on the same table: a lead is a handful of columns and a
 * status, read whole and written whole, with its history row in the same transaction. Two people acting on one lead
 * meet at {@link #findForUpdate}'s row lock; the version still moves on every write for the screens that send it back.
 */
@Repository
class LeadRepositoryAdapter implements LeadRepository {

    private static final String COLUMNS = """
            id, company_name, tax_code, contact_name, contact_title, phone, email, province, interests, monthly_volume,
            note, submitted_at, status, assigned_to, assigned_by, assigned_at, contacted_at, contact_note,
            converted_customer_id, converted_at, disqualified_reason, disqualified_by, disqualified_at, version""";

    private static final RowMapper<Lead> LEAD = LeadRepositoryAdapter::lead;

    private final NamedParameterJdbcTemplate jdbc;

    LeadRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Lead> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM customer.lead WHERE id = :id",
                new MapSqlParameterSource("id", id), LEAD).stream().findFirst();
    }

    @Override
    public Optional<Lead> findForUpdate(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM customer.lead WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), LEAD).stream().findFirst();
    }

    @Override
    public void insert(Lead lead, LeadChange change) {
        Lead.Form form = lead.form();
        jdbc.update("""
                INSERT INTO customer.lead (id, company_name, tax_code, contact_name, contact_title, phone, email,
                                           province, interests, monthly_volume, note, consent_at, status, submitted_at,
                                           version, created_at, created_by)
                VALUES (:id, :company, :taxCode, :contact, :title, :phone, :email, :province,
                        CAST(string_to_array(:interests, ',') AS varchar(32)[]), :volume, :note, :at, 'NEW', :at, 0,
                        :at, 'public-form')""",
                new MapSqlParameterSource("id", lead.id()).addValue("company", form.companyName())
                        .addValue("taxCode", form.taxCode()).addValue("contact", form.contactName())
                        .addValue("title", form.contactTitle()).addValue("phone", form.phone())
                        .addValue("email", form.email()).addValue("province", form.province())
                        .addValue("interests", form.interests().stream().map(Enum::name)
                                .collect(Collectors.joining(",")))
                        .addValue("volume", form.monthlyVolume()).addValue("note", form.note())
                        .addValue("at", Timestamp.from(lead.submittedAt())));
        record(change);
    }

    @Override
    public void update(Lead lead, LeadChange change) {
        jdbc.update("""
                UPDATE customer.lead
                   SET status = :status, assigned_to = :assignedTo, assigned_by = :assignedBy, assigned_at = :assignedAt,
                       contacted_at = :contactedAt, contact_note = :contactNote,
                       converted_customer_id = :customer, converted_at = :convertedAt,
                       disqualified_reason = :disqualifiedReason, disqualified_by = :disqualifiedBy,
                       disqualified_at = :disqualifiedAt, version = version + 1,
                       last_modified_at = :at, last_modified_by = :by
                 WHERE id = :id""",
                new MapSqlParameterSource("id", lead.id()).addValue("status", lead.status().name())
                        .addValue("assignedTo", lead.assignedTo()).addValue("assignedBy", lead.assignedBy())
                        .addValue("assignedAt", timestamp(lead.assignedAt()))
                        .addValue("contactedAt", timestamp(lead.contactedAt()))
                        .addValue("contactNote", lead.contactNote())
                        .addValue("customer", lead.convertedCustomerId())
                        .addValue("convertedAt", timestamp(lead.convertedAt()))
                        .addValue("disqualifiedReason", lead.disqualifiedReason())
                        .addValue("disqualifiedBy", lead.disqualifiedBy())
                        .addValue("disqualifiedAt", timestamp(lead.disqualifiedAt()))
                        .addValue("at", Timestamp.from(change.changedAt()))
                        .addValue("by", change.changedBy() == null ? "system" : change.changedBy().toString()));
        record(change);
    }

    private void record(LeadChange change) {
        jdbc.update("""
                INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, from_user_id, to_user_id, note,
                                                   changed_by, changed_at, version, created_at, created_by)
                VALUES (:id, :lead, :from, :to, :fromUser, :toUser, :note, :by, :at, 0, :at, :creator)""",
                new MapSqlParameterSource("id", change.id()).addValue("lead", change.leadId())
                        .addValue("from", change.fromStatus() == null ? null : change.fromStatus().name())
                        .addValue("to", change.toStatus().name()).addValue("fromUser", change.fromUserId())
                        .addValue("toUser", change.toUserId()).addValue("note", change.note())
                        .addValue("by", change.changedBy()).addValue("at", Timestamp.from(change.changedAt()))
                        .addValue("creator", change.changedBy() == null ? "system" : change.changedBy().toString()));
    }

    @Override
    public boolean submittedSince(String taxCode, String email, Instant since) {
        if (taxCode == null && email == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM customer.lead
                                WHERE submitted_at >= :since
                                  AND ((CAST(:taxCode AS varchar) IS NOT NULL AND tax_code = :taxCode)
                                       OR (CAST(:email AS varchar) IS NOT NULL AND lower(email) = lower(:email))))""",
                new MapSqlParameterSource("taxCode", taxCode).addValue("email", email)
                        .addValue("since", Timestamp.from(since)), Boolean.class));
    }

    @Override
    public List<Lead> list(LeadStatus status, UUID assignedTo, int page, int size) {
        return jdbc.query("SELECT " + COLUMNS + " FROM customer.lead " + where()
                        + " ORDER BY submitted_at DESC, id DESC LIMIT :size OFFSET :offset",
                filter(status, assignedTo).addValue("size", size).addValue("offset", (long) page * size), LEAD);
    }

    @Override
    public long count(LeadStatus status, UUID assignedTo) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM customer.lead " + where(), filter(status, assignedTo),
                Long.class);
        return count == null ? 0 : count;
    }

    private static String where() {
        return """
                WHERE (CAST(:status AS varchar) IS NULL OR status = :status)
                  AND (CAST(:assignedTo AS uuid) IS NULL OR assigned_to = :assignedTo)""";
    }

    private static MapSqlParameterSource filter(LeadStatus status, UUID assignedTo) {
        return new MapSqlParameterSource("status", status == null ? null : status.name())
                .addValue("assignedTo", assignedTo);
    }

    @Override
    public List<LeadChange> history(UUID leadId) {
        return jdbc.query("""
                SELECT id, lead_id, from_status, to_status, from_user_id, to_user_id, note, changed_by, changed_at
                  FROM customer.lead_history WHERE lead_id = :lead ORDER BY changed_at, id""",
                new MapSqlParameterSource("lead", leadId), (rs, row) -> new LeadChange(
                        rs.getObject("id", UUID.class), rs.getObject("lead_id", UUID.class),
                        status(rs.getString("from_status")), status(rs.getString("to_status")),
                        rs.getObject("from_user_id", UUID.class), rs.getObject("to_user_id", UUID.class),
                        rs.getString("note"), rs.getObject("changed_by", UUID.class),
                        rs.getTimestamp("changed_at").toInstant()));
    }

    @Override
    public long countOpenOf(UUID salesUserId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM customer.lead
                 WHERE assigned_to = :user AND status IN ('ASSIGNED', 'CONTACTED')""",
                new MapSqlParameterSource("user", salesUserId), Long.class);
        return count == null ? 0 : count;
    }

    private static Lead lead(ResultSet rs, int row) throws SQLException {
        Array interests = rs.getArray("interests");
        List<Lead.Interest> codes = interests == null ? List.of()
                : Arrays.stream((String[]) interests.getArray()).map(Lead.Interest::valueOf).toList();
        Lead.Form form = new Lead.Form(rs.getString("company_name"), rs.getString("tax_code"),
                rs.getString("contact_name"), rs.getString("contact_title"), rs.getString("phone"),
                rs.getString("email"), rs.getString("province"), codes, rs.getString("monthly_volume"),
                rs.getString("note"));
        return new Lead(rs.getObject("id", UUID.class), form, instant(rs, "submitted_at"),
                LeadStatus.valueOf(rs.getString("status")), rs.getObject("assigned_to", UUID.class),
                rs.getObject("assigned_by", UUID.class), instant(rs, "assigned_at"), instant(rs, "contacted_at"),
                rs.getString("contact_note"), rs.getObject("converted_customer_id", UUID.class),
                instant(rs, "converted_at"), rs.getString("disqualified_reason"),
                rs.getObject("disqualified_by", UUID.class), instant(rs, "disqualified_at"), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static LeadStatus status(String value) {
        return value == null ? null : LeadStatus.valueOf(value);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
