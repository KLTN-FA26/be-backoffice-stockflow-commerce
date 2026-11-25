package com.stockflow.product.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.product.internal.domain.ProductChangeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The product's own history ({@code product.product_change_log}, SCRUM-461): written in the
 * transaction of the change it describes, so an entry exists exactly when the change does.
 *
 * <p>{@code platform.audit_log} cannot stand in for it: it is written after the commit, in its own
 * transaction, and records neither the status a change moved from and to nor the reason. The table is
 * append-only ({@code tg_product_change_log_append_only}); a draft product's history goes with the
 * product.</p>
 *
 * <p>Plain SQL, no entity: nothing here is ever loaded, changed and saved back.</p>
 */
@Repository
public class ProductChangeLogRepository {

    private static final int MAX_REASON = 500;

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider users;
    private final Clock clock;

    public ProductChangeLogRepository(JdbcTemplate jdbc, CurrentUserProvider users, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.clock = clock;
    }

    /**
     * One entry of the history, as stored, with what its subject is called today.
     *
     * <p>{@code subjectSku} and {@code subjectLabel} are read with the page, in the same statement, so a history
     * screen needs no variant list to say which SKU or image a line is about: the SKU of the variant (for an image,
     * of the variant it belongs to), and the variant's name or the image's file name (its URL when it was carried
     * over as one). Both are null for the product itself and for a subject deleted since.</p>
     */
    public record Entry(ProductChangeType type, UUID subjectId, String fromStatus, String toStatus, String reason,
                        List<String> changedFields, UUID actorId, Instant occurredAt, String subjectSku,
                        String subjectLabel) {
    }

    /**
     * Appends an entry for {@code productId}, by the current user ({@code null} when the system acts).
     * Must run inside the transaction that makes the change.
     *
     * @param subjectId the variant, image or SKU concerned; null for the product itself
     * @param from      the subject's status before, when the change moved it
     * @param reason    why, null when there is none to give
     */
    public void record(UUID productId, ProductChangeType type, UUID subjectId, String from, String to, String reason,
                       List<String> changedFields) {
        UUID actor = users.current().map(CurrentUser::userId).orElse(null);
        String why = abbreviated(reason);
        List<String> fields = changedFields == null ? List.of() : changedFields;
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO product.product_change_log (id, product_id, change_type, subject_type, subject_id,
                                                            from_status, to_status, reason, changed_fields,
                                                            actor_id, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""");
            statement.setObject(1, Identifiers.newId());
            statement.setObject(2, productId);
            statement.setString(3, type.name());
            statement.setString(4, type.subject().name());
            statement.setObject(5, subjectId);
            statement.setString(6, from);
            statement.setString(7, to);
            statement.setString(8, why);
            statement.setArray(9, connection.createArrayOf("text", fields.toArray()));
            statement.setObject(10, actor);
            statement.setTimestamp(11, Timestamp.from(clock.instant()));
            return statement;
        });
    }

    /** The column holds 500 characters; a rejection reason, which the product row keeps whole, may be longer. */
    private static String abbreviated(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String value = reason.strip();
        return value.length() <= MAX_REASON ? value : value.substring(0, MAX_REASON - 1) + "…";
    }

    /**
     * Newest first; ties on the time keep a stable order by id. The subject's SKU and label come from the same
     * statement: an image subject joins {@code media}, every other non-product subject is a variant (a SKU's
     * units, sales units and older logistics entries name the variant too).
     */
    public Page<Entry> page(UUID productId, Pageable pageable) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM product.product_change_log WHERE product_id = ?", Long.class, productId);
        List<Entry> rows = jdbc.query("""
                SELECT l.change_type, l.subject_id, l.from_status, l.to_status, l.reason, l.changed_fields,
                       l.actor_id, l.occurred_at,
                       coalesce(v.sku, mv.sku) AS subject_sku,
                       coalesce(v.name, m.original_name, m.url) AS subject_label
                  FROM product.product_change_log l
                  LEFT JOIN product.variants v ON v.id = l.subject_id AND l.subject_type NOT IN ('PRODUCT', 'MEDIA')
                  LEFT JOIN product.media m ON m.id = l.subject_id AND l.subject_type = 'MEDIA'
                  LEFT JOIN product.variants mv ON mv.id = m.variant_id
                 WHERE l.product_id = ?
                 ORDER BY l.occurred_at DESC, l.id DESC
                 LIMIT ? OFFSET ?""",
                (rs, n) -> new Entry(ProductChangeType.valueOf(rs.getString("change_type")),
                        rs.getObject("subject_id", UUID.class), rs.getString("from_status"),
                        rs.getString("to_status"), rs.getString("reason"), fieldsOf(rs.getArray("changed_fields")),
                        rs.getObject("actor_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("subject_sku"), rs.getString("subject_label")),
                productId, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(rows, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()),
                total == null ? 0 : total);
    }

    private static List<String> fieldsOf(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
