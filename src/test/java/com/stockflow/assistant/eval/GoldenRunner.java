package com.stockflow.assistant.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.stockflow.assistant.internal.domain.PendingMessage;
import com.stockflow.assistant.internal.service.AnswerNumbers;
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
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Runs one golden case through the real HTTP endpoint, as its persona, and scores what came back (design §10.2).
 *
 * <p>The tool results are read from {@code assistant.tool_call} rather than from the answer: they are what the model
 * actually saw, which is what an ungrounded number is checked against.</p>
 *
 * <p>A case with earlier questions asks them first, in the same conversation. The tools, recall and facts of the case
 * are those of its last question; numbers may be grounded in any result of the conversation, and a leak counts in
 * any answer.</p>
 *
 * <p><b>Facts and leaks are read from what the panel shows</b>: the answer's text and the cells of the tables under
 * it (design §7). The prompt tells the model not to copy the rows a table already shows, so a fact in a table is
 * stated to the user as surely as one in the text, and a forbidden value in a cell is as much a leak. Ungrounded
 * numbers are still checked in the text alone: the cells are the tool results themselves.</p>
 */
final class GoldenRunner {

    private static final String BASE = "/api/v1/assistant/conversations";

    /** A turn the application wrote, not a request to the provider. */
    private static final String CLOSING_TURN_MODEL = PendingMessage.CLOSING_MODEL;

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
        long[] tokens = new long[3];
        MvcResult opened = http.asRole(role, user, post(BASE)).andReturn();
        JsonNode created = json.readTree(opened.getResponse().getContentAsString(StandardCharsets.UTF_8));
        if (!created.path("data").path("id").isTextual()) {
            return CaseResult.unanswered(golden, opened.getResponse().getStatus(), errorCode(created), List.of(), 0,
                    tokens);
        }
        UUID conversation = UUID.fromString(created.path("data").path("id").asText());
        String invariantBefore = golden.invariantQuery() == null ? null
                : jdbc.queryForObject(golden.invariantQuery(), String.class);

        List<String> earlierAnswers = new ArrayList<>();
        List<String> shownAnswers = new ArrayList<>();
        for (GoldenCase.Earlier earlier : golden.earlier()) {
            Reply reply = ask(role, user, conversation, earlier.question(), tokens);
            if (reply.status() != 200) {
                return CaseResult.unanswered(golden, reply.status(), reply.errorCode(), earlierAnswers,
                        modelRequests(conversation), tokens);
            }
            earlierAnswers.add(reply.answer());
            shownAnswers.add(reply.shown());
        }
        long started = System.nanoTime();
        Reply reply = ask(role, user, conversation, golden.question(), tokens);
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        String answer = reply.answer();
        shownAnswers.add(reply.shown());

        // Null when the question was refused before it was stored (the assistant off, the daily cap).
        Integer stored = jdbc.queryForObject("""
                select max(sequence) from assistant.message where conversation_id = ?::uuid and role = 'USER'""",
                Integer.class, conversation);
        int lastQuestion = stored == null ? -1 : stored;
        List<Map<String, Object>> everyCall = jdbc.queryForList("""
                select t.tool_name, t.outcome, t.result, m.sequence from assistant.tool_call t
                join assistant.message m on m.id = t.message_id
                where t.conversation_id = ?::uuid order by m.sequence, t.id""", conversation);
        List<Map<String, Object>> calls = everyCall.stream()
                .filter(call -> ((Number) call.get("sequence")).intValue() > lastQuestion).toList();
        List<String> tools = calls.stream().map(call -> (String) call.get("tool_name")).toList();
        List<String> outcomes = calls.stream().map(call -> (String) call.get("outcome")).toList();
        List<String> sources = new ArrayList<>(everyCall.stream().filter(call -> "OK".equals(call.get("outcome")))
                .map(call -> (String) call.get("result")).toList());
        List<String> errors = everyCall.stream().filter(call -> !"OK".equals(call.get("outcome")))
                .map(call -> (String) call.get("result")).toList();
        sources.add(golden.question());
        sources.addAll(jdbc.queryForList("""
                select content::text from assistant.message where conversation_id = ?::uuid and role = 'USER'""",
                String.class, conversation));
        AnswerNumbers.Grounds grounds = GoldenScorer.grounds(sources, errors);

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
                : facts.stream().filter(Objects::nonNull).filter(fact -> !GoldenScorer.states(reply.shown(), fact))
                .toList();
        List<String> leaks = forbidden.stream().filter(Objects::nonNull)
                .filter(value -> shownAnswers.stream().anyMatch(each -> GoldenScorer.states(each, value))).toList();
        boolean toolsOk = tools.containsAll(golden.expectTools())
                && golden.forbiddenTools().stream().noneMatch(tools::contains)
                && (!golden.expectNoTools() || tools.isEmpty());
        List<String> retrieved = retrieved(calls);
        // Supported: the rule id is in what the searches of this conversation returned - as a passage's anchor, or
        // quoted in its text.
        String searched = everyCall.stream().filter(call -> "searchKnowledge".equals(call.get("tool_name")))
                .map(call -> (String) call.get("result")).collect(java.util.stream.Collectors.joining("\n"))
                .toUpperCase(java.util.Locale.ROOT);
        List<String> unsupported = GoldenScorer.namedRules(answer).stream()
                .filter(anchor -> !searched.contains(anchor)).toList();
        // Where the true answer is "none", any code the question did not write was made up: a tool that found
        // nothing gives the answer none to repeat.
        List<String> invented = golden.expectNoData()
                ? GoldenScorer.ungroundedCodes(reply.shown(), List.of(golden.question())) : List.of();
        return new CaseResult(golden, reply.status(), reply.errorCode(), answer, earlierAnswers, tools, outcomes,
                missing, leaks, GoldenScorer.ungroundedNumbers(answer, grounds),
                GoldenScorer.ungroundedCodes(answer, grounds), toolsOk,
                Objects.equals(invariantBefore, invariantAfter), retrieved, unsupported,
                retrieved.containsAll(golden.expectRetrieved()), GoldenScorer.copiesAnExample(reply.shown()),
                invented, latencyMs, modelRequests(conversation), tokens[0], tokens[1], tokens[2]);
    }

    /** One question, adding its tokens to {@code tokens} (input, cached input, output). */
    private Reply ask(Role role, UUID user, UUID conversation, String question, long[] tokens) throws Exception {
        MvcResult result = http.asRole(role, user, post(BASE + "/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + TextNode.valueOf(question) + "}")).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode usage = body.path("data").path("usage");
        tokens[0] += usage.path("inputTokens").asLong();
        tokens[1] += usage.path("cachedInputTokens").asLong();
        tokens[2] += usage.path("outputTokens").asLong();
        return new Reply(result.getResponse().getStatus(), errorCode(body),
                body.path("data").path("answer").asText(""), shown(body.path("data")));
    }

    /** What the panel shows of an answer: its text, then every cell of the tables under it, one per line. */
    private static String shown(JsonNode data) {
        StringBuilder shown = new StringBuilder(data.path("answer").asText(""));
        for (JsonNode table : data.path("tables")) {
            for (JsonNode row : table.path("rows")) {
                row.forEach(cell -> shown.append('\n').append(cell.asText()));
            }
        }
        return shown.toString();
    }

    /** @param shown the answer and the cells of its tables ({@link #shown}) */
    private record Reply(int status, String errorCode, String answer, String shown) {
    }

    private static String errorCode(JsonNode body) {
        return body.path("errorCode").isTextual() ? body.path("errorCode").asText() : null;
    }

    /** Requests the case made to the model provider: every model turn but the ones the application closed itself. */
    private int modelRequests(UUID conversation) {
        return jdbc.queryForObject("""
                select count(*) from assistant.message
                where conversation_id = ?::uuid and role = 'ASSISTANT' and model <> ?""",
                Integer.class, conversation, CLOSING_TURN_MODEL);
    }

    /** Anchors the rule searches of this answer returned, in order. */
    private List<String> retrieved(List<Map<String, Object>> calls) throws Exception {
        List<String> anchors = new ArrayList<>();
        for (Map<String, Object> call : calls) {
            if ("searchKnowledge".equals(call.get("tool_name")) && "OK".equals(call.get("outcome"))) {
                for (JsonNode passage : json.readTree((String) call.get("result")).path("passages")) {
                    anchors.add(passage.path("anchor").asText());
                }
            }
        }
        return anchors;
    }

    /**
     * One case, scored.
     *
     * @param errorCode      the error the endpoint answered with; null for an answer
     * @param earlierAnswers what the case's earlier questions were answered, in order
     * @param tools          tools called for the last question
     * @param missing        expected facts the answer does not state
     * @param leaks          values the persona may not see that an answer states
     * @param ungrounded     numbers in the answer that no tool result, question or context contains
     * @param ungroundedCodes codes in the answer that no tool result, question or context writes
     * @param retrieved      anchors the rule searches of the last question returned
     * @param unsupported    rule ids the answer names that no search returned: a citation from memory
     * @param recallOk       every anchor the case expects was among those retrieved
     * @param copiedExample  the answer writes a placeholder of the system prompt's examples
     * @param invented       for a case whose answer is "none", codes the answer writes that the question did not
     * @param latencyMs      the last question's
     * @param modelRequests  requests to the model provider over the whole case: what it cost of a daily quota
     */
    record CaseResult(GoldenCase golden, int httpStatus, String errorCode, String answer, List<String> earlierAnswers,
                      List<String> tools, List<String> outcomes, List<String> missing, List<String> leaks,
                      List<String> ungrounded, List<String> ungroundedCodes, boolean toolsOk,
                      boolean invariantHeld, List<String> retrieved, List<String> unsupported, boolean recallOk,
                      boolean copiedExample, List<String> invented, long latencyMs, int modelRequests,
                      long inputTokens, long cachedInputTokens, long outputTokens) {

        /**
         * Statuses that say nothing about the assistant: the provider's quota or an outage, a busy conversation, a
         * rate limit. The same set the idempotency filter does not store, for the same reason: such an outcome is
         * asked again, not recorded.
         */
        private static final Set<Integer> UNSETTLED = Set.of(408, 409, 423, 425, 429);

        CaseResult {
            answer = answer == null ? "" : answer;
            earlierAnswers = earlierAnswers == null ? List.of() : List.copyOf(earlierAnswers);
            tools = tools == null ? List.of() : List.copyOf(tools);
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
            missing = missing == null ? List.of() : List.copyOf(missing);
            leaks = leaks == null ? List.of() : List.copyOf(leaks);
            ungrounded = ungrounded == null ? List.of() : List.copyOf(ungrounded);
            ungroundedCodes = ungroundedCodes == null ? List.of() : List.copyOf(ungroundedCodes);
            invented = invented == null ? List.of() : List.copyOf(invented);
            retrieved = retrieved == null ? List.of() : List.copyOf(retrieved);
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }

        /** A result scored before codes, copied examples and "none" answers were checked: none of them found. */
        CaseResult(GoldenCase golden, int httpStatus, String errorCode, String answer, List<String> earlierAnswers,
                   List<String> tools, List<String> outcomes, List<String> missing, List<String> leaks,
                   List<String> ungrounded, boolean toolsOk, boolean invariantHeld, List<String> retrieved,
                   List<String> unsupported, boolean recallOk, long latencyMs, int modelRequests,
                   long inputTokens, long cachedInputTokens, long outputTokens) {
            this(golden, httpStatus, errorCode, answer, earlierAnswers, tools, outcomes, missing, leaks, ungrounded,
                    List.of(), toolsOk, invariantHeld, retrieved, unsupported, recallOk, false, List.of(), latencyMs,
                    modelRequests, inputTokens, cachedInputTokens, outputTokens);
        }

        /** An earlier question of the case got no answer, so its own question was never asked. */
        static CaseResult unanswered(GoldenCase golden, int httpStatus, String errorCode, List<String> earlierAnswers,
                                     int modelRequests, long[] tokens) {
            return new CaseResult(golden, httpStatus, errorCode, "", earlierAnswers, List.of(), List.of(), List.of(),
                    List.of(), List.of(), false, true, List.of(), List.of(), golden.expectRetrieved().isEmpty(), 0,
                    modelRequests, tokens[0], tokens[1], tokens[2]);
        }

        /** A result about the assistant, to be scored and kept; otherwise the case is to be asked again. */
        boolean settled() {
            return httpStatus < 500 && !UNSETTLED.contains(httpStatus);
        }

        boolean answered() {
            return httpStatus == 200 && !answer.isBlank();
        }

        boolean factsOk() {
            return answered() && missing.isEmpty();
        }

        boolean refusalOk() {
            return answered() && leaks.isEmpty();
        }

        /**
         * A question no rule answers is answered citing none: no rule or flow code, and no passage it was shown
         * (a state machine's section, a term), made to fit.
         */
        boolean noPassageOk() {
            String said = answer.toLowerCase(java.util.Locale.ROOT);
            return !golden.expectNoPassage() || GoldenScorer.namedRules(answer).isEmpty()
                    && retrieved.stream().noneMatch(anchor -> said.contains(anchor.toLowerCase(java.util.Locale.ROOT)));
        }

        /**
         * The answer states data — a number or a code nothing holds — without a single lookup that returned data for
         * its question (a refused call, or a search of the rules, looks nothing up): the
         * fabrication the system prompt forbids, which the P9 report must show at zero.
         */
        boolean fabricated() {
            return answered() && !lookedUp() && (!ungrounded.isEmpty() || !ungroundedCodes.isEmpty());
        }

        /** A call for the last question returned data: it ran, and it was not a search of the rules. */
        boolean lookedUp() {
            for (int i = 0; i < tools.size() && i < outcomes.size(); i++) {
                if ("OK".equals(outcomes.get(i)) && !"searchKnowledge".equals(tools.get(i))) {
                    return true;
                }
            }
            return false;
        }

        /** A question whose true answer is "none" was answered without a code it did not ask about. */
        boolean noDataOk() {
            return !golden.expectNoData() || invented.isEmpty();
        }

        /** Every check of the case holds. */
        boolean passed() {
            return answered() && leaks.isEmpty() && toolsOk && invariantHeld && unsupported.isEmpty() && recallOk
                    && noPassageOk() && noDataOk() && !fabricated() && !copiedExample
                    && (golden.expectRefusal() ? refusalOk() : factsOk());
        }
    }
}
