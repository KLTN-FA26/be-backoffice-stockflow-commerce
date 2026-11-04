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

/**
 * Design §5.5: idle conversations go after the retention period, with their messages, tool calls and the verdicts
 * given their answers.
 */
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

    @Test
    @DisplayName("a verdict on an answer goes with its conversation, through the message it rates; a recent one stays")
    void feedbackGoesWithItsConversation() {
        UUID owner = tx.execute(s -> ReferenceRows.user(entityManager));
        Instant old = Instant.now().minus(120, ChronoUnit.DAYS);
        UUID idle = conversation(owner, old, null);
        UUID recent = conversation(owner, Instant.now().minus(1, ChronoUnit.DAYS), null);
        UUID idleVerdict = verdict(owner, answer(idle, old));
        UUID recentVerdict = verdict(owner, answer(recent, Instant.now()));

        job.deleteIdleConversations();

        assertThat(exists(idle)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from assistant.answer_feedback where id = ?", Long.class,
                idleVerdict)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from assistant.answer_feedback where id = ?", Long.class,
                recentVerdict)).isOne();
    }

    @Test
    @DisplayName("MCP and today runs, which belong to no conversation, go by age; a recent one stays")
    void standaloneRunsGoByAge() {
        UUID owner = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID old = standaloneRun(owner, "MCP", Instant.now().minus(120, ChronoUnit.DAYS));
        UUID recent = standaloneRun(owner, "TODAY", Instant.now().minus(1, ChronoUnit.DAYS));

        job.deleteIdleConversations();

        assertThat(jdbc.queryForObject("select count(*) from assistant.tool_call where id = ?", Long.class, old))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from assistant.tool_call where id = ?", Long.class, recent))
                .isOne();
    }

    @Test
    @DisplayName("the table refuses a chat run without its conversation, and an MCP run with one")
    void channelsAndTurnsAgree() {
        UUID owner = tx.execute(s -> ReferenceRows.user(entityManager));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> standaloneRun(owner, "CHAT", Instant.now()))
                .hasMessageContaining("\"ck_tool_call_channel_turn\"");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> standaloneRun(owner, "EMAIL", Instant.now()))
                .hasMessageContaining("\"ck_tool_call_channel\"");
    }

    /** A tool run of {@code channel} for {@code owner} that belongs to no conversation. */
    private UUID standaloneRun(UUID owner, String channel, Instant at) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO assistant.tool_call (id, channel, user_id, call_id, tool_name, arguments, outcome, result,
                                                 duration_ms, version, created_at)
                VALUES (?, ?, ?, 'mcp-1', 'getStockLevels', '{}'::jsonb, 'OK', '{}', 3, 0, ?)""",
                id, channel, owner, Timestamp.from(at)));
        return id;
    }

    /** An answer in {@code conversation}: an assistant turn holding text, as the loop stores one. */
    private UUID answer(UUID conversation, Instant at) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO assistant.message (id, conversation_id, sequence, role, text, content, stop_reason, model,
                    input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, version, created_at)
                VALUES (?, ?, 1, 'ASSISTANT', 'Kho HCM còn 75.', '{"closing":true}'::jsonb, 'END_TURN', 'scripted',
                        10, 5, 0, 0, 0, ?)""", id, conversation, Timestamp.from(at)));
        return id;
    }

    private UUID verdict(UUID owner, UUID message) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO assistant.answer_feedback (id, message_id, owner_user_id, verdict, reason, version,
                    created_at)
                VALUES (?, ?, ?, 'DOWN', 'Số tồn của kho EVN không đúng', 0, now())""", id, message, owner));
        return id;
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
