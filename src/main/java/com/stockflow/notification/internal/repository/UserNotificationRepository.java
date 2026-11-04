package com.stockflow.notification.internal.repository;

import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The inbox table ({@code notification.user_notification}), in SQL rather than JPA: its hot operations are
 * set-based ({@code INSERT ... ON CONFLICT DO NOTHING}, mark everything read, purge) and a loaded entity
 * would only be a way to lose them.
 *
 * <p><b>Every read and every update takes the recipient id and puts it in the {@code WHERE}.</b> That is
 * the whole access rule of the inbox: a message is reachable only by the person it was posted to, whatever
 * the caller's role or data scope. A method here that did not take a recipient would be a leak; the purge,
 * which clears old rows of everyone, is the one deliberate exception and takes no id from a request.</p>
 */
@Repository
public class UserNotificationRepository {

    /** One message as the recipient sees it. */
    public record Row(UUID id, String category, InboxSeverity severity, String title, String body, String link,
                      String sourceType, String sourceId, Instant createdAt, Instant readAt) {
    }

    private static final String COLUMNS =
            "id, category, severity, title, body, link, source_type, source_id, created_at, read_at";

    private final JdbcTemplate jdbc;

    public UserNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the message for the recipient unless it already has this {@code dedupeKey}.
     *
     * @return whether a row was created
     */
    public boolean insertIfAbsent(UUID id, UUID recipientId, InboxMessage message, Instant now) {
        return jdbc.update("""
                INSERT INTO notification.user_notification
                    (id, recipient_id, category, severity, title, body, link, source_type, source_id, dedupe_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (recipient_id, dedupe_key) DO NOTHING""",
                id, recipientId, message.category(), message.severity().name(), message.title(), message.body(),
                message.link(), message.sourceType(), message.sourceId(), message.dedupeKey(),
                Timestamp.from(now)) == 1;
    }

    /** The recipient's messages, newest first; {@code id} breaks ties so a page boundary never repeats a row. */
    public List<Row> page(UUID recipientId, boolean unreadOnly, int limit, long offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notification.user_notification"
                        + " WHERE recipient_id = ?" + (unreadOnly ? " AND read_at IS NULL" : "")
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("category"),
                        InboxSeverity.valueOf(rs.getString("severity")), rs.getString("title"),
                        rs.getString("body"), rs.getString("link"), rs.getString("source_type"),
                        rs.getString("source_id"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()),
                recipientId, limit, offset);
    }

    public long count(UUID recipientId, boolean unreadOnly) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM notification.user_notification"
                        + " WHERE recipient_id = ?" + (unreadOnly ? " AND read_at IS NULL" : ""),
                Long.class, recipientId);
        return count == null ? 0 : count;
    }

    /**
     * Marks one of the recipient's messages read; a message already read keeps its first read time.
     *
     * @return whether the recipient has such a message. False for an id that does not exist and for one that
     *         belongs to somebody else, which the caller answers the same way.
     */
    public boolean markRead(UUID id, UUID recipientId, Instant now) {
        // GREATEST: the database refuses read_at before created_at (ck_user_notification_read), and the
        // two timestamps may come from different clocks.
        return jdbc.update("""
                UPDATE notification.user_notification
                SET read_at = COALESCE(read_at, GREATEST(created_at, ?))
                WHERE id = ? AND recipient_id = ?""", Timestamp.from(now), id, recipientId) == 1;
    }

    /** Marks every unread message of the recipient read. @return how many were unread */
    public int markAllRead(UUID recipientId, Instant now) {
        return jdbc.update("""
                UPDATE notification.user_notification
                SET read_at = GREATEST(created_at, ?)
                WHERE recipient_id = ? AND read_at IS NULL""", Timestamp.from(now), recipientId);
    }

    /** Deletes messages that were read before {@code cutoff}, for everyone. Unread ones stay. @return how many */
    public int deleteReadBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM notification.user_notification WHERE read_at IS NOT NULL AND read_at < ?",
                Timestamp.from(cutoff));
    }
}
