package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.SpeechTranscriber;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * {@link SpeechTranscriber} on Gemini's {@code generateContent} (REST), as {@link GeminiDocumentReader} reads a photo:
 * the instruction is the system instruction, the recording an {@code inline_data} part ({@code audio/wav}, base64),
 * then one line asking for the transcript. Gemini bills 32 tokens a second of audio, so a minute is under 2,000 input
 * tokens; the answer is capped at {@value #MAX_OUTPUT_TOKENS} tokens, far more than a minute of speech. One call, never
 * retried: the recording leaves once. A transcript cut off by the cap is an error, never a shorter question.
 *
 * <p><b>A transcript, not an answer.</b> A model asked a question out loud tends to answer it; the instruction
 * ({@link VoicePrompts#INSTRUCTION}) says to write down what was said and nothing else, and the person reads it
 * before sending it anyway. A refusal or an empty candidate is an empty transcript: the screen says nothing was heard,
 * which is what the person can act on.</p>
 *
 * <p><b>Nothing of the recording or of the transcript reaches a log.</b></p>
 */
class GeminiSpeechTranscriber implements SpeechTranscriber {

    /** Far more than a minute of speech, so that a thinking model's own tokens do not cut the transcript. */
    static final int MAX_OUTPUT_TOKENS = 4_096;

    /** Finish reasons of a declined candidate: nothing is written down, which the person is told. */
    private static final Set<String> REFUSALS = Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT",
            "SPII", "LANGUAGE");

    private final GeminiCall call;
    private final AssistantProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    /** @param http based at the API's root, as for {@link GeminiLanguageModel} */
    GeminiSpeechTranscriber(RestClient http, AssistantProperties properties) {
        this(http, properties, Thread::sleep);
    }

    GeminiSpeechTranscriber(RestClient http, AssistantProperties properties, Sleeper sleeper) {
        this.call = new GeminiCall(http, properties.apiKey(), sleeper, " (speech)", "model provider");
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Transcript transcribe(Audio audio) {
        // Not patient: a retry after a timeout would send the recording again while the provider may still hold it.
        return transcript(call.post("/v1beta/models/{model}:generateContent", properties.model(), write(body(audio)),
                false));
    }

    ObjectNode body(Audio audio) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", VoicePrompts.INSTRUCTION);
        ArrayNode parts = body.putArray("contents").addObject().put("role", "user").putArray("parts");
        parts.addObject().putObject("inline_data")
                .put("mime_type", audio.mediaType())
                .put("data", Base64.getEncoder().encodeToString(audio.bytes()));
        parts.addObject().put("text", "Transcribe the recording.");
        // No temperature: Google advises against lowering it for its newer models, which then tend to loop.
        body.putObject("generationConfig").put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        return body;
    }

    Transcript transcript(JsonNode response) {
        String model = response.path("modelVersion").asText(properties.model());
        JsonNode metadata = response.path("usageMetadata");
        LanguageModel.Usage usage = new LanguageModel.Usage(metadata.path("promptTokenCount").asLong(0),
                metadata.path("candidatesTokenCount").asLong(0) + metadata.path("thoughtsTokenCount").asLong(0), 0, 0);
        JsonNode candidate = response.path("candidates").path(0);
        String finish = candidate.path("finishReason").asText("");
        if ("MAX_TOKENS".equals(finish)) {
            // A question cut off mid-sentence read as whole would be sent as asked; better to say so.
            throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE,
                    "The recording could not be written down whole; try a shorter question");
        }
        if (candidate.isMissingNode() || REFUSALS.contains(finish)) {
            return new Transcript("", usage, model);
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.has("text") && !part.path("thought").asBoolean(false)) {
                texts.add(part.path("text").asText());
            }
        }
        return new Transcript(String.join("", texts).strip(), usage, model);
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a JSON tree always serialises", impossible);
        }
    }
}
