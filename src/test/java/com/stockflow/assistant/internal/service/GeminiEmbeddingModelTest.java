package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.EmbeddingModel.Purpose;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Gemini embedding adapter without a network: what a question and a batch of passages are sent as, where the key
 * goes, which failures are tried again (passages only), and which error code each failure becomes.
 */
class GeminiEmbeddingModelTest {

    private static final String QUERY_URL = "http://gemini.test/v1beta/models/gemini-embedding-001:embedContent";
    private static final String BATCH_URL = "http://gemini.test/v1beta/models/gemini-embedding-001:batchEmbedContents";

    /** The free tier's per-minute quota: it frees up within the minute. */
    private static final String MINUTE_SPENT = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota",
              "details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                  {"quotaId":"EmbedContentRequestsPerMinutePerProjectPerModel-FreeTier"}]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"3s"}]}}""";

    /** The daily quota, as Google words it for the generate endpoints too. */
    private static final String DAY_SPENT = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"You exceeded your current quota.",
              "details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                  {"quotaId":"EmbedContentRequestsPerDayPerProjectPerModel-FreeTier"}]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"33s"}]}}""";

    private final List<Long> slept = new ArrayList<>();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private GeminiEmbeddingModel model() {
        return new GeminiEmbeddingModel(builder.build(), "gemini-embedding-001", 3, "test-key", slept::add);
    }

    /** A {@code batchEmbedContents} answer: vector i is {i, 1, 0}. */
    private static String batch(int count) {
        return IntStream.range(0, count).mapToObj(i -> "{\"values\":[" + i + ",1,0]}")
                .collect(Collectors.joining(",", "{\"embeddings\":[", "]}"));
    }

    @Test
    @DisplayName("a question is one embedContent call: RETRIEVAL_QUERY, the length asked for, the key in a header")
    void aQuestion() {
        server.expect(ExpectedCount.once(), requestTo(QUERY_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.model").value("models/gemini-embedding-001"))
                .andExpect(jsonPath("$.content.parts[0].text").value("hàng in theo yêu cầu có được trả lại không"))
                .andExpect(jsonPath("$.taskType").value("RETRIEVAL_QUERY"))
                .andExpect(jsonPath("$.outputDimensionality").value(3))
                .andRespond(withSuccess("{\"embedding\":{\"values\":[0.25,-0.5,6.103515625E-5]}}",
                        MediaType.APPLICATION_JSON));

        List<float[]> vectors = model().embed(List.of("hàng in theo yêu cầu có được trả lại không"), Purpose.QUERY);

        assertThat(vectors).hasSize(1);
        // Values a float holds exactly (the last is 2^-14), so the comparison is exact.
        assertThat(vectors.getFirst()).containsExactly(0.25f, -0.5f, 6.103515625E-5f);
        server.verify();
    }

    @Test
    @DisplayName("passages are one batchEmbedContents call, RETRIEVAL_DOCUMENT each, the vectors in the order sent")
    void passages() {
        server.expect(ExpectedCount.once(), requestTo(BATCH_URL))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.requests.length()").value(3))
                .andExpect(jsonPath("$.requests[0].model").value("models/gemini-embedding-001"))
                .andExpect(jsonPath("$.requests[1].taskType").value("RETRIEVAL_DOCUMENT"))
                .andExpect(jsonPath("$.requests[2].content.parts[0].text").value("third"))
                .andExpect(jsonPath("$.requests[2].outputDimensionality").value(3))
                .andRespond(withSuccess(batch(3), MediaType.APPLICATION_JSON));

        List<float[]> vectors = model().embed(List.of("first", "second", "third"), Purpose.DOCUMENT);

        assertThat(vectors).extracting(vector -> vector[0]).containsExactly(0f, 1f, 2f);
        server.verify();
    }

    @Test
    @DisplayName("more passages than one batch may hold are sent in batches of a hundred")
    void largeBatchesAreSplit() {
        server.expect(ExpectedCount.once(), requestTo(BATCH_URL))
                .andExpect(jsonPath("$.requests.length()").value(GeminiEmbeddingModel.MAX_BATCH))
                .andRespond(withSuccess(batch(GeminiEmbeddingModel.MAX_BATCH), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(BATCH_URL))
                .andExpect(jsonPath("$.requests.length()").value(50))
                .andExpect(jsonPath("$.requests[0].content.parts[0].text").value("passage 100"))
                .andRespond(withSuccess(batch(50), MediaType.APPLICATION_JSON));

        List<float[]> vectors = model().embed(IntStream.range(0, 150).mapToObj(i -> "passage " + i).toList(),
                Purpose.DOCUMENT);

        assertThat(vectors).hasSize(150);
        assertThat(vectors.get(120)[0]).as("the second batch's 21st").isEqualTo(20f);
        server.verify();
    }

    @Test
    @DisplayName("nothing to embed calls nothing")
    void nothingToEmbed() {
        assertThat(model().embed(List.of(), Purpose.DOCUMENT)).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    @DisplayName("400, 401, 403 and 404 are the key, the model or the request: ASSISTANT_MISCONFIGURED, never retried")
    void misconfigured(int status) {
        server.expect(ExpectedCount.once(), requestTo(BATCH_URL))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":" + status + ",\"status\":\"INVALID_ARGUMENT\","
                                + "\"message\":\"API key not valid. Please pass a valid API key.\"}}"));

        assertThatThrownBy(() -> model().embed(List.of("a", "b"), Purpose.DOCUMENT))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MISCONFIGURED);
                    assertThat(e.getMessage()).doesNotContain("API key not valid");
                });
        assertThat(slept).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a spent daily quota is ASSISTANT_QUOTA_EXHAUSTED at once, even for passages")
    void dailyQuota() {
        server.expect(ExpectedCount.once(), requestTo(BATCH_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body(DAY_SPENT));

        assertThatThrownBy(() -> model().embed(List.of("a", "b"), Purpose.DOCUMENT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        assertThat(slept).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("passages wait out the per-minute quota as RetryInfo asks and are sent again")
    void passagesWaitForThePerMinuteQuota() {
        server.expect(ExpectedCount.once(), requestTo(BATCH_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body(MINUTE_SPENT));
        server.expect(ExpectedCount.once(), requestTo(BATCH_URL))
                .andRespond(withSuccess(batch(2), MediaType.APPLICATION_JSON));

        assertThat(model().embed(List.of("a", "b"), Purpose.DOCUMENT)).hasSize(2);
        assertThat(slept).containsExactly(3000L);
        server.verify();
    }

    @Test
    @DisplayName("passages try a 5xx again twice, then ASSISTANT_UNAVAILABLE")
    void passagesRetryServerErrors() {
        server.expect(ExpectedCount.times(3), requestTo(QUERY_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"message\":\"overloaded\"}}"));

        assertThatThrownBy(() -> model().embed(List.of("one passage"), Purpose.DOCUMENT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThat(slept).containsExactly(2000L, 4000L);
        server.verify();
    }

    @Test
    @DisplayName("a question is never waited for: the per-minute quota and a timeout fail at once, and the search "
            + "goes by keyword")
    void aQuestionDoesNotWait() {
        server.expect(ExpectedCount.once(), requestTo(QUERY_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body(MINUTE_SPENT));
        server.expect(ExpectedCount.once(), requestTo(QUERY_URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        GeminiEmbeddingModel model = model();

        assertThatThrownBy(() -> model.embed(List.of("question"), Purpose.QUERY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThatThrownBy(() -> model.embed(List.of("question"), Purpose.QUERY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThat(slept).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a vector of another length is a misconfiguration; a missing vector or a count that does not match "
            + "is the provider's failure")
    void malformedAnswers() {
        server.expect(ExpectedCount.once(), requestTo(QUERY_URL))
                .andRespond(withSuccess("{\"embedding\":{\"values\":[0.1,0.2,0.3,0.4]}}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(QUERY_URL))
                .andRespond(withSuccess("{\"embedding\":{}}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(BATCH_URL))
                .andRespond(withSuccess(batch(2), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(QUERY_URL))
                .andRespond(withSuccess("{\"embedding\":{\"values\":[0.1,\"x\",0.3]}}", MediaType.APPLICATION_JSON));
        GeminiEmbeddingModel model = model();

        assertThatThrownBy(() -> model.embed(List.of("q"), Purpose.QUERY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MISCONFIGURED));
        assertThatThrownBy(() -> model.embed(List.of("q"), Purpose.QUERY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThatThrownBy(() -> model.embed(List.of("a", "b", "c"), Purpose.DOCUMENT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThatThrownBy(() -> model.embed(List.of("q"), Purpose.QUERY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        server.verify();
    }
}
