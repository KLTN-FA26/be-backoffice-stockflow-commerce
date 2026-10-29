package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every {@link AssistantTool}, and the one place a tool call is carried out (design §3.2).
 *
 * <p><b>Through the proxy.</b> The tools are injected as Spring beans, so {@code tool.run(...)} below goes through
 * each tool's proxy and its {@code @RequiresPermission} is enforced — calling a tool any other way would skip the
 * permission check and the data scope.</p>
 *
 * <p><b>Failures become results.</b> A denied, missing or invalid request is something the model explains to the
 * user, so it comes back as an error result with a code, never as an exception that abandons the answer. An
 * unexpected failure is logged with the correlation id and reaches the model only as {@code TOOL_FAILED}: its message
 * may carry paths or upstream names, the same rule {@code GlobalExceptionHandler} applies to clients.</p>
 *
 * <p><b>Times in Vietnam time.</b> Every {@code Instant} in an answer is written as a clock in Vietnam shows it, with
 * the offset ({@code 2026-10-28T00:30:12+07:00}), never in UTC ({@code 2026-10-27T17:30:12Z}). The model is told
 * the business runs on Vietnam time and reads a UTC time as local: seven hours early, and an order the 00:30
 * settlement completed shown as completed the day before. Converting here, once for every tool, means no tool
 * converts on its own and a new one cannot forget to.</p>
 */
@Component
public class AssistantTools {

    private static final Logger log = LoggerFactory.getLogger(AssistantTools.class);

    private final Map<String, AssistantTool> byName;
    private final List<ToolSpec> specs;
    private final ObjectMapper json;
    private final ObjectMapper results;

    AssistantTools(List<AssistantTool> tools, ObjectMapper json) {
        this.byName = tools.stream().collect(Collectors.toMap(tool -> tool.spec().name(), Function.identity(),
                (first, second) -> {
                    throw new IllegalStateException("two tools are named " + first.spec().name());
                }));
        // Sorted, so the tool list is byte-identical on every call and every start: the provider caches it.
        this.specs = tools.stream().map(AssistantTool::spec).sorted(Comparator.comparing(ToolSpec::name)).toList();
        this.json = json;
        this.results = resultMapper(json);
    }

    /**
     * What answers are written with: a copy of the application's mapper, so its settings still hold (ISO dates, absent
     * nulls) and the mapper every endpoint uses is left as it is, plus {@link VietnamTimeSerializer} for every
     * {@code Instant}.
     */
    private static ObjectMapper resultMapper(ObjectMapper json) {
        return json.copy().registerModule(new SimpleModule("assistant-tool-results")
                .addSerializer(Instant.class, new VietnamTimeSerializer()));
    }

    public List<ToolSpec> specs() {
        return specs;
    }

    public List<AssistantTool> all() {
        return List.copyOf(byName.values());
    }

    public ToolExecution execute(ToolCall call) {
        long started = System.nanoTime();
        AssistantTool tool = byName.get(call.name());
        if (tool == null) {
            return done(call, ToolOutcome.INVALID, "UNKNOWN_TOOL: there is no tool named " + call.name(), started);
        }
        try {
            Object answer = tool.run(ToolArguments.parse(json, call.arguments()));
            String result = results.writeValueAsString(answer);
            if (result.length() > ToolExecution.MAX_RESULT_LENGTH) {
                return done(call, ToolOutcome.INVALID, "RESULT_TOO_LARGE: the answer is too long to return; ask "
                        + "again more narrowly (fewer rows, one SKU, one warehouse)", started);
            }
            return done(call, ToolOutcome.OK, result, started);
        } catch (BusinessException refused) {
            return done(call, outcome(refused), explain(refused), started);
        } catch (IllegalArgumentException invalid) {
            // Value objects (a SKU, a code) reject malformed input this way; it is the model's to correct.
            return done(call, ToolOutcome.INVALID, "VALIDATION_FAILED: " + invalid.getMessage(), started);
        } catch (JsonProcessingException | RuntimeException failure) {
            log.error("Assistant tool {} failed (correlation id {})", call.name(),
                    MDC.get(CorrelationIdFilter.MDC_KEY), failure);
            return done(call, ToolOutcome.FAILED, "TOOL_FAILED: the tool could not answer; the error is logged",
                    started);
        }
    }

    private static ToolOutcome outcome(BusinessException refused) {
        ErrorCode code = refused.errorCode();
        if (code == ErrorCode.FORBIDDEN || code == ErrorCode.OUT_OF_DATA_SCOPE || code == ErrorCode.UNAUTHORIZED) {
            return ToolOutcome.DENIED;
        }
        if (code.httpStatus() == 404) {
            return ToolOutcome.NOT_FOUND;
        }
        return code.isServerFault() ? ToolOutcome.FAILED : ToolOutcome.INVALID;
    }

    private static String explain(BusinessException refused) {
        ErrorCode code = refused.errorCode();
        return switch (outcome(refused)) {
            // "Missing permission: inventory-alerts:READ" - the permission is what the model must name to the user.
            case DENIED -> "NOT_PERMITTED: " + (code == ErrorCode.FORBIDDEN ? refused.getMessage()
                    : "the data is outside the warehouses or records this user may see");
            case NOT_FOUND -> "NOT_FOUND: " + refused.getMessage();
            case FAILED -> {
                log.error("Assistant tool failed with {} (correlation id {})", code,
                        MDC.get(CorrelationIdFilter.MDC_KEY), refused);
                yield "TOOL_FAILED: the tool could not answer; the error is logged";
            }
            default -> code.name() + ": " + refused.getMessage();
        };
    }

    private static ToolExecution done(ToolCall call, ToolOutcome outcome, String result, long started) {
        return new ToolExecution(call, outcome, result, (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * An instant as {@link BusinessDate#dateTime}, to the second. The offset keeps it one exact instant that
     * {@code Instant.parse} reads back ({@link AnswerSources} does, for proposeReorder's {@code generatedAt}); the
     * microseconds the database keeps are noise to a reader and cost tokens on every row.
     */
    private static final class VietnamTimeSerializer extends StdSerializer<Instant> {

        VietnamTimeSerializer() {
            super(Instant.class);
        }

        @Override
        public void serialize(Instant value, JsonGenerator out, SerializerProvider provider) throws IOException {
            out.writeString(BusinessDate.dateTime(value.truncatedTo(ChronoUnit.SECONDS))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
    }
}
