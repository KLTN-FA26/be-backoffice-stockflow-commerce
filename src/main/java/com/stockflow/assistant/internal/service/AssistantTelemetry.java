package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.common.error.BusinessException;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The assistant's traces and metrics (design §11): one span per answer, per model call, per tool and per knowledge
 * search, named and tagged after OpenTelemetry's semantic conventions for generative AI, so Jaeger, Langfuse or any
 * OTLP backend reads them as what they are. They reach Jaeger through the application's OTLP exporter; nothing here
 * talks to a backend.
 *
 * <p><b>No content.</b> A span carries names, outcomes, sizes and token counts — never the question, the prompt, a
 * tool's arguments or its result. Those hold what people typed and what the business owns, and a trace store is read
 * by more people, and kept longer, than the chat's own records. The conventions make content capture opt-in for the
 * same reason; it is not offered.</p>
 *
 * <p><b>Metrics come with the observations</b>, as timers tagged with the low-cardinality values: the answer's p50 and
 * p95 ({@code assistant.answer}), each model call ({@code gen_ai.client.operation}), each tool ({@code assistant.tool}).
 * Tokens are a distribution per model and type ({@code gen_ai.client.token.usage}), and the router's moves a counter
 * ({@code assistant.route.events}).</p>
 */
@Component
public class AssistantTelemetry {

    /** For code built without a registry, tests among it: observations and meters that go nowhere. */
    static final AssistantTelemetry NONE = new AssistantTelemetry(ObservationRegistry.NOOP,
            new CompositeMeterRegistry());

    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    AssistantTelemetry(ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations;
        this.meters = meters;
    }

    /** One answer to one question, whichever model, tools and retries it took. */
    <T> T answer(ToolChannel channel, Supplier<T> answering) {
        Observation observation = Observation.createNotStarted("assistant.answer", observations)
                .contextualName("assistant answer")
                .lowCardinalityKeyValue("assistant.channel", channel.name());
        return observed(observation, answering, result -> "OK");
    }

    /**
     * One call to a model: {@code chat {model}} in the conventions' words.
     *
     * @param route    the model as the route names it
     * @param provider its vendor's configured name
     * @param model    its id at that vendor
     */
    LanguageModel.Reply chat(String route, String provider, String model, Supplier<LanguageModel.Reply> calling) {
        Observation observation = Observation.createNotStarted("gen_ai.client.operation", observations)
                .contextualName("chat " + model)
                .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
                .lowCardinalityKeyValue("gen_ai.provider.name", provider)
                .lowCardinalityKeyValue("gen_ai.request.model", model)
                .highCardinalityKeyValue("assistant.route.model", route);
        return observed(observation, () -> {
            LanguageModel.Reply reply = calling.get();
            LanguageModel.Usage usage = reply.usage();
            // Every input token, read from the cache, written to it or neither: Claude counts the last two apart.
            long input = usage.inputTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens();
            observation.highCardinalityKeyValue("gen_ai.response.model", reply.model())
                    .highCardinalityKeyValue("gen_ai.usage.input_tokens", Long.toString(input))
                    .highCardinalityKeyValue("gen_ai.usage.output_tokens", Long.toString(usage.outputTokens()))
                    .highCardinalityKeyValue("gen_ai.response.finish_reasons", reply.stopReason().name());
            tokens(provider, model, "input", input);
            tokens(provider, model, "output", usage.outputTokens());
            return reply;
        }, reply -> reply.stopReason().name());
    }

    /** One tool run: {@code execute_tool {tool}}, with its outcome and the size of what it returned. */
    ToolExecution tool(ToolChannel channel, String tool, Supplier<ToolExecution> running) {
        Observation observation = Observation.createNotStarted("assistant.tool", observations)
                .contextualName("execute_tool " + tool)
                .lowCardinalityKeyValue("gen_ai.operation.name", "execute_tool")
                .lowCardinalityKeyValue("gen_ai.tool.name", tool)
                .lowCardinalityKeyValue("assistant.channel", channel.name());
        return observed(observation, () -> {
            ToolExecution execution = running.get();
            observation.highCardinalityKeyValue("gen_ai.tool.call.id", execution.call().id())
                    .highCardinalityKeyValue("assistant.tool.result_chars", Integer.toString(
                            execution.result().length()));
            return execution;
        }, execution -> execution.outcome().name());
    }

    /** One knowledge search, with how many passages each ranking found. */
    <T> T retrieval(Supplier<T> searching) {
        Observation observation = Observation.createNotStarted("assistant.retrieval", observations)
                .contextualName("assistant retrieval");
        return observed(observation, searching, result -> "OK");
    }

    /** Adds a count to the search running on this thread, if one is: words, meaning, fused. */
    void retrievalFound(String ranking, int passages) {
        Observation current = observations.getCurrentObservation();
        if (current != null && "assistant.retrieval".equals(current.getContext().getName())) {
            current.highCardinalityKeyValue("assistant.retrieval." + ranking, Integer.toString(passages));
        }
    }

    /**
     * What became of an answer whose numbers nothing held (design §3.1): {@code corrected}, {@code still_unverified}
     * (rewritten, some still unheld), {@code kept} (the rewrite was unusable, or not better without losing something),
     * {@code failed} (the rewrite call failed; the answer stands), {@code no_time} (the lease had no room to ask).
     */
    void repair(String outcome) {
        meters.counter("assistant.answer.repairs", "outcome", outcome).increment();
    }

    /**
     * What became of a suggestion (ADR-0011): {@code suggested}, {@code confirmed} (with {@code edited}: whether its
     * owner changed it first), {@code refused} (the document's module refused it; it stays open), {@code dismissed}.
     * The acceptance rate is confirmed over suggested.
     */
    void action(String kind, String outcome, boolean edited) {
        meters.counter("assistant.actions", "kind", kind, "outcome", outcome, "edited", Boolean.toString(edited))
                .increment();
    }

    /** The router moved a question: {@code fallback} before any reply, {@code escalation} after a useless one. */
    void routeEvent(String event, String from) {
        meters.counter("assistant.route.events", "event", event, "from", from).increment();
    }

    /**
     * What a span is told of a failure: its code or its type, nothing more. The exception itself would put its message,
     * its causes and its stack in the trace store — a database error quotes the row it refused, a provider's error
     * what it was sent.
     */
    static final class Failure extends RuntimeException {

        Failure(String what) {
            super(what, null, false, false);
        }
    }

    private void tokens(String provider, String model, String type, long count) {
        DistributionSummary.builder("gen_ai.client.token.usage")
                .tag("gen_ai.provider.name", provider)
                .tag("gen_ai.request.model", model)
                .tag("gen_ai.token.type", type)
                .baseUnit("tokens")
                .register(meters)
                .record(count);
    }

    /** Runs {@code work} in {@code observation}, tagging its outcome; a failure is tagged with its error code. */
    private static <T> T observed(Observation observation, Supplier<T> work,
                                  java.util.function.Function<T, String> outcome) {
        observation.start();
        try (Observation.Scope scope = observation.openScope()) {
            T result = work.get();
            observation.lowCardinalityKeyValue("assistant.outcome", outcome.apply(result));
            return result;
        } catch (BusinessException failed) {
            observation.lowCardinalityKeyValue("assistant.outcome", failed.errorCode().name());
            observation.error(new Failure(failed.errorCode().name()));
            throw failed;
        } catch (RuntimeException | Error failed) {
            observation.lowCardinalityKeyValue("assistant.outcome", "ERROR");
            observation.error(new Failure(failed.getClass().getSimpleName()));
            throw failed;
        } finally {
            observation.stop();
        }
    }
}
