package com.stockflow.assistant.internal.service;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The words the voice transcribers send with a recording (ADR-0015), kept in resources as the system prompt is: they
 * hold Vietnamese, which has no place in the code.
 *
 * <ul>
 *   <li>{@link #INSTRUCTION}, {@code classpath:assistant/voice-instruction.txt}: what Gemini is told to do with the
 *       recording.</li>
 *   <li>{@link #HINT}, {@code classpath:assistant/voice-hint.txt}: Whisper's vocabulary hint, words of the trade so
 *       that they are spelled as the staff spell them. It names no code: a hint with a code in it would be heard in
 *       every silence.</li>
 *   <li>{@link #NUMBER_WORDS}, {@code classpath:assistant/voice-number-words.txt}: Vietnamese number words, marked in a
 *       transcript as digits are, since "hai" heard for "bảy" changes a quantity as much as a digit does. Not
 *       "không", which ends most questions.</li>
 * </ul>
 */
final class VoicePrompts {

    static final String INSTRUCTION = read("assistant/voice-instruction.txt");

    static final String HINT = read("assistant/voice-hint.txt");

    static final Set<String> NUMBER_WORDS = Set.of(read("assistant/voice-number-words.txt").split("\\s+"));

    private VoicePrompts() {
    }

    private static String read(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException missing) {
            throw new UncheckedIOException("classpath:" + resource + " is missing", missing);
        }
    }
}
