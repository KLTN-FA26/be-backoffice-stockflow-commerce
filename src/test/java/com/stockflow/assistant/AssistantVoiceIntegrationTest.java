package com.stockflow.assistant;

import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.SpeechTranscriber;
import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A spoken question written down (ADR-0015) through the real security chain and grants: the question's guard, a
 * portal customer refused, the recording checked before it could leave, and the codes and numbers to check marked.
 * The speech model is a test double; nothing it hears is stored anywhere.
 */
@SecuredIntegrationTest
class AssistantVoiceIntegrationTest {

    private static final String TRANSCRIPTIONS = "/api/v1/assistant/transcriptions";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @MockitoBean SpeechTranscriber transcriber;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc http;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        when(transcriber.available()).thenReturn(true);
    }

    /** {@code seconds} of silence, 16 kHz mono 16-bit PCM. */
    private static byte[] wav(double seconds) {
        int data = (int) Math.round(seconds * 16_000) * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(16_000).putInt(32_000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data);
        return buffer.array();
    }

    private static MockHttpServletRequestBuilder upload(byte[] bytes, String type) {
        return multipart(TRANSCRIPTIONS).file(new MockMultipartFile("file", "question.wav", type, bytes));
    }

    private UUID staff(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role));
        return user;
    }

    @Test
    @DisplayName("a warehouse clerk's recording comes back as text with its codes and numbers marked; nothing is kept")
    void writtenDown() throws Exception {
        when(transcriber.transcribe(any())).thenReturn(new SpeechTranscriber.Transcript(
                "Còn bao nhiêu thùng EVAL-LID-90 ở HCM-A01-1-B, khoảng 12 thùng?", LanguageModel.Usage.NONE,
                "speech-test"));
        long conversations = jdbc.queryForObject("select count(*) from assistant.conversation", Long.class);

        http.asRole(Role.WAREHOUSE_STAFF, staff("WAREHOUSE_STAFF"), upload(wav(2), "audio/wav"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value("Còn bao nhiêu thùng EVAL-LID-90 ở HCM-A01-1-B, khoảng 12 "
                        + "thùng?"))
                .andExpect(jsonPath("$.data.codes[0]").value("EVAL-LID-90"))
                .andExpect(jsonPath("$.data.codes[1]").value("HCM-A01-1-B"))
                .andExpect(jsonPath("$.data.numbers[0]").value("12"))
                .andExpect(jsonPath("$.data.seconds").value(2.0));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from assistant.conversation",
                Long.class)).as("a transcription asks nothing and keeps nothing").isEqualTo(conversations);
    }

    @Test
    @DisplayName("a file that is not WAV is refused with 415 and a minute and more with 400, before anything is sent")
    void checkedBeforeSending() throws Exception {
        UUID clerk = staff("WAREHOUSE_STAFF");

        http.asRole(Role.WAREHOUSE_STAFF, clerk, upload("not a recording".getBytes(StandardCharsets.UTF_8),
                "audio/wav")).andExpect(status().isUnsupportedMediaType());
        http.asRole(Role.WAREHOUSE_STAFF, clerk, upload(wav(1), "audio/webm"))
                .andExpect(status().isUnsupportedMediaType());
        http.asRole(Role.WAREHOUSE_STAFF, clerk, upload(wav(61), "audio/wav")).andExpect(status().isBadRequest());

        verify(transcriber, never()).transcribe(any());
    }

    @Test
    @DisplayName("a portal customer and an anonymous caller are refused; voice switched off is ASSISTANT_DISABLED")
    void refused() throws Exception {
        UUID account = tx.execute(s -> ReferenceRows.user(entityManager));
        http.asCustomer(account, upload(wav(1), "audio/wav")).andExpect(status().isForbidden());
        http.anonymous(upload(wav(1), "audio/wav")).andExpect(status().isUnauthorized());

        when(transcriber.available()).thenReturn(false);
        http.asRole(Role.SALES_STAFF, staff("SALES_STAFF"), upload(wav(1), "audio/wav"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_DISABLED"));
        verify(transcriber, never()).transcribe(any());
    }
}
