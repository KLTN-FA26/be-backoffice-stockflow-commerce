package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.assistant.internal.domain.LanguageModel;
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
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link DocumentReader} on Gemini's {@code generateContent} (REST), beside {@link GeminiLanguageModel} and on the same
 * key, model and endpoint.
 *
 * <p><b>The request.</b> The instruction is the system instruction; the one user content holds the image first, as an
 * {@code inline_data} part (base64, with its {@code mime_type}), then the context as text: Google's guidance for one
 * image with a prompt is image first. The answer is constrained by {@code generationConfig}: {@code responseMimeType}
 * {@code application/json} and the schema as a {@code responseSchema}, translated into the API's OpenAPI subset by
 * {@link GeminiLanguageModel#schema}, with each object's {@code propertyOrdering} set to its declared order, because
 * the order the model writes in is the order it reasons in. The API reads both spellings of a field's name;
 * {@code inline_data} is the one its image examples use.</p>
 *
 * <p><b>Failures say what to do next</b>, exactly as for the chat ({@link GeminiLanguageModel}, whose helpers this
 * reuses): a per-minute 429, a 5xx or a timeout is tried again {@value AssistantProperties#MAX_RETRIES} times, then
 * {@code ASSISTANT_UNAVAILABLE}; a spent quota is {@code ASSISTANT_QUOTA_EXHAUSTED} at once; 400, 401, 403 and 404 are
 * {@code ASSISTANT_MISCONFIGURED}, logged as errors. A 400 cannot be the photo's fault here: what is sent is a JPEG
 * the application decoded and encoded itself ({@link DocumentImageProcessor}).</p>
 *
 * <p><b>Nothing of the document reaches a log</b>: the call log has the path and status only, and the lines below
 * the outcome, the model and the sizes.</p>
 */
class GeminiDocumentReader implements DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(GeminiDocumentReader.class);

    /** The key, the model name or our request; as {@link GeminiLanguageModel} reads them. */
    private static final Set<Integer> MISCONFIGURATION = Set.of(400, 401, 403, 404);

    /** Finish reasons of a declined candidate. */
    private static final Set<String> REFUSALS = Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT",
            "SPII", "IMAGE_SAFETY", "LANGUAGE");

    private final RestClient http;
    private final AssistantProperties properties;
    private final GeminiLanguageModel.Sleeper sleeper;
    private final ObjectMapper json = new ObjectMapper();

    /** @param http based at the API's root, as for {@link GeminiLanguageModel} */
    GeminiDocumentReader(RestClient http, AssistantProperties properties) {
        this(http, properties, Thread::sleep);
    }

    GeminiDocumentReader(RestClient http, AssistantProperties properties, GeminiLanguageModel.Sleeper sleeper) {
        this.http = http;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    private record Raw(int status, String body) {
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Reading read(Request request) {
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
                    log.warn("Gemini could not be reached reading a document (attempt {}): {}", attempt + 1,
                            unreachable.getMessage());
                    pause(Duration.ofSeconds(2L * (attempt + 1)));
                    continue;
                }
                throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model provider could not be reached",
                        unreachable);
            }
            int status = response.status();
            if (status == 200) {
                return reading(read(response.body()));
            }
            JsonNode error = error(response.body());
            String reason = GeminiLanguageModel.errorSummary(error);
            if (status == 429 && GeminiLanguageModel.quotaSpent(error)) {
                log.warn("Gemini's quota for {} is used up: {}", properties.model(), reason);
                throw new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED,
                        "The model provider's quota is used up; the assistant answers again once it is renewed");
            }
            if (MISCONFIGURATION.contains(status)) {
                log.error("Gemini refused the key, the model or the document request with {}: {}", status, reason);
                throw new BusinessException(ErrorCode.ASSISTANT_MISCONFIGURED, "The model provider answered " + status);
            }
            boolean retryable = status == 429 || status >= 500;
            Optional<Duration> wait = retryable ? GeminiLanguageModel.retryDelay(error, status, attempt)
                    : Optional.empty();
            if (wait.isPresent() && attempt < AssistantProperties.MAX_RETRIES) {
                log.warn("Gemini answered {} reading a document (attempt {}): {}", status, attempt + 1, reason);
                pause(wait.get());
                continue;
            }
            if (retryable) {
                log.warn("Gemini answered {} reading a document: {}", status, reason);
            } else {
                log.error("Gemini rejected the document request with {}: {}", status, reason);
            }
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model provider answered " + status);
        }
    }

    ObjectNode body(Request request) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.instruction());
        ObjectNode content = body.putArray("contents").addObject().put("role", "user");
        ArrayNode parts = content.putArray("parts");
        parts.addObject().putObject("inline_data")
                .put("mime_type", request.image().mediaType())
                .put("data", Base64.getEncoder().encodeToString(request.image().bytes()));
        parts.addObject().put("text", request.context());
        ObjectNode generation = body.putObject("generationConfig");
        generation.put("responseMimeType", "application/json");
        generation.set("responseSchema", ordered(GeminiLanguageModel.schema(json.valueToTree(request.schema()))));
        generation.put("maxOutputTokens", properties.maxOutputTokens());
        return body;
    }

    /** Sets every object's {@code propertyOrdering} to the order its properties were declared in. */
    static JsonNode ordered(JsonNode schema) {
        if (!schema.isObject()) {
            return schema;
        }
        ObjectNode node = (ObjectNode) schema;
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            ArrayNode order = JsonNodeFactory.instance.arrayNode();
            properties.properties().forEach(entry -> {
                order.add(entry.getKey());
                ordered(entry.getValue());
            });
            node.set("propertyOrdering", order);
        }
        if (node.has("items")) {
            ordered(node.path("items"));
        }
        return node;
    }

    Reading reading(JsonNode response) {
        String model = response.path("modelVersion").asText(properties.model());
        LanguageModel.Usage usage = usage(response.path("usageMetadata"));
        JsonNode candidate = response.path("candidates").path(0);
        if (candidate.isMissingNode()) {
            // The request itself was blocked: no candidate at all.
            return new Reading(Outcome.REFUSED, null, usage, model);
        }
        String finish = candidate.path("finishReason").asText("");
        if (REFUSALS.contains(finish)) {
            return new Reading(Outcome.REFUSED, null, usage, model);
        }
        if ("MAX_TOKENS".equals(finish)) {
            return new Reading(Outcome.CUT_SHORT, null, usage, model);
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.has("text") && !part.path("thought").asBoolean(false)) {
                texts.add(part.path("text").asText());
            }
        }
        String answer = String.join("", texts);
        return answer.isBlank() ? new Reading(Outcome.NOTHING, null, usage, model)
                : new Reading(Outcome.READ, answer, usage, model);
    }

    private static LanguageModel.Usage usage(JsonNode metadata) {
        long prompt = metadata.path("promptTokenCount").asLong(0);
        long cached = metadata.path("cachedContentTokenCount").asLong(0);
        long output = metadata.path("candidatesTokenCount").asLong(0) + metadata.path("thoughtsTokenCount").asLong(0);
        return new LanguageModel.Usage(Math.max(0, prompt - cached), output, cached, 0);
    }

    /** The {@code error} object of a refused call's body; a missing node when the body is not the API's JSON. */
    private JsonNode error(String body) {
        try {
            return json.readTree(body).path("error");
        } catch (IOException notJson) {
            return MissingNode.getInstance();
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
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model provider's answer is not JSON",
                    corrupt);
        }
    }
}
