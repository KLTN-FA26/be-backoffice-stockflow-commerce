package com.stockflow.assistant.internal.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link DocumentReader} on Anthropic's Messages API, through the official SDK, beside {@link ClaudeLanguageModel} and
 * on the same key, model and effort.
 *
 * <p><b>The request.</b> The instruction is the system prompt. The one user message holds the image as a base64
 * {@code image} block, then the context as text: Claude reads an image best when it comes before the words about it.
 * The answer is held to the schema by structured outputs ({@code output_config.format}, {@code json_schema}), which
 * the provider enforces while the model writes. Not a tool: forcing a tool call is refused on this model while
 * thinking is on, and a tool the model may merely choose to call could come back as prose instead. Thinking is not
 * configured (it is always on for the default model) and no temperature is sent, as for the chat.</p>
 *
 * <p><b>Failures say what to do next</b>, through the same mapping as the chat ({@link ClaudeLanguageModel#failure}):
 * the SDK retries 408, 409, 429, 5xx and connection failures; what remains is {@code ASSISTANT_MISCONFIGURED} for
 * 400 to 404, {@code ASSISTANT_QUOTA_EXHAUSTED} for a long 429, {@code ASSISTANT_UNAVAILABLE} otherwise. The image is
 * one the application decoded and encoded itself ({@link DocumentImageProcessor}), well under the provider's size
 * limit, so a 400 is ours or the key's, never the user's photo.</p>
 *
 * <p>A refusal ({@code stop_reason: refusal}) and a cut-off answer read nothing; their text, which may not match the
 * schema, is never returned. The refusal fallback is sent as for the chat, when it is switched on.</p>
 */
class ClaudeDocumentReader implements DocumentReader, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClaudeDocumentReader.class);

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final AssistantProperties properties;

    ClaudeDocumentReader(AnthropicClient client, AssistantProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Reading read(Request request) {
        Message response;
        try {
            response = client.messages().create(params(request));
        } catch (AnthropicServiceException rejected) {
            throw ClaudeLanguageModel.failure(rejected);
        } catch (AnthropicException unreachable) {
            // A connection failure or a timeout, after the SDK's own retries.
            log.warn("The model provider could not be reached reading a document: {}", unreachable.getMessage());
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "The model provider could not be reached",
                    unreachable);
        }
        return reading(response);
    }

    MessageCreateParams params(Request request) {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        request.schema().forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxOutputTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(request.instruction()).build()))
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(properties.effort()))
                        .format(JsonOutputFormat.builder().schema(schema.build()).build())
                        .build())
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofImage(ImageBlockParam.builder()
                                .source(Base64ImageSource.builder()
                                        .data(Base64.getEncoder().encodeToString(request.image().bytes()))
                                        .mediaType(Base64ImageSource.MediaType.of(request.image().mediaType()))
                                        .build())
                                .build()),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(request.context()).build())));
        if (properties.refusalFallback()) {
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return params.build();
    }

    static Reading reading(Message response) {
        LanguageModel.Usage usage = new LanguageModel.Usage(response.usage().inputTokens(),
                response.usage().outputTokens(), response.usage().cacheReadInputTokens().orElse(0L),
                response.usage().cacheCreationInputTokens().orElse(0L));
        String model = response.model().asString();
        String stop = response.stopReason().map(StopReason::asString).orElse("");
        if ("refusal".equals(stop)) {
            return new Reading(Outcome.REFUSED, null, usage, model);
        }
        if ("max_tokens".equals(stop)) {
            return new Reading(Outcome.CUT_SHORT, null, usage, model);
        }
        String answer = response.content().stream()
                .flatMap(block -> block.text().map(TextBlock::text).stream())
                .collect(Collectors.joining());
        if (!"end_turn".equals(stop) || answer.isBlank()) {
            return new Reading(Outcome.NOTHING, null, usage, model);
        }
        return new Reading(Outcome.READ, answer, usage, model);
    }

    @Override
    public void close() {
        client.close();
    }
}
