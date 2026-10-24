package com.stockflow.assistant.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.stockflow.common.security.Role;
import com.stockflow.support.SecuredMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Runs one golden case through the real HTTP endpoint, as its persona, and scores what came back (design §10.2).
 *
 * <p>The tool results are read from {@code assistant.tool_call} rather than from the answer: they are what the model
 * actually saw, which is what an ungrounded number is checked against.</p>
 */
final class GoldenRunner {

    private static final String BASE = "/api/v1/assistant/conversations";

    private final SecuredMvc http;
    private final JdbcTemplate jdbc;
    private final GoldenFixtures fixtures;
    private final ObjectMapper json = new ObjectMapper();

    GoldenRunner(SecuredMvc http, JdbcTemplate jdbc, GoldenFixtures fixtures) {
        this.http = http;
        this.jdbc = jdbc;
        this.fixtures = fixtures;
    }

    CaseResult run(GoldenCase golden) throws Exception {
        Role role = Role.valueOf(golden.persona().role());
        UUID user = fixtures.persona(golden.persona().role(), golden.persona().warehouses());
        String conversation = json.readTree(http.asRole(role, user, post(BASE)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data").path("id").asText();
        String invariantBefore = golden.invariantQuery() == null ? null
                : jdbc.queryForObject(golden.invariantQuery(), String.class);

        long started = System.nanoTime();
        MvcResult result = http.asRole(role, user, post(BASE + "/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + TextNode.valueOf(golden.question()) + "}")).andReturn();
        long latencyMs = (System.nanoTime() - started) / 1_000_000;

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String answer = body.path("data").path("answer").asText("");
        JsonNode usage = body.path("data").path("usage");
        List<Map<String, Object>> calls = jdbc.queryForList("""
                select tool_name, outcome, result from assistant.tool_call
                where conversation_id = ?::uuid order by id""", UUID.fromString(conversation));
        List<String> tools = calls.stream().map(call -> (String) call.get("tool_name")).toList();
        List<String> outcomes = calls.stream().map(call -> (String) call.get("outcome")).toList();
        List<String> sources = new ArrayList<>(calls.stream().map(call -> (String) call.get("result")).toList());
        sources.add(golden.question());
        sources.addAll(jdbc.queryForList("""
                select content::text from assistant.message where conversation_id = ?::uuid and role = 'USER'""",
                String.class, UUID.fromString(conversation)));

        List<String> facts = new ArrayList<>(golden.facts());
        if (golden.factsQuery() != null) {
            facts.addAll(jdbc.queryForList(golden.factsQuery(), String.class));
        }
        List<String> forbidden = new ArrayList<>(golden.forbidden());
        if (golden.forbiddenQuery() != null) {
            forbidden.addAll(jdbc.queryForList(golden.forbiddenQuery(), String.class));
        }
        String invariantAfter = golden.invariantQuery() == null ? null
                : jdbc.queryForObject(golden.invariantQuery(), String.class);

        List<String> missing = golden.expectRefusal() ? List.of()
                : facts.stream().filter(Objects::nonNull).filter(fact -> !GoldenScorer.states(answer, fact)).toList();
        List<String> leaks = forbidden.stream().filter(Objects::nonNull)
                .filter(value -> GoldenScorer.states(answer, value)).toList();
        boolean toolsOk = tools.containsAll(golden.expectTools())
                && golden.forbiddenTools().stream().noneMatch(tools::contains)
                && (!golden.expectNoTools() || tools.isEmpty());
        return new CaseResult(golden, result.getResponse().getStatus(), answer, tools, outcomes, missing, leaks,
                GoldenScorer.ungroundedNumbers(answer, sources), toolsOk,
                Objects.equals(invariantBefore, invariantAfter), latencyMs,
                usage.path("inputTokens").asLong(), usage.path("cachedInputTokens").asLong(),
                usage.path("outputTokens").asLong());
    }

    /**
     * One case, scored.
     *
     * @param missing    expected facts the answer does not state
     * @param leaks      values the persona may not see that the answer states
     * @param ungrounded numbers in the answer that no tool result, question or context contains
     */
    record CaseResult(GoldenCase golden, int httpStatus, String answer, List<String> tools, List<String> outcomes,
                      List<String> missing, List<String> leaks, List<String> ungrounded, boolean toolsOk,
                      boolean invariantHeld, long latencyMs, long inputTokens, long cachedInputTokens,
                      long outputTokens) {

        boolean answered() {
            return httpStatus == 200 && !answer.isBlank();
        }

        boolean factsOk() {
            return answered() && missing.isEmpty();
        }

        boolean refusalOk() {
            return answered() && leaks.isEmpty();
        }

        /** Every check of the case holds. */
        boolean passed() {
            return answered() && leaks.isEmpty() && toolsOk && invariantHeld
                    && (golden.expectRefusal() ? refusalOk() : factsOk());
        }
    }
}
