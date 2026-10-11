package com.stockflow.common.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only, bounded adapter to the version-pinned Modulith registry; framework still owns all writes. */
@Repository
@Transactional(readOnly = true)
public class PendingPublicationReader {
    public record PendingPublication(UUID id, String serializedEvent) {}
    private final JdbcTemplate jdbc;
    public PendingPublicationReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<PendingPublication> page(String type, String listener, Instant before, UUID after, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Retry page size must be 1..50");
        String sql = "select id, serialized_event from public.event_publication where event_type=? and listener_id=? "
                + "and completion_date is null and publication_date<? "
                + (after == null ? "" : "and id>? ") + "order by id limit ?";
        Object[] args = after == null ? new Object[]{type, listener, Timestamp.from(before), limit}
                : new Object[]{type, listener, Timestamp.from(before), after, limit};
        return jdbc.query(sql, (row, index) -> new PendingPublication(row.getObject("id", UUID.class), row.getString("serialized_event")), args);
    }
}
