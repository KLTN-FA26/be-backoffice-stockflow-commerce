package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.http.RestClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The route the application builds from its settings (design §11): the {@code LanguageModel} bean alone while routing
 * is unset, nothing at all while the assistant is off, and otherwise an adapter per model, every one of them on the
 * provider's one key. The Gemini calls are answered by an interceptor: nothing leaves the test.
 */
class ModelRouterConfigTest {

    // Built at run time, so nothing key-shaped sits in the repository for a secret scanner to flag.
    private static final String GOOGLE_KEY = "AIza" + "test-key";

    /** The free tier's daily refusal: a per-day quota, which the adapter reports at once as spent. */
    private static final String DAY_SPENT = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota","details":[
              {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                {"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}""";

    private static final String ANSWER = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"Còn 40 cái."}]},"finishReason":"STOP"}],
             "modelVersion":"gemini-3.5-flash"}""";

    private final RestClientFactory http = mock(RestClientFactory.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T02:00:00Z"), ZoneOffset.UTC);
    private final AssistantModelConfig config = new AssistantModelConfig();
    private final LanguageModel.Request request = new LanguageModel.Request("prompt", List.of(),
            List.of(new Turn.Question("Còn bao nhiêu CUP-12OZ?", "ctx")));

    private static AssistantProperties properties(boolean enabled, String provider, String key, String model) {
        return new AssistantProperties(enabled, key, model, "low", 6, 16000, 50, 90, Duration.ofSeconds(90),
                Duration.ofMinutes(15), true, "", provider);
    }

    private static ModelRoutingProperties routing(String... models) {
        return new ModelRoutingProperties(List.of(models), true, null);
    }

    /** Gemini as the interceptor plays it: the cheap model's quota is spent, any other model answers. */
    private List<String> geminiAnswering(List<String> keys) {
        List<String> paths = new ArrayList<>();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test")
                .requestInterceptor((sent, body, execution) -> {
                    paths.add(sent.getURI().getPath());
                    keys.add(sent.getHeaders().getFirst("x-goog-api-key"));
                    boolean spent = sent.getURI().getPath().contains("flash-lite");
                    MockClientHttpResponse response = new MockClientHttpResponse(
                            (spent ? DAY_SPENT : ANSWER).getBytes(StandardCharsets.UTF_8),
                            spent ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return response;
                });
        when(http.forService("gemini", GeminiLanguageModel.DEFAULT_BASE_URL)).thenReturn(builder);
        return paths;
    }

    @Test
    @DisplayName("routing unset: every question goes to the LanguageModel bean, as before")
    void unsetIsTheBeanAlone() {
        LanguageModel bean = mock(LanguageModel.class);
        Turn.ModelTurn closing = new Turn.ModelTurn("{}", "x", List.of());
        when(bean.closingTurn("x")).thenReturn(closing);

        ModelRouter router = config.modelRouter(bean, properties(true, "gemini", GOOGLE_KEY,
                "gemini-3.5-flash-lite"), routing(), http, clock);

        assertThat(router.names()).containsExactly("gemini-3.5-flash-lite");
        assertThat(router.route().model().closingTurn("x")).isSameAs(closing);
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("a switched-off assistant, or one without a key of its own, routes nowhere whatever the list says: "
            + "no adapter is built")
    void offRoutesNowhere() {
        for (AssistantProperties off : List.of(properties(false, "gemini", GOOGLE_KEY, "gemini-3.5-flash-lite"),
                properties(true, "gemini", "sk-ant-" + "api03-test", "gemini-3.5-flash-lite"))) {
            ModelRouter router = config.modelRouter(config.languageModel(off, http), off,
                    routing("gemini-3.5-flash-lite", "gemini-3.5-flash"), http, clock);

            assertThat(router.available()).isFalse();
            assertThat(router.names()).containsExactly("gemini-3.5-flash-lite");
        }
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("each model of the list is called by its own name and with the provider's one key; a spent quota on "
            + "the first moves the question to the second and sets the first aside")
    void eachModelByItsName() {
        List<String> keys = new ArrayList<>();
        List<String> paths = geminiAnswering(keys);
        AssistantProperties properties = properties(true, "gemini", GOOGLE_KEY, "gemini-3.5-flash-lite");
        ModelRouter router = config.modelRouter(config.languageModel(properties, http), properties,
                routing("gemini-3.5-flash-lite", "gemini-3.5-flash"), http, clock);

        ModelRouter.Route route = router.route();
        assertThatThrownBy(() -> route.model().next(request)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        BusinessException spent = new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED);
        route.failedBeforeReplying(spent);
        assertThat(route.fallBack(spent)).isTrue();

        assertThat(route.model().next(request).turn().text()).isEqualTo("Còn 40 cái.");
        assertThat(paths).containsExactly("/v1beta/models/gemini-3.5-flash-lite:generateContent",
                "/v1beta/models/gemini-3.5-flash:generateContent");
        assertThat(keys).containsOnly(GOOGLE_KEY);
        assertThat(router.route().name()).isEqualTo("gemini-3.5-flash");
    }

    @Test
    @DisplayName("a stockflow.assistant.model the list leaves out gets no question: they start on the list's first")
    void theListDecides() {
        List<String> paths = geminiAnswering(new ArrayList<>());
        // ASSISTANT_MODEL left unset under provider gemini: the model is application.yml's Claude default.
        AssistantProperties properties = properties(true, "gemini", GOOGLE_KEY, "claude-opus-5-5");
        ModelRouter router = config.modelRouter(config.languageModel(properties, http), properties,
                routing("gemini-3.5-flash", "gemini-3.5-pro"), http, clock);

        assertThat(router.names()).containsExactly("gemini-3.5-flash", "gemini-3.5-pro");
        router.route().model().next(request);
        assertThat(paths).containsExactly("/v1beta/models/gemini-3.5-flash:generateContent");
    }

    @Test
    @DisplayName("a route may mix vendors: a local model first, the Gemini assistant's own model after it, each on its "
            + "own vendor's quota day; a vendor without its own key is left out, never sent the assistant's")
    void mixedVendors() {
        List<String> openaiPaths = new ArrayList<>();
        List<String> authorizations = new ArrayList<>();
        RestClient.Builder ollama = RestClient.builder().baseUrl("http://localhost:11434/v1")
                .requestInterceptor((sent, body, execution) -> {
                    openaiPaths.add(sent.getURI().getPath());
                    authorizations.add(sent.getHeaders().getFirst("Authorization"));
                    MockClientHttpResponse response = new MockClientHttpResponse("""
                            {"model":"qwen3:8b","choices":[{"finish_reason":"stop",
                              "message":{"role":"assistant","content":"Còn 40 cái."}}]}"""
                            .getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return response;
                });
        when(http.forService("openai-compatible", "http://localhost:11434/v1")).thenReturn(ollama);
        AssistantProperties properties = properties(true, "gemini", GOOGLE_KEY, "gemini-3.5-flash");
        LanguageModel bean = mock(LanguageModel.class);

        ModelRouter router = config.modelRouter(bean, properties,
                routing("openai/qwen3:8b", "anthropic/claude-haiku-5-5", "gemini-3.5-flash"), http, clock);

        assertThat(router.names()).as("Claude has no ANTHROPIC_API_KEY here: left out")
                .containsExactly("openai/qwen3:8b", "gemini-3.5-flash");
        ModelRouter.Route route = router.route();
        assertThat(route.model().next(request).turn().text()).isEqualTo("Còn 40 cái.");
        assertThat(openaiPaths).containsExactly("/v1/chat/completions");
        assertThat(authorizations).as("the Gemini key never reaches the local server").containsOnlyNulls();
        assertThat(router.renewal(clock.instant())).as("the router's own day is the assistant's provider's")
                .isEqualTo(Instant.parse("2026-10-25T07:00:00Z"));
    }

    @Test
    @DisplayName("under LOCAL_ONLY a cloud model of the list is left out of a local assistant's route")
    void localOnlyRoute() {
        AssistantProperties local = new AssistantProperties(true, "", "qwen3:8b", "low", 6, 16000, 50, 90,
                Duration.ofSeconds(90), Duration.ofMinutes(15), true, "", "openai", "", GOOGLE_KEY, null,
                DataPolicy.LOCAL_ONLY);
        LanguageModel bean = mock(LanguageModel.class);

        ModelRouter router = config.modelRouter(bean, local, routing("qwen3:8b", "gemini/gemini-3.5-flash"), http,
                clock);

        assertThat(router.names()).containsExactly("qwen3:8b");
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("Claude models of the list get an adapter each, the configured one being the bean; the ones built "
            + "for the route are closed with it")
    void claudeModels() throws Exception {
        LanguageModel bean = mock(LanguageModel.class);
        Turn.ModelTurn closing = new Turn.ModelTurn("{}", "x", List.of());
        when(bean.closingTurn("x")).thenReturn(closing);
        ModelRouter router = config.modelRouter(bean, properties(true, "anthropic", "sk-ant-" + "api03-test",
                "claude-opus-5-5"), routing("claude-haiku-5-5", "claude-opus-5-5"), http, clock);

        ModelRouter.Route route = router.route();
        assertThat(route.model().closingTurn("x").providerContent()).as("Claude's own format, from its own adapter")
                .contains("\"role\":\"assistant\"");
        assertThat(route.escalate()).isTrue();
        assertThat(route.model().closingTurn("x")).isSameAs(closing);
        verify(bean).closingTurn("x");
        router.close();
    }
}
