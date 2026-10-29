package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
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
import java.util.regex.Pattern;

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
 * <p><b>A failure says what to do next.</b> A 429 of the free tier's per-minute quota, a 5xx, or a timeout is tried
 * again {@value AssistantProperties#MAX_RETRIES} times, waiting what the API's {@code RetryInfo} asks, at most
 * {@value #MAX_BACKOFF_SECONDS} seconds, and then fails as {@code ASSISTANT_UNAVAILABLE}, which a user may retry. A
 * spent quota fails at once as {@code ASSISTANT_QUOTA_EXHAUSTED}: its {@code QuotaFailure} names a per-day quota, or
 * its {@code RetryInfo} asks for more than a minute. 400, 401, 403 and 404 are the key, the model name or our own
 * request; they fail at once as {@code ASSISTANT_MISCONFIGURED}, logged as an error, because no retry changes them and
 * "try again in a few minutes" would send every user back for nothing. The waits are not part of
 * {@code AssistantProperties#longestModelCall}; at two of a minute each they still fit the default lease.</p>
 *
 * <p><b>An answer has text.</b> {@code END_TURN} is a {@code STOP} that wrote something. A candidate with neither text
 * nor a function call did not answer, whatever its finish reason says — {@code MALFORMED_FUNCTION_CALL} (the model
 * wrote a call it could not format), {@code UNEXPECTED_TOOL_CALL}, {@code TOO_MANY_TOOL_CALLS}, or a bare
 * {@code STOP} — so it is {@code OTHER}, never an {@code END_TURN} with nothing in it.</p>
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

    private static final Duration MAX_BACKOFF = Duration.ofSeconds(MAX_BACKOFF_SECONDS);

    /**
     * The key, the model name or our request: Google answers 400 for a key that is not valid, 403 for a suspended
     * key or an API that is not enabled, 404 for a model it does not serve.
     */
    private static final Set<Integer> MISCONFIGURATION = Set.of(400, 401, 403, 404);

    /** The error detail that names the quota a 429 is about ({@code quotaId}, "...PerDay..." or "...PerMinute..."). */
    private static final String QUOTA_FAILURE = "google.rpc.QuotaFailure";

    /** A Google API key or an Anthropic one, should an error message ever quote it back. */
    private static final Pattern KEY = Pattern.compile("AIza[0-9A-Za-z_-]{30,}|sk-ant-[0-9A-Za-z_-]+");

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
            JsonNode error = error(response.body());
            String reason = errorSummary(error);
            if (status == 429 && quotaSpent(error)) {
                log.warn("Gemini's quota for {} is used up: {}", properties.model(), reason);
                throw new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED,
                        "The model provider's quota is used up; the assistant answers again once it is renewed");
            }
            if (MISCONFIGURATION.contains(status)) {
                log.error("Gemini refused the key, the model or the request with {}: {}", status, reason);
                throw new BusinessException(ErrorCode.ASSISTANT_MISCONFIGURED, "The model provider answered " + status);
            }
            boolean retryable = status == 429 || status >= 500;
            Optional<Duration> wait = retryable ? retryDelay(error, status, attempt) : Optional.empty();
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
        body.set("contents", contents(request.history(), request.note()));
        if (!request.tools().isEmpty()) {
            ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
            request.tools().forEach(tool -> declarations.add(declaration(tool)));
            // NONE on the answer's last call: the declarations stay, since the history holds calls to them.
            body.putObject("toolConfig").putObject("functionCallingConfig")
                    .put("mode", request.toolsAllowed() ? "AUTO" : "NONE");
        }
        body.putObject("generationConfig").put("maxOutputTokens", properties.maxOutputTokens());
        return body;
    }

    ArrayNode contents(List<Turn> history) {
        return contents(history, null);
    }

    /** @param note a line from the application, sent as the last part of the last user content; null for none */
    ArrayNode contents(List<Turn> history, String note) {
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
        if (note != null) {
            user.add(text(note));
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
        String text = texts.isEmpty() ? null : String.join("\n\n", texts);
        StopReason stop;
        if (REFUSALS.contains(finish)) {
            stop = StopReason.REFUSAL;
        } else if ("MAX_TOKENS".equals(finish)) {
            stop = StopReason.MAX_TOKENS;
        } else if (!calls.isEmpty()) {
            stop = StopReason.TOOL_USE;
        } else if ("STOP".equals(finish) && text != null) {
            stop = StopReason.END_TURN;
        } else {
            // MALFORMED_FUNCTION_CALL, UNEXPECTED_TOOL_CALL, TOO_MANY_TOOL_CALLS, a bare STOP: nothing was answered.
            stop = StopReason.OTHER;
        }
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

    /** The {@code error} object of a refused call's body; a missing node when the body is not the API's JSON. */
    JsonNode error(String body) {
        try {
            return json.readTree(body).path("error");
        } catch (IOException notJson) {
            return MissingNode.getInstance();
        }
    }

    /**
     * Whether a 429 is a quota that will not come back within this answer. Two signs, either enough: the
     * {@code QuotaFailure} detail names a per-day quota ({@code GenerateRequestsPerDayPerProjectPerModel-FreeTier}), or
     * {@code RetryInfo} asks for more than {@value #MAX_BACKOFF_SECONDS} seconds. The free tier's daily refusal has
     * come both ways: "retry after 74900s", and a per-day quota beside a {@code RetryInfo} of half a minute, which read
     * alone would have every question wait out two retries for the same refusal.
     */
    static boolean quotaSpent(JsonNode error) {
        for (JsonNode detail : error.path("details")) {
            if (detail.path("@type").asText("").endsWith(QUOTA_FAILURE)) {
                for (JsonNode violation : detail.path("violations")) {
                    if (violation.path("quotaId").asText("").contains("PerDay")) {
                        return true;
                    }
                }
            }
        }
        return requestedDelay(error).filter(delay -> delay.compareTo(MAX_BACKOFF) > 0).isPresent();
    }

    /**
     * How long to wait before the next attempt: what {@code RetryInfo} asks ("17s", "1.5s"). Without one, a 429 is
     * the free tier's per-minute quota, which frees up within the minute, so 15 s then 30 s; a 5xx, a short growing
     * wait.
     *
     * @return empty when the API asks for more than {@value #MAX_BACKOFF_SECONDS} seconds: waiting a minute twice
     *         would only make the user wait two minutes for the same refusal
     */
    static Optional<Duration> retryDelay(JsonNode error, int status, int attempt) {
        Optional<Duration> asked = requestedDelay(error);
        if (asked.isPresent()) {
            return asked.get().compareTo(MAX_BACKOFF) > 0 ? Optional.empty() : asked;
        }
        return Optional.of(Duration.ofSeconds((status == 429 ? 15L : 2L) * (attempt + 1)));
    }

    /** {@code RetryInfo}'s delay, when the error carries one in a form that can be read. */
    private static Optional<Duration> requestedDelay(JsonNode error) {
        for (JsonNode detail : error.path("details")) {
            String delay = detail.path("retryDelay").asText("");
            if (delay.endsWith("s")) {
                try {
                    double seconds = Double.parseDouble(delay.substring(0, delay.length() - 1));
                    if (seconds >= 0) {
                        return Optional.of(Duration.ofMillis((long) (seconds * 1000)));
                    }
                } catch (NumberFormatException unreadable) {
                    // Not the API's shape; the caller's fallback will do.
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Google's status and message for the log, at most 300 characters. Anything shaped like a key is blanked first:
     * the refusal of a suspended key can quote it ("Consumer 'api_key:AIza…' has been suspended").
     */
    static String errorSummary(JsonNode error) {
        if (!error.isObject()) {
            return "(unreadable body)";
        }
        String message = KEY.matcher(error.path("message").asText("")).replaceAll("[key]");
        return error.path("status").asText("") + " " + (message.length() > 300 ? message.substring(0, 300) : message);
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
