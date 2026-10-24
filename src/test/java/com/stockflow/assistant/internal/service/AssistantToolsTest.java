package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Design §3.2: every way a tool call can end becomes a result the model can explain, never an abandoned answer. */
class AssistantToolsTest {

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
    @DisplayName("the tool list is sorted by name, so it is byte-identical on every call; names are unique")
    void specs() {
        AssistantTools tools = new AssistantTools(List.of(tool("zeta", a -> 1), tool("alpha", a -> 1)),
                new ObjectMapper());
        assertThat(tools.specs()).extracting(ToolSpec::name).containsExactly("alpha", "zeta");
        assertThatThrownBy(() -> new AssistantTools(List.of(tool("same", a -> 1), tool("same", a -> 2)),
                new ObjectMapper())).isInstanceOf(IllegalStateException.class);
    }
}
