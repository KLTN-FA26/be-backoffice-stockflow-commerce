package com.stockflow.assistant.internal.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.DefaultSleeper;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.Sleeper;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * {@link LanguageModel} on Anthropic's Messages API, through the official Java SDK (ADR-0010).
 *
 * <p><b>Replay is byte-faithful.</b> A reply is stored as {@code Message.toParam()} serialised by the SDK's own
 * mapper, and read back by the same mapper into a {@code MessageParam}. Nothing in between edits it: the model's
 * thinking blocks, including their signatures, travel back exactly as they came, which the provider requires for a
 * history to stay valid (design §3.4).</p>
 *
 * <p><b>Consecutive user-side turns are merged.</b> A question that follows tool results, or a question after one
 * whose answer failed, is sent with the turn before it as one user message: tool results first, as the API requires,
 * then the text.</p>
 *
 * <p><b>Caching.</b> The tool list and the system prompt are identical on every call and come first; the system
 * block carries a cache breakpoint, and the request carries the automatic one so the growing history is reused
 * across the rounds of one answer.</p>
 *
 * <p><b>A failure says what to do next.</b> The SDK itself retries 408, 409, 429, 5xx and connection failures
 * {@value AssistantProperties#MAX_RETRIES} times. What reaches the adapter after that: 400 to 404 — the request, the
 * key, the account's credit, what the key may do, the model name — is {@code ASSISTANT_MISCONFIGURED}, logged as an
 * error, because waiting changes none of it; a 429 that asks to wait more than a minute is a spent quota,
 * {@code ASSISTANT_QUOTA_EXHAUSTED}, and so is a wait that long between two attempts ({@link BoundedSleeper});
 * anything else, a timeout included, is {@code ASSISTANT_UNAVAILABLE}, worth asking again. The provider's own words
 * stay in the log and never reach the client.</p>
 *
 * <p><b>An answer has text.</b> An {@code end_turn} holding nothing but thinking did not answer, so it is
 * {@code OTHER}, as in {@link GeminiLanguageModel}: {@code END_TURN} always comes with text.</p>
 *
 * <p>Model behaviour this relies on, checked against the provider's documentation in October 2026: thinking is
 * always on for the default model and must not be configured; forced tool choice is rejected, so the default
 * {@code auto} is used, and {@code none} on an answer's last call; {@code temperature} is not sent.</p>
 */
class ClaudeLanguageModel implements LanguageModel, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClaudeLanguageModel.class);

    /** The SDK's own mapper: it knows how to read back what it wrote. */
    private static final JsonMapper SDK_JSON = ObjectMappers.jsonMapper();

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    /**
     * Refusals no retry changes: the request (400), the key (401), the account's credit (402, {@code billing_error}),
     * what the key may do (403) and the model name (404).
     */
    private static final Set<Integer> MISCONFIGURATION = Set.of(400, 401, 402, 403, 404);

    /**
     * Longer than this, a wait the API asks for is a spent quota and not a burst: the per-minute limits refill within
     * the minute, and a user who asked again sooner would only meet the same refusal.
     */
    static final Duration QUOTA_WAIT = Duration.ofMinutes(1);

    private final AnthropicClient client;
    private final AssistantProperties properties;

    ClaudeLanguageModel(AnthropicClient client, AssistantProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Reply next(Request request) {
        Message response;
        try {
            response = client.messages().create(params(request));
        } catch (AnthropicServiceException rejected) {
            throw failure(rejected);
        } catch (AnthropicException unreachable) {
            // A connection failure or a timeout, after the SDK's own retries.
            log.warn("The model provider could not be reached: {}", unreachable.getMessage());
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model provider could not be reached",
                    unreachable);
        }
        return reply(response);
    }

    /** What a refusal the SDK gave up on means for the user (class javadoc); logged here with the provider's words. */
    static BusinessException failure(AnthropicServiceException rejected) {
        int status = rejected.statusCode();
        if (MISCONFIGURATION.contains(status)) {
            log.error("The model provider refused the request with {}: {}", status, rejected.getMessage());
            return new BusinessException(ErrorCode.ASSISTANT_MISCONFIGURED, "The model provider answered " + status,
                    rejected);
        }
        if (status == 429 && retryAfter(rejected.headers()).filter(wait -> wait.compareTo(QUOTA_WAIT) > 0)
                .isPresent()) {
            log.warn("The model provider's quota is used up: {}", rejected.getMessage());
            return new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED,
                    "The model provider's quota is used up; the assistant answers again once it is renewed", rejected);
        }
        log.warn("The model provider answered {}: {}", status, rejected.getMessage());
        return new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model provider answered " + status,
                rejected);
    }

    /** The wait a refusal asks for, read as the SDK reads it: {@code retry-after-ms}, else {@code retry-after}. */
    static Optional<Duration> retryAfter(Headers headers) {
        try {
            List<String> millis = headers.values("retry-after-ms");
            if (!millis.isEmpty()) {
                return Optional.of(Duration.ofMillis((long) Double.parseDouble(millis.getFirst())));
            }
            List<String> seconds = headers.values("retry-after");
            if (!seconds.isEmpty()) {
                return Optional.of(Duration.ofMillis((long) (Double.parseDouble(seconds.getFirst()) * 1000)));
            }
        } catch (NumberFormatException httpDate) {
            // An HTTP date instead of seconds, which the API does not send: read as no wait asked, so still retryable.
        }
        return Optional.empty();
    }

    /**
     * The SDK's wait between two attempts, refused when it is longer than {@link #QUOTA_WAIT}.
     *
     * <p>This SDK version sleeps whatever wait a refusal asks for, with no upper bound: a quota spent until tomorrow
     * would hold the request thread and the conversation for hours, twice, before the refusal even reached the
     * adapter. The API asks for a wait on its 429s, so a wait that long is a spent quota, and it fails at once as
     * {@code ASSISTANT_QUOTA_EXHAUSTED}; the exception passes through the SDK unchanged.</p>
     */
    static final class BoundedSleeper implements Sleeper {

        private final Sleeper delegate;

        BoundedSleeper() {
            this(new DefaultSleeper());
        }

        BoundedSleeper(Sleeper delegate) {
            this.delegate = delegate;
        }

        @Override
        public void sleep(Duration duration) {
            if (duration.compareTo(QUOTA_WAIT) > 0) {
                throw quotaSpent(duration);
            }
            delegate.sleep(duration);
        }

        @Override
        public CompletableFuture<Void> sleepAsync(Duration duration) {
            return duration.compareTo(QUOTA_WAIT) > 0 ? CompletableFuture.failedFuture(quotaSpent(duration))
                    : delegate.sleepAsync(duration);
        }

        @Override
        public void close() {
            delegate.close();
        }

        private static BusinessException quotaSpent(Duration asked) {
            log.warn("The model provider asked to wait {} s before trying again: its quota is used up",
                    asked.toSeconds());
            return new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED,
                    "The model provider's quota is used up; the assistant answers again once it is renewed");
        }
    }

    /**
     * The request as sent. On the answer's last call the tools stay declared, because the history holds
     * {@code tool_use} blocks, but the model may not call one: {@code tool_choice: none}, which thinking accepts —
     * only a forced choice is rejected.
     */
    MessageCreateParams params(Request request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxOutputTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.systemPrompt())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(properties.effort())).build())
                .cacheControl(CacheControlEphemeral.builder().build())
                .messages(messages(request.history(), request.note()));
        request.tools().forEach(tool -> params.addTool(tool(tool)));
        // A tool_choice with no tools is refused; a user with no tool at all (a hand-made role) has none to offer.
        if (!request.toolsAllowed() && !request.tools().isEmpty()) {
            params.toolChoice(ToolChoiceNone.builder().build());
        }
        if (properties.refusalFallback()) {
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return params.build();
    }

    /** An assistant turn holding only {@code text}, in the provider's format, so it replays like any other. */
    @Override
    public Turn.ModelTurn closingTurn(String text) {
        MessageParam param = MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(List.of(ContentBlockParam.ofText(TextBlockParam.builder().text(text).build())))
                .build();
        return new Turn.ModelTurn(write(param), text, List.of());
    }

    @Override
    public void close() {
        client.close();
    }

    static Reply reply(Message response) {
        List<ToolCall> calls = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            block.text().map(TextBlock::text).filter(text -> !text.isBlank()).ifPresent(texts::add);
            block.toolUse().ifPresent(use -> calls.add(call(use)));
        }
        String text = texts.isEmpty() ? null : String.join("\n\n", texts);
        Turn.ModelTurn turn = new Turn.ModelTurn(write(response.toParam()), text, calls);
        Usage usage = new Usage(response.usage().inputTokens(), response.usage().outputTokens(),
                response.usage().cacheReadInputTokens().orElse(0L),
                response.usage().cacheCreationInputTokens().orElse(0L));
        StopReason stop = response.stopReason().map(reason -> switch (reason.asString()) {
            case "end_turn" -> text == null ? StopReason.OTHER : StopReason.END_TURN;
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "refusal" -> StopReason.REFUSAL;
            default -> StopReason.OTHER;
        }).orElse(StopReason.OTHER);
        return new Reply(turn, stop, usage, response.model().asString());
    }

    private static ToolCall call(ToolUseBlock use) {
        try {
            return new ToolCall(use.id(), use.name(), SDK_JSON.writeValueAsString(use._input()));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a tool input always serialises", impossible);
        }
    }

    static List<MessageParam> messages(List<Turn> history) {
        return messages(history, null);
    }

    /**
     * @param note a line from the application, sent as the last block of the last user message, after any tool
     *             results (which must come first), or as a user message of its own after a model turn; null for none
     */
    static List<MessageParam> messages(List<Turn> history, String note) {
        List<MessageParam> messages = new ArrayList<>();
        List<ContentBlockParam> user = new ArrayList<>();
        for (Turn turn : history) {
            switch (turn) {
                case Turn.Question question -> {
                    user.add(text(question.context()));
                    user.add(text(question.text()));
                }
                case Turn.ToolResults results -> {
                    // Tool results must open the user message that answers the tool calls.
                    List<ContentBlockParam> blocks = results.results().stream().map(ClaudeLanguageModel::result)
                            .collect(Collectors.toCollection(ArrayList::new));
                    blocks.addAll(user);
                    user = blocks;
                }
                case Turn.ModelTurn model -> {
                    flush(messages, user);
                    user = new ArrayList<>();
                    messages.add(read(model.providerContent()));
                }
            }
        }
        if (note != null) {
            user.add(text(note));
        }
        flush(messages, user);
        return messages;
    }

    private static void flush(List<MessageParam> messages, List<ContentBlockParam> user) {
        if (!user.isEmpty()) {
            messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(user).build());
        }
    }

    private static ContentBlockParam text(String text) {
        return ContentBlockParam.ofText(TextBlockParam.builder().text(text).build());
    }

    private static ContentBlockParam result(ToolResult result) {
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(result.callId())
                .content(result.content())
                .isError(result.error())
                .build());
    }

    static Tool tool(ToolSpec spec) {
        Map<String, Object> schema = spec.inputSchema();
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        Object declared = schema.get("properties");
        if (declared instanceof Map<?, ?> map) {
            map.forEach((name, property) -> properties.putAdditionalProperty((String) name, JsonValue.from(property)));
        }
        List<String> required = new ArrayList<>();
        if (schema.get("required") instanceof List<?> list) {
            list.forEach(name -> required.add((String) name));
        }
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .strict(true)
                .build();
    }

    private static String write(MessageParam param) {
        try {
            return SDK_JSON.writeValueAsString(param);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a message param always serialises", impossible);
        }
    }

    static MessageParam read(String providerContent) {
        try {
            return SDK_JSON.readValue(providerContent, MessageParam.class);
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("a stored model turn is not a provider message", corrupt);
        }
    }
}
