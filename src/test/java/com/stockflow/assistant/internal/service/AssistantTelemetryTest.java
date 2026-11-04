package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The assistant's spans and metrics (design §11): named after OpenTelemetry's generative-AI conventions, tagged with
 * outcomes and token counts, nested under the answer, and never carrying a question, a prompt or a tool's result.
 */
class AssistantTelemetryTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final List<Observation.Context> stopped = new ArrayList<>();
    private AssistantTelemetry telemetry;

    @BeforeEach
    void setUp() {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        telemetry = new AssistantTelemetry(observations, meters);
    }

    @Test
    @DisplayName("a model call is a chat span with its vendor, model, tokens and stop reason, inside the answer's")
    void chat() {
        LanguageModel.Reply reply = new LanguageModel.Reply(new Turn.ModelTurn("{}", "Còn 40 cái.", List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(1200, 30, 800, 0), "gemini-3.6-flash");

        telemetry.answer(ToolChannel.CHAT, () -> telemetry.chat("gemini-3.5-flash", "gemini", "gemini-3.5-flash",
                () -> reply));

        Observation.Context chat = stopped.getFirst();
        assertThat(chat.getContextualName()).isEqualTo("chat gemini-3.5-flash");
        assertThat(chat.getParentObservation().getContextView().getName()).isEqualTo("assistant.answer");
        assertThat(chat.getLowCardinalityKeyValue("gen_ai.provider.name").getValue()).isEqualTo("gemini");
        assertThat(chat.getLowCardinalityKeyValue("assistant.outcome").getValue()).isEqualTo("END_TURN");
        assertThat(chat.getHighCardinalityKeyValue("gen_ai.response.model").getValue()).isEqualTo("gemini-3.6-flash");
        assertThat(chat.getHighCardinalityKeyValue("gen_ai.usage.input_tokens").getValue()).isEqualTo("2000");
        assertThat(chat.getHighCardinalityKeyValue("gen_ai.usage.output_tokens").getValue()).isEqualTo("30");
        assertThat(meters.get("gen_ai.client.token.usage").tag("gen_ai.token.type", "input").summary().totalAmount())
                .isEqualTo(2000);
        Timer answers = meters.get("assistant.answer").tag("assistant.channel", "CHAT").timer();
        assertThat(answers.count()).isOne();
    }

    @Test
    @DisplayName("a failed call is tagged with its error code, and the error propagates")
    void failure() {
        assertThatThrownBy(() -> telemetry.chat("m", "openai", "qwen3:8b", () -> {
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE);
        })).isInstanceOf(BusinessException.class);

        assertThat(meters.get("gen_ai.client.operation").tag("assistant.outcome", "ASSISTANT_UNAVAILABLE").timer()
                .count()).isOne();
        assertThat(stopped.getFirst().getError()).as("the code alone reaches the span")
                .isInstanceOf(AssistantTelemetry.Failure.class).hasMessage("ASSISTANT_UNAVAILABLE");
    }

    @Test
    @DisplayName("a tool run is an execute_tool span with its channel and outcome; no argument or result travels")
    void tool() {
        ToolCall call = new ToolCall("call-1", "findOrder", "{\"orderNumber\":\"SO-1\",\"note\":\"secret note\"}");

        telemetry.tool(ToolChannel.MCP, "findOrder",
                () -> new ToolExecution(call, ToolOutcome.DENIED, "NOT_PERMITTED: Missing permission: x", 3));

        Observation.Context span = stopped.getFirst();
        assertThat(span.getContextualName()).isEqualTo("execute_tool findOrder");
        assertThat(span.getLowCardinalityKeyValue("assistant.channel").getValue()).isEqualTo("MCP");
        assertThat(span.getLowCardinalityKeyValue("assistant.outcome").getValue()).isEqualTo("DENIED");
        assertThat(span.getAllKeyValues().stream().map(keyValue -> keyValue.getValue()))
                .noneMatch(value -> value.contains("secret note") || value.contains("NOT_PERMITTED"));
        assertThat(meters.get("assistant.tool").tag("gen_ai.tool.name", "findOrder").timer().count()).isOne();
    }

    @Test
    @DisplayName("a failure reaches the span as its code alone: no message, no cause, no stack")
    void failureCarriesNoText() {
        assertThatThrownBy(() -> telemetry.answer(ToolChannel.CHAT, () -> {
            throw new IllegalStateException("Failing row contains (secret note)",
                    new RuntimeException("PSQL: arguments {\"note\":\"secret\"}"));
        })).isInstanceOf(IllegalStateException.class);

        Throwable recorded = stopped.getFirst().getError();
        assertThat(recorded.getMessage()).isEqualTo("IllegalStateException");
        assertThat(recorded.getCause()).isNull();
        assertThat(recorded.getStackTrace()).isEmpty();
        assertThat(meters.get("assistant.answer").tag("assistant.outcome", "ERROR").timer().count()).isOne();
    }

    @Test
    @DisplayName("an Error is tagged too, so every timer of a name has the same tag keys")
    void errorsAreTagged() {
        assertThatThrownBy(() -> telemetry.answer(ToolChannel.CHAT, () -> {
            throw new AssertionError("x");
        })).isInstanceOf(AssertionError.class);

        assertThat(meters.get("assistant.answer").tag("assistant.outcome", "ERROR").timer().count()).isOne();
    }

    @Test
    @DisplayName("a search counts what each ranking found on its own span; outside a search the counts go nowhere")
    void retrieval() {
        telemetry.retrievalFound("words", 9);
        telemetry.retrieval(() -> {
            telemetry.retrievalFound("words", 10);
            telemetry.retrievalFound("meaning", 3);
            return List.of();
        });

        Observation.Context search = stopped.getFirst();
        assertThat(search.getHighCardinalityKeyValue("assistant.retrieval.words").getValue()).isEqualTo("10");
        assertThat(search.getHighCardinalityKeyValue("assistant.retrieval.meaning").getValue()).isEqualTo("3");
    }

    @Test
    @DisplayName("the router's moves are counted by kind and by the model moved away from")
    void routeEvents() {
        telemetry.routeEvent("fallback", "gemini-3.5-flash-lite");
        telemetry.routeEvent("fallback", "gemini-3.5-flash-lite");

        assertThat(meters.get("assistant.route.events").tag("event", "fallback").counter().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("without a registry, nothing is recorded and the work still runs")
    void none() {
        assertThat(AssistantTelemetry.NONE.answer(ToolChannel.CHAT, () -> "ran")).isEqualTo("ran");
    }
}
