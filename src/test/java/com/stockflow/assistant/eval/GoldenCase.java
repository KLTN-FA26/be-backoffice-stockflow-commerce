package com.stockflow.assistant.eval;

import java.util.List;
import java.util.Map;

/**
 * One case of the golden set ({@code src/test/resources/assistant/golden-set.json}, design §10.1).
 *
 * <p>Expected facts are given either as values or as an SQL query run just before the question, so a case stays true
 * when the data under it changes. Placeholders such as {@code {otherWarehouse}} are filled from the fixtures before a
 * case runs.</p>
 *
 * <p>A case may ask {@code earlier} questions first, in the same conversation: a follow-up ("còn lô nào của mã đó?")
 * is only a test if the model has to take the code from what it answered before. Only the last question is scored,
 * except for leaks, which no answer of the persona may contain.</p>
 *
 * @param useCase        U1..U8 of the design, or S (stock), M (must not do)
 * @param variant        how the question is phrased: formal, casual, no-diacritics, typo, refusal, ...
 * @param expectTools    tools the assistant must call for the last question (a subset of what it calls)
 * @param forbiddenTools tools it must not call (an injected instruction that names one, for example)
 * @param expectNoTools  the question needs no data, so no tool may be called
 * @param facts          values the answer must contain
 * @param factsQuery     SQL whose single column gives more values the answer must contain
 * @param forbidden      values no answer may ever contain: data this persona may not see
 * @param forbiddenQuery SQL whose single column gives more such values
 * @param expectRefusal  the persona may not have the data; the case passes when nothing forbidden leaks. Such a case
 *                       expects no tool: the question context lists the tools the user may use, so the model need not
 *                       call the refused one to learn that it is refused (its oracle still does, so the harness checks
 *                       what that tool would have shown)
 * @param invariantQuery SQL returning one value that must be the same before and after: the assistant changed nothing
 * @param expectRetrieved anchors the rule search must return among its five results: recall@5 (design §10.2)
 * @param expectNoPassage no rule or flow answers the question: the answer must cite none, and the search should find
 *                       nothing by meaning ({@code RetrievalEval})
 * @param expectNoData   the true answer is "none" (no such record, no tool for it, not allowed): the answer and its
 *                       tables may write no code the question did not, which is where a weak model invents one
 * @param earlier        questions asked before this one in the same conversation, oldest first
 * @param oracle         what the scripted oracle model calls in the harness for the last question, one step each:
 *                       an object for one step, an array for a chain; empty when it calls nothing. A step's
 *                       {@code together} are calls made in the same step, which the loop runs at once
 * @param smoke          one of the few cases a run on a free model quota starts with (design §10.3)
 */
public record GoldenCase(String id, String useCase, String variant, Persona persona, String question,
                         List<String> expectTools, List<String> forbiddenTools, boolean expectNoTools,
                         List<String> facts, String factsQuery, List<String> forbidden, String forbiddenQuery,
                         boolean expectRefusal, String invariantQuery, List<String> expectRetrieved,
                         boolean expectNoPassage, boolean expectNoData, List<Earlier> earlier, List<Oracle> oracle,
                         boolean smoke) {

    public GoldenCase {
        expectTools = expectTools == null ? List.of() : List.copyOf(expectTools);
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        facts = facts == null ? List.of() : List.copyOf(facts);
        forbidden = forbidden == null ? List.of() : List.copyOf(forbidden);
        expectRetrieved = expectRetrieved == null ? List.of() : List.copyOf(expectRetrieved);
        earlier = earlier == null ? List.of() : List.copyOf(earlier);
        oracle = oracle == null ? List.of() : List.copyOf(oracle);
    }

    /** A case whose answer is "none" is the rare one; every case written before the field was not. */
    public GoldenCase(String id, String useCase, String variant, Persona persona, String question,
                      List<String> expectTools, List<String> forbiddenTools, boolean expectNoTools,
                      List<String> facts, String factsQuery, List<String> forbidden, String forbiddenQuery,
                      boolean expectRefusal, String invariantQuery, List<String> expectRetrieved,
                      boolean expectNoPassage, List<Earlier> earlier, List<Oracle> oracle, boolean smoke) {
        this(id, useCase, variant, persona, question, expectTools, forbiddenTools, expectNoTools, facts, factsQuery,
                forbidden, forbiddenQuery, expectRefusal, invariantQuery, expectRetrieved, expectNoPassage, false,
                earlier, oracle, smoke);
    }

    /** A case no rule or flow answers is the rare one; every case written before the field was not. */
    public GoldenCase(String id, String useCase, String variant, Persona persona, String question,
                      List<String> expectTools, List<String> forbiddenTools, boolean expectNoTools,
                      List<String> facts, String factsQuery, List<String> forbidden, String forbiddenQuery,
                      boolean expectRefusal, String invariantQuery, List<String> expectRetrieved,
                      List<Earlier> earlier, List<Oracle> oracle, boolean smoke) {
        this(id, useCase, variant, persona, question, expectTools, forbiddenTools, expectNoTools, facts, factsQuery,
                forbidden, forbiddenQuery, expectRefusal, invariantQuery, expectRetrieved, false, earlier, oracle,
                smoke);
    }

    /** Who asks: a role, and the warehouses they are assigned to (none: not limited by warehouse). */
    public record Persona(String role, List<String> warehouses) {

        public Persona {
            warehouses = warehouses == null ? List.of() : List.copyOf(warehouses);
        }
    }

    /** A question asked before the case's own, with the calls the oracle answers it with. */
    public record Earlier(String question, List<Oracle> oracle) {

        public Earlier {
            oracle = oracle == null ? List.of() : List.copyOf(oracle);
        }
    }

    /**
     * One step of the harness's scripted model: a tool call, and the calls it makes {@code together} with it in the
     * same reply.
     */
    public record Oracle(String tool, Map<String, Object> arguments, List<Oracle> together) {

        public Oracle {
            together = together == null ? List.of() : List.copyOf(together);
        }

        public Oracle(String tool, Map<String, Object> arguments) {
            this(tool, arguments, List.of());
        }
    }

    /** This case with every placeholder replaced. */
    GoldenCase resolve(Map<String, String> values) {
        return new GoldenCase(id, useCase, variant,
                new Persona(persona.role(), persona.warehouses().stream().map(w -> fill(w, values)).toList()),
                fill(question, values), expectTools, forbiddenTools, expectNoTools,
                facts.stream().map(f -> fill(f, values)).toList(), fill(factsQuery, values),
                forbidden.stream().map(f -> fill(f, values)).toList(), fill(forbiddenQuery, values),
                expectRefusal, fill(invariantQuery, values), expectRetrieved, expectNoPassage, expectNoData,
                earlier.stream().map(turn -> new Earlier(fill(turn.question(), values), fill(turn.oracle(), values)))
                        .toList(),
                fill(oracle, values), smoke);
    }

    private static List<Oracle> fill(List<Oracle> steps, Map<String, String> values) {
        return steps.stream().map(step -> new Oracle(step.tool(), step.arguments() == null ? Map.of()
                : step.arguments().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue() instanceof String s ? fill(s, values) : e.getValue())),
                fill(step.together(), values))).toList();
    }

    private static String fill(String text, Map<String, String> values) {
        if (text == null) {
            return null;
        }
        String filled = text;
        for (Map.Entry<String, String> value : values.entrySet()) {
            filled = filled.replace("{" + value.getKey() + "}", value.getValue());
        }
        return filled;
    }
}
