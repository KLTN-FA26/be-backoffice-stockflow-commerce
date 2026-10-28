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
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link LanguageModel} on Google's Gemini API ({@code generateContent}, REST), the second adapter behind the port
 * (ADR-0010). Chosen with {@code stockflow.assistant.provider=gemini}; the loop, the tools and the stored history do
 * not change.
 *
 * <p><b>Replay is verbatim.</b> A reply is stored as the candidate's {@code content} exactly as it came, and sent back
 * as it is. Its function-call parts carry {@code thoughtSignature}s that the API requires on the next turn of a
 * function-calling exchange; rebuilding the parts from the lifted values would drop them.</p>
 *
 * <p><b>Function responses name their call.</b> Gemini matches a response to its call by name (and by id when the call
 * had one), so the adapter reads the names back from the model turns of the history; a {@link ToolResult} carries only
 * the call id. Ids the API did not give are made up locally ({@value #LOCAL_ID_PREFIX}…) and never sent.</p>
 *
 * <p><b>Consecutive user-side turns are merged</b>, function responses first, as in {@link ClaudeLanguageModel}.</p>
 *
 * <p><b>Schemas are translated, not trusted.</b> Gemini's function declarations take an OpenAPI subset: upper-case
 * types and no {@code additionalProperties}. There is no strict mode, so arguments may not match the schema;
 * {@link ToolArguments} validates every one in Java either way.</p>
 *
 * <p><b>Retries.</b> A 429 (the free tier's per-minute quota), a 5xx, or a timeout is tried again
 * {@value AssistantProperties#MAX_RETRIES} times, waiting what the API's {@code RetryInfo} asks, at most
 * {@value #MAX_BACKOFF_SECONDS} seconds. Other 4xx are a bug on our side and fail at once. The waits are not part of
 * {@code AssistantProperties#longestModelCall}; at two of a minute each they still fit the default lease.</p>
 *
 * <p><b>HTTP through {@code RestClientFactory}</b>, for its correlation id and call log; the log line has the path
 * and status only, and the key travels in the {@code x-goog-api-key} header, never in a URL.</p>
 */
class GeminiLanguageModel implements LanguageModel {

    private static final Logger log = LoggerFactory.getLogger(GeminiLanguageModel.class);

    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    static final String LOCAL_ID_PREFIX = "gemini-local-";
    /** A minute: the free tier's quotas are per minute, and a shorter cap only fails the same way again. */
    static final int MAX_BACKOFF_SECONDS = 60;

    private static final Set<String> REFUSALS = Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT",
            "SPII", "IMAGE_SAFETY", "LANGUAGE");
    private static final Set<String> SCHEMA_KEYS = Set.of("type", "description", "enum", "items", "properties",
            "required");

    private final RestClient http;
    private final AssistantProperties properties;
    private final ObjectMapper json = new ObjectMapper();
    private final Sleeper sleeper;

    /** @param http based at the API's root ({@link #DEFAULT_BASE_URL} or {@code stockflow.assistant.base-url}) */
    GeminiLanguageModel(RestClient http, AssistantProperties properties) {
        this(http, properties, Thread::sleep);
    }

    GeminiLanguageModel(RestClient http, AssistantProperties properties, Sleeper sleeper) {
        this.http = http;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    private record Raw(int status, String body) {
    }

    /** Waits between attempts; replaced in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Reply next(Request request) {
        String body = write(body(request));
        for (int attempt = 0; ; attempt++) {
            Raw response;
            try {
                response = http.post()
                        .uri("/v1beta/models/{model}:generateContent", properties.model())
                        .header("x-goog-api-key", properties.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .exchange((sent, received) -> new Raw(received.getStatusCode().value(),
                                new String(received.getBody().readAllBytes(), StandardCharsets.UTF_8)));
            } catch (ResourceAccessException unreachable) {
                if (attempt < AssistantProperties.MAX_RETRIES) {
                    log.warn("Gemini could not be reached (attempt {}): {}", attempt + 1, unreachable.getMessage());
                    pause(Duration.ofSeconds(2L * (attempt + 1)));
                    continue;
                }
                throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model provider could not be reached",
                        unreachable);
            }
            int status = response.status();
            if (status == 200) {
                return reply(read(response.body()), properties.model());
            }
            boolean retryable = status == 429 || status >= 500;
            String reason = errorSummary(response.body());
            Optional<Duration> wait = retryable ? retryDelay(response.body(), status, attempt) : Optional.empty();
            if (wait.isPresent() && attempt < AssistantProperties.MAX_RETRIES) {
                log.warn("Gemini answered {} (attempt {}): {}", status, attempt + 1, reason);
                pause(wait.get());
                continue;
            }
            if (retryable) {
                log.warn("Gemini answered {}: {}", status, reason);
            } else {
                log.error("Gemini rejected the request with {}: {}", status, reason);
            }
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model provider answered " + status);
        }
    }

    /** A model turn holding only {@code text}, in Gemini's format, so it replays like any other. */
    @Override
    public Turn.ModelTurn closingTurn(String text) {
        ObjectNode content = JsonNodeFactory.instance.objectNode().put("role", "model");
        content.putArray("parts").addObject().put("text", text);
        return new Turn.ModelTurn(write(content), text, List.of());
    }

    ObjectNode body(Request request) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.systemPrompt());
        body.set("contents", contents(request.history()));
        if (!request.tools().isEmpty()) {
            ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
            request.tools().forEach(tool -> declarations.add(declaration(tool)));
            body.putObject("toolConfig").putObject("functionCallingConfig").put("mode", "AUTO");
        }
        body.putObject("generationConfig").put("maxOutputTokens", properties.maxOutputTokens());
        return body;
    }

    ArrayNode contents(List<Turn> history) {
        ArrayNode contents = JsonNodeFactory.instance.arrayNode();
        Map<String, String> callNames = new HashMap<>();
        List<ObjectNode> user = new ArrayList<>();
        for (Turn turn : history) {
            switch (turn) {
                case Turn.Question question -> {
                    user.add(text(question.context()));
                    user.add(text(question.text()));
                }
                case Turn.ToolResults results -> {
                    // Function responses must open the user content that answers the calls.
                    List<ObjectNode> parts = new ArrayList<>();
                    results.results().forEach(result -> parts.add(response(result, callNames)));
                    parts.addAll(user);
                    user = parts;
                }
                case Turn.ModelTurn model -> {
                    flush(contents, user);
                    user = new ArrayList<>();
                    model.toolCalls().forEach(call -> callNames.put(call.id(), call.name()));
                    contents.add(read(model.providerContent()));
                }
            }
        }
        flush(contents, user);
        return contents;
    }

    private static void flush(ArrayNode contents, List<ObjectNode> user) {
        if (!user.isEmpty()) {
            ObjectNode content = contents.addObject().put("role", "user");
            ArrayNode parts = content.putArray("parts");
            user.forEach(parts::add);
        }
    }

    private static ObjectNode text(String text) {
        return JsonNodeFactory.instance.objectNode().put("text", text);
    }

    private ObjectNode response(ToolResult result, Map<String, String> callNames) {
        String name = callNames.get(result.callId());
        if (name == null) {
            throw new IllegalStateException("tool result " + result.callId() + " answers no call in the history");
        }
        ObjectNode functionResponse = JsonNodeFactory.instance.objectNode();
        if (!result.callId().startsWith(LOCAL_ID_PREFIX)) {
            functionResponse.put("id", result.callId());
        }
        functionResponse.put("name", name);
        // The API wants an object; the tool's own JSON (or error code) travels as one string field.
        functionResponse.putObject("response").put(result.error() ? "error" : "result", result.content());
        ObjectNode part = JsonNodeFactory.instance.objectNode();
        part.set("functionResponse", functionResponse);
        return part;
    }

    ObjectNode declaration(ToolSpec spec) {
        ObjectNode declaration = JsonNodeFactory.instance.objectNode()
                .put("name", spec.name())
                .put("description", spec.description());
        declaration.set("parameters", schema(json.valueToTree(spec.inputSchema())));
        return declaration;
    }

    /** JSON Schema as {@link ToolSchema} writes it, turned into Gemini's OpenAPI subset. */
    static JsonNode schema(JsonNode node) {
        if (!node.isObject()) {
            return node;
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        node.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (!SCHEMA_KEYS.contains(key)) {
                return;
            }
            switch (key) {
                case "type" -> out.put("type", value.asText().toUpperCase());
                case "items" -> out.set("items", schema(value));
                case "properties" -> {
                    ObjectNode properties = out.putObject("properties");
                    value.properties().forEach(property -> properties.set(property.getKey(),
                            schema(property.getValue())));
                }
                default -> out.set(key, value);
            }
        });
        return out;
    }

    Reply reply(JsonNode response, String requestedModel) {
        String model = response.path("modelVersion").asText(requestedModel);
        JsonNode candidate = response.path("candidates").path(0);
        Usage usage = usage(response.path("usageMetadata"));
        if (candidate.isMissingNode()) {
            // The prompt itself was blocked: no candidate at all.
            return new Reply(closingTurn(""), StopReason.REFUSAL, usage, model);
        }
        JsonNode content = candidate.path("content");
        List<String> texts = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        int index = 0;
        for (JsonNode part : content.path("parts")) {
            if (part.has("text") && !part.path("thought").asBoolean(false) && !part.path("text").asText().isBlank()) {
                texts.add(part.path("text").asText());
            }
            JsonNode call = part.path("functionCall");
            if (call.isObject()) {
                String id = call.hasNonNull("id") ? call.path("id").asText()
                        : LOCAL_ID_PREFIX + index + "-" + UUID.randomUUID();
                calls.add(new ToolCall(id, call.path("name").asText(), write(call.path("args").isObject()
                        ? call.path("args") : JsonNodeFactory.instance.objectNode())));
            }
            index++;
        }
        String finish = candidate.path("finishReason").asText("");
        StopReason stop;
        if (REFUSALS.contains(finish)) {
            stop = StopReason.REFUSAL;
        } else if ("MAX_TOKENS".equals(finish)) {
            stop = StopReason.MAX_TOKENS;
        } else if (!calls.isEmpty()) {
            stop = StopReason.TOOL_USE;
        } else if ("STOP".equals(finish)) {
            stop = StopReason.END_TURN;
        } else {
            stop = StopReason.OTHER;
        }
        String text = texts.isEmpty() ? null : String.join("\n\n", texts);
        // A candidate with no parts (blocked, or cut before any output) is not a valid turn to send back.
        String providerContent = content.path("parts").isArray() && !content.path("parts").isEmpty()
                ? write(content) : closingTurn(text == null ? "" : text).providerContent();
        return new Reply(new Turn.ModelTurn(providerContent, text, calls), stop, usage, model);
    }

    private static Usage usage(JsonNode metadata) {
        long prompt = metadata.path("promptTokenCount").asLong(0);
        long cached = metadata.path("cachedContentTokenCount").asLong(0);
        long output = metadata.path("candidatesTokenCount").asLong(0) + metadata.path("thoughtsTokenCount").asLong(0);
        return new Usage(Math.max(0, prompt - cached), output, cached, 0);
    }

    /**
     * What {@code RetryInfo} asks ("17s", "1.5s"). Without one: a 429 is the free tier's per-minute quota, which frees
     * up within the minute, so 15 s then 30 s; a 5xx, a short growing wait.
     *
     * @return empty when the API asks for more than {@value #MAX_BACKOFF_SECONDS} seconds: that is a daily quota
     *         ({@code GenerateRequestsPerDay…}, "retry after 74900s"), and waiting a minute twice only makes the
     *         user wait two minutes for the same refusal
     */
    Optional<Duration> retryDelay(String body, int status, int attempt) {
        Duration fallback = Duration.ofSeconds((status == 429 ? 15L : 2L) * (attempt + 1));
        try {
            for (JsonNode detail : json.readTree(body).path("error").path("details")) {
                String delay = detail.path("retryDelay").asText("");
                if (delay.endsWith("s")) {
                    double seconds = Double.parseDouble(delay.substring(0, delay.length() - 1));
                    return seconds > MAX_BACKOFF_SECONDS ? Optional.empty()
                            : Optional.of(Duration.ofMillis((long) (seconds * 1000)));
                }
            }
        } catch (IOException | NumberFormatException unreadable) {
            // Not the API's error shape; the fallback will do.
        }
        return Optional.of(fallback);
    }

    private String errorSummary(String body) {
        try {
            JsonNode error = json.readTree(body).path("error");
            String message = error.path("message").asText("");
            return error.path("status").asText("") + " " + (message.length() > 300 ? message.substring(0, 300)
                    : message);
        } catch (IOException notJson) {
            return "(unreadable body)";
        }
    }

    private void pause(Duration delay) {
        try {
            sleeper.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "Interrupted waiting to retry", interrupted);
        }
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
            throw new IllegalStateException("not JSON: a stored turn or a provider response", corrupt);
        }
    }
}
