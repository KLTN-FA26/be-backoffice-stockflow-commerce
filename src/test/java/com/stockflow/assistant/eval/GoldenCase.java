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
 * @param useCase        U1..U8 of the design, or S (stock), M (must not do)
 * @param variant        how the question is phrased: formal, casual, no-diacritics, typo, refusal, ...
 * @param expectTools    tools the assistant must call (a subset of what it calls)
 * @param forbiddenTools tools it must not call (an injected instruction that names one, for example)
 * @param expectNoTools  the question needs no data, so no tool may be called
 * @param facts          values the answer must contain
 * @param factsQuery     SQL whose single column gives more values the answer must contain
 * @param forbidden      values the answer must never contain: data this persona may not see
 * @param forbiddenQuery SQL whose single column gives more such values
 * @param expectRefusal  the persona may not have the data; the case passes when nothing forbidden leaks
 * @param invariantQuery SQL returning one value that must be the same before and after: the assistant changed nothing
 * @param expectRetrieved anchors the rule search must return among its five results: recall@5 (design §10.2)
 * @param oracle         what the scripted oracle model calls in the harness; null when it calls nothing
 */
public record GoldenCase(String id, String useCase, String variant, Persona persona, String question,
                         List<String> expectTools, List<String> forbiddenTools, boolean expectNoTools,
                         List<String> facts, String factsQuery, List<String> forbidden, String forbiddenQuery,
                         boolean expectRefusal, String invariantQuery, List<String> expectRetrieved, Oracle oracle) {

    public GoldenCase {
        expectTools = expectTools == null ? List.of() : List.copyOf(expectTools);
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        facts = facts == null ? List.of() : List.copyOf(facts);
        forbidden = forbidden == null ? List.of() : List.copyOf(forbidden);
        expectRetrieved = expectRetrieved == null ? List.of() : List.copyOf(expectRetrieved);
    }

    /** Who asks: a role, and the warehouses they are assigned to (none: not limited by warehouse). */
    public record Persona(String role, List<String> warehouses) {

        public Persona {
            warehouses = warehouses == null ? List.of() : List.copyOf(warehouses);
        }
    }

    /** The tool call the harness's scripted model makes for this case. */
    public record Oracle(String tool, Map<String, Object> arguments) {
    }

    /** This case with every placeholder replaced. */
    GoldenCase resolve(Map<String, String> values) {
        return new GoldenCase(id, useCase, variant,
                new Persona(persona.role(), persona.warehouses().stream().map(w -> fill(w, values)).toList()),
                fill(question, values), expectTools, forbiddenTools, expectNoTools,
                facts.stream().map(f -> fill(f, values)).toList(), fill(factsQuery, values),
                forbidden.stream().map(f -> fill(f, values)).toList(), fill(forbiddenQuery, values),
                expectRefusal, fill(invariantQuery, values), expectRetrieved,
                oracle == null ? null : new Oracle(oracle.tool(),
                oracle.arguments() == null ? Map.of() : oracle.arguments().entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                e -> e.getValue() instanceof String s ? fill(s, values) : e.getValue()))));
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
