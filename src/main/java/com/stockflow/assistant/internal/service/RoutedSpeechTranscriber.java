package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.SpeechTranscriber;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.List;

/**
 * Voice over the chat's route (ADR-0015): when {@code voice.model} is unset, a recording goes to the first model of
 * {@code stockflow.assistant.routing.models} that can hear, and to the next when that one's quota is spent. Pinned to
 * the first model, every transcription would fail for the rest of the day once its free quota ran out, while questions
 * moved on to the next model.
 *
 * <p>Only a spent quota moves on: the provider refused before hearing anything, so the recording is still sent once.
 * Any other failure is the answer. Stateless: a spent model costs one quick refusal per recording, which is cheaper
 * than a second piece of quota bookkeeping beside {@link ModelRouter}'s.</p>
 */
final class RoutedSpeechTranscriber implements SpeechTranscriber {

    private final List<SpeechTranscriber> candidates;

    RoutedSpeechTranscriber(List<SpeechTranscriber> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("a route has at least one model");
        }
        this.candidates = List.copyOf(candidates);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Transcript transcribe(Audio audio) {
        BusinessException spent = null;
        for (SpeechTranscriber candidate : candidates) {
            try {
                return candidate.transcribe(audio);
            } catch (BusinessException refused) {
                if (refused.errorCode() != ErrorCode.ASSISTANT_QUOTA_EXHAUSTED) {
                    throw refused;
                }
                spent = refused;
            }
        }
        throw spent;
    }

    List<SpeechTranscriber> candidates() {
        return candidates;
    }
}
