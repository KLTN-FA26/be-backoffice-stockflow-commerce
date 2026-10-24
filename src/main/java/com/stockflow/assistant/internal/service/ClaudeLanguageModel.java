package com.stockflow.assistant.internal.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * <p>Model behaviour this relies on, checked against the provider's documentation in October 2026: thinking is
 * always on for the default model and must not be configured; forced tool choice is rejected, so the default
 * {@code auto} is used; {@code temperature} is not sent.</p>
 */
class ClaudeLanguageModel implements LanguageModel, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClaudeLanguageModel.class);

    /** The SDK's own mapper: it knows how to read back what it wrote. */
    private static final JsonMapper SDK_JSON = ObjectMappers.jsonMapper();

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

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
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxOutputTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.systemPrompt())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(properties.effort())).build())
                .cacheControl(CacheControlEphemeral.builder().build())
                .messages(messages(request.history()));
        request.tools().forEach(tool -> params.addTool(tool(tool)));
        if (properties.refusalFallback()) {
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        Message response;
        try {
            response = client.messages().create(params.build());
        } catch (AnthropicServiceException rejected) {
            // A 4xx other than 429 is a bug on our side (a malformed request); log it loudly. Either way the caller
            // gets a retryable 503 and the provider's message never reaches the client.
            if (rejected.statusCode() >= 400 && rejected.statusCode() < 500 && rejected.statusCode() != 429) {
                log.error("The model provider rejected the request with {}: {}", rejected.statusCode(),
                        rejected.getMessage());
            } else {
                log.warn("The model provider answered {}: {}", rejected.statusCode(), rejected.getMessage());
            }
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model provider answered "
                    + rejected.statusCode(), rejected);
        } catch (AnthropicException unreachable) {
            log.warn("The model provider could not be reached: {}", unreachable.getMessage());
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model provider could not be reached",
                    unreachable);
        }
        return reply(response);
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
            case "end_turn" -> StopReason.END_TURN;
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
