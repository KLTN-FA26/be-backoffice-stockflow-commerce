package com.stockflow.assistant.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An answer someone gave a verdict, as the System Admin reviews it (contract §5.1): the question it answered, the
 * answer, the tools it called and the verdict with its comment. Text is as stored, with the customer aliases the model
 * saw ({@code KH-1}), never the names: a review becomes a golden-set case committed to the repository.
 *
 * @param givenAt    when the verdict was last given (a second verdict replaces the first)
 * @param rater      who gave it, by role; null for a verdict given before the roles were recorded (V20270107000400)
 * @param question   the question answered; null when none precedes it (never for an answer the panel produced)
 * @param tools      every tool call made for the answer, in order
 */
public record MarkedAnswer(UUID feedbackId, UUID messageId, UUID conversationId, FeedbackVerdict verdict,
                           String comment, Instant givenAt, Rater rater, String question, String answer,
                           Instant answeredAt, String model, List<MarkedTool> tools) {

    public MarkedAnswer {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * The roles and warehouses of the person who rated, when they rated.
     *
     * @param roles      their roles, '+'-joined ({@code SALES_STAFF+SALES_MANAGER}), {@code OTHER} for a role created at
     *                   run time
     * @param warehouses the warehouses they were limited to, by prefix; empty when not limited
     */
    public record Rater(String roles, List<String> warehouses) {

        public Rater {
            warehouses = warehouses == null ? List.of() : List.copyOf(warehouses);
        }
    }

    /**
     * One tool call made for the answer.
     *
     * @param step      the sequence of the model turn that asked for it: calls of one turn were asked for together
     * @param arguments the arguments the model gave, as JSON text
     */
    public record MarkedTool(int step, String tool, String arguments, ToolOutcome outcome) {
    }
}
