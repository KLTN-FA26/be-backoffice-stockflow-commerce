package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What an answer rests on, read from the tool calls that produced it: the rules it cites and the proposals it carries
 * (design §6.3, §8).
 *
 * <p><b>A citation is checked, not trusted.</b> An anchor counts only when the answer names it <i>and</i>
 * {@code searchKnowledge} returned it in the same answer. A rule id the model names from memory is not reported as a
 * citation; the golden set counts it as an unsupported one.</p>
 */
public final class AnswerSources {

    static final String KNOWLEDGE_TOOL = "searchKnowledge";
    static final String REORDER_TOOL = "proposeReorder";

    private AnswerSources() {
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
     * The proposals {@code proposeReorder} returned, one per supplier, for the panel to offer as pre-filled
     * purchase-order forms. Each carries when it was computed: reopened days later, a proposal is history, and the
     * panel marks it so rather than offering stale quantities as current.
     */
    static List<Proposal> proposals(List<ToolExecution> executions, ObjectMapper json) {
        List<Proposal> proposals = new ArrayList<>();
        for (JsonNode result : results(executions, REORDER_TOOL, json)) {
            for (JsonNode proposal : result.path("proposals")) {
                List<ProposalLine> lines = new ArrayList<>();
                proposal.path("lines").forEach(line -> lines.add(new ProposalLine(line.path("sku").asText(),
                        line.path("quantity").asLong())));
                proposals.add(new Proposal(proposal.path("type").asText(), text(proposal, "supplierCode"),
                        text(proposal, "supplierName"), instant(proposal.path("generatedAt")), List.copyOf(lines)));
            }
        }
        return List.copyOf(proposals);
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
    }

    public record ProposalLine(String sku, long quantity) {
    }
}
