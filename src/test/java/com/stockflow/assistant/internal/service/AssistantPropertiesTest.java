package com.stockflow.assistant.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which key the assistant ends up with: {@code ASSISTANT_API_KEY}, else the chosen provider's own variable, and never
 * a key that belongs to the other vendor.
 */
class AssistantPropertiesTest {

    // Built at run time, so nothing key-shaped sits in the repository for a secret scanner to flag.
    private static final String ANTHROPIC_KEY = "sk-ant-" + "api03-test";
    private static final String GOOGLE_KEY = "AIza" + "test-key";

    private static AssistantProperties properties(String provider, String apiKey, String anthropicKey,
                                                  String geminiKey) {
        return new AssistantProperties(true, apiKey, "some-model", "low", 6, 16000, 50, 90, Duration.ofSeconds(90),
                Duration.ofMinutes(15), true, "", provider, anthropicKey, geminiKey);
    }

    @Test
    @DisplayName("without ASSISTANT_API_KEY each provider takes its own variable, never the other vendor's")
    void eachProviderTakesItsOwnKey() {
        assertThat(properties("anthropic", "", ANTHROPIC_KEY, GOOGLE_KEY).apiKey()).isEqualTo(ANTHROPIC_KEY);
        assertThat(properties("gemini", "", ANTHROPIC_KEY, GOOGLE_KEY).apiKey()).isEqualTo(GOOGLE_KEY);

        // The rotation accident: the Gemini key is briefly missing while the evaluation's Anthropic key is set.
        AssistantProperties geminiWithoutItsKey = properties("gemini", "", ANTHROPIC_KEY, "");
        assertThat(geminiWithoutItsKey.apiKey()).isEmpty();
        assertThat(geminiWithoutItsKey.usable()).isFalse();
        assertThat(geminiWithoutItsKey.keyVariable()).isEqualTo("GEMINI_API_KEY");
        assertThat(properties("anthropic", "", "", GOOGLE_KEY).apiKey()).isEmpty();
        assertThat(properties("anthropic", "", "", GOOGLE_KEY).keyVariable()).isEqualTo("ANTHROPIC_API_KEY");
    }

    @Test
    @DisplayName("ASSISTANT_API_KEY wins over the provider's own variable, trimmed")
    void theAssistantKeyWins() {
        AssistantProperties explicit = properties("gemini", "  " + GOOGLE_KEY + "-2 ", "", GOOGLE_KEY);

        assertThat(explicit.apiKey()).isEqualTo(GOOGLE_KEY + "-2");
        assertThat(explicit.usable()).isTrue();
    }

    @Test
    @DisplayName("a key shaped like the other vendor's is not usable, under either provider")
    void anotherVendorsKeyIsNotUsable() {
        assertThat(properties("gemini", ANTHROPIC_KEY, "", "").keyOfAnotherVendor()).isTrue();
        assertThat(properties("gemini", ANTHROPIC_KEY, "", "").usable()).isFalse();
        assertThat(properties("anthropic", GOOGLE_KEY, "", "").keyOfAnotherVendor()).isTrue();
        assertThat(properties("anthropic", GOOGLE_KEY, "", "").usable()).isFalse();

        assertThat(properties("anthropic", ANTHROPIC_KEY, "", "").usable()).isTrue();
        assertThat(properties("gemini", GOOGLE_KEY, "", "").usable()).isTrue();
        // A proxy's own key has neither shape and is used as given.
        assertThat(properties("anthropic", "proxy-key", "", "").usable()).isTrue();
    }

    @Test
    @DisplayName("the settings built with a key given directly have nothing to fall back to")
    void aKeyGivenDirectly() {
        AssistantProperties direct = new AssistantProperties(true, "", "some-model", "low", 6, 16000, 50, 90,
                Duration.ofSeconds(90), Duration.ofMinutes(15), true, "", "gemini");

        assertThat(direct.apiKey()).isEmpty();
        assertThat(direct.anthropicApiKey()).isEmpty();
        assertThat(direct.geminiApiKey()).isEmpty();
        assertThat(direct.usable()).isFalse();
    }

    private static AssistantProperties openai(String apiKey, String baseUrl, String serverKey, DataPolicy policy) {
        return new AssistantProperties(true, apiKey, "qwen3:8b", "low", 6, 16000, 50, 90, Duration.ofSeconds(90),
                Duration.ofMinutes(15), true, "", "openai", ANTHROPIC_KEY, GOOGLE_KEY,
                new AssistantProperties.OpenAiCompatible(baseUrl, serverKey), policy);
    }

    @Test
    @DisplayName("an OpenAI-compatible server needs no key, takes only its own, and never a cloud vendor's")
    void openaiCompatible() {
        AssistantProperties local = openai("", null, "", DataPolicy.CLOUD_ALLOWED);
        assertThat(local.usable()).isTrue();
        assertThat(local.apiKey()).as("neither cloud vendor's key").isEmpty();
        assertThat(local.endpoint()).isEqualTo(AssistantProperties.OpenAiCompatible.OLLAMA);
        assertThat(local.keyVariable()).isEqualTo("OPENAI_COMPATIBLE_API_KEY");

        assertThat(openai("", "http://vllm:8000/v1/", "vllm-token", DataPolicy.CLOUD_ALLOWED).apiKey())
                .isEqualTo("vllm-token");
        assertThat(openai("", "http://vllm:8000/v1/", "", DataPolicy.CLOUD_ALLOWED).endpoint())
                .as("trailing slash dropped").isEqualTo("http://vllm:8000/v1");
        assertThat(openai(GOOGLE_KEY, null, "", DataPolicy.CLOUD_ALLOWED).usable()).isFalse();
        assertThat(openai("", null, ANTHROPIC_KEY, DataPolicy.CLOUD_ALLOWED).usable()).isFalse();
    }

    @Test
    @DisplayName("LOCAL_ONLY leaves a cloud provider unusable, and a model server on a public host too")
    void localOnly() {
        AssistantProperties gemini = new AssistantProperties(true, GOOGLE_KEY, "gemini-3.5-flash", "low", 6, 16000,
                50, 90, Duration.ofSeconds(90), Duration.ofMinutes(15), true, "", "gemini", "", "", null,
                DataPolicy.LOCAL_ONLY);
        assertThat(gemini.usable()).isFalse();
        assertThat(gemini.allowedByPolicy()).isFalse();

        assertThat(openai("", "http://localhost:11434/v1", "", DataPolicy.LOCAL_ONLY).usable()).isTrue();
        assertThat(openai("", "https://models.example.com/v1", "", DataPolicy.LOCAL_ONLY).usable()).isFalse();
        assertThat(openai("", "https://models.example.com/v1", "", DataPolicy.CLOUD_ALLOWED).usable()).isTrue();
    }

    @Test
    @DisplayName("an unknown provider fails the start")
    void unknownProvider() {
        assertThatThrownBy(() -> properties("mistral", "key", "", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("openai");
    }

    @Test
    @DisplayName("toString names no key")
    void toStringHidesTheKeys() {
        assertThat(properties("anthropic", "", ANTHROPIC_KEY, GOOGLE_KEY).toString())
                .contains("apiKey=<set>")
                .doesNotContain(ANTHROPIC_KEY, GOOGLE_KEY);
    }
}
