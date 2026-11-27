package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.assistant.internal.domain.Turn;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@link LanguageModel} on OpenAI's chat-completions API, as Ollama, vLLM and LM Studio serve it: the adapter that runs
 * the assistant on a model inside the company's own network (design §11, {@code provider=openai}). The loop, the tools
 * and the stored history do not change.
 *
 * <p><b>Replay is the assistant message as it came</b>, with three repairs, all so that the next call can use it: a
 * tool call the server gave no id gets one ({@value #LOCAL_ID_PREFIX}…), written into the stored message as well,
 * because a tool message must name the call it answers; arguments that are not a JSON object replay as {@code {}}
 * ({@link #replayable}); and the visible text replaces a {@code content} that held a reasoning model's {@code <think>}
 * block, which these models are trained not to see again and which would only cost context.</p>
 *
 * <p><b>{@code max_tokens}</b> is the smaller of {@code max-output-tokens} and the server's own
 * {@code openai-compatible.max-output-tokens}: a local model's context holds the prompt and the answer together, and a
 * server such as vLLM refuses a request whose prompt plus {@code max_tokens} exceeds it.</p>
 *
 * <p><b>The last call allows no tool</b> ({@code tool_choice: none}); the definitions stay, since the history calls
 * them. A server that ignores {@code tool_choice} may still ask for one, and the loop then closes the answer as it does
 * for any model that does.</p>
 *
 * <p><b>Arguments are validated in Java</b> ({@link ToolArguments}), whatever the model wrote: these servers have no
 * strict mode. They arrive as a JSON string, as OpenAI sends them, or as an object, as some servers do.</p>
 *
 * <p><b>An answer has text.</b> {@code END_TURN} is a stop that wrote something; a reply with neither text nor a tool
 * call is {@code OTHER}, so the loop asks again instead of showing an empty answer.</p>
 */
class OpenAiCompatibleLanguageModel implements LanguageModel {

    static final String LOCAL_ID_PREFIX = "call-local-";

    /** A reasoning model's thinking, when the server leaves it in {@code content}. */
    private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?(</think>|$)");

    private final OpenAiCompatibleCall call;
    private final AssistantProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    /** @param http based at the server's API root ({@code stockflow.assistant.openai-compatible.base-url}) */
    OpenAiCompatibleLanguageModel(RestClient http, AssistantProperties properties) {
        this(http, properties, Thread::sleep);
    }

    OpenAiCompatibleLanguageModel(RestClient http, AssistantProperties properties, Sleeper sleeper) {
        this.call = new OpenAiCompatibleCall(http, properties.apiKey(), sleeper, "");
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Reply next(Request request) {
        return reply(call.post("/chat/completions", write(body(request)), true), properties.model());
    }

    /** An assistant message holding only {@code text}, so it replays like any other. */
    @Override
    public Turn.ModelTurn closingTurn(String text) {
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("role", "assistant").put("content", text);
        return new Turn.ModelTurn(write(message), text, List.of());
    }

    ObjectNode body(Request request) {
        ObjectNode body = JsonNodeFactory.instance.objectNode()
                .put("model", properties.model())
                .put("stream", false)
                .put("max_tokens", maxTokens(properties));
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());
        messages.addAll(messages(request.history(), request.note()));
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            request.tools().forEach(tool -> tools.add(declaration(tool)));
            body.put("tool_choice", request.toolsAllowed() ? "auto" : "none");
        }
        return body;
    }

    /** The answer's room on such a server (class javadoc). */
    static int maxTokens(AssistantProperties properties) {
        return Math.min(properties.maxOutputTokens(), properties.openaiCompatible().maxOutputTokens());
    }

    /**
     * @param note a line from the application, sent as a last user message, after a model turn too; null for none.
     *             After a user message (the question, when a withdrawn answer is to be looked up) it is added to that
     *             message: a chat template that requires roles to alternate (Gemma, Mistral on llama.cpp or vLLM)
     *             refuses two user messages in a row
     */
    ArrayNode messages(List<Turn> history, String note) {
        ArrayNode messages = JsonNodeFactory.instance.arrayNode();
        for (Turn turn : history) {
            switch (turn) {
                case Turn.Question question -> messages.addObject().put("role", "user")
                        .put("content", question.context() + "\n\n" + question.text());
                case Turn.ToolResults results -> results.results().forEach(result -> messages.add(toolMessage(result)));
                case Turn.ModelTurn model -> messages.add(read(model.providerContent()));
            }
        }
        if (note != null) {
            JsonNode previous = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (previous instanceof ObjectNode user && "user".equals(user.path("role").asText())
                    && user.path("content").isTextual()) {
                user.put("content", user.path("content").asText() + "\n\n" + note);
            } else {
                messages.addObject().put("role", "user").put("content", note);
            }
        }
        return messages;
    }

    private static ObjectNode toolMessage(ToolResult result) {
        // The tool's own JSON, or its error line ("NOT_PERMITTED: ..."), which names the failure itself.
        return JsonNodeFactory.instance.objectNode()
                .put("role", "tool")
                .put("tool_call_id", result.callId())
                .put("content", result.content());
    }

    ObjectNode declaration(ToolSpec spec) {
        ObjectNode tool = JsonNodeFactory.instance.objectNode().put("type", "function");
        ObjectNode function = tool.putObject("function")
                .put("name", spec.name())
                .put("description", spec.description());
        function.set("parameters", json.valueToTree(spec.inputSchema()));
        return tool;
    }

    Reply reply(JsonNode response, String requestedModel) {
        String model = response.path("model").asText(requestedModel);
        Usage usage = usage(response.path("usage"));
        JsonNode choice = response.path("choices").path(0);
        if (choice.isMissingNode()) {
            return new Reply(closingTurn(""), StopReason.OTHER, usage, model);
        }
        JsonNode received = choice.path("message");
        String text = visibleText(received.path("content"));
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("role", "assistant");
        message.put("content", text == null ? "" : text);
        List<ToolCall> calls = new ArrayList<>();
        JsonNode receivedCalls = received.path("tool_calls");
        if (receivedCalls.isArray() && !receivedCalls.isEmpty()) {
            ArrayNode stored = message.putArray("tool_calls");
            for (JsonNode receivedCall : receivedCalls) {
                JsonNode function = receivedCall.path("function");
                String id = receivedCall.hasNonNull("id") && !receivedCall.path("id").asText().isBlank()
                        ? receivedCall.path("id").asText() : LOCAL_ID_PREFIX + UUID.randomUUID();
                String arguments = arguments(function.path("arguments"));
                String name = function.path("name").asText("");
                calls.add(new ToolCall(id, name, arguments));
                ObjectNode storedCall = stored.addObject().put("id", id).put("type", "function");
                storedCall.putObject("function").put("name", name).put("arguments", replayable(arguments));
            }
        }
        String finish = choice.path("finish_reason").asText("");
        StopReason stop;
        if ("content_filter".equals(finish)) {
            stop = StopReason.REFUSAL;
        } else if ("length".equals(finish)) {
            stop = StopReason.MAX_TOKENS;
        } else if (!calls.isEmpty()) {
            stop = StopReason.TOOL_USE;
        } else if (text != null) {
            stop = StopReason.END_TURN;
        } else {
            // A stop with nothing written: no answer, whatever the finish reason says.
            stop = StopReason.OTHER;
        }
        return new Reply(new Turn.ModelTurn(write(message), text, calls), stop, usage, model);
    }

    /**
     * The text a user may see: {@code content} as a string or as text parts, without a {@code <think>} block; null
     * when nothing is left.
     */
    static String visibleText(JsonNode content) {
        String raw;
        if (content.isTextual()) {
            raw = content.asText();
        } else if (content.isArray()) {
            List<String> parts = new ArrayList<>();
            content.forEach(part -> {
                if ("text".equals(part.path("type").asText()) && part.path("text").isTextual()) {
                    parts.add(part.path("text").asText());
                }
            });
            raw = String.join("", parts);
        } else {
            return null;
        }
        String visible = THINKING.matcher(raw).replaceAll("").strip();
        return visible.isEmpty() ? null : visible;
    }

    /** The arguments as a JSON string: OpenAI sends a string, some servers an object; {@code {}} when absent. */
    private String arguments(JsonNode arguments) {
        if (arguments.isTextual()) {
            return arguments.asText().isBlank() ? "{}" : arguments.asText();
        }
        return arguments.isObject() ? write(arguments) : "{}";
    }

    /**
     * What the stored message replays as a call's arguments: the model's own when they are a JSON object, {@code {}}
     * otherwise. The call keeps what the model wrote, so {@link ToolArguments} refuses it and the model is told; but
     * vLLM parses replayed arguments and Ollama refuses ones it cannot, and that refusal would end the whole answer.
     */
    private String replayable(String arguments) {
        try {
            JsonNode parsed = json.readTree(arguments);
            return parsed != null && parsed.isObject() ? arguments : "{}";
        } catch (JsonProcessingException notJson) {
            return "{}";
        }
    }

    private static Usage usage(JsonNode usage) {
        long prompt = usage.path("prompt_tokens").asLong(0);
        long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        return new Usage(Math.max(0, prompt - cached), usage.path("completion_tokens").asLong(0), cached, 0);
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a JSON tree always serialises", impossible);
        }
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("not JSON: a stored turn", corrupt);
        }
    }
}
