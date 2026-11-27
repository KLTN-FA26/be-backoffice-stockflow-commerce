package com.stockflow.assistant.internal.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The assistant's instructions, {@code classpath:assistant/system-prompt.txt}, read once at startup.
 *
 * <p>A file rather than a constant so it can be read and reviewed as prose. Read once, and never assembled from
 * per-request values: the provider caches the prompt by its exact bytes, so anything that varies per request (the
 * date, the asker's roles) travels with the question instead (design §3.3).</p>
 */
@Component
public class SystemPrompt {

    static final String RESOURCE = "assistant/system-prompt.txt";

    private final String text;

    SystemPrompt() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            this.text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException missing) {
            throw new UncheckedIOException("classpath:" + RESOURCE + " is missing", missing);
        }
    }

    public String text() {
        return text;
    }
}
