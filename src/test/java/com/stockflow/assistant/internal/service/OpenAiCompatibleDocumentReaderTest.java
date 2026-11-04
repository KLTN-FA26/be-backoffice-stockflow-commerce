package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.DocumentReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A delivery note read by a vision model on an OpenAI-compatible server: the photo travels as a {@code data:} URL
 * before the context, the answer is asked for as JSON of the schema, and what comes back is told apart.
 */
class OpenAiCompatibleDocumentReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenAiCompatibleDocumentReader reader = new OpenAiCompatibleDocumentReader(
            RestClient.create("http://ollama.test/v1"),
            new AssistantProperties(true, "", "qwen2.5vl:3b", "low", 6, 16000, 50, 90, Duration.ofSeconds(90),
                    Duration.ofMinutes(15), true, "", "openai"), millis -> { });

    @Test
    @DisplayName("the image comes first as a data URL, then the context; the schema is the response format")
    void body() {
        JsonNode body = reader.body(new DocumentReader.Request("Read the note.", "PO-PLAY-0001, line 1 EVAL-CUP-12OZ",
                new DocumentReader.Image(new byte[] {(byte) 0xFF, (byte) 0xD8, 1}, "image/jpeg"),
                Map.of("type", "object", "properties", Map.of("lines", Map.of("type", "array")))));

        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        JsonNode content = body.path("messages").get(1).path("content");
        assertThat(content.get(0).path("image_url").path("url").asText()).startsWith("data:image/jpeg;base64,/9gB");
        assertThat(content.get(1).path("text").asText()).startsWith("PO-PLAY-0001");
        assertThat(body.path("response_format").path("json_schema").path("schema").path("type").asText())
                .isEqualTo("object");
    }

    @Test
    @DisplayName("an answer is READ with its JSON; a cut-off, a filtered or an empty reply is told apart")
    void reading() throws Exception {
        DocumentReader.Reading read = reader.reading(JSON.readTree("""
                {"model":"qwen2.5vl:3b","choices":[{"finish_reason":"stop",
                  "message":{"role":"assistant","content":"{\\"lines\\":[]}"}}],
                 "usage":{"prompt_tokens":900,"completion_tokens":40}}"""));
        assertThat(read.outcome()).isEqualTo(DocumentReader.Outcome.READ);
        assertThat(read.json()).isEqualTo("{\"lines\":[]}");
        assertThat(read.usage().inputTokens()).isEqualTo(900);

        assertThat(reader.reading(JSON.readTree("""
                {"choices":[{"finish_reason":"length","message":{"content":"{\\"li"}}]}""")).outcome())
                .isEqualTo(DocumentReader.Outcome.CUT_SHORT);
        assertThat(reader.reading(JSON.readTree("""
                {"choices":[{"finish_reason":"content_filter","message":{"content":""}}]}""")).outcome())
                .isEqualTo(DocumentReader.Outcome.REFUSED);
        assertThat(reader.reading(JSON.readTree("""
                {"choices":[{"finish_reason":"stop","message":{"content":"  "}}]}""")).outcome())
                .isEqualTo(DocumentReader.Outcome.NOTHING);
    }
}
