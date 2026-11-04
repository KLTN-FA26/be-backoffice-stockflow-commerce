package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.assistant.internal.domain.LanguageModel;
import org.springframework.web.client.RestClient;

import java.util.Base64;

/**
 * {@link DocumentReader} on a server speaking OpenAI's chat-completions API with images, such as a vision model on a
 * local Ollama (design §8.1): a photographed delivery note read without the photo leaving the company's network.
 *
 * <p><b>The request.</b> The instruction is the system message; the one user message holds the image first, as a
 * {@code data:} URL, then the context as text, the order {@link GeminiDocumentReader} uses. The answer is asked for as
 * JSON matching the schema ({@code response_format} {@code json_schema}); a server that ignores it still has the
 * instruction's description of the fields, and {@code ReceiptDraftService} checks every value in Java either way.</p>
 *
 * <p><b>Failures</b> are {@link OpenAiCompatibleCall}'s, as for the chat. Nothing of the document reaches a log.</p>
 */
class OpenAiCompatibleDocumentReader implements DocumentReader {

    private final OpenAiCompatibleCall call;
    private final AssistantProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    /** @param http based at the server's API root */
    OpenAiCompatibleDocumentReader(RestClient http, AssistantProperties properties) {
        this(http, properties, Thread::sleep);
    }

    OpenAiCompatibleDocumentReader(RestClient http, AssistantProperties properties, Sleeper sleeper) {
        this.call = new OpenAiCompatibleCall(http, properties.apiKey(), sleeper, " (document)");
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Reading read(Request request) {
        return reading(call.post("/chat/completions", write(body(request)), true));
    }

    ObjectNode body(Request request) {
        ObjectNode body = JsonNodeFactory.instance.objectNode()
                .put("model", properties.model())
                .put("stream", false)
                .put("max_tokens", OpenAiCompatibleLanguageModel.maxTokens(properties));
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.instruction());
        ArrayNode content = messages.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "image_url").putObject("image_url").put("url", "data:"
                + request.image().mediaType() + ";base64,"
                + Base64.getEncoder().encodeToString(request.image().bytes()));
        content.addObject().put("type", "text").put("text", request.context());
        ObjectNode format = body.putObject("response_format").put("type", "json_schema");
        format.putObject("json_schema").put("name", "reading").put("strict", false)
                .set("schema", json.valueToTree(request.schema()));
        return body;
    }

    Reading reading(JsonNode response) {
        String model = response.path("model").asText(properties.model());
        JsonNode usage = response.path("usage");
        long prompt = usage.path("prompt_tokens").asLong(0);
        long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        LanguageModel.Usage tokens = new LanguageModel.Usage(Math.max(0, prompt - cached),
                usage.path("completion_tokens").asLong(0), cached, 0);
        JsonNode choice = response.path("choices").path(0);
        if (choice.isMissingNode()) {
            return new Reading(Outcome.NOTHING, null, tokens, model);
        }
        String finish = choice.path("finish_reason").asText("");
        if ("content_filter".equals(finish)) {
            return new Reading(Outcome.REFUSED, null, tokens, model);
        }
        if ("length".equals(finish)) {
            return new Reading(Outcome.CUT_SHORT, null, tokens, model);
        }
        String answer = OpenAiCompatibleLanguageModel.visibleText(choice.path("message").path("content"));
        return answer == null ? new Reading(Outcome.NOTHING, null, tokens, model)
                : new Reading(Outcome.READ, answer, tokens, model);
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a JSON tree always serialises", impossible);
        }
    }
}
