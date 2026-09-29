package com.stockflow.notification.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** A durable round-robin position, not a second event queue. ShedLock serializes its writer. */
@Repository
@Transactional
public class PoRetryCursorRepository {
    private final JdbcTemplate jdbc;

    public PoRetryCursorRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public UUID load() {
        return jdbc.queryForObject("select last_publication_id from notification.po_retry_cursor where id = 1", UUID.class);
    }

    public void save(UUID publicationId) {
        jdbc.update("update notification.po_retry_cursor set last_publication_id = ? where id = 1", publicationId);
    }
}
