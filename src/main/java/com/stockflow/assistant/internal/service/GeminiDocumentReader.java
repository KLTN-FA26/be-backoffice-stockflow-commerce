package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.assistant.internal.domain.LanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
 * <p><b>Failures say what to do next</b>, exactly as for the chat: the one loop of {@link GeminiCall}. A 400 cannot be
 * the photo's fault here: what is sent is a JPEG the application decoded and encoded itself
 * ({@link DocumentImageProcessor}).</p>
 *
 * <p><b>Nothing of the document reaches a log</b>: the call log has the path and status only, and the lines below
 * the outcome, the model and the sizes.</p>
 */
class GeminiDocumentReader implements DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(GeminiDocumentReader.class);

    /** Finish reasons of a declined candidate. */
    private static final Set<String> REFUSALS = Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT",
            "SPII", "IMAGE_SAFETY", "LANGUAGE");

    private final GeminiCall call;
    private final AssistantProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    /** @param http based at the API's root, as for {@link GeminiLanguageModel} */
    GeminiDocumentReader(RestClient http, AssistantProperties properties) {
        this(http, properties, Thread::sleep);
    }

    GeminiDocumentReader(RestClient http, AssistantProperties properties, Sleeper sleeper) {
        this.call = new GeminiCall(http, properties.apiKey(), sleeper, " (document)", "model provider");
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return true;
    }

    /** One {@code generateContent} call; how a failure is told and retried is {@link GeminiCall}'s. */
    @Override
    public Reading read(Request request) {
        return reading(call.post("/v1beta/models/{model}:generateContent", properties.model(), write(body(request)),
                true));
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

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a JSON tree always serialises", impossible);
        }
    }
}
