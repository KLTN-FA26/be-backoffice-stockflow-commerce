package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.common.id.Identifiers;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Design §5.5: idle conversations go after the retention period, with their messages and tool calls. */
@SecuredIntegrationTest
class AssistantRetentionJobIntegrationTest {

    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired AssistantRetentionJob job;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    @Test
    @DisplayName("an idle conversation is deleted whole; a recent one and one being answered are kept")
    void deletesIdleConversations() {
        UUID owner = tx.execute(s -> ReferenceRows.user(entityManager));
        Instant old = Instant.now().minus(120, ChronoUnit.DAYS);
        UUID idle = conversation(owner, old, null);
        UUID recent = conversation(owner, Instant.now().minus(1, ChronoUnit.DAYS), null);
        UUID answering = conversation(owner, old, Instant.now());
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO assistant.message (id, conversation_id, sequence, role, text, content, version, created_at)
                VALUES (?, ?, 0, 'USER', 'Còn bao nhiêu?', '{"type":"question","text":"x","context":"y"}'::jsonb, 0,
                        ?)""", Identifiers.newId(), idle, Timestamp.from(old)));

        job.deleteIdleConversations();

        assertThat(exists(idle)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from assistant.message where conversation_id = ?",
                Long.class, idle)).isZero();
        assertThat(exists(recent)).isTrue();
        assertThat(exists(answering)).as("never while a question is being answered").isTrue();
    }

    private UUID conversation(UUID owner, Instant lastActivity, Instant answeringSince) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO assistant.conversation (id, owner_user_id, title, message_count, last_activity_at,
                    answering_since, version, created_at)
                VALUES (?, ?, 'Còn bao nhiêu?', 1, ?, ?, 0, ?)""", id, owner, Timestamp.from(lastActivity),
                answeringSince == null ? null : Timestamp.from(answeringSince), Timestamp.from(lastActivity)));
        return id;
    }

    private boolean exists(UUID conversation) {
        return jdbc.queryForObject("select count(*) from assistant.conversation where id = ?", Long.class,
                conversation) == 1;
    }
}
