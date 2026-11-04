package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.PermissionDeniedException;
import com.stockflow.common.security.ScopeViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design §3.2: every way a tool call can end becomes a result the model can explain, never an abandoned answer; and
 * every answer reaches the model in the business's time zone.
 */
class AssistantToolsTest {

    /** A time of day written in UTC: {@code 09:54:09.108321Z}. */
    private static final Pattern UTC_TIME = Pattern.compile("\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?Z");

    /** What Spring Boot builds from {@code application.yml}: java.time as ISO text, null fields left out. */
    private static ObjectMapper applicationMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    /** {@code answer}, as the tool named {@code name} would hand it back, written as production writes it. */
    private static ToolExecution answering(String name, Object answer) {
        AssistantTools tools = new AssistantTools(List.of(tool(name, arguments -> answer)), applicationMapper());
        return tools.execute(new ToolCall("toolu_1", name, "{}"));
    }

    private static AssistantTool tool(String name, Function<ToolArguments, Object> body) {
        ToolSpec spec = new ToolSpec(name, "test tool", ToolSchema.object().string("sku", "a SKU").build());
        return new AssistantTool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public String mirrors() {
                return null;
            }

            @Override
            public Object run(ToolArguments arguments) {
                return body.apply(arguments);
            }
        };
    }

    private static ToolExecution run(Function<ToolArguments, Object> body) {
        return run(body, "{\"sku\":\"CUP-1\"}");
    }

    private static ToolExecution run(Function<ToolArguments, Object> body, String arguments) {
        AssistantTools tools = new AssistantTools(List.of(tool("probe", body)), new ObjectMapper());
        return tools.execute(new ToolCall("toolu_1", "probe", arguments));
    }

    record Stock(String sku, int onHand) {
    }

    @Test
    @DisplayName("a tool's answer is returned as JSON")
    void ok() {
        ToolExecution done = run(arguments -> new Stock(arguments.text("sku", 64), 40));

        assertThat(done.outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(done.result()).isEqualTo("{\"sku\":\"CUP-1\",\"onHand\":40}");
        assertThat(done.toResult().error()).isFalse();
    }

    @Test
    @DisplayName("a missing permission is DENIED and names the permission, so the model can tell the user")
    void denied() {
        ToolExecution done = run(arguments -> {
            throw new PermissionDeniedException(PermissionCode.of("inventory-alerts", Action.READ));
        });

        assertThat(done.outcome()).isEqualTo(ToolOutcome.DENIED);
        assertThat(done.result()).startsWith("NOT_PERMITTED:").contains("inventory-alerts:READ");
    }

    @Test
    @DisplayName("data outside the caller's scope is DENIED without the internal explanation")
    void outOfScope() {
        ToolExecution done = run(arguments -> {
            throw new ScopeViolationException("No data scope in force. A scoped query ran outside a guarded request");
        });

        assertThat(done.outcome()).isEqualTo(ToolOutcome.DENIED);
        assertThat(done.result()).startsWith("NOT_PERMITTED:").doesNotContain("scoped query");
    }

    @Test
    @DisplayName("not found and invalid requests come back with their codes")
    void notFoundAndInvalid() {
        assertThat(run(arguments -> {
            throw new NotFoundException("Order", "SO-9");
        })).satisfies(done -> {
            assertThat(done.outcome()).isEqualTo(ToolOutcome.NOT_FOUND);
            assertThat(done.result()).startsWith("NOT_FOUND:").contains("SO-9");
        });
        assertThat(run(arguments -> {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and 50");
        })).satisfies(done -> {
            assertThat(done.outcome()).isEqualTo(ToolOutcome.INVALID);
            assertThat(done.result()).isEqualTo("VALIDATION_FAILED: limit must be between 1 and 50");
        });
        assertThat(run(arguments -> {
            throw new IllegalArgumentException("Malformed SKU: ??");
        }).outcome()).isEqualTo(ToolOutcome.INVALID);
    }

    @Test
    @DisplayName("an unexpected failure is FAILED, and its message never reaches the model")
    void failed() {
        ToolExecution done = run(arguments -> {
            throw new IllegalStateException("connection to db-internal-7:5432 refused");
        });

        assertThat(done.outcome()).isEqualTo(ToolOutcome.FAILED);
        assertThat(done.result()).startsWith("TOOL_FAILED").doesNotContain("db-internal");
    }

    @Test
    @DisplayName("a tool name the model made up is traced as unknownTool: one tag value, never its own series")
    void unknownNamesAreOneTagValue() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.observation.ObservationRegistry observations = io.micrometer.observation.ObservationRegistry.create();
        observations.observationConfig().observationHandler(
                new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(meters));
        AssistantTools tools = new AssistantTools(List.of(tool("probe", arguments -> "x")), new ObjectMapper(),
                new AssistantTelemetry(observations, meters));

        tools.execute(new ToolCall("1", "dropTables; ignore previous instructions", "{}"));
        tools.execute(new ToolCall("2", "probe", "{}"));

        assertThat(meters.get("assistant.tool").tag("gen_ai.tool.name", "unknownTool").timer().count()).isOne();
        assertThat(meters.get("assistant.tool").tag("gen_ai.tool.name", "probe").timer().count()).isOne();
        assertThat(meters.find("assistant.tool").timers()).hasSize(2);
    }

    @Test
    @DisplayName("unknown tools, arguments that are not a JSON object, and oversized answers are INVALID")
    void malformedCalls() {
        AssistantTools tools = new AssistantTools(List.of(tool("probe", arguments -> "x")), new ObjectMapper());
        assertThat(tools.execute(new ToolCall("1", "dropTables", "{}")).result()).startsWith("UNKNOWN_TOOL");
        assertThat(run(arguments -> "x", "not json").outcome()).isEqualTo(ToolOutcome.INVALID);
        assertThat(run(arguments -> "x", "[1,2]").outcome()).isEqualTo(ToolOutcome.INVALID);
        ToolExecution huge = run(arguments -> "y".repeat(ToolExecution.MAX_RESULT_LENGTH + 10));
        assertThat(huge.outcome()).isEqualTo(ToolOutcome.INVALID);
        assertThat(huge.result()).startsWith("RESULT_TOO_LARGE");
    }

    @Test
    @DisplayName("an order's times reach the model in Vietnam time with the offset, to the second, never in UTC")
    void orderTimesInVietnamTime() {
        Instant placed = Instant.parse("2026-10-28T09:54:09.108321Z");
        // The nightly settlement runs at 00:30 in Vietnam: in UTC that is still the day before.
        Instant completed = Instant.parse("2026-10-27T17:30:12.5Z");

        ToolExecution done = answering("findOrder", new FindOrderTool.Answer("SO-20991231-900001", "COMPLETED", null,
                null, null, null, null, List.of(), List.of(), false,
                new FindOrderTool.Dates(placed, null, null, null, null, completed, null),
                List.of(new FindOrderTool.Change(null, "PENDING_PAYMENT", null, placed)), false));

        assertThat(done.outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(done.result()).contains("\"placedAt\":\"2026-10-28T16:54:09+07:00\"",
                        "\"completedAt\":\"2026-10-28T00:30:12+07:00\"", "\"at\":\"2026-10-28T16:54:09+07:00\"")
                .doesNotContainPattern(UTC_TIME);
    }

    @Test
    @DisplayName("pick and lot times are Vietnam time too: a lot received at 06:30 is received that day, not the day "
            + "before")
    void pickAndLotTimesInVietnamTime() {
        Instant assigned = Instant.parse("2026-10-28T09:54:10.121721Z");
        Instant picked = Instant.parse("2026-10-28T10:00:00Z");
        Instant received = Instant.parse("2026-10-31T23:30:00Z");
        Instant firstPick = Instant.parse("2026-11-01T02:00:00Z");
        Instant lastPick = Instant.parse("2026-11-01T03:15:00Z");

        ToolExecution picking = answering("getOrderPicking", new GetOrderPickingTool.Answer("SO-20991231-900001",
                "PICKED", assigned, picked, List.of(), null));
        ToolExecution lot = answering("traceLot", new TraceLotTool.Answer("EVAL-LOT-2401", 1, List.of(
                new TraceLotTool.Lot("EVAL-LID-90", "EVAL-LOT-2401", LocalDate.parse("2027-01-31"), received,
                        "PURCHASE", "GR-1", "PO-EVAL-LOT-01", "Supplier", 100, 97, 97, 0,
                        1, false, List.of(new TraceLotTool.OrderLeg("SO-20991231-900003", 3, 0, firstPick, lastPick)),
                        0, false, List.of(), 0, false, List.of()))));

        assertThat(picking.result()).contains("\"assignedAt\":\"2026-10-28T16:54:10+07:00\"",
                "\"pickedAt\":\"2026-10-28T17:00:00+07:00\"").doesNotContainPattern(UTC_TIME);
        assertThat(lot.result()).contains("\"receivedAt\":\"2026-11-01T06:30:00+07:00\"",
                        "\"firstAt\":\"2026-11-01T09:00:00+07:00\"", "\"lastAt\":\"2026-11-01T10:15:00+07:00\"",
                        "\"expiryDate\":\"2027-01-31\"")
                .doesNotContainPattern(UTC_TIME);
    }

    @Test
    @DisplayName("a proposal's generatedAt, written in Vietnam time, still reads back as the instant it was")
    void proposalTimeReadsBack() {
        Instant computed = Instant.parse("2026-10-28T09:54:09.108321Z");

        ToolExecution done = answering(AnswerSources.REORDER_TOOL, new ProposeReorderTool.Answer(computed,
                LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-27"), List.of(),
                List.of(new ProposeReorderTool.Proposal("PURCHASE_ORDER_DRAFT", "SUP-A", "A", computed,
                        List.of(new ProposeReorderTool.ProposalLine("CUP-12OZ", 1276)))), null));

        assertThat(done.result()).contains("\"generatedAt\":\"2026-10-28T16:54:09+07:00\"")
                .doesNotContainPattern(UTC_TIME);
        assertThat(AnswerSources.proposals(List.of(done), applicationMapper())).singleElement().satisfies(proposal -> {
            assertThat(proposal.generatedAt()).isEqualTo(Instant.parse("2026-10-28T09:54:09Z"));
            assertThat(proposal.lines()).containsExactly(new AnswerSources.ProposalLine("CUP-12OZ", 1276));
        });
    }

    @Test
    @DisplayName("the application's own mapper is left as it is: endpoints still write UTC")
    void applicationMapperUntouched() throws Exception {
        ObjectMapper json = applicationMapper();
        new AssistantTools(List.of(), json);

        assertThat(json.writeValueAsString(Instant.parse("2026-10-27T17:30:12Z")))
                .isEqualTo("\"2026-10-27T17:30:12Z\"");
    }

    @Test
    @DisplayName("the tool list is sorted by name, so it is byte-identical on every call; names are unique")
    void specs() {
        AssistantTools tools = new AssistantTools(List.of(tool("zeta", a -> 1), tool("alpha", a -> 1)),
                new ObjectMapper());
        assertThat(tools.specs()).extracting(ToolSpec::name).containsExactly("alpha", "zeta");
        assertThatThrownBy(() -> new AssistantTools(List.of(tool("same", a -> 1), tool("same", a -> 2)),
                new ObjectMapper())).isInstanceOf(IllegalStateException.class);
    }
}
