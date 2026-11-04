package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.Reranker;
import com.stockflow.common.http.RestClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TeiRerankerTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://tei.local");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private void answers(String json) {
        server.expect(requestTo("http://tei.local/rerank")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.query").value("hàng in theo yêu cầu"))
                .andExpect(jsonPath("$.texts[1]").value("b"))
                .andExpect(jsonPath("$.truncate").value(true))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private List<Integer> rank() {
        return new TeiReranker(builder.build()).rank("hàng in theo yêu cầu", List.of("a", "b", "c"));
    }

    @Test
    @DisplayName("the passages come back best first; one the server left out keeps its place after the scored ones")
    void bestFirst() {
        answers("[{\"index\":2,\"score\":0.91},{\"index\":0,\"score\":0.12}]");

        assertThat(rank()).containsExactly(2, 0, 1);
        server.verify();
    }

    @Test
    @DisplayName("an answer naming a passage it was not sent, one twice, or no list at all is not read")
    void anUnreadableAnswer() {
        for (String json : List.of("[{\"index\":3,\"score\":0.9}]",
                "[{\"index\":1,\"score\":0.9},{\"index\":1,\"score\":0.8}]", "{\"error\":\"overloaded\"}",
                "[{\"score\":0.9}]")) {
            server.reset();
            answers(json);
            assertThatThrownBy(this::rank).as(json).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("a reranker outside the company's network is never asked; none configured is none")
    void onlyInsideTheNetwork() {
        RestClientFactory http = mock(RestClientFactory.class);
        when(http.forService(eq("tei-reranker"), any())).thenReturn(RestClient.builder());

        assertThat(new RerankerConfig().reranker(KnowledgeProperties.DEFAULTS, http)).isSameAs(Reranker.NONE);
        assertThat(new RerankerConfig().reranker(withReranker("https://rerank.example.com"), http))
                .isSameAs(Reranker.NONE);
        assertThat(new RerankerConfig().reranker(withReranker("http://localhost:8081"), http))
                .isInstanceOf(TeiReranker.class);
    }

    private static KnowledgeProperties withReranker(String baseUrl) {
        return new KnowledgeProperties(3.0, 10, false,
                new KnowledgeProperties.Reranker(baseUrl, Duration.ofSeconds(5), 20), false);
    }
}
