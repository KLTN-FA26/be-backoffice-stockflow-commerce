package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.SpeechTranscriber;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * A spoken question as it is accepted (ADR-0015): a WAV file of 16-bit PCM, at most {@value #MAX_BYTES} bytes and
 * {@value #MAX_SECONDS} seconds, judged by its own header rather than by what the upload claims.
 *
 * <p><b>Why WAV only.</b> Both providers read it, a browser makes it without a codec library (the playground resamples
 * to 16 kHz mono, about 32 KB a second), and its length is in its header, so a minute's limit is checked here before
 * anything is sent, not discovered on the bill. Sixty seconds is far more than a question takes and keeps a request
 * under 2 MB, a tenth of what Gemini accepts inline.</p>
 */
final class WavAudio {

    /** Two megabytes: a minute of 16 kHz mono 16-bit PCM is 1.92 MB. */
    static final long MAX_BYTES = 2L * 1024 * 1024;

    /** A minute, more than any question takes. */
    static final int MAX_SECONDS = 60;

    /** Shorter than this is a tap of the button, not a question; nothing is sent. */
    static final double MIN_SECONDS = 0.3;

    static final String MEDIA_TYPE = "audio/wav";

    /** What browsers and tools declare a WAV file as. */
    private static final Set<String> ACCEPTED = Set.of("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave");

    private WavAudio() {
    }

    /**
     * The upload as a recording to send: 413 above {@value #MAX_BYTES} bytes, 415 when it is not declared as WAV or is
     * not one, 400 when it is not 16-bit PCM, shorter than {@value #MIN_SECONDS} seconds or longer than
     * {@value #MAX_SECONDS}. Reads at most one byte past the limit, whatever the upload claims its size is.
     */
    static SpeechTranscriber.Audio accept(FileUpload upload) {
        if (upload.sizeBytes() > MAX_BYTES) {
            throw tooLarge();
        }
        String declared = upload.contentType() == null ? ""
                : upload.contentType().split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!ACCEPTED.contains(declared)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "A spoken question is a WAV file");
        }
        byte[] bytes;
        try {
            bytes = upload.content().readNBytes((int) MAX_BYTES + 1);
        } catch (IOException unreadable) {
            throw new StorageException("Could not read the uploaded recording", unreadable);
        }
        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
        }
        return new SpeechTranscriber.Audio(bytes, MEDIA_TYPE, seconds(bytes));
    }

    /**
     * The length of a WAV file in seconds, from its {@code fmt } and {@code data} chunks. A {@code data} chunk whose
     * size runs past the file's end, as a recorder that never went back to fill it in writes, counts what is there.
     */
    static double seconds(byte[] wav) {
        if (wav.length < 12 || !tag(wav, 0, "RIFF") || !tag(wav, 8, "WAVE")) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "The file's contents are not WAV");
        }
        int format = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        long dataBytes = -1;
        int at = 12;
        while (at + 8 <= wav.length) {
            long size = uint32(wav, at + 4);
            int body = at + 8;
            if (tag(wav, at, "fmt ") && size >= 16 && body + 16 <= wav.length) {
                format = uint16(wav, body);
                channels = uint16(wav, body + 2);
                sampleRate = (int) uint32(wav, body + 4);
                bits = uint16(wav, body + 14);
            } else if (tag(wav, at, "data")) {
                dataBytes = Math.min(size, wav.length - body);
                break;
            }
            long next = body + size + (size % 2);
            if (next > wav.length) {
                break;
            }
            at = (int) next;
        }
        if (format != 1 || bits != 16 || channels < 1 || channels > 2 || sampleRate < 8_000 || sampleRate > 48_000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A spoken question is 16-bit PCM WAV, mono or stereo, 8 to 48 kHz");
        }
        if (dataBytes < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The WAV file holds no sound");
        }
        double seconds = (double) dataBytes / ((long) sampleRate * channels * 2);
        if (seconds < MIN_SECONDS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The recording is too short to hold a question");
        }
        if (seconds > MAX_SECONDS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A spoken question is at most " + MAX_SECONDS + " seconds");
        }
        return seconds;
    }

    private static BusinessException tooLarge() {
        return new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,
                "A spoken question is at most " + MAX_BYTES / (1024 * 1024) + " MB");
    }

    private static boolean tag(byte[] bytes, int at, String tag) {
        for (int i = 0; i < 4; i++) {
            if (bytes[at + i] != tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int uint16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;
    }

    private static long uint32(byte[] bytes, int at) {
        return (bytes[at] & 0xFFL) | (bytes[at + 1] & 0xFFL) << 8 | (bytes[at + 2] & 0xFFL) << 16
                | (bytes[at + 3] & 0xFFL) << 24;
    }
}
