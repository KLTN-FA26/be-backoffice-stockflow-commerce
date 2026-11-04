package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * One POST to Gemini's REST API, tried again when that can help, and mapped to the assistant's error codes. The chat
 * ({@link GeminiLanguageModel}), the delivery-note reader ({@link GeminiDocumentReader}) and the embeddings
 * ({@link GeminiEmbeddingModel}) each held a copy of this loop; a fix to one did not reach the others.
 *
 * <p><b>A failure says what to do next</b>, the same way for all three:</p>
 * <ul>
 *   <li>a 429 of the free tier's per-minute quota, a 5xx or an unreachable server is tried again
 *       {@value AssistantProperties#MAX_RETRIES} times, waiting what the API's {@code RetryInfo} asks, at most a minute,
 *       and then fails as {@code ASSISTANT_UNAVAILABLE}, which a user may retry;</li>
 *   <li>a spent quota ({@link GeminiLanguageModel#quotaSpent}) fails at once as {@code ASSISTANT_QUOTA_EXHAUSTED};</li>
 *   <li>400, 401, 403 and 404 are the key, the model name or our own request: {@code ASSISTANT_MISCONFIGURED} at once,
 *       logged as an error, because no retry changes them;</li>
 *   <li>a 200 whose body is not JSON is the provider failing, not a bug of ours: {@code ASSISTANT_UNAVAILABLE}.</li>
 * </ul>
 *
 * <p>A call that is not {@code patient} — a question's embedding, which a search waits on — is tried once.</p>
 *
 * <p><b>HTTP through {@code RestClientFactory}</b>, for its correlation id and call log; the key travels in the
 * {@code x-goog-api-key} header, never in a URL, and anything shaped like a key is blanked from a logged error.</p>
 */
final class GeminiCall {

    private static final Logger log = LoggerFactory.getLogger(GeminiCall.class);

    /**
     * The key, the model name or our request: Google answers 400 for a key that is not valid, 403 for a suspended
     * key or an API that is not enabled, 404 for a model it does not serve.
     */
    private static final Set<Integer> MISCONFIGURATION = Set.of(400, 401, 403, 404);

    private final RestClient http;
    private final String apiKey;
    private final Sleeper sleeper;
    private final String subject;
    private final String provider;
    private final ObjectMapper json = new ObjectMapper();

    private static final ObjectMapper READER = new ObjectMapper();

    /**
     * @param http     based at the API's root
     * @param subject  what the calls are for, in the log: "", " (document)", " (embeddings)"
     * @param provider what the user-facing message calls the other side: "model provider", "embedding provider"
     */
    GeminiCall(RestClient http, String apiKey, Sleeper sleeper, String subject, String provider) {
        this.http = http;
        this.apiKey = apiKey;
        this.sleeper = sleeper;
        this.subject = subject;
        this.provider = provider;
    }

    private record Raw(int status, String body) {
    }

    /** POSTs {@code body} to {@code uri} (with {@code {model}} filled in); the response's JSON on a 200. */
    JsonNode post(String uri, String model, String body, boolean patient) {
        for (int attempt = 0; ; attempt++) {
            Raw response;
            try {
                response = http.post()
                        .uri(uri, model)
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .exchange((sent, received) -> new Raw(received.getStatusCode().value(),
                                new String(received.getBody().readAllBytes(), StandardCharsets.UTF_8)));
            } catch (ResourceAccessException unreachable) {
                if (patient && attempt < AssistantProperties.MAX_RETRIES) {
                    log.warn("Gemini{} could not be reached (attempt {}): {}", subject, attempt + 1,
                            unreachable.getMessage());
                    pause(Duration.ofSeconds(2L * (attempt + 1)));
                    continue;
                }
                throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The " + provider + " could not be reached",
                        unreachable);
            }
            int status = response.status();
            if (status == 200) {
                return parsed(response.body());
            }
            JsonNode error = error(response.body());
            String reason = GeminiLanguageModel.errorSummary(error);
            if (status == 429 && GeminiLanguageModel.quotaSpent(error)) {
                log.warn("Gemini's quota for {} is used up{}: {}", model, subject, reason);
                throw new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED,
                        "The " + provider + "'s quota is used up; the assistant answers again once it is renewed");
            }
            if (MISCONFIGURATION.contains(status)) {
                log.error("Gemini refused the key, the model or the request{} with {}: {}", subject, status, reason);
                throw new BusinessException(ErrorCode.ASSISTANT_MISCONFIGURED, "The " + provider + " answered "
                        + status);
            }
            boolean retryable = status == 429 || status >= 500;
            Optional<Duration> wait = retryable && patient ? GeminiLanguageModel.retryDelay(error, status, attempt)
                    : Optional.empty();
            if (wait.isPresent() && attempt < AssistantProperties.MAX_RETRIES) {
                log.warn("Gemini answered {}{} (attempt {}): {}", status, subject, attempt + 1, reason);
                pause(wait.get());
                continue;
            }
            if (retryable) {
                log.warn("Gemini answered {}{}: {}", status, subject, reason);
            } else {
                log.error("Gemini rejected the request{} with {}: {}", subject, status, reason);
            }
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The " + provider + " answered " + status);
        }
    }

    private JsonNode parsed(String body) {
        try {
            return json.readTree(body);
        } catch (IOException notJson) {
            log.error("Gemini answered 200{} with a body that is not JSON", subject);
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE,
                    "The " + provider + " answered with something that is not JSON", notJson);
        }
    }

    /** The {@code error} object of a refused call's body; a missing node when the body is not the API's JSON. */
    static JsonNode error(String body) {
        try {
            JsonNode node = READER.readTree(body);
            return node == null ? MissingNode.getInstance() : node.path("error");
        } catch (IOException notJson) {
            return MissingNode.getInstance();
        }
    }

    private void pause(Duration delay) {
        try {
            sleeper.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "Interrupted waiting to retry", interrupted);
        }
    }
}
