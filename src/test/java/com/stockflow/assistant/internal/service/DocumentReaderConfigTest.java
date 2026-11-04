package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.http.RestClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.awt.image.BufferedImage;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The document reader follows the chat's switch, provider and key exactly: no reader where the chat has no model, and
 * never one vendor's key sent to the other with a photo.
 */
class DocumentReaderConfigTest {

    // Built at run time, so nothing key-shaped sits in the repository for a secret scanner to flag.
    private static final String ANTHROPIC_KEY = "sk-ant-" + "api03-test";
    private static final String GOOGLE_KEY = "AIza" + "test-key";

    private final RestClientFactory http = mock(RestClientFactory.class);

    private static AssistantProperties properties(boolean enabled, String provider, String apiKey) {
        return new AssistantProperties(enabled, apiKey, "some-model", "low", 6, 16000, 50, 90, Duration.ofSeconds(90),
                Duration.ofMinutes(15), true, "", provider, "", "");
    }

    @Test
    @DisplayName("switched off, or with no key, there is no reader: it refuses with ASSISTANT_DISABLED")
    void offMeansOff() throws Exception {
        for (AssistantProperties off : new AssistantProperties[] {properties(false, "gemini", GOOGLE_KEY),
                properties(true, "anthropic", "")}) {
            DocumentReader reader = new DocumentReaderConfig().documentReader(off, http);
            assertThat(reader.available()).isFalse();
            DocumentReader.Request request = new DocumentReader.Request("i", "c", new DocumentReader.Image(
                    DocumentImageProcessorTest.jpeg(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)),
                    "image/jpeg"), ReceiptDraftService.SCHEMA);
            assertThatThrownBy(() -> reader.read(request)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_DISABLED));
        }
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("a key shaped like the other vendor's is never sent, the photo with it: no reader, no client")
    void noKeyCrossesVendors() {
        assertThat(new DocumentReaderConfig().documentReader(properties(true, "gemini", ANTHROPIC_KEY), http)
                .available()).isFalse();
        assertThat(new DocumentReaderConfig().documentReader(properties(true, "anthropic", GOOGLE_KEY), http)
                .available()).isFalse();
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("each provider with its own key gets its own reader")
    void eachProviderItsReader() throws Exception {
        when(http.forService("gemini", GeminiLanguageModel.DEFAULT_BASE_URL)).thenReturn(RestClient.builder());
        assertThat(new DocumentReaderConfig().documentReader(properties(true, "gemini", GOOGLE_KEY), http))
                .isInstanceOf(GeminiDocumentReader.class);

        DocumentReader claude = new DocumentReaderConfig().documentReader(properties(true, "anthropic",
                ANTHROPIC_KEY), http);
        assertThat(claude).isInstanceOf(ClaudeDocumentReader.class);
        assertThat(claude.available()).isTrue();
        ((ClaudeDocumentReader) claude).close();
    }
}
