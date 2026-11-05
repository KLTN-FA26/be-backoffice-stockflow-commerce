package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One POST to a server speaking OpenAI's API ({@code /chat/completions}, {@code /embeddings},
 * {@code /audio/transcriptions}), tried again when that can help, and mapped to the assistant's error codes the way
 * {@link GeminiCall} maps Google's: the chat, the embeddings, the delivery-note reader and the voice transcriber on such
 * a server all go through it.
 *
 * <p><b>A failure says what to do next.</b></p>
 * <ul>
 *   <li>An unreachable server, a 5xx, or a 429 that asks for a minute or less is tried again
 *       {@value AssistantProperties#MAX_RETRIES} times, then {@code ASSISTANT_UNAVAILABLE}, which a user may retry. A
 *       local Ollama that is not running is the common case, and the log says so. A {@code Retry-After} of more than
 *       {@value #MAX_BACKOFF_SECONDS} seconds is never waited for, on any status.</li>
 *   <li>A 429 whose {@code Retry-After} asks for more than that, or whose error is {@code insufficient_quota}, is a
 *       spent quota: {@code ASSISTANT_QUOTA_EXHAUSTED} at once, so the router moves the next questions on.</li>
 *   <li>400, 401, 403, 404 and 422 are the key, the model name (Ollama answers 404 for a model it has not pulled) or
 *       our own request, such as a prompt longer than the model's context: {@code ASSISTANT_MISCONFIGURED} at once,
 *       logged as an error, because no retry changes them.</li>
 *   <li>A 200 whose body is not JSON is the server failing: {@code ASSISTANT_UNAVAILABLE}.</li>
 * </ul>
 *
 * <p><b>The key</b>, when there is one, travels as a bearer token, never in the URL; anything shaped like a key is
 * blanked from a logged error. A call that is not {@code patient} — a question's embedding — is tried once.</p>
 */
final class OpenAiCompatibleCall {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleCall.class);

    /** A minute: longer than that, waiting inside an answer only makes the user wait for the same refusal. */
    static final int MAX_BACKOFF_SECONDS = 60;

    private static final Set<Integer> MISCONFIGURATION = Set.of(400, 401, 403, 404, 422);

    /** An OpenAI, Anthropic or Google key, or a bearer token, should an error message ever quote one back. */
    private static final Pattern KEY = Pattern.compile(
            "sk-[0-9A-Za-z_-]{16,}|AIza[0-9A-Za-z_-]{30,}|(?i)bearer\\s+[0-9A-Za-z._-]{8,}");

    private final RestClient http;
    private final String apiKey;
    private final Sleeper sleeper;
    private final String subject;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param http    based at the API's root ({@code stockflow.assistant.openai-compatible.base-url})
     * @param apiKey  blank for a server that needs none
     * @param subject what the calls are for, in the log: "", " (embeddings)", " (document)", " (speech)"
     */
    OpenAiCompatibleCall(RestClient http, String apiKey, Sleeper sleeper, String subject) {
        this.http = http;
        this.apiKey = apiKey;
        this.sleeper = sleeper;
        this.subject = subject;
    }

    private record Raw(int status, String body, String retryAfter) {
    }

    /** POSTs {@code body}, JSON, to {@code path}; the response's JSON on a 200. */
    JsonNode post(String path, String body, boolean patient) {
        return send(path, MediaType.APPLICATION_JSON, body, patient);
    }

    /**
     * POSTs {@code parts} as {@code multipart/form-data} to {@code path}, as {@code /audio/transcriptions} takes a
     * recording; the response's JSON on a 200. A part is a value, or an {@code HttpEntity} carrying its own headers.
     */
    JsonNode postMultipart(String path, MultiValueMap<String, Object> parts, boolean patient) {
        return send(path, MediaType.MULTIPART_FORM_DATA, parts, patient);
    }

    private JsonNode send(String path, MediaType type, Object body, boolean patient) {
        for (int attempt = 0; ; attempt++) {
            Raw response;
            try {
                RestClient.RequestBodySpec request = http.post().uri(path).contentType(type);
                if (!apiKey.isEmpty()) {
                    request.header("Authorization", "Bearer " + apiKey);
                }
                response = request.body(body).exchange((sent, received) -> new Raw(received.getStatusCode().value(),
                        new String(received.getBody().readAllBytes(), StandardCharsets.UTF_8),
                        received.getHeaders().getFirst("Retry-After")));
            } catch (ResourceAccessException unreachable) {
                if (patient && attempt < AssistantProperties.MAX_RETRIES) {
                    log.warn("The OpenAI-compatible server{} could not be reached (attempt {}): {}", subject,
                            attempt + 1, unreachable.getMessage());
                    pause(Duration.ofSeconds(2L * (attempt + 1)));
                    continue;
                }
                log.warn("The OpenAI-compatible server{} could not be reached; for a local model, check that it is "
                        + "running and that stockflow.assistant.openai-compatible.base-url points at it", subject);
                throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model server could not be reached",
                        unreachable);
            }
            int status = response.status();
            if (status == 200) {
                return parsed(response.body());
            }
            JsonNode error = error(response.body());
            String reason = errorSummary(error);
            Optional<Duration> asked = retryAfter(response.retryAfter());
            boolean tooLong = asked.filter(wait -> wait.toSeconds() > MAX_BACKOFF_SECONDS).isPresent();
            if (status == 429 && (tooLong || quotaSpent(error))) {
                log.warn("The OpenAI-compatible server's quota is used up{}: {}", subject, reason);
                throw new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED,
                        "The model server's quota is used up; the assistant answers again once it is renewed");
            }
            if (MISCONFIGURATION.contains(status)) {
                log.error("The OpenAI-compatible server refused the key, the model or the request{} with {}: {}",
                        subject, status, reason);
                throw new BusinessException(ErrorCode.ASSISTANT_MISCONFIGURED, "The model server answered " + status);
            }
            // A wait of more than a minute is never sat out inside an answer, whatever the status: two of them would
            // outlast the lease the question holds on its conversation.
            boolean retryable = (status == 429 || status >= 500) && !tooLong;
            if (retryable && patient && attempt < AssistantProperties.MAX_RETRIES) {
                Duration wait = asked.orElse(Duration.ofSeconds((status == 429 ? 5L : 2L) * (attempt + 1)));
                log.warn("The OpenAI-compatible server answered {}{} (attempt {}): {}", status, subject, attempt + 1,
                        reason);
                pause(wait);
                continue;
            }
            log.warn("The OpenAI-compatible server answered {}{}: {}", status, subject, reason);
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model server answered " + status);
        }
    }

    /** {@code Retry-After} in seconds, the form these servers send; empty when absent or a date. */
    static Optional<Duration> retryAfter(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(header.strip());
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException aDate) {
            return Optional.empty();
        }
    }

    /**
     * Whether a 429 is a spent quota rather than a burst: OpenAI says so in the error's code or type
     * ({@code insufficient_quota}), with no {@code Retry-After}, since waiting does not bring it back.
     */
    static boolean quotaSpent(JsonNode error) {
        return "insufficient_quota".equals(error.path("code").asText(""))
                || "insufficient_quota".equals(error.path("type").asText(""));
    }

    /** The {@code error} of a refused call's body: an object (OpenAI), a string (Ollama), or missing. */
    JsonNode error(String body) {
        try {
            JsonNode node = json.readTree(body);
            return node == null ? MissingNode.getInstance() : node.path("error");
        } catch (IOException notJson) {
            return MissingNode.getInstance();
        }
    }

    /** The server's error message for the log, as {@link #errorSummary(JsonNode)} gives it. */
    String errorSummary(String body) {
        return errorSummary(error(body));
    }

    /**
     * The server's error message for the log, at most 300 characters, keys blanked. OpenAI puts it in
     * {@code error.message}; Ollama sends {@code error} as a plain string.
     */
    static String errorSummary(JsonNode error) {
        String message = error.isTextual() ? error.asText() : error.path("message").asText("");
        if (message.isEmpty()) {
            return "(no error message)";
        }
        message = KEY.matcher(message).replaceAll("[key]");
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private JsonNode parsed(String body) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) {
                throw new IOException("not a JSON object");
            }
            return node;
        } catch (IOException notJson) {
            log.error("The OpenAI-compatible server answered 200{} with a body that is not a JSON object", subject);
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE,
                    "The model server answered with something that is not JSON", notJson);
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
