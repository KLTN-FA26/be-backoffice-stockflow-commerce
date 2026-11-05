package com.stockflow.assistant.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataBoundaryTest {

    private static final String GEMINI = "https://generativelanguage.googleapis.com";

    @Test
    @DisplayName("empty lists allow whatever the policy allows; the policy is checked first")
    void emptyListsAllowAny() {
        DataBoundary any = DataBoundary.of(DataPolicy.CLOUD_ALLOWED, List.of(), List.of());

        assertThat(any.refusal(ModelProvider.GEMINI, GEMINI)).isEmpty();
        assertThat(any.refusal(ModelProvider.ANTHROPIC, "https://api.anthropic.com")).isEmpty();
        assertThat(DataBoundary.of(DataPolicy.LOCAL_ONLY, List.of(), List.of()).refusal(ModelProvider.GEMINI, GEMINI))
                .hasValueSatisfying(reason -> assertThat(reason).contains("LOCAL_ONLY"));
    }

    @Test
    @DisplayName("a provider not on the list is refused, whatever the policy allows; case and blanks do not matter")
    void providers() {
        DataBoundary gemini = DataBoundary.of(DataPolicy.CLOUD_ALLOWED, List.of(" Gemini ", "", "openai"), List.of());

        assertThat(gemini.refusal(ModelProvider.GEMINI, GEMINI)).isEmpty();
        assertThat(gemini.refusal(ModelProvider.ANTHROPIC, "https://api.anthropic.com"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("anthropic", "allowed-providers"));
        assertThat(gemini.refusal(ModelProvider.OPENAI, "http://localhost:11434/v1")).isEmpty();
    }

    @Test
    @DisplayName("a host outside the company's network must be on the list; one inside it always may be used")
    void hosts() {
        DataBoundary listed = DataBoundary.of(DataPolicy.CLOUD_ALLOWED, List.of(),
                List.of("generativelanguage.googleapis.com"));

        assertThat(listed.refusal(ModelProvider.GEMINI, GEMINI)).isEmpty();
        assertThat(listed.refusal(ModelProvider.OPENAI, "https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("allowed-hosts"));
        assertThat(listed.refusal(ModelProvider.OPENAI, "http://localhost:11434/v1")).as("nothing leaves").isEmpty();
        assertThat(listed.refusal(ModelProvider.OPENAI, "https://user:secret@evil.example/v1"))
                .hasValueSatisfying(reason -> assertThat(reason).doesNotContain("secret"));
    }

    @Test
    @DisplayName("a mistake in the lists switches the assistant off with the reason, never the application")
    void mistakes() {
        DataBoundary typo = DataBoundary.of(DataPolicy.CLOUD_ALLOWED, List.of("gemeni"), List.of());
        assertThat(typo.refusal(ModelProvider.GEMINI, GEMINI))
                .hasValueSatisfying(reason -> assertThat(reason).contains("gemeni"));
        assertThat(typo.describe()).startsWith("nothing");
        assertThat(properties(List.of("gemeni"), List.of()).allowedByPolicy()).as("the properties still bind")
                .isFalse();
        assertThat(DataBoundary.of(DataPolicy.CLOUD_ALLOWED, List.of(), List.of("https://api.anthropic.com"))
                .refusal(ModelProvider.ANTHROPIC, "https://api.anthropic.com"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("not a bare host name"));
        assertThat(DataBoundary.of(DataPolicy.CLOUD_ALLOWED, List.of(), List.of("*.googleapis.com"))
                .refusal(ModelProvider.GEMINI, GEMINI)).isPresent();
    }

    @Test
    @DisplayName("providers listed without hosts pin those providers' own API hosts: a base URL elsewhere is refused, "
            + "and a server inside the network is allowed whatever the lists say")
    void providersPinTheirHosts() {
        DataBoundary gemini = DataBoundary.of(DataPolicy.CLOUD_ALLOWED, List.of("gemini"), List.of());

        assertThat(gemini.refusal(ModelProvider.GEMINI, GEMINI)).isEmpty();
        assertThat(gemini.refusal(ModelProvider.GEMINI, "https://proxy.example.com"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("allowed-hosts"));
        assertThat(gemini.refusal(ModelProvider.OPENAI, "http://localhost:11434/v1"))
                .as("a local embedding server: nothing leaves").isEmpty();
        assertThat(DataBoundary.of(DataPolicy.CLOUD_ALLOWED, List.of("openai"), List.of())
                .refusal(ModelProvider.OPENAI, "https://api.together.example/v1"))
                .as("an OpenAI-compatible server outside the network must be named").isPresent();
    }

    @Test
    @DisplayName("the assistant's own model and a routed one are refused outside the lists, with the reason")
    void modelsOutsideTheLists() {
        AssistantProperties geminiOnly = properties(List.of("gemini"), List.of());

        assertThat(geminiOnly.allowedByPolicy()).isTrue();
        ModelCatalog.ModelSpec claude = ModelCatalog.spec(geminiOnly, "anthropic/claude-sonnet-5-5");
        assertThat(ModelCatalog.refusal(geminiOnly, claude))
                .hasValueSatisfying(reason -> assertThat(reason).contains("allowed-providers"));
        assertThat(ModelCatalog.settingsFor(geminiOnly, ModelCatalog.spec(geminiOnly, "gemini/gemini-3.5-flash"))
                .allowedProviders()).as("a routed model's settings keep the lists").containsExactly("gemini");
    }

    private static AssistantProperties properties(List<String> providers, List<String> hosts) {
        return new AssistantProperties(true, "AIza-test-key", "gemini-3.5-flash-lite", "low", 6, 16000, 50, 90,
                Duration.ofSeconds(90), Duration.ofMinutes(15), true, "", "gemini", "", "",
                new AssistantProperties.OpenAiCompatible(null, null), DataPolicy.CLOUD_ALLOWED, providers, hosts);
    }
}
