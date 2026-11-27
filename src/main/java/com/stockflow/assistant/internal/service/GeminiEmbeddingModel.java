package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.assistant.internal.domain.EmbeddingModel;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link EmbeddingModel} on Google's Gemini API ({@code embedContent} for a question, {@code batchEmbedContents} for
 * passages; REST): the vectors behind searching the rules by meaning (design §6.4). Chosen by
 * {@code EmbeddingModelConfig} only for a Gemini assistant; Anthropic has no embedding API.
 *
 * <p><b>Task types.</b> A passage is embedded as {@code RETRIEVAL_DOCUMENT}, a question as {@code RETRIEVAL_QUERY}, so
 * the model places a question near the passage that answers it rather than near passages merely worded like it.
 * {@code outputDimensionality} asks for the configured length, and a vector of any other length is refused as a
 * misconfiguration: stored, it would never match the search's length and be embedded again on every start.</p>
 *
 * <p><b>A question does not wait; a passage may.</b> A question is embedded while someone waits for the answer, and
 * the search falls back to keywords on any failure, so it gets one attempt. Passages are embedded in the background
 * after start-up, where waiting out the free tier's per-minute quota costs nobody anything: a per-minute 429, a 5xx or
 * a timeout is tried again {@value AssistantProperties#MAX_RETRIES} times, waiting what Google's {@code RetryInfo}
 * asks, at most a minute. A spent daily quota ({@code ASSISTANT_QUOTA_EXHAUSTED}) and 400, 401, 403 and 404 (the key,
 * the model, the length or the request: {@code ASSISTANT_MISCONFIGURED}) are never tried again. The loop is
 * {@link GeminiCall}, the chat's own.</p>
 *
 * <p><b>The key travels in the {@code x-goog-api-key} header</b>, never in the URL, which the call log of
 * {@code RestClientFactory} writes.</p>
 */
class GeminiEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingModel.class);

    /** {@code batchEmbedContents} refuses more requests than this in one call. */
    static final int MAX_BATCH = 100;

    static final String EMBED = "/v1beta/models/{model}:embedContent";
    static final String BATCH = "/v1beta/models/{model}:batchEmbedContents";

    private final GeminiCall gemini;
    private final String model;
    private final int dimensions;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param http based at the API's root: {@link GeminiLanguageModel#DEFAULT_BASE_URL}, or the assistant's base URL
     */
    GeminiEmbeddingModel(RestClient http, String model, int dimensions, String apiKey) {
        this(http, model, dimensions, apiKey, Thread::sleep);
    }

    GeminiEmbeddingModel(RestClient http, String model, int dimensions, String apiKey, Sleeper sleeper) {
        this.gemini = new GeminiCall(http, apiKey, sleeper, " (embeddings)", "embedding provider");
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts, Purpose purpose) {
        if (texts.isEmpty()) {
            return List.of();
        }
        boolean patient = purpose == Purpose.DOCUMENT;
        if (texts.size() == 1) {
            JsonNode response = call(EMBED, write(request(texts.getFirst(), purpose)), patient);
            return List.of(vector(response.path("embedding")));
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += MAX_BATCH) {
            List<String> batch = texts.subList(from, Math.min(from + MAX_BATCH, texts.size()));
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            ArrayNode requests = body.putArray("requests");
            batch.forEach(text -> requests.add(request(text, purpose)));
            JsonNode embeddings = call(BATCH, write(body), patient).path("embeddings");
            if (!embeddings.isArray() || embeddings.size() != batch.size()) {
                log.error("Gemini returned {} embeddings for {} texts", embeddings.size(), batch.size());
                throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE,
                        "The embedding provider returned a vector count that does not match the texts sent");
            }
            embeddings.forEach(embedding -> vectors.add(vector(embedding)));
        }
        return vectors;
    }

    /** One {@code EmbedContentRequest}: alone for {@code embedContent}, or one of the {@code requests} of a batch. */
    ObjectNode request(String text, Purpose purpose) {
        ObjectNode request = JsonNodeFactory.instance.objectNode().put("model", "models/" + model);
        request.putObject("content").putArray("parts").addObject().put("text", text);
        request.put("taskType", purpose == Purpose.QUERY ? "RETRIEVAL_QUERY" : "RETRIEVAL_DOCUMENT");
        request.put("outputDimensionality", dimensions);
        return request;
    }

    /** {@code ContentEmbedding.values}, checked: the length asked for, and numbers only. */
    private float[] vector(JsonNode embedding) {
        JsonNode values = embedding.path("values");
        if (!values.isArray()) {
            log.error("Gemini returned an embedding without values");
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The embedding provider returned no vector");
        }
        if (values.size() != dimensions) {
            log.error("Gemini's {} returned vectors of {} dimensions where {} were asked for: check "
                    + "stockflow.assistant.embeddings.model and dimensions", model, values.size(), dimensions);
            throw new BusinessException(ErrorCode.ASSISTANT_MISCONFIGURED,
                    "The embedding has " + values.size() + " dimensions, not " + dimensions);
        }
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            JsonNode value = values.get(i);
            float number = (float) value.asDouble();
            if (!value.isNumber() || !Float.isFinite(number)) {
                log.error("Gemini returned an embedding holding something other than a finite number");
                throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE,
                        "The embedding provider returned a vector that is not numbers");
            }
            vector[i] = number;
        }
        return vector;
    }

    /** One call, tried again only when {@code patient}; how a failure is told is {@link GeminiCall}'s. */
    private JsonNode call(String uri, String body, boolean patient) {
        return gemini.post(uri, model, body, patient);
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a JSON tree always serialises", impossible);
        }
    }
}
