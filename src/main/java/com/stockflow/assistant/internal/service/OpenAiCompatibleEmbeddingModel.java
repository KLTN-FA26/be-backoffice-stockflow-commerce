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
 * {@link EmbeddingModel} on a server speaking OpenAI's {@code /embeddings} API, such as a local Ollama serving
 * {@code bge-m3}: the vectors behind searching the rules by meaning (design §6.4) without sending a passage or a
 * question outside the company's network, and whichever vendor answers the chat.
 *
 * <p><b>One kind of vector.</b> OpenAI's API has no task type: a passage and a question are embedded alike, which is
 * how the multilingual models meant for this ({@code bge-m3}) are used. The length is not asked for, since most local
 * servers ignore the {@code dimensions} field; a vector of any other length than the configured one is refused as a
 * misconfiguration, as {@link GeminiEmbeddingModel} refuses it.</p>
 *
 * <p><b>A question does not wait; a passage may</b>: one attempt for a question, retries for the background indexing,
 * through {@link OpenAiCompatibleCall}.</p>
 */
class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingModel.class);

    /** Texts per call: a local server embeds a batch on one device, and the background indexing sends 50 at most. */
    static final int MAX_BATCH = 64;

    private final OpenAiCompatibleCall documents;
    private final OpenAiCompatibleCall questions;
    private final String model;
    private final int dimensions;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param documents based at the server's API root, waiting long enough for a model to load
     * @param questions the same server, waiting only as long as a search may wait for a question's vector
     */
    OpenAiCompatibleEmbeddingModel(RestClient documents, RestClient questions, String model, int dimensions,
                                   String apiKey) {
        this(documents, questions, model, dimensions, apiKey, Thread::sleep);
    }

    /** One client for both, as tests use it. */
    OpenAiCompatibleEmbeddingModel(RestClient http, String model, int dimensions, String apiKey, Sleeper sleeper) {
        this(http, http, model, dimensions, apiKey, sleeper);
    }

    private OpenAiCompatibleEmbeddingModel(RestClient documents, RestClient questions, String model, int dimensions,
                                           String apiKey, Sleeper sleeper) {
        this.documents = new OpenAiCompatibleCall(documents, apiKey, sleeper, " (embeddings)");
        this.questions = new OpenAiCompatibleCall(questions, apiKey, sleeper, " (embeddings)");
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
        boolean patient = purpose == Purpose.DOCUMENT;
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += MAX_BATCH) {
            List<String> batch = texts.subList(from, Math.min(from + MAX_BATCH, texts.size()));
            ObjectNode body = JsonNodeFactory.instance.objectNode().put("model", model);
            ArrayNode input = body.putArray("input");
            batch.forEach(input::add);
            OpenAiCompatibleCall call = patient ? documents : questions;
            vectors.addAll(vectors(call.post("/embeddings", write(body), patient), batch.size()));
        }
        return vectors;
    }

    /** {@code data}, put back in the order of the texts by each entry's {@code index}, and checked. */
    private List<float[]> vectors(JsonNode response, int expected) {
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() != expected) {
            log.error("The embedding server returned {} vectors for {} texts", data.isArray() ? data.size() : 0,
                    expected);
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE,
                    "The embedding provider returned a vector count that does not match the texts sent");
        }
        float[][] ordered = new float[expected][];
        for (int position = 0; position < expected; position++) {
            JsonNode entry = data.get(position);
            int index = entry.path("index").asInt(position);
            if (index < 0 || index >= expected || ordered[index] != null) {
                throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE,
                        "The embedding provider returned vectors that cannot be matched to the texts");
            }
            ordered[index] = vector(entry.path("embedding"));
        }
        return List.of(ordered);
    }

    private float[] vector(JsonNode values) {
        if (!values.isArray()) {
            log.error("The embedding server returned an entry without a vector");
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The embedding provider returned no vector");
        }
        if (values.size() != dimensions) {
            log.error("Embedding model {} returned vectors of {} dimensions where {} are configured: check "
                    + "stockflow.assistant.embeddings.model and dimensions", model, values.size(), dimensions);
            throw new BusinessException(ErrorCode.ASSISTANT_MISCONFIGURED,
                    "The embedding has " + values.size() + " dimensions, not " + dimensions);
        }
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            JsonNode value = values.get(i);
            float number = (float) value.asDouble();
            if (!value.isNumber() || !Float.isFinite(number)) {
                log.error("The embedding server returned a vector holding something other than a finite number");
                throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE,
                        "The embedding provider returned a vector that is not numbers");
            }
            vector[i] = number;
        }
        return vector;
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a JSON tree always serialises", impossible);
        }
    }
}
