package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.domain.AnswerFeedback;
import com.stockflow.assistant.internal.domain.AnswerFeedbackRepository;
import com.stockflow.assistant.internal.domain.FeedbackVerdict;
import com.stockflow.assistant.internal.domain.MarkedAnswer;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.entity.AssistantAnswerFeedbackJpaEntity;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Specs;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@link AnswerFeedbackRepository} over the verdicts' Spring Data repository, and the messages' for the lock; the
 * review list in SQL.
 *
 * <p><b>The answer is locked before its verdict is read.</b> Find-then-insert alone lets two clicks on one answer both
 * find nothing and both insert, and the second fails on the unique key. Under the lock the second waits, then finds
 * the first's row and rewrites it.</p>
 *
 * <p><b>The rater is recorded as the checks allow, never at the cost of the verdict.</b> A warehouse prefix the
 * column's pattern would refuse is left out of the list rather than failing the rating ({@link #warehouses}).</p>
 *
 * <p><b>The review list reads a page in three statements</b> ({@link #marked}): the count, the page of verdicts with
 * each answer and the question before it (a lateral read of {@code uk_message_sequence}), and every tool call of the
 * page's conversations, assigned to its answer in memory by sequence, as the transcript assigns them.</p>
 */
@Repository
class AnswerFeedbackRepositoryAdapter implements AnswerFeedbackRepository {

    /** {@code ck_answer_feedback_rater_warehouses}: one prefix. */
    private static final Pattern WAREHOUSE = Pattern.compile("[A-Za-z0-9_-]+");

    /** The column's length. */
    private static final int MAX_WAREHOUSES = 500;

    /** The verdicts of one kind given in a period, by the latest verdict's time; shared by the count and the page. */
    private static final String MARKED_WHERE = "\n" + """
             WHERE f.verdict = :verdict
               AND coalesce(f.last_modified_at, f.created_at) >= :from
               AND coalesce(f.last_modified_at, f.created_at) < :to""";

    private final AssistantAnswerFeedbackJpaRepository feedback;
    private final AssistantMessageJpaRepository messages;
    private final NamedParameterJdbcTemplate jdbc;

    AnswerFeedbackRepositoryAdapter(AssistantAnswerFeedbackJpaRepository feedback,
                                    AssistantMessageJpaRepository messages, NamedParameterJdbcTemplate jdbc) {
        this.feedback = feedback;
        this.messages = messages;
        this.jdbc = jdbc;
    }

    @Override
    public boolean record(UUID conversationId, UUID messageId, UUID ownerUserId, AnswerFeedback given,
                          MarkedAnswer.Rater rater) {
        if (messages.lockAnswer(messageId, conversationId).isEmpty()) {
            return false;
        }
        AssistantAnswerFeedbackJpaEntity row = feedback
                .findAllInScope(Specs.eq("messageId", messageId)).stream().findFirst()
                .orElseGet(() -> new AssistantAnswerFeedbackJpaEntity(Identifiers.newId(), messageId, ownerUserId));
        row.give(given.verdict(), given.reason(), rater == null ? null : rater.roles(),
                rater == null ? null : warehouses(rater.warehouses()));
        feedback.save(row);
        return true;
    }

    @Override
    public Map<UUID, FeedbackVerdict> verdictsOf(Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        return feedback.findAllInScope(Specs.in("messageId", messageIds)).stream()
                .collect(Collectors.toMap(AssistantAnswerFeedbackJpaEntity::getMessageId,
                        AssistantAnswerFeedbackJpaEntity::getVerdict));
    }

    @Override
    public PageResponse<MarkedAnswer> marked(FeedbackVerdict verdict, Instant from, Instant to, int page, int size) {
        MapSqlParameterSource keys = new MapSqlParameterSource("verdict", verdict.name())
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to))
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM assistant.answer_feedback f" + MARKED_WHERE, keys,
                Long.class);
        if (total == null || total == 0) {
            return PageResponse.empty(page, size);
        }
        List<Row> rows = jdbc.query("""
                SELECT f.id, f.message_id, f.verdict, f.reason, coalesce(f.last_modified_at, f.created_at) AS given_at,
                       f.rater_roles, f.rater_warehouses,
                       a.conversation_id, a.sequence, a.text AS answer, a.model, a.created_at AS answered_at,
                       q.sequence AS question_sequence, q.text AS question,
                       (SELECT min(n.sequence) FROM assistant.message n
                         WHERE n.conversation_id = a.conversation_id AND n.role = 'USER'
                           AND n.sequence > a.sequence) AS next_question_sequence
                FROM assistant.answer_feedback f
                JOIN assistant.message a ON a.id = f.message_id
                LEFT JOIN LATERAL (SELECT m.sequence, m.text FROM assistant.message m
                                    WHERE m.conversation_id = a.conversation_id AND m.role = 'USER'
                                      AND m.sequence < a.sequence
                                    ORDER BY m.sequence DESC LIMIT 1) q ON TRUE""" + MARKED_WHERE + """

                 ORDER BY given_at DESC, f.id DESC
                 LIMIT :limit OFFSET :offset""", keys, (rs, row) -> new Row(
                rs.getObject("id", UUID.class), rs.getObject("message_id", UUID.class),
                FeedbackVerdict.valueOf(rs.getString("verdict")), rs.getString("reason"),
                rs.getTimestamp("given_at").toInstant(),
                rater(rs.getString("rater_roles"), rs.getString("rater_warehouses")),
                rs.getObject("conversation_id", UUID.class), rs.getString("answer"), rs.getString("model"),
                rs.getTimestamp("answered_at").toInstant(), (Integer) rs.getObject("question_sequence"),
                rs.getString("question"), (Integer) rs.getObject("next_question_sequence")));
        Map<UUID, List<ToolRow>> tools = toolsOf(rows.stream().map(Row::conversationId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        List<MarkedAnswer> items = rows.stream()
                .map(row -> row.marked(tools.getOrDefault(row.conversationId(), List.of()))).toList();
        return PageResponse.of(items, page, size, total);
    }

    /** Every tool call of {@code conversations}, by conversation, in the order they were asked for. */
    private Map<UUID, List<ToolRow>> toolsOf(Collection<UUID> conversations) {
        Map<UUID, List<ToolRow>> byConversation = new HashMap<>();
        if (conversations.isEmpty()) {
            return byConversation;
        }
        jdbc.query("""
                        SELECT t.conversation_id, m.sequence, t.tool_name, t.arguments::text AS arguments, t.outcome
                        FROM assistant.tool_call t
                        JOIN assistant.message m ON m.id = t.message_id
                        WHERE t.conversation_id IN (:conversations) AND m.role = 'ASSISTANT'
                        ORDER BY m.sequence, t.id""",
                new MapSqlParameterSource("conversations", conversations),
                (RowCallbackHandler) rs -> byConversation
                        .computeIfAbsent(rs.getObject("conversation_id", UUID.class), id -> new ArrayList<>())
                        .add(new ToolRow(rs.getInt("sequence"), rs.getString("tool_name"),
                                rs.getString("arguments"), ToolOutcome.valueOf(rs.getString("outcome")))));
        return byConversation;
    }

    /** The prefixes the column's pattern takes, sorted, ','-joined, within its length; empty when there are none. */
    static String warehouses(List<String> codes) {
        StringBuilder joined = new StringBuilder();
        for (String code : codes.stream().filter(code -> code != null && WAREHOUSE.matcher(code).matches())
                .sorted().distinct().toList()) {
            if (joined.length() + code.length() + 1 > MAX_WAREHOUSES) {
                break;
            }
            joined.append(joined.isEmpty() ? "" : ",").append(code);
        }
        return joined.toString();
    }

    private static MarkedAnswer.Rater rater(String roles, String warehouses) {
        if (roles == null) {
            return null;
        }
        return new MarkedAnswer.Rater(roles, warehouses == null || warehouses.isEmpty() ? List.of()
                : Arrays.asList(warehouses.split(",")));
    }

    private record ToolRow(int sequence, String tool, String arguments, ToolOutcome outcome) {
    }

    /**
     * One verdict as read, with the bounds of its answer's exchange: the turns after its question and before the next
     * one, which is where the transcript finds the answer's tool calls.
     */
    private record Row(UUID feedbackId, UUID messageId, FeedbackVerdict verdict, String comment, Instant givenAt,
                       MarkedAnswer.Rater rater, UUID conversationId, String answer, String model,
                       Instant answeredAt, Integer questionSequence, String question, Integer nextQuestionSequence) {

        MarkedAnswer marked(List<ToolRow> conversationTools) {
            int after = questionSequence == null ? -1 : questionSequence;
            int before = nextQuestionSequence == null ? Integer.MAX_VALUE : nextQuestionSequence;
            List<MarkedAnswer.MarkedTool> tools = conversationTools.stream()
                    .filter(tool -> tool.sequence() > after && tool.sequence() < before)
                    .map(tool -> new MarkedAnswer.MarkedTool(tool.sequence(), tool.tool(), tool.arguments(),
                            tool.outcome()))
                    .toList();
            return new MarkedAnswer(feedbackId, messageId, conversationId, verdict, comment, givenAt, rater, question,
                    answer, answeredAt, model, tools);
        }
    }
}
