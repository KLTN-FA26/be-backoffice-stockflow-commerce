package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.SpeechTranscriber;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileUpload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** A spoken question's file (ADR-0015): judged by its own header, never by what the upload declares. */
class WavAudioTest {

    /** A WAV file of {@code seconds} of silence; {@code dataSize} overrides the data chunk's declared size. */
    static byte[] wav(int sampleRate, int channels, int bits, int format, double seconds, Long dataSize) {
        int data = (int) Math.round(seconds * sampleRate * channels * bits / 8.0);
        ByteBuffer buffer = ByteBuffer.allocate(44 + 12 + data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + 12 + data)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII));
        // A chunk before fmt, as some recorders write, to be stepped over.
        buffer.put("LIST".getBytes(StandardCharsets.US_ASCII)).putInt(4).put("INFO".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) format)
                .putShort((short) channels).putInt(sampleRate).putInt(sampleRate * channels * bits / 8)
                .putShort((short) (channels * bits / 8)).putShort((short) bits);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataSize == null ? data : (int) (long) dataSize);
        return buffer.array();
    }

    private static FileUpload upload(byte[] bytes, String type) {
        return new FileUpload("question.wav", type, bytes.length, new ByteArrayInputStream(bytes));
    }

    private static void refused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("16 kHz mono 16-bit PCM is accepted, its length read from the header, sent as audio/wav")
    void accepted() {
        SpeechTranscriber.Audio audio = WavAudio.accept(upload(wav(16_000, 1, 16, 1, 4.5, null), "audio/wav"));

        assertThat(audio.seconds()).isCloseTo(4.5, within(0.001));
        assertThat(audio.mediaType()).isEqualTo("audio/wav");
        assertThat(audio.toString()).doesNotContain("RIFF");
        assertThat(WavAudio.accept(upload(wav(44_100, 2, 16, 1, 2, null), "audio/x-wav; codecs=1")).seconds())
                .isCloseTo(2, within(0.001));
    }

    @Test
    @DisplayName("a data chunk whose size runs past the end, as a streaming recorder writes, counts what is there")
    void openEndedData() {
        assertThat(WavAudio.seconds(wav(16_000, 1, 16, 1, 3, 0xFFFFFFFFL))).isCloseTo(3, within(0.001));
    }

    @Test
    @DisplayName("not declared WAV, or not WAV inside, is 415; too big is 413 before it is read")
    void refusedByType() {
        refused(() -> WavAudio.accept(upload(wav(16_000, 1, 16, 1, 1, null), "audio/webm")),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        refused(() -> WavAudio.accept(upload("not a recording at all".getBytes(StandardCharsets.UTF_8), "audio/wav")),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        refused(() -> WavAudio.accept(new FileUpload("big.wav", "audio/wav", WavAudio.MAX_BYTES + 1,
                new ByteArrayInputStream(new byte[0]))), ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("not 16-bit PCM, a tap shorter than the minimum, or longer than a minute is 400")
    void refusedByContent() {
        refused(() -> WavAudio.seconds(wav(16_000, 1, 8, 1, 1, null)), ErrorCode.VALIDATION_FAILED);
        refused(() -> WavAudio.seconds(wav(16_000, 1, 16, 3, 1, null)), ErrorCode.VALIDATION_FAILED);
        refused(() -> WavAudio.seconds(wav(16_000, 1, 16, 1, 0.1, null)), ErrorCode.VALIDATION_FAILED);
        refused(() -> WavAudio.seconds(wav(8_000, 1, 16, 1, 61, null)), ErrorCode.VALIDATION_FAILED);
    }
}
