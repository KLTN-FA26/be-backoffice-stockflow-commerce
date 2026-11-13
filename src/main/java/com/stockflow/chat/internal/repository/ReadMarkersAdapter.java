package com.stockflow.chat.internal.repository;

import com.stockflow.chat.internal.domain.ReadMarkers;
import com.stockflow.chat.internal.domain.SenderType;
import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements the {@link ReadMarkers} port with one upsert: the {@code where} of its update keeps a marker from moving
 * back whatever order two tabs report in, and {@code tg_participant_read_forward} says so again. Plain SQL, because a
 * marker is a counter, not an aggregate - loading and merging an entity per "read" would race exactly where the upsert
 * does not. The message it names must already be stored (the caller saved it with a flush).
 */
@Repository
class ReadMarkersAdapter implements ReadMarkers {

    private final NamedParameterJdbcTemplate jdbc;

    ReadMarkersAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Marker advance(UUID conversationId, UUID userId, SenderType readerType, long seq, Instant at) {
        List<Marker> written = jdbc.query("""
                        insert into chat.participant_read as r (id, conversation_id, user_id, reader_type, last_read_seq,
                                                                last_read_message_id, last_read_at, version, created_at)
                        select :id, :conversation, :user, :type, m.seq, m.id, :at, 0, :at
                          from chat.message m where m.conversation_id = :conversation and m.seq = :seq
                        on conflict (conversation_id, user_id) do update
                           set last_read_seq = excluded.last_read_seq,
                               last_read_message_id = excluded.last_read_message_id,
                               last_read_at = excluded.last_read_at,
                               version = r.version + 1,
                               last_modified_at = excluded.last_read_at
                         where excluded.last_read_seq > r.last_read_seq
                        returning r.user_id, r.reader_type, r.last_read_seq, r.last_read_at""",
                new MapSqlParameterSource().addValue("id", Identifiers.newId()).addValue("conversation", conversationId)
                        .addValue("user", userId).addValue("type", column(readerType)).addValue("seq", seq)
                        .addValue("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)), ROW);
        if (!written.isEmpty()) {
            return written.get(0);
        }
        // Nothing written: the marker was already there or further (or no message has that place).
        return of(conversationId).stream().filter(m -> m.userId().equals(userId)).findFirst()
                .orElse(new Marker(userId, readerType, 0, null));
    }

    @Override
    public List<Marker> of(UUID conversationId) {
        return jdbc.query("""
                select user_id, reader_type, last_read_seq, last_read_at from chat.participant_read
                 where conversation_id = :conversation order by user_id""", Map.of("conversation", conversationId),
                ROW);
    }

    /** {@code reader_type} says CUSTOMER or STAFF; the domain's name for the staff side is AGENT. */
    private static String column(SenderType side) {
        // The sender type's name, not a literal: the reader side is a message side, not a role (NoRoleNameAccessTest).
        return side == SenderType.CUSTOMER ? SenderType.CUSTOMER.name() : STAFF_READER;
    }

    private static SenderType side(String column) {
        return SenderType.CUSTOMER.name().equals(column) ? SenderType.CUSTOMER : SenderType.AGENT;
    }

    private static final String STAFF_READER = "STAFF";

    private static final RowMapper<Marker> ROW = (rs, n) -> {
        OffsetDateTime at = rs.getObject("last_read_at", OffsetDateTime.class);
        return new Marker(rs.getObject("user_id", UUID.class), side(rs.getString("reader_type")),
                rs.getLong("last_read_seq"), at == null ? null : at.toInstant());
    };
}
