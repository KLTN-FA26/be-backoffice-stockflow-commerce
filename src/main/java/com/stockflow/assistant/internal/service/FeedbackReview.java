package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.assistant.internal.domain.AnswerFeedbackRepository;
import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.FeedbackVerdict;
import com.stockflow.assistant.internal.domain.MarkedAnswer;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The answers people marked wrong, for the System Admin to review and turn into golden-set cases (contract §5.1,
 * design §7 and §10.1; AQ-16).
 *
 * <p><b>Each one comes with a golden-set case to paste</b>, in the shape of {@code golden-set.json}: the question, the
 * persona (the rater's first role and their warehouses), the tools that answered (OK) as {@code expectTools}, and the
 * calls as the oracle's steps, calls asked for in the same model turn as one step's {@code together}. What only a
 * person can say is left for them: {@code useCase} is {@code TODO}, and {@code facts} is empty, because the facts a
 * right answer must hold are exactly what the marked answer got wrong. A verdict given before raters were recorded has
 * persona {@code TODO}.</p>
 *
 * <p><b>Text as stored, with the aliases the model saw</b> ({@code KH-1}, ADR-0018): the case is committed to the
 * repository, where a customer's real name has no business, and the fixtures of the golden set name their own
 * customers anyway. A question naming a customer the conversation had not met yet was stored as typed
 * ({@code CustomerPseudonyms}), and a comment always is: the reviewer reads both before committing a case. The answer
 * text, the question and the comment are other people's words: the endpoint is the System Admin's alone
 * ({@code assistant-feedback:READ}).</p>
 */
@Service
public class FeedbackReview {

    /** A page holds whole answers and their calls: smaller than the usual maximum. */
    public static final int MAX_PAGE_SIZE = 50;

    /** The period read when none is given: the last thirty days. */
    static final int DEFAULT_DAYS = 30;

    private final AnswerFeedbackRepository verdicts;
    private final ObjectMapper json;
    private final Clock clock;

    FeedbackReview(AnswerFeedbackRepository verdicts, ObjectMapper json, Clock clock) {
        this.verdicts = verdicts;
        this.json = json;
        this.clock = clock;
    }

    /** One marked answer, and the golden-set case it would make. */
    public record Review(MarkedAnswer answer, ObjectNode goldenCase) {
    }

    /**
     * The answers given {@code verdict} on the business days {@code from} to {@code to}, both included, latest verdict
     * first.
     *
     * @param from null for {@value #DEFAULT_DAYS} days before {@code to}
     * @param to   null for today
     * @throws BusinessException {@code VALIDATION_FAILED} when {@code to} is before {@code from};
     *                           {@code UNSUPPORTED_PARAMETER} for a page out of range or larger than
     *                           {@value #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true)
    public PageResponse<Review> marked(FeedbackVerdict verdict, LocalDate from, LocalDate to, int page, int size) {
        Pages.of(page, size);
        if (size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER,
                    "Page size must not exceed %d, got %d".formatted(MAX_PAGE_SIZE, size));
        }
        LocalDate last = to == null ? BusinessDate.of(clock.instant()) : to;
        LocalDate first = from == null ? last.minusDays(DEFAULT_DAYS - 1L) : from;
        if (last.isBefore(first)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "to " + last + " is before from " + first);
        }
        Instant start = first.atStartOfDay(BusinessDate.ZONE).toInstant();
        Instant end = last.plusDays(1).atStartOfDay(BusinessDate.ZONE).toInstant();
        return verdicts.marked(verdict, start, end, page, size).map(answer -> new Review(answer, goldenCase(answer)));
    }

    /** {@code answer} as a golden-set case to complete (class javadoc). */
    ObjectNode goldenCase(MarkedAnswer answer) {
        ObjectNode golden = json.createObjectNode();
        golden.put("id", "FB-" + answer.feedbackId().toString().replace("-", "").substring(24));
        golden.put("useCase", "TODO");
        golden.put("variant", "from-feedback");
        ObjectNode persona = golden.putObject("persona");
        MarkedAnswer.Rater rater = answer.rater();
        List<String> roles = rater == null ? List.of() : FeatureUse.roles(rater.roles());
        persona.put("role", roles.isEmpty() || FeatureUse.OTHER_ROLE.equals(roles.getFirst()) ? "TODO"
                : roles.getFirst());
        ArrayNode warehouses = persona.putArray("warehouses");
        if (rater != null) {
            rater.warehouses().forEach(warehouses::add);
        }
        golden.put("question", answer.question() == null ? "" : answer.question());
        Set<String> answered = new LinkedHashSet<>();
        answer.tools().stream().filter(tool -> tool.outcome() == ToolOutcome.OK)
                .forEach(tool -> answered.add(tool.tool()));
        if (answer.tools().isEmpty()) {
            golden.put("expectNoTools", true);
        } else {
            ArrayNode expect = golden.putArray("expectTools");
            answered.forEach(expect::add);
        }
        golden.putArray("facts");
        golden.set("oracle", oracle(answer.tools()));
        return golden;
    }

    /** The calls as the oracle's steps: one object for a single step, an array for a chain, empty for none. */
    private JsonNode oracle(List<MarkedAnswer.MarkedTool> tools) {
        Map<Integer, List<MarkedAnswer.MarkedTool>> steps = new LinkedHashMap<>();
        tools.forEach(tool -> steps.computeIfAbsent(tool.step(), step -> new ArrayList<>()).add(tool));
        ArrayNode chain = json.createArrayNode();
        for (List<MarkedAnswer.MarkedTool> step : steps.values()) {
            ObjectNode call = call(step.getFirst());
            if (step.size() > 1) {
                ArrayNode together = call.putArray("together");
                step.subList(1, step.size()).forEach(other -> together.add(call(other)));
            }
            chain.add(call);
        }
        return chain.size() == 1 ? chain.get(0) : chain;
    }

    private ObjectNode call(MarkedAnswer.MarkedTool tool) {
        ObjectNode call = json.createObjectNode();
        call.put("tool", tool.tool());
        call.set("arguments", arguments(tool.arguments()));
        return call;
    }

    private JsonNode arguments(String text) {
        if (text == null || text.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException unreadable) {
            return json.getNodeFactory().textNode(text);
        }
    }
}
