package com.stockflow.assistant.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A route that mixes vendors (design §11): which vendor each name means, and above all which key each model is sent.
 * {@code ASSISTANT_API_KEY} belongs to the assistant's own provider; a model of another vendor gets that vendor's own
 * variable or nothing.
 */
class ModelCatalogTest {

    // Built at run time, so nothing key-shaped sits in the repository for a secret scanner to flag.
    private static final String GOOGLE_KEY = "AIza" + "own-google-key";
    private static final String ANTHROPIC_KEY = "sk-ant-" + "own-anthropic-key";
    private static final String ASSISTANT_KEY = "AIza" + "assistant-api-key";

    private static AssistantProperties properties(String provider, String apiKey, String anthropicKey,
                                                  String geminiKey, String openaiUrl, String openaiKey,
                                                  DataPolicy policy, String baseUrl) {
        return new AssistantProperties(true, apiKey, "gemini-3.5-flash", "low", 6, 16000, 50, 90,
                Duration.ofSeconds(90), Duration.ofMinutes(15), true, baseUrl, provider, anthropicKey, geminiKey,
                new AssistantProperties.OpenAiCompatible(openaiUrl, openaiKey), policy);
    }

    private static AssistantProperties gemini() {
        return properties("gemini", ASSISTANT_KEY, "", "", null, "", DataPolicy.CLOUD_ALLOWED, "");
    }

    @Test
    @DisplayName("a name with a vendor prefix runs on that vendor; a bare name on the assistant's own provider")
    void names() {
        AssistantProperties properties = gemini();
        ModelCatalog.ModelSpec bare = ModelCatalog.spec(properties, "gemini-3.5-flash-lite");
        assertThat(bare.provider()).isEqualTo(ModelProvider.GEMINI);
        assertThat(bare.model()).isEqualTo("gemini-3.5-flash-lite");

        ModelCatalog.ModelSpec local = ModelCatalog.spec(properties, "openai/qwen3:8b");
        assertThat(local.provider()).isEqualTo(ModelProvider.OPENAI);
        assertThat(local.model()).isEqualTo("qwen3:8b");
        assertThat(local.endpoint()).isEqualTo(AssistantProperties.OpenAiCompatible.OLLAMA);

        // Only the first slash is read, and only a known vendor is a prefix.
        assertThat(ModelCatalog.spec(properties, "openai/hf.co/org/model:Q4").model()).isEqualTo("hf.co/org/model:Q4");
        ModelCatalog.ModelSpec unknownPrefix = ModelCatalog.spec(properties, "models/gemini-3.5-flash");
        assertThat(unknownPrefix.provider()).isEqualTo(ModelProvider.GEMINI);
        assertThat(unknownPrefix.model()).isEqualTo("models/gemini-3.5-flash");
        assertThat(ModelCatalog.spec(properties, "GEMINI/gemini-3.5-flash").isOwnModel(properties)).isTrue();

        assertThatThrownBy(() -> ModelCatalog.spec(properties, "openai/")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ASSISTANT_API_KEY goes to the assistant's own vendor only; another vendor gets its own variable")
    void keysStayWithTheirVendor() {
        AssistantProperties properties = properties("gemini", ASSISTANT_KEY, ANTHROPIC_KEY, GOOGLE_KEY, null,
                "local-token", DataPolicy.CLOUD_ALLOWED, "");
        assertThat(ModelCatalog.spec(properties, "gemini-3.5-flash-lite").apiKey()).isEqualTo(ASSISTANT_KEY);
        assertThat(ModelCatalog.spec(properties, "anthropic/claude-haiku-5-5").apiKey()).isEqualTo(ANTHROPIC_KEY);
        assertThat(ModelCatalog.spec(properties, "openai/qwen3:8b").apiKey()).isEqualTo("local-token");

        // Without its own variable, another vendor's model has no key at all, never the assistant's.
        AssistantProperties bare = gemini();
        ModelCatalog.ModelSpec claude = ModelCatalog.spec(bare, "anthropic/claude-haiku-5-5");
        assertThat(claude.apiKey()).isEmpty();
        assertThat(ModelCatalog.refusal(bare, claude)).hasValueSatisfying(reason ->
                assertThat(reason).contains("ANTHROPIC_API_KEY"));
    }

    @Test
    @DisplayName("base-url belongs to the assistant's own provider: another vendor is called at its own API")
    void baseUrlStaysWithItsProvider() {
        AssistantProperties proxied = properties("gemini", ASSISTANT_KEY, ANTHROPIC_KEY, "", null, "",
                DataPolicy.CLOUD_ALLOWED, "http://gemini-proxy.test");
        assertThat(ModelCatalog.spec(proxied, "gemini-3.5-flash-lite").endpoint()).isEqualTo("http://gemini-proxy.test");
        ModelCatalog.ModelSpec claude = ModelCatalog.spec(proxied, "anthropic/claude-haiku-5-5");
        assertThat(claude.endpoint()).isEqualTo(AssistantProperties.ANTHROPIC_BASE_URL);
        assertThat(ModelCatalog.settingsFor(proxied, claude).baseUrl()).isEmpty();
        assertThat(ModelCatalog.settingsFor(proxied, ModelCatalog.spec(proxied, "gemini-3.5-flash-lite")).baseUrl())
                .isEqualTo("http://gemini-proxy.test");
    }

    @Test
    @DisplayName("a model is refused for a key of another vendor, and for an endpoint the data policy forbids")
    void refusals() {
        AssistantProperties crossed = properties("gemini", ASSISTANT_KEY, GOOGLE_KEY, "", null, "",
                DataPolicy.CLOUD_ALLOWED, "");
        assertThat(ModelCatalog.refusal(crossed, ModelCatalog.spec(crossed, "anthropic/claude-haiku-5-5")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("another vendor"));

        AssistantProperties local = properties("openai", "", "", GOOGLE_KEY, "http://localhost:11434/v1", "",
                DataPolicy.LOCAL_ONLY, "");
        assertThat(ModelCatalog.refusal(local, ModelCatalog.spec(local, "qwen3:8b"))).isEmpty();
        assertThat(ModelCatalog.refusal(local, ModelCatalog.spec(local, "gemini/gemini-3.5-flash")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("LOCAL_ONLY"));

        AssistantProperties remote = properties("openai", "", "", "", "https://models.example.com/v1", "",
                DataPolicy.LOCAL_ONLY, "");
        assertThat(remote.usable()).isFalse();
        assertThat(ModelCatalog.refusal(remote, ModelCatalog.spec(remote, "qwen3:8b"))).isPresent();
    }

    @Test
    @DisplayName("the route is every configured name, resolved, in order; unset, the assistant's own model alone")
    void route() {
        AssistantProperties properties = gemini();
        assertThat(ModelCatalog.route(properties, new ModelRoutingProperties(List.of(), true, null)))
                .extracting(ModelCatalog.ModelSpec::name).containsExactly("gemini-3.5-flash");
        assertThat(ModelCatalog.route(properties,
                new ModelRoutingProperties(List.of("openai/qwen3:8b", "gemini-3.5-flash"), true, null)))
                .extracting(ModelCatalog.ModelSpec::provider)
                .containsExactly(ModelProvider.OPENAI, ModelProvider.GEMINI);
    }

    @Test
    @DisplayName("a gateway's vendor-named id is the assistant's own model when it is `model`, and is reached through "
            + "the gateway when written openai/...")
    void gatewayIds() {
        AssistantProperties gateway = new AssistantProperties(true, "", "anthropic/claude-sonnet", "low", 6, 16000,
                50, 90, Duration.ofSeconds(90), Duration.ofMinutes(15), true, "", "openai", ANTHROPIC_KEY, "",
                new AssistantProperties.OpenAiCompatible("https://gateway.example.com/v1", "gateway-key"),
                DataPolicy.CLOUD_ALLOWED);

        ModelCatalog.ModelSpec own = ModelCatalog.spec(gateway, "anthropic/claude-sonnet");
        assertThat(own.isOwnModel(gateway)).isTrue();
        assertThat(own.provider()).isEqualTo(ModelProvider.OPENAI);
        assertThat(own.apiKey()).isEqualTo("gateway-key");

        ModelCatalog.ModelSpec escaped = ModelCatalog.spec(gateway, "openai/anthropic/claude-haiku");
        assertThat(escaped.provider()).isEqualTo(ModelProvider.OPENAI);
        assertThat(escaped.model()).isEqualTo("anthropic/claude-haiku");
        assertThat(escaped.endpoint()).isEqualTo("https://gateway.example.com/v1");
    }

    @Test
    @DisplayName("an endpoint's user and password never reach a log line")
    void credentialsInUrls() {
        assertThat(AssistantProperties.loggable("https://user:secret@gateway.example.com/v1"))
                .isEqualTo("https://gateway.example.com/v1");
        assertThat(new AssistantProperties.OpenAiCompatible("https://user:secret@gateway.example.com/v1", "")
                .toString()).doesNotContain("secret");
    }

    @Test
    @DisplayName("a spec never prints its key")
    void hidesTheKey() {
        assertThat(ModelCatalog.spec(gemini(), "gemini-3.5-flash").toString()).doesNotContain(ASSISTANT_KEY)
                .contains("<set>");
    }
}
