package com.stockflow.assistant.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code LOCAL_ONLY} lets through (design §13 AQ-01): only an OpenAI-compatible server every address of which is
 * private. A cloud vendor's API is never local, whatever the policy is told; a numeric host in any form but four
 * decimals is refused, since the resolver reads it as an address; and a name is checked by what it resolves to.
 */
class DataPolicyTest {

    /** The names this test's DNS knows: two internal services, and one a search domain turns into a public host. */
    private static final Map<String, String> DNS = Map.of(
            "ollama", "172.18.0.5",
            "host.docker.internal", "192.168.65.254",
            "gpu-box.local", "10.0.4.2",
            "model.localhost", "127.0.0.1",
            "localhost", "127.0.0.1",
            "models", "8.8.8.8");

    private static final DataPolicy.Resolver RESOLVER = host -> {
        String address = DNS.get(host);
        if (address == null) {
            throw new UnknownHostException(host);
        }
        return new InetAddress[] {InetAddress.getByName(address)};
    };

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:11434/v1", "http://127.0.0.1:11434/v1", "http://[::1]:11434/v1",
            "http://10.0.4.2:8000/v1", "http://172.20.0.5/v1", "http://192.168.1.20:11434/v1",
            "http://ollama:11434/v1", "http://host.docker.internal:11434/v1", "http://gpu-box.local/v1",
            "http://model.localhost/v1", "http://169.254.10.1/v1", "http://[fd12:3456::1]:8000/v1",
            "http://LOCALHOST:11434/v1"})
    @DisplayName("private addresses, and internal-looking names that resolve to private addresses, are local")
    void local(String url) {
        assertThat(DataPolicy.isLocal(url, RESOLVER)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.openai.com/v1", "https://generativelanguage.googleapis.com",
            "https://api.anthropic.com", "http://8.8.8.8/v1", "http://172.32.0.1/v1", "http://[2001:db8::1]/v1",
            "https://models.example.com/v1", "not a url", "", "http:///v1", "http://[::ffff:8.8.8.8]/v1",
            "http://0.0.0.0:11434/v1"})
    @DisplayName("a public host, a public address or an unreadable URL is not")
    void notLocal(String url) {
        assertThat(DataPolicy.isLocal(url, RESOLVER)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://134744072:11434/v1", "http://0x08080808/v1", "http://010.0.0.1/v1",
            "http://10.1/v1", "http://0x7f.1/v1", "http://2130706433/v1", "http://127.1/v1"})
    @DisplayName("a numeric host in any form but four decimals is refused: 134744072 is 8.8.8.8, 2130706433 127.0.0.1")
    void numericFormsAreRefused(String url) {
        assertThat(DataPolicy.isLocal(url, RESOLVER)).isFalse();
    }

    @Test
    @DisplayName("an internal-looking name is refused when it resolves to a public address, or does not resolve")
    void namesAreCheckedByWhatTheyResolveTo() {
        assertThat(DataPolicy.isLocal("http://models:8000/v1", RESOLVER)).as("a search domain made it public")
                .isFalse();
        assertThat(DataPolicy.isLocal("http://nowhere:8000/v1", RESOLVER)).as("unresolvable").isFalse();
        assertThat(DataPolicy.isLocal("http://user:secret@localhost@evil.com/v1", RESOLVER)).isFalse();
    }

    @Test
    @DisplayName("a host the IDN rules refuse is simply not local, never an exception at start-up")
    void unreadableNames() {
        assertThat(DataPolicy.isLocal("http://" + "a".repeat(64) + "/v1", RESOLVER)).isFalse();
    }

    @Test
    @DisplayName("LOCAL_ONLY allows a local OpenAI-compatible server only; CLOUD_ALLOWED allows everything")
    void allows() {
        assertThat(DataPolicy.LOCAL_ONLY.allows(ModelProvider.OPENAI, "http://localhost:11434/v1")).isTrue();
        assertThat(DataPolicy.LOCAL_ONLY.allows(ModelProvider.OPENAI, "https://api.openai.com/v1")).isFalse();
        // A cloud vendor pointed at a local proxy is still that vendor: the proxy forwards to it.
        assertThat(DataPolicy.LOCAL_ONLY.allows(ModelProvider.GEMINI, "http://localhost:8080")).isFalse();
        assertThat(DataPolicy.LOCAL_ONLY.allows(ModelProvider.ANTHROPIC, "http://localhost:8080")).isFalse();
        assertThat(DataPolicy.CLOUD_ALLOWED.allows(ModelProvider.GEMINI,
                GeminiLanguageModel.DEFAULT_BASE_URL)).isTrue();
    }
}
