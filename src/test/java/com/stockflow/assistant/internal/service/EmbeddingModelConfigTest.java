package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.EmbeddingModel;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.http.RestClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * When the knowledge search may search by meaning, and above all which key leaves the application for it: a Gemini
 * key to Google for a Gemini assistant only, never an Anthropic key, and nothing at all under Anthropic.
 */
class EmbeddingModelConfigTest {

    // Built at run time, so nothing key-shaped sits in the repository for a secret scanner to flag.
    private static final String ANTHROPIC_KEY = "sk-ant-" + "api03-test";
    private static final String GOOGLE_KEY = "AIza" + "test-key";
    private static final String EMBEDDING_KEY = "AIza" + "embedding-key";

    private final RestClientFactory http = mock(RestClientFactory.class);

    private static AssistantProperties assistant(boolean enabled, String provider, String apiKey) {
        return new AssistantProperties(enabled, apiKey, "some-model", "low", 6, 16000, 50, 90, Duration.ofSeconds(90),
                Duration.ofMinutes(15), true, "", provider, "", "");
    }

    private static EmbeddingProperties embeddings(boolean enabled, String apiKey) {
        return new EmbeddingProperties(enabled, apiKey, "gemini-embedding-001", 768);
    }

    private EmbeddingModel model(EmbeddingProperties embeddings, AssistantProperties assistant) {
        return new EmbeddingModelConfig().embeddingModel(embeddings, assistant, http);
    }

    @Test
    @DisplayName("a Gemini assistant with a key searches by meaning with Gemini, at the length and model configured")
    void aGeminiAssistantSearchesByMeaning() {
        when(http.forService("gemini-embeddings", GeminiLanguageModel.DEFAULT_BASE_URL))
                .thenReturn(RestClient.builder());

        EmbeddingModel model = model(embeddings(true, ""), assistant(true, "gemini", GOOGLE_KEY));

        assertThat(model).isInstanceOf(GeminiEmbeddingModel.class);
        assertThat(model.available()).isTrue();
        assertThat(model.name()).isEqualTo("gemini-embedding-001");
        assertThat(model.dimensions()).isEqualTo(768);
    }

    @Test
    @DisplayName("the key sent is the embeddings' own when set, else the Gemini assistant's")
    void whichKeyIsSent() {
        assertKeySent(embeddings(true, EMBEDDING_KEY), EMBEDDING_KEY);
        assertKeySent(embeddings(true, ""), GOOGLE_KEY);
    }

    /** Asks one question's vector through the configured model, against a server expecting {@code key}. */
    private void assertKeySent(EmbeddingProperties embeddings, String key) {
        // As RestClientFactory builds it: based at the API's root.
        RestClient.Builder builder = RestClient.builder().baseUrl(GeminiLanguageModel.DEFAULT_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(GeminiLanguageModel.DEFAULT_BASE_URL
                        + "/v1beta/models/gemini-embedding-001:embedContent"))
                .andExpect(header("x-goog-api-key", key))
                .andRespond(withSuccess("{\"embedding\":{\"values\":[" + "0.5,".repeat(767) + "0.5]}}",
                        MediaType.APPLICATION_JSON));
        when(http.forService("gemini-embeddings", GeminiLanguageModel.DEFAULT_BASE_URL)).thenReturn(builder);

        model(embeddings, assistant(true, "gemini", GOOGLE_KEY)).embed(List.of("question"),
                EmbeddingModel.Purpose.QUERY);

        server.verify();
    }

    @Test
    @DisplayName("under provider anthropic nothing is embedded, even with a key of its own: no client is built")
    void anthropicStaysKeywordOnly() {
        EmbeddingModel model = model(embeddings(true, EMBEDDING_KEY), assistant(true, "anthropic", ANTHROPIC_KEY));

        assertThat(model.available()).isFalse();
        verifyNoInteractions(http);
        assertThat(embeddings(true, EMBEDDING_KEY).keyFor(assistant(true, "anthropic", ANTHROPIC_KEY))).isEmpty();
    }

    @Test
    @DisplayName("switched off, or with an assistant that cannot answer, the search is keyword-only")
    void offOrUnusable() {
        assertThat(model(embeddings(false, ""), assistant(true, "gemini", GOOGLE_KEY)).available()).isFalse();
        assertThat(model(embeddings(true, ""), assistant(false, "gemini", GOOGLE_KEY)).available()).isFalse();
        assertThat(model(embeddings(true, ""), assistant(true, "gemini", "")).available()).isFalse();
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("an embeddings key shaped like Anthropic's is never sent to Google")
    void anAnthropicKeyNeverGoesToGoogle() {
        EmbeddingModel model = model(embeddings(true, ANTHROPIC_KEY), assistant(true, "gemini", GOOGLE_KEY));

        assertThat(model.available()).isFalse();
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("the keyword-only model refuses to embed")
    void unavailableRefuses() {
        EmbeddingModel model = model(embeddings(false, ""), assistant(true, "gemini", GOOGLE_KEY));

        assertThatThrownBy(() -> model.embed(List.of("x"), EmbeddingModel.Purpose.QUERY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_DISABLED));
    }

    @Test
    @DisplayName("the properties refuse a blank model and a length Gemini does not make, and never print the key")
    void properties() {
        assertThatThrownBy(() -> new EmbeddingProperties(true, "", " ", 768))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingProperties(true, "", "gemini-embedding-001", 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingProperties(true, "", "gemini-embedding-001", 3073))
                .isInstanceOf(IllegalArgumentException.class);

        EmbeddingProperties trimmed = new EmbeddingProperties(true, "  " + EMBEDDING_KEY + " ",
                " gemini-embedding-001 ", 1536);
        assertThat(trimmed.apiKey()).isEqualTo(EMBEDDING_KEY);
        assertThat(trimmed.model()).isEqualTo("gemini-embedding-001");
        assertThat(trimmed.toString()).contains("apiKey=<set>").doesNotContain(EMBEDDING_KEY);
    }
}
