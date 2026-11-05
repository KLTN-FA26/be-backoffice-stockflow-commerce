package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.SpeechTranscriber;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.http.RestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the {@link SpeechTranscriber} (ADR-0015), as {@link DocumentReaderConfig} chooses the delivery-note reader:
 * on the chat's switch and settings, and only when {@value #ENABLED} is true as well.
 *
 * <p><b>Off unless asked for.</b> A recording is someone's voice, and what they say may name a customer or an amount;
 * the agreement to send tool results to a model provider (design §13 AQ-01) does not cover it, so voice does not come
 * on with the chat.</p>
 *
 * <p><b>The model</b> is {@value #MODEL} when set, read as a routed model is ({@link ModelCatalog}), so the data
 * policy and the allow-lists of {@link DataBoundary} decide whether it may be called: {@code openai/whisper-small}
 * sends the recording to the OpenAI-compatible server the company runs, which is the one choice a {@code LOCAL_ONLY}
 * policy leaves. Unset, it is the chat's route ({@code routing.models}, or the chat's model alone): its Gemini models
 * that the boundary allows, tried in order when a quota is spent ({@link RoutedSpeechTranscriber}), as questions move
 * on. A local chat model does not hear, and Claude takes no audio. Whatever is refused is logged once at start-up,
 * without the key, and the endpoint then answers {@code ASSISTANT_DISABLED}.</p>
 */
@Configuration(proxyBeanMethods = false)
class SpeechTranscriberConfig {

    /** The property that turns voice on. */
    static final String ENABLED = "stockflow.assistant.voice.enabled";

    /** The model that transcribes; the chat's own when unset. */
    static final String MODEL = "stockflow.assistant.voice.model";

    private static final Logger log = LoggerFactory.getLogger(SpeechTranscriberConfig.class);

    @Bean
    SpeechTranscriber speechTranscriber(AssistantProperties assistant, ModelRoutingProperties routing,
                                        RestClientFactory http,
                                        @Value("${" + ENABLED + ":false}") boolean enabled,
                                        @Value("${" + MODEL + ":}") String voiceModel) {
        if (!enabled || !assistant.usable()) {
            return new Unavailable();
        }
        if (voiceModel == null || voiceModel.isBlank()) {
            return routed(assistant, routing, http);
        }
        ModelCatalog.ModelSpec spec = ModelCatalog.spec(assistant, voiceModel);
        Optional<String> refusal = ModelCatalog.refusal(assistant, spec);
        if (refusal.isPresent()) {
            log.warn("Spoken questions are not transcribed: {} {} cannot be used, {}", MODEL, spec.name(),
                    refusal.get());
            return new Unavailable();
        }
        AssistantProperties properties = ModelCatalog.settingsFor(assistant, spec);
        return switch (properties.providerKind()) {
            case GEMINI -> {
                log.info("Spoken questions are transcribed by Gemini, model {}", properties.model());
                yield new GeminiSpeechTranscriber(AssistantModelConfig.patient(http, "gemini", properties), properties);
            }
            case OPENAI -> {
                log.info("Spoken questions are transcribed by model {} at {}", properties.model(),
                        AssistantProperties.loggable(properties.endpoint()));
                yield new OpenAiCompatibleSpeechTranscriber(
                        AssistantModelConfig.patient(http, "openai-compatible", properties), properties);
            }
            case ANTHROPIC -> {
                log.warn("Spoken questions are not transcribed: Claude takes no audio; set {} to a Gemini or "
                        + "OpenAI-compatible speech model", MODEL);
                yield new Unavailable();
            }
        };
    }

    /** The Gemini models of the chat's route the boundary allows, in route order; none: voice is off. */
    private static SpeechTranscriber routed(AssistantProperties assistant, ModelRoutingProperties routing,
                                            RestClientFactory http) {
        List<SpeechTranscriber> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ModelCatalog.ModelSpec spec : ModelCatalog.route(assistant, routing)) {
            if (spec.provider() == ModelProvider.GEMINI && ModelCatalog.refusal(assistant, spec).isEmpty()) {
                AssistantProperties properties = ModelCatalog.settingsFor(assistant, spec);
                candidates.add(new GeminiSpeechTranscriber(AssistantModelConfig.patient(http, "gemini", properties),
                        properties));
                names.add(properties.model());
            }
        }
        if (candidates.isEmpty()) {
            log.warn("Spoken questions are not transcribed: no model of the chat's route can hear (a local text model "
                    + "or Claude); set {} to a Gemini or OpenAI-compatible speech model", MODEL);
            return new Unavailable();
        }
        log.info("Spoken questions are transcribed by Gemini, models {} in that order", names);
        return candidates.size() == 1 ? candidates.getFirst() : new RoutedSpeechTranscriber(candidates);
    }

    /** Voice switched off, or no model that may hear it: it says so. */
    static final class Unavailable implements SpeechTranscriber {

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Transcript transcribe(Audio audio) {
            throw new BusinessException(ErrorCode.ASSISTANT_DISABLED);
        }
    }
}
