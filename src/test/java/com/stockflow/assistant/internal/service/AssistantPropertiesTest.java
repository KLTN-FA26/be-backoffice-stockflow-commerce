package com.stockflow.assistant.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    @DisplayName("toString names no key")
    void toStringHidesTheKeys() {
        assertThat(properties("anthropic", "", ANTHROPIC_KEY, GOOGLE_KEY).toString())
                .contains("apiKey=<set>")
                .doesNotContain(ANTHROPIC_KEY, GOOGLE_KEY);
    }
}
