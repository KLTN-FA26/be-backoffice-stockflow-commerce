package com.stockflow.assistant.internal.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Claude document reader against the SDK's own types, without a network: the photo is a base64 image block before
 * the text, the answer is held to the schema by structured outputs rather than a tool, and a refusal or a cut-off
 * answer is never read as data.
 */
class ClaudeDocumentReaderTest {

    private static final AssistantProperties PROPERTIES = new AssistantProperties(true, "sk-ant-" + "test",
            "claude-opus-5-5", "low", 6, 16000, 50, 90, Duration.ofSeconds(90), Duration.ofMinutes(15), true, "",
            "anthropic");
    private static final byte[] PHOTO = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 9, 8, 7};

    private static DocumentReader.Request request() {
        return new DocumentReader.Request("Read the delivery note.", "The order: {\"openLines\":[]}",
                new DocumentReader.Image(PHOTO, "image/jpeg"), ReceiptDraftService.SCHEMA);
    }

    @Test
    @DisplayName("the request: system prompt, then one user message with the image block before the text, and the "
            + "schema as output_config.format; no tools, no thinking settings, no temperature")
    void requestShape() throws Exception {
        MessageCreateParams params = new ClaudeDocumentReader(null, PROPERTIES).params(request());

        JsonNode body = ObjectMappers.jsonMapper().readTree(ObjectMappers.jsonMapper()
                .writeValueAsString(params._body()));
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(16000);
        assertThat(body.path("system").get(0).path("text").asText()).isEqualTo("Read the delivery note.");
        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("user");
        JsonNode image = messages.get(0).path("content").get(0);
        assertThat(image.path("type").asText()).isEqualTo("image");
        assertThat(image.path("source").path("type").asText()).isEqualTo("base64");
        assertThat(image.path("source").path("media_type").asText()).isEqualTo("image/jpeg");
        assertThat(Base64.getDecoder().decode(image.path("source").path("data").asText())).isEqualTo(PHOTO);
        assertThat(messages.get(0).path("content").get(1).path("text").asText()).startsWith("The order:");

        JsonNode output = body.path("output_config");
        assertThat(output.path("effort").asText()).isEqualTo("low");
        assertThat(output.path("format").path("type").asText()).isEqualTo("json_schema");
        JsonNode schema = output.path("format").path("schema");
        assertThat(schema).isEqualTo(ObjectMappers.jsonMapper().valueToTree(ReceiptDraftService.SCHEMA));
        assertThat(schema.path("properties").path("lines").path("items").path("properties").fieldNames())
                .toIterable().containsExactly("readText", "poLineNo", "quantity", "lotNumber", "expiryDate");
        assertThat(body.has("tools")).isFalse();
        assertThat(body.has("tool_choice")).isFalse();
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("temperature")).isFalse();

        // The refusal fallback, as for the chat.
        assertThat(params._additionalBodyProperties()).containsEntry("fallbacks", JsonValue.from("default"));
        assertThat(params._headers().values("anthropic-beta")).contains("server-side-fallback-2026-07-01");
    }

    @Test
    @DisplayName("an end_turn with text is the answer; a refusal, a cut-off and an empty one read nothing")
    void readings() throws Exception {
        DocumentReader.Reading read = ClaudeDocumentReader.reading(message("end_turn",
                "{\"type\":\"text\",\"text\":\"{\\\"lines\\\":[]}\"}"));
        assertThat(read.outcome()).isEqualTo(DocumentReader.Outcome.READ);
        assertThat(read.json()).isEqualTo("{\"lines\":[]}");
        assertThat(read.model()).isEqualTo("claude-opus-5-5");
        assertThat(read.usage()).isEqualTo(new LanguageModel.Usage(2400, 90, 0, 0));

        assertThat(ClaudeDocumentReader.reading(message("refusal",
                "{\"type\":\"text\",\"text\":\"I can't help with that.\"}")).outcome())
                .isEqualTo(DocumentReader.Outcome.REFUSED);
        DocumentReader.Reading cut = ClaudeDocumentReader.reading(message("max_tokens",
                "{\"type\":\"text\",\"text\":\"{\\\"lines\\\":[{\"}"));
        assertThat(cut.outcome()).isEqualTo(DocumentReader.Outcome.CUT_SHORT);
        assertThat(cut.json()).isNull();
        assertThat(ClaudeDocumentReader.reading(message("end_turn",
                "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"}")).outcome())
                .as("thinking alone is no answer").isEqualTo(DocumentReader.Outcome.NOTHING);
    }

    @Test
    @DisplayName("what the SDK throws becomes the chat's codes: a refused key is MISCONFIGURED, a timeout UNAVAILABLE")
    void failures() {
        MessageService messages = mock(MessageService.class);
        AnthropicClient client = mock(AnthropicClient.class);
        when(client.messages()).thenReturn(messages);
        when(messages.create(any(MessageCreateParams.class))).thenThrow(
                UnauthorizedException.builder().headers(Headers.builder().build())
                        .body(JsonValue.from(Map.of("type", "error", "error",
                                Map.of("type", "authentication_error", "message", "invalid x-api-key"))))
                        .build(),
                new AnthropicIoException("Request timed out"));
        ClaudeDocumentReader reader = new ClaudeDocumentReader(client, PROPERTIES);

        assertThatThrownBy(() -> reader.read(request())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MISCONFIGURED));
        assertThatThrownBy(() -> reader.read(request())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
    }

    private static Message message(String stopReason, String block) throws Exception {
        return ObjectMappers.jsonMapper().readValue("""
                {"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[%s],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":2400,"output_tokens":90,"cache_read_input_tokens":0,
                          "cache_creation_input_tokens":0}}""".formatted(block, stopReason), Message.class);
    }
}
