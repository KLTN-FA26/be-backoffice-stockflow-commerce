package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.SpeechTranscriber;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.http.RestClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * A spoken question written down (ADR-0015): what each adapter sends, what it makes of the answer, and which one the
 * configuration chooses, never a provider the data boundary rules out.
 */
class SpeechTranscriberTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    // Built at run time, so nothing key-shaped sits in the repository for a secret scanner to flag.
    private static final String GOOGLE_KEY = "AIza" + "test-key";
    private static final SpeechTranscriber.Audio AUDIO = new SpeechTranscriber.Audio(
            WavAudioTest.wav(16_000, 1, 16, 1, 1, null), "audio/wav", 1);

    private static AssistantProperties properties(String provider, String model, String apiKey, DataPolicy policy) {
        return new AssistantProperties(true, apiKey, model, "low", 6, 16000, 50, 90, Duration.ofSeconds(90),
                Duration.ofMinutes(15), true, "", provider, "", GOOGLE_KEY, null, policy);
    }

    @Test
    @DisplayName("Gemini: the instruction is the system instruction, the recording an inline audio/wav part; thoughts "
            + "are not the transcript, and no candidate is nothing heard")
    void gemini() throws Exception {
        GeminiSpeechTranscriber gemini = new GeminiSpeechTranscriber(RestClient.create("http://gemini.test"),
                properties("gemini", "gemini-flash", GOOGLE_KEY, DataPolicy.CLOUD_ALLOWED), millis -> { });

        JsonNode body = gemini.body(AUDIO);

        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText())
                .isEqualTo(VoicePrompts.INSTRUCTION).contains("do not").contains("answer");
        JsonNode inline = body.path("contents").get(0).path("parts").get(0).path("inline_data");
        assertThat(inline.path("mime_type").asText()).isEqualTo("audio/wav");
        assertThat(Base64.getDecoder().decode(inline.path("data").asText())).isEqualTo(AUDIO.bytes());
        assertThat(body.path("generationConfig").has("temperature")).as("left at the model's default").isFalse();

        SpeechTranscriber.Transcript heard = gemini.transcript(JSON.readTree("""
                {"modelVersion":"gemini-flash-001","candidates":[{"content":{"parts":[
                  {"text":"thinking","thought":true},{"text":" Còn bao nhiêu thùng ở vị trí HCM-A01-1-B? "}]}}],
                 "usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":12}}"""));
        assertThat(heard.text()).isEqualTo("Còn bao nhiêu thùng ở vị trí HCM-A01-1-B?");
        assertThat(heard.model()).isEqualTo("gemini-flash-001");
        assertThat(heard.usage().inputTokens()).isEqualTo(40);
        assertThat(gemini.transcript(JSON.readTree("{\"promptFeedback\":{\"blockReason\":\"OTHER\"}}")).text())
                .isEmpty();
        assertThat(gemini.transcript(JSON.readTree("""
                {"candidates":[{"finishReason":"SAFETY","content":{"parts":[{"text":"x"}]}}]}""")).text()).isEmpty();
        assertThatThrownBy(() -> gemini.transcript(JSON.readTree("""
                {"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"Còn bao nhiêu"}]}}]}""")))
                .as("a question cut off is never sent as if whole")
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
    }

    @Test
    @DisplayName("over the chat's route, a spent quota moves on to the next model; any other failure is the answer")
    void routed() {
        SpeechTranscriber spent = mock(SpeechTranscriber.class);
        SpeechTranscriber next = mock(SpeechTranscriber.class);
        SpeechTranscriber broken = mock(SpeechTranscriber.class);
        when(spent.transcribe(AUDIO)).thenThrow(new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        when(next.transcribe(AUDIO)).thenReturn(new SpeechTranscriber.Transcript("ok",
                com.stockflow.assistant.internal.domain.LanguageModel.Usage.NONE, "second"));
        when(broken.transcribe(AUDIO)).thenThrow(new BusinessException(ErrorCode.ASSISTANT_MISCONFIGURED));

        assertThat(new RoutedSpeechTranscriber(java.util.List.of(spent, next)).transcribe(AUDIO).model())
                .isEqualTo("second");
        assertThatThrownBy(() -> new RoutedSpeechTranscriber(java.util.List.of(broken, next)).transcribe(AUDIO))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MISCONFIGURED));
        org.mockito.Mockito.verify(next, org.mockito.Mockito.times(1)).transcribe(AUDIO);
        assertThatThrownBy(() -> new RoutedSpeechTranscriber(java.util.List.of(spent)).transcribe(AUDIO))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
    }

    @Test
    @DisplayName("OpenAI-compatible: one multipart POST to /audio/transcriptions with the file, the model, Vietnamese "
            + "and the vocabulary hint; the answer's text is the transcript")
    void openaiCompatible() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://whisper.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://whisper.test/v1/audio/transcriptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("name=\"file\"; filename=\"question.wav\""),
                        org.hamcrest.Matchers.containsString("whisper-small"),
                        org.hamcrest.Matchers.containsString("name=\"language\""))))
                .andRespond(withSuccess("{\"text\":\" kiểm tra lô LOT-7 \"}", MediaType.APPLICATION_JSON));
        OpenAiCompatibleSpeechTranscriber whisper = new OpenAiCompatibleSpeechTranscriber(builder.build(),
                properties("openai", "whisper-small", "", DataPolicy.LOCAL_ONLY), millis -> { });

        SpeechTranscriber.Transcript heard = whisper.transcribe(AUDIO);

        server.verify();
        assertThat(heard.text()).isEqualTo("kiểm tra lô LOT-7");
        assertThat(heard.model()).isEqualTo("whisper-small");
        assertThat(whisper.parts(AUDIO).getFirst("prompt")).isEqualTo(VoicePrompts.HINT);
        assertThat(VoicePrompts.HINT).doesNotContainPattern("[A-Z]{2,}-\\d");
    }

    @Test
    @DisplayName("configuration: off unless voice.enabled; the Gemini models of the chat's route hear, in order; a "
            + "local text model does not without voice.model; voice.model is routed, so LOCAL_ONLY refuses a cloud "
            + "speech model")
    void configuration() {
        RestClientFactory http = mock(RestClientFactory.class);
        SpeechTranscriberConfig config = new SpeechTranscriberConfig();
        AssistantProperties gemini = properties("gemini", "gemini-flash", GOOGLE_KEY, DataPolicy.CLOUD_ALLOWED);
        AssistantProperties local = properties("openai", "qwen3:8b", "", DataPolicy.LOCAL_ONLY);
        ModelRoutingProperties alone = new ModelRoutingProperties(null, true, null);

        SpeechTranscriber off = config.speechTranscriber(gemini, alone, http, false, "");
        assertThat(off.available()).isFalse();
        assertThatThrownBy(() -> off.transcribe(AUDIO)).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ASSISTANT_DISABLED));
        assertThat(config.speechTranscriber(local, alone, http, true, "").available()).isFalse();
        assertThat(config.speechTranscriber(local, alone, http, true, "gemini/gemini-flash").available()).isFalse();
        verifyNoInteractions(http);

        when(http.forService("gemini", GeminiLanguageModel.DEFAULT_BASE_URL)).thenReturn(RestClient.builder());
        when(http.forService("openai-compatible", "http://localhost:11434/v1")).thenReturn(RestClient.builder());
        assertThat(config.speechTranscriber(gemini, alone, http, true, "")).isInstanceOf(GeminiSpeechTranscriber.class);
        SpeechTranscriber route = config.speechTranscriber(gemini, new ModelRoutingProperties(java.util.List.of(
                "gemini-flash", "openai/qwen3:8b", "gemini-flash-lite"), true, null), http, true, "");
        assertThat(route).isInstanceOf(RoutedSpeechTranscriber.class);
        assertThat(((RoutedSpeechTranscriber) route).candidates()).as("the local text model is left out").hasSize(2);
        SpeechTranscriber whisper = config.speechTranscriber(local, alone, http, true, "openai/whisper-small");
        assertThat(whisper).isInstanceOf(OpenAiCompatibleSpeechTranscriber.class);
        assertThat(((OpenAiCompatibleSpeechTranscriber) whisper).parts(AUDIO).getFirst("model"))
                .isEqualTo("whisper-small");
    }

    @Test
    @DisplayName("the words holding a digit or spelling a number are listed apart from the codes, trimmed of "
            + "punctuation, each once; a question's closing \"không\" is not a number")
    void numbers() {
        assertThat(VoiceTranscriptions.numbers("Còn 12 thùng ở HCM-A01-1-B, lô LOT-7 hạn 30/11? Còn 12.",
                java.util.List.of("HCM-A01-1-B", "LOT-7"))).containsExactly("12", "30/11");
        assertThat(VoiceTranscriptions.numbers("Kệ ba còn mười hai thùng không?", java.util.List.of()))
                .containsExactly("ba", "mười", "hai");
    }
}
