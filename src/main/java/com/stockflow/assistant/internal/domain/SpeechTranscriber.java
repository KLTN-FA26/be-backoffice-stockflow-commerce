package com.stockflow.assistant.internal.domain;

import java.util.Objects;

/**
 * The port to a model that writes down what was said in a short recording (ADR-0015, design §8.3): the assistant's
 * only audio input, kept apart from {@link LanguageModel} and {@link DocumentReader}.
 *
 * <p><b>Why a port of its own.</b> A transcript is not a question: it goes back to the person who spoke, who reads it,
 * corrects it and only then sends it as a question. A misheard digit names another real carton or order, so nothing
 * heard is ever acted on unread. The recording leaves the system once, in this call, and is kept nowhere.</p>
 *
 * <p><b>Nothing it returns is trusted.</b> {@link Transcript#text()} is the model's text exactly as written; the caller
 * shows it for correcting and marks the codes and numbers in it, the parts a misheard sound changes most.</p>
 */
public interface SpeechTranscriber {

    /**
     * False when voice is switched off, or its model cannot be used (no key of its own, or ruled out by the data
     * policy and the allow-lists): the caller then refuses before the recording is sent.
     */
    boolean available();

    /**
     * One transcription: one model call.
     *
     * @throws com.stockflow.common.error.BusinessException the codes of {@link LanguageModel#next}:
     *         {@code ASSISTANT_UNAVAILABLE} (worth trying again), {@code ASSISTANT_QUOTA_EXHAUSTED} and
     *         {@code ASSISTANT_MISCONFIGURED} (not), {@code ASSISTANT_DISABLED} when {@link #available()} is false
     */
    Transcript transcribe(Audio audio);

    /**
     * A recording as it is sent: WAV, 16-bit PCM, checked by {@code WavAudio} before it gets here.
     *
     * @param seconds its length, from the WAV header
     */
    record Audio(byte[] bytes, String mediaType, double seconds) {

        public Audio {
            Objects.requireNonNull(mediaType, "mediaType");
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("a recording has bytes");
            }
        }

        /** The size and length only: the bytes are someone's voice and have no place in a log line. */
        @Override
        public String toString() {
            return "Audio[" + mediaType + ", " + bytes.length + " bytes, " + seconds + " s]";
        }
    }

    /**
     * @param text  what was heard, exactly as the model wrote it; empty when nothing was said
     * @param usage the call's tokens, as the provider bills them; zeros where it bills by the second
     * @param model the model that answered, as the provider names it
     */
    record Transcript(String text, LanguageModel.Usage usage, String model) {

        public Transcript {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(model, "model");
        }
    }
}
