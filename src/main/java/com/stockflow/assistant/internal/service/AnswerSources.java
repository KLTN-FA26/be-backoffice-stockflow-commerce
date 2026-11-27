package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.History;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.Turn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What an answer rests on, read from the tool calls that produced it: the rules it cites
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
        AnswerNumbers.Grounds.Builder grounds = AnswerNumbers.Grounds.builder();
        for (Turn turn : exchange) {
            if (turn instanceof Turn.Question question) {
                grounds.source(question.text()).context(question.context());
            } else if (turn instanceof Turn.ToolResults results) {
                // A refused or failed call grounds the numbers its line names, never a code: "NOT_FOUND: CUP-A" for a
                // code the model made up does not make it one that was looked up (AnswerNumbers.Grounds).
                results.results().forEach(result -> {
                    if (result.error()) {
                        grounds.error(result.content());
                    } else {
                        grounds.source(result.content());
                    }
                });
            }
        }
        return grounds.build();
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

    /** The retrieved anchors the answer names ({@link #names}). */
    static List<String> citations(String text, List<ToolExecution> executions, ObjectMapper json) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String answer = text.toLowerCase(Locale.ROOT);
        return retrieved(executions, json).stream()
                .filter(anchor -> names(answer, anchor.toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * Whether {@code answer} names {@code anchor} as a whole, not inside a longer one: {@code 17 §4.1} is not named by
     * "17 §4.12", nor {@code 5 BR-01} by "15 BR-01", nor {@code 15 BR-1} by "15 BR-10". Both lower-case.
     */
    static boolean names(String answer, String anchor) {
        for (int at = answer.indexOf(anchor); at >= 0; at = answer.indexOf(anchor, at + 1)) {
            int end = at + anchor.length();
            boolean before = at == 0 || !Character.isLetterOrDigit(answer.codePointBefore(at));
            boolean after = end == answer.length() || !Character.isLetterOrDigit(answer.codePointAt(end))
                    && !(answer.charAt(end) == '.' && end + 1 < answer.length()
                    && Character.isDigit(answer.charAt(end + 1)));
            if (before && after) {
                return true;
            }
        }
        return false;
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

}
