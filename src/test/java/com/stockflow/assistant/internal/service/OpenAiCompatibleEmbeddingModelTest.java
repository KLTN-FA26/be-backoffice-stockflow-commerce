package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.EmbeddingModel;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Vectors from an OpenAI-compatible {@code /embeddings} server, such as a local Ollama with {@code bge-m3}: in the
 * order of the texts, of the configured length, and a question tried once while a passage may wait.
 */
class OpenAiCompatibleEmbeddingModelTest {

    private static final String PATH = "http://ollama.test/v1/embeddings";

    private final List<Long> slept = new ArrayList<>();

    private OpenAiCompatibleEmbeddingModel model(RestClient.Builder builder, int dimensions) {
        return new OpenAiCompatibleEmbeddingModel(builder.build(), "bge-m3", dimensions, "", slept::add);
    }

    @Test
    @DisplayName("vectors come back in the order of the texts, whatever order the server lists them in")
    void ordered() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PATH)).andExpect(content().json("{\"model\":\"bge-m3\",\"input\":[\"a\",\"b\"]}"))
                .andRespond(withSuccess("""
                        {"data":[{"index":1,"embedding":[0.0,1.0,0.0]},{"index":0,"embedding":[1.0,0.0,0.0]}]}""",
                        MediaType.APPLICATION_JSON));

        List<float[]> vectors = model(builder, 3).embed(List.of("a", "b"), EmbeddingModel.Purpose.DOCUMENT);

        assertThat(vectors.get(0)).containsExactly(1f, 0f, 0f);
        assertThat(vectors.get(1)).containsExactly(0f, 1f, 0f);
        server.verify();
    }

    @Test
    @DisplayName("a vector of another length than configured is a misconfiguration")
    void wrongLength() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PATH)).andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1.0,2.0]}]}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model(builder, 1024).embed(List.of("a"), EmbeddingModel.Purpose.QUERY))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MISCONFIGURED));
    }

    @Test
    @DisplayName("a count that does not match the texts is refused")
    void wrongCount() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PATH)).andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1.0]}]}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model(builder, 1).embed(List.of("a", "b"), EmbeddingModel.Purpose.DOCUMENT))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
    }

    @Test
    @DisplayName("a question is tried once; passages wait out a failing server")
    void patience() {
        RestClient.Builder question = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer once = MockRestServiceServer.bindTo(question).build();
        once.expect(ExpectedCount.once(), requestTo(PATH)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> model(question, 1).embed(List.of("q"), EmbeddingModel.Purpose.QUERY))
                .isInstanceOf(BusinessException.class);
        once.verify();
        assertThat(slept).isEmpty();

        RestClient.Builder passages = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer retried = MockRestServiceServer.bindTo(passages).build();
        retried.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        retried.expect(requestTo(PATH)).andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1.0]}]}",
                MediaType.APPLICATION_JSON));
        assertThat(model(passages, 1).embed(List.of("p"), EmbeddingModel.Purpose.DOCUMENT)).hasSize(1);
        retried.verify();
        assertThat(slept).containsExactly(2000L);
    }

    @Test
    @DisplayName("more texts than a batch holds are sent in batches")
    void batches() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PATH)).andRespond(withSuccess(data(OpenAiCompatibleEmbeddingModel.MAX_BATCH),
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(PATH)).andRespond(withSuccess(data(1), MediaType.APPLICATION_JSON));

        List<String> texts = IntStream.range(0, OpenAiCompatibleEmbeddingModel.MAX_BATCH + 1)
                .mapToObj(i -> "passage " + i).toList();
        assertThat(model(builder, 1).embed(texts, EmbeddingModel.Purpose.DOCUMENT)).hasSize(texts.size());
        server.verify();
    }

    private static String data(int count) {
        StringBuilder data = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            data.append(i == 0 ? "" : ",").append("{\"index\":").append(i).append(",\"embedding\":[0.5]}");
        }
        return data.append("]}").toString();
    }
}
