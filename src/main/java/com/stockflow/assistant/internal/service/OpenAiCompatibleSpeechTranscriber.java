package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.SpeechTranscriber;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * {@link SpeechTranscriber} on {@code POST /audio/transcriptions} of a server speaking OpenAI's API: a Whisper server
 * inside the company (faster-whisper behind an OpenAI-compatible front) or OpenAI itself. The multipart fields are the
 * API's own: {@code file}, {@code model}, {@code language} {@code vi} (told rather than guessed, which a few seconds of
 * speech guess badly), {@code response_format} {@code json} and {@code temperature} 0. The answer is {@code text}.
 *
 * <p><b>The prompt</b> is Whisper's vocabulary hint ({@link VoicePrompts#HINT}), not an instruction.</p>
 *
 * <p>Failures are {@link OpenAiCompatibleCall}'s, as for the chat, but never retried: the recording leaves once.
 * Nothing of the recording or of the transcript reaches a log.</p>
 */
class OpenAiCompatibleSpeechTranscriber implements SpeechTranscriber {

    private final OpenAiCompatibleCall call;
    private final AssistantProperties properties;

    /** @param http based at the server's API root, as for {@link OpenAiCompatibleLanguageModel} */
    OpenAiCompatibleSpeechTranscriber(RestClient http, AssistantProperties properties) {
        this(http, properties, Thread::sleep);
    }

    OpenAiCompatibleSpeechTranscriber(RestClient http, AssistantProperties properties, Sleeper sleeper) {
        this.call = new OpenAiCompatibleCall(http, properties.apiKey(), sleeper, " (speech)");
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Transcript transcribe(Audio audio) {
        // Not patient: a slow CPU Whisper that times out still holds the first copy; a retry would send it again.
        JsonNode answer = call.postMultipart("/audio/transcriptions", parts(audio), false);
        return new Transcript(answer.path("text").asText("").strip(), LanguageModel.Usage.NONE, properties.model());
    }

    MultiValueMap<String, Object> parts(Audio audio) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(audio.mediaType()));
        ByteArrayResource file = new ByteArrayResource(audio.bytes()) {
            @Override
            public String getFilename() {
                return "question.wav";
            }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new HttpEntity<>(file, fileHeaders));
        parts.add("model", properties.model());
        parts.add("language", "vi");
        parts.add("prompt", VoicePrompts.HINT);
        parts.add("response_format", "json");
        parts.add("temperature", "0");
        return parts;
    }
}
