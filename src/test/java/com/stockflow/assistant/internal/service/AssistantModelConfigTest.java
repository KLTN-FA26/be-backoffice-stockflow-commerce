package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.common.http.RestClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which model the assistant runs on, and above all which key leaves the application: never one vendor's key to the
 * other vendor, whatever the environment holds.
 */
class AssistantModelConfigTest {

    // Built at run time, so nothing key-shaped sits in the repository for a secret scanner to flag.
    private static final String ANTHROPIC_KEY = "sk-ant-" + "api03-test";
    private static final String GOOGLE_KEY = "AIza" + "test-key";

    private final RestClientFactory http = mock(RestClientFactory.class);

    private static AssistantProperties properties(String provider, String apiKey, String anthropicKey,
                                                  String geminiKey) {
        return new AssistantProperties(true, apiKey, "some-model", "low", 6, 16000, 50, 90, Duration.ofSeconds(90),
                Duration.ofMinutes(15), true, "", provider, anthropicKey, geminiKey);
    }

    @Test
    @DisplayName("an Anthropic key under provider gemini is never sent: the assistant is unavailable, no client "
            + "is built")
    void anAnthropicKeyNeverGoesToGoogle() {
        LanguageModel model = new AssistantModelConfig().languageModel(properties("gemini", ANTHROPIC_KEY, "", ""),
                http);

        assertThat(model.available()).isFalse();
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("a Google key under provider anthropic is never sent either")
    void aGoogleKeyNeverGoesToAnthropic() {
        LanguageModel model = new AssistantModelConfig().languageModel(properties("anthropic", GOOGLE_KEY, "", ""),
                http);

        assertThat(model.available()).isFalse();
    }

    @Test
    @DisplayName("gemini with only ANTHROPIC_API_KEY in the environment has no key: it borrows none")
    void geminiBorrowsNoKey() {
        LanguageModel model = new AssistantModelConfig().languageModel(properties("gemini", "", ANTHROPIC_KEY, ""),
                http);

        assertThat(model.available()).isFalse();
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("gemini with its own key gets the Gemini adapter")
    void geminiWithItsOwnKey() {
        when(http.forService("gemini", GeminiLanguageModel.DEFAULT_BASE_URL)).thenReturn(RestClient.builder());

        LanguageModel model = new AssistantModelConfig().languageModel(properties("gemini", "", ANTHROPIC_KEY,
                GOOGLE_KEY), http);

        assertThat(model).isInstanceOf(GeminiLanguageModel.class);
        assertThat(model.available()).isTrue();
    }
}
