package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.History;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.Turn;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What an answer rests on, read from the tool calls that produced it: the rules it cites, the proposals it carries
 * (design §6.3, §8) and what its numbers may rest on (design §7, {@link AnswerNumbers}).
 *
 * <p><b>A citation is checked, not trusted.</b> An anchor counts only when the answer names it <i>and</i>
 * {@code searchKnowledge} returned it in the same answer. A rule id the model names from memory is not reported as a
 * citation; the golden set counts it as an unsupported one.</p>
 *
 * <p><b>A number is checked against what the model was given for it</b>: the question and its context, every result
 * the answer's lookups returned, and the same of the {@value History#EARLIER_QUESTIONS} questions before it, which
 * the model was sent again with their answers ({@link History}). A figure repeated from an earlier answer is grounded
 * by that answer's results, not by its text: a number never checked when it was first written does not become
 * checked by being repeated.</p>
 */
public final class AnswerSources {

    static final String KNOWLEDGE_TOOL = "searchKnowledge";
    static final String REORDER_TOOL = "proposeReorder";

    private AnswerSources() {
    }

    /**
     * The grounds of the exchanges before the last one in {@code turns}, at most {@value History#EARLIER_QUESTIONS} of
     * them, nearest last: what an answer to the last question may repeat ({@link #groundsOf}).
     *
     * @param turns a conversation's stored turns, oldest first, ending with a question and anything produced for it
     */
    static AnswerNumbers.Grounds earlierGrounds(List<Turn> turns) {
        List<List<Turn>> exchanges = exchanges(turns);
        AnswerNumbers.Grounds grounds = AnswerNumbers.Grounds.none();
        for (List<Turn> exchange : exchanges.subList(Math.max(0, exchanges.size() - 1 - History.EARLIER_QUESTIONS),
                Math.max(0, exchanges.size() - 1))) {
            grounds = grounds.and(groundsOf(exchange));
        }
        return grounds;
    }

    /**
     * What one exchange gives a number to rest on: its question and the question's context, and every tool result,
     * a failed call's line included, since a limit it names ("at most 366 days") is the user's to hear.
     *
     * @param exchange a question and the turns produced for it; a list not opening with a question is read the same
     */
    static AnswerNumbers.Grounds groundsOf(List<Turn> exchange) {
        List<String> sources = new ArrayList<>();
        for (Turn turn : exchange) {
            if (turn instanceof Turn.Question question) {
                sources.add(question.text());
                sources.add(question.context());
            } else if (turn instanceof Turn.ToolResults results) {
                results.results().forEach(result -> sources.add(result.content()));
            }
        }
        return AnswerNumbers.Grounds.of(sources);
    }

    /** {@code turns} cut at every question: each exchange opens with its question. Turns before the first are left out. */
    static List<List<Turn>> exchanges(List<Turn> turns) {
        List<List<Turn>> exchanges = new ArrayList<>();
        for (Turn turn : turns) {
            if (turn instanceof Turn.Question) {
                exchanges.add(new ArrayList<>());
            }
            if (!exchanges.isEmpty()) {
                exchanges.getLast().add(turn);
            }
        }
        return exchanges;
    }

    /** Anchors {@code searchKnowledge} returned across {@code executions}, in order, each once. */
    static List<String> retrieved(List<ToolExecution> executions, ObjectMapper json) {
        Set<String> anchors = new LinkedHashSet<>();
        for (JsonNode result : results(executions, KNOWLEDGE_TOOL, json)) {
            result.path("passages").forEach(passage -> {
                String anchor = passage.path("anchor").asText("");
                if (!anchor.isEmpty()) {
                    anchors.add(anchor);
                }
            });
        }
        return List.copyOf(anchors);
    }

    /** The retrieved anchors the answer names. */
    static List<String> citations(String text, List<ToolExecution> executions, ObjectMapper json) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String answer = text.toLowerCase(Locale.ROOT);
        return retrieved(executions, json).stream()
                .filter(anchor -> answer.contains(anchor.toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * The proposals {@code proposeReorder} returned, one per supplier and type, for the panel to offer as pre-filled
     * purchase-order forms; when it ran more than once for the question (a model calling it twice, or a question
     * answered again on a stronger model), the last one: two forms for one supplier invite two purchase orders. Each
     * carries when it was computed: reopened days later, a proposal is history, and the panel marks it so rather than
     * offering stale quantities as current.
     */
    static List<Proposal> proposals(List<ToolExecution> executions, ObjectMapper json) {
        Map<List<String>, Proposal> latest = new LinkedHashMap<>();
        for (JsonNode result : results(executions, REORDER_TOOL, json)) {
            for (JsonNode proposal : result.path("proposals")) {
                List<ProposalLine> lines = new ArrayList<>();
                proposal.path("lines").forEach(line -> lines.add(new ProposalLine(line.path("sku").asText(),
                        line.path("quantity").asLong())));
                Proposal found = new Proposal(proposal.path("type").asText(), text(proposal, "supplierCode"),
                        text(proposal, "supplierName"), instant(proposal.path("generatedAt")), List.copyOf(lines));
                // Arrays.asList: a proposal without a supplier code is still one key, which List.of would refuse.
                latest.put(Arrays.asList(found.type(), found.supplierCode()), found);
            }
        }
        return List.copyOf(latest.values());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    /**
     * Written by {@code AssistantTools} as Vietnam time with its offset ({@code 2026-10-28T16:54:09+07:00}); a result
     * stored before that is UTC text. {@code Instant.parse} reads both; epoch seconds are read for a mapper that
     * writes dates as timestamps.
     */
    private static Instant instant(JsonNode value) {
        if (value.isTextual()) {
            return Instant.parse(value.asText());
        }
        if (value.isNumber()) {
            return Instant.ofEpochSecond(value.longValue(), value.decimalValue().remainder(BigDecimal.ONE)
                    .movePointRight(9).longValue());
        }
        return null;
    }

    private static List<JsonNode> results(List<ToolExecution> executions, String tool, ObjectMapper json) {
        List<JsonNode> results = new ArrayList<>();
        for (ToolExecution execution : executions) {
            if (execution.outcome() == ToolOutcome.OK && tool.equals(execution.call().name())) {
                try {
                    results.add(json.readTree(execution.result()));
                } catch (JsonProcessingException notJson) {
                    // An OK result is the tool's own JSON; anything else is not a source.
                }
            }
        }
        return results;
    }

    /**
     * A purchase order the user may open pre-filled; nothing is created until they submit it.
     *
     * @param generatedAt when the quantities were computed; null only for a result that did not say
     */
    public record Proposal(String type, String supplierCode, String supplierName, Instant generatedAt,
                           List<ProposalLine> lines) {

        /** Which proposal of an answer this is: one per type and supplier, as {@link #proposals} keeps them. */
        public String key() {
            return type + ":" + supplierCode;
        }
    }

    public record ProposalLine(String sku, long quantity) {
    }
}
