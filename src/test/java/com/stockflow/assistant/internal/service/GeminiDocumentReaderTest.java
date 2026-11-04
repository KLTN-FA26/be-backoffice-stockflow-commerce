package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Gemini document reader on the wire, without a network: the photo goes as an {@code inline_data} part before the
 * text, the answer is held to the schema by {@code responseSchema}, and a failure becomes the same error code as for
 * the chat.
 */
class GeminiDocumentReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AssistantProperties PROPERTIES = new AssistantProperties(true, "test-key",
            "gemini-3.5-flash", "low", 6, 16000, 50, 90, Duration.ofSeconds(90), Duration.ofMinutes(15), true, "",
            "gemini");
    private static final String PATH = "http://gemini.test/v1beta/models/gemini-3.5-flash:generateContent";
    private static final byte[] PHOTO = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    private static final String ANSWER = """
            {"candidates":[{"content":{"role":"model","parts":[
               {"text":"reading the table first","thought":true},
               {"text":"{\\"lines\\":[{\\"readText\\":\\"Cốc 12oz 1.200\\",\\"poLineNo\\":1,\\"quantity\\":1200,"},
               {"text":"\\"lotNumber\\":\\"\\",\\"expiryDate\\":\\"\\"}]}"}]},
              "finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":2100,"candidatesTokenCount":60,"thoughtsTokenCount":40,
                              "cachedContentTokenCount":0},
             "modelVersion":"gemini-3.5-flash-001"}""";

    private final List<Long> slept = new ArrayList<>();

    private static DocumentReader.Request request() {
        return new DocumentReader.Request("Read the delivery note.", "The order: {\"openLines\":[]}",
                new DocumentReader.Image(PHOTO, "image/jpeg"), ReceiptDraftService.SCHEMA);
    }

    @Test
    @DisplayName("the request: instruction as system instruction, the image as inline_data before the text, a JSON "
            + "answer held to the translated schema, the key in a header")
    void requestShape() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<String> sent = new AtomicReference<>();
        server.expect(requestTo(PATH)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(request -> sent.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        new GeminiDocumentReader(builder.build(), PROPERTIES, slept::add).read(request());

        server.verify();
        JsonNode body = JSON.readTree(sent.get());
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText())
                .isEqualTo("Read the delivery note.");
        JsonNode parts = body.path("contents").get(0).path("parts");
        assertThat(body.path("contents").get(0).path("role").asText()).isEqualTo("user");
        assertThat(parts.get(0).path("inline_data").path("mime_type").asText()).isEqualTo("image/jpeg");
        assertThat(Base64.getDecoder().decode(parts.get(0).path("inline_data").path("data").asText()))
                .isEqualTo(PHOTO);
        assertThat(parts.get(1).path("text").asText()).startsWith("The order:");
        JsonNode generation = body.path("generationConfig");
        assertThat(generation.path("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(generation.path("maxOutputTokens").asInt()).isEqualTo(16000);
        JsonNode schema = generation.path("responseSchema");
        assertThat(schema.path("type").asText()).isEqualTo("OBJECT");
        JsonNode item = schema.path("properties").path("lines").path("items");
        assertThat(schema.path("properties").path("lines").path("type").asText()).isEqualTo("ARRAY");
        assertThat(item.path("properties").path("poLineNo").path("type").asText()).isEqualTo("INTEGER");
        assertThat(item.path("propertyOrdering")).extracting(JsonNode::asText)
                .containsExactly("readText", "poLineNo", "quantity", "lotNumber", "expiryDate");
        assertThat(item.path("required")).hasSize(5);
        assertThat(sent.get()).as("Gemini's schema subset has no additionalProperties")
                .doesNotContain("additionalProperties");
        assertThat(body.has("tools")).isFalse();
    }

    @Test
    @DisplayName("an answer is the text parts joined, thoughts left out, with the model that answered and its tokens")
    void answer() throws Exception {
        DocumentReader.Reading reading = offline().reading(JSON.readTree(ANSWER));

        assertThat(reading.outcome()).isEqualTo(DocumentReader.Outcome.READ);
        assertThat(JSON.readTree(reading.json()).path("lines").get(0).path("quantity").asInt()).isEqualTo(1200);
        assertThat(reading.json()).doesNotContain("reading the table first");
        assertThat(reading.model()).isEqualTo("gemini-3.5-flash-001");
        assertThat(reading.usage()).isEqualTo(new LanguageModel.Usage(2100, 100, 0, 0));
    }

    @Test
    @DisplayName("a declined image, a blocked request, a cut-off or an empty answer read nothing")
    void nothingRead() throws Exception {
        assertThat(offline().reading(JSON.readTree("""
                {"candidates":[{"finishReason":"IMAGE_SAFETY"}]}""")).outcome())
                .isEqualTo(DocumentReader.Outcome.REFUSED);
        assertThat(offline().reading(JSON.readTree("""
                {"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}""")).outcome())
                .isEqualTo(DocumentReader.Outcome.REFUSED);
        DocumentReader.Reading cut = offline().reading(JSON.readTree("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"lines\\":[{\\"readText\\""}]},
                  "finishReason":"MAX_TOKENS"}]}"""));
        assertThat(cut.outcome()).isEqualTo(DocumentReader.Outcome.CUT_SHORT);
        assertThat(cut.json()).as("a partial answer is never handed on").isNull();
        assertThat(offline().reading(JSON.readTree("""
                {"candidates":[{"content":{"parts":[{"text":"  "}]},"finishReason":"STOP"}]}""")).outcome())
                .isEqualTo(DocumentReader.Outcome.NOTHING);
    }

    @Test
    @DisplayName("a spent daily quota fails at once as ASSISTANT_QUOTA_EXHAUSTED, without waiting")
    void quotaSpent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(PATH))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota","details":[
                                  {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                                    {"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},
                                  {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"33s"}]}}"""));

        assertThatThrownBy(() -> new GeminiDocumentReader(builder.build(), PROPERTIES, slept::add).read(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        server.verify();
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("400 is the key, the model or our request, never the photo, which was re-encoded here: "
            + "ASSISTANT_MISCONFIGURED, not retried")
    void misconfigured() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"bad\"}}"));

        assertThatThrownBy(() -> new GeminiDocumentReader(builder.build(), PROPERTIES, slept::add).read(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MISCONFIGURED));
        server.verify();
    }

    @Test
    @DisplayName("a 5xx is tried again twice, then ASSISTANT_UNAVAILABLE, which a user may retry")
    void unavailable() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.times(3), requestTo(PATH))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"message\":\"overloaded\"}}"));

        assertThatThrownBy(() -> new GeminiDocumentReader(builder.build(), PROPERTIES, slept::add).read(request()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
                    assertThat(e.errorCode().isRetryable()).isTrue();
                });
        server.verify();
        assertThat(slept).hasSize(AssistantProperties.MAX_RETRIES);
    }

    private GeminiDocumentReader offline() {
        return new GeminiDocumentReader(RestClient.create("http://gemini.test"), PROPERTIES, slept::add);
    }
}
