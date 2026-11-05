package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.SpeechTranscriber;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A spoken question written down (ADR-0015, design §8.3), for the person who spoke to read, correct and send as a
 * question of their own. Nothing is kept: not the recording, not the text, not a row anywhere; the log has the length,
 * the model and how it ended, never what was said.
 *
 * <p><b>What to check.</b> A misheard digit names another real carton, lot or order, and the person reading a
 * transcript reads past it. So the answer lists the codes ({@link AnswerCodes}) and the other words holding a digit or
 * spelling a number ({@link VoicePrompts#NUMBER_WORDS}), for the screen to mark: those are what to check against the
 * label, or to scan instead of saying. The text is returned as the codes were read from it (NFC, one kind of dash), so
 * every code marked is found in it.</p>
 *
 * <p><b>A day's budget of its own.</b> A transcription is not a question and is not counted against the daily question
 * cap, which counts stored questions; so it has a cap of its own, {@code stockflow.assistant.voice.daily-limit} a user
 * (a token bucket refilled over a day), beside the per-minute bucket the questions share.</p>
 */
@Service
public class VoiceTranscriptions {

    private static final Logger log = LoggerFactory.getLogger(VoiceTranscriptions.class);

    private final SpeechTranscriber transcriber;
    private final MeterRegistry meters;
    private final RateLimiter limiter;
    private final int dailyLimit;

    VoiceTranscriptions(SpeechTranscriber transcriber, MeterRegistry meters, RateLimiter limiter,
                        @Value("${stockflow.assistant.voice.daily-limit:100}") int dailyLimit) {
        this.transcriber = transcriber;
        this.meters = meters;
        this.limiter = limiter;
        this.dailyLimit = dailyLimit;
    }

    /**
     * @param text    what was heard; empty when nothing was
     * @param codes   the codes written in it, each once, in order
     * @param numbers the other words holding a digit, each once, in order
     * @param seconds the recording's length
     */
    public record Heard(String text, List<String> codes, List<String> numbers, double seconds) {
    }

    /**
     * Checks the recording ({@link WavAudio}) and has it written down.
     *
     * @param user who speaks: the day's budget is theirs
     * @throws BusinessException {@code ASSISTANT_DISABLED} when voice is off, before the upload is read;
     *         {@code RATE_LIMITED} past the day's budget; the codes of {@link WavAudio#accept} and of
     *         {@link SpeechTranscriber#transcribe}
     */
    public Heard transcribe(FileUpload upload, UUID user) {
        if (!transcriber.available()) {
            throw new BusinessException(ErrorCode.ASSISTANT_DISABLED);
        }
        SpeechTranscriber.Audio audio = WavAudio.accept(upload);
        if (!limiter.tryAcquire("assistant-voice-day:" + user, dailyLimit, Duration.ofDays(1)).allowed()) {
            meters.counter("assistant.voice.transcriptions", "outcome", "DAILY_LIMIT").increment();
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "At most " + dailyLimit + " spoken questions a day; type the question instead");
        }
        SpeechTranscriber.Transcript transcript;
        try {
            transcript = transcriber.transcribe(audio);
        } catch (BusinessException failed) {
            meters.counter("assistant.voice.transcriptions", "outcome", failed.errorCode().name()).increment();
            log.warn("A spoken question of {} s was not transcribed: {}", Math.round(audio.seconds()),
                    failed.errorCode());
            throw failed;
        }
        String text = AnswerNumbers.normalise(transcript.text());
        meters.counter("assistant.voice.transcriptions", "outcome", text.isEmpty() ? "NOTHING_HEARD" : "OK")
                .increment();
        log.info("A spoken question of {} s transcribed by {}: {} characters", Math.round(audio.seconds()),
                transcript.model(), text.length());
        List<String> codes = AnswerCodes.written(text);
        return new Heard(text, codes, numbers(text, codes), audio.seconds());
    }

    /**
     * The words of {@code text} holding a digit or spelling a number that are not one of {@code codes}, trimmed of
     * punctuation.
     */
    static List<String> numbers(String text, List<String> codes) {
        Set<String> written = new LinkedHashSet<>();
        Set<String> known = new LinkedHashSet<>();
        codes.forEach(code -> known.add(code.toUpperCase(Locale.ROOT)));
        for (String word : text.split("\\s+")) {
            String trimmed = word.replaceAll("^[\\p{P}&&[^-]]+|[\\p{P}&&[^%]]+$", "");
            boolean number = trimmed.chars().anyMatch(Character::isDigit)
                    || VoicePrompts.NUMBER_WORDS.contains(trimmed.toLowerCase(Locale.ROOT));
            if (number && !known.contains(trimmed.toUpperCase(Locale.ROOT))) {
                written.add(trimmed);
            }
        }
        return new ArrayList<>(written);
    }
}
