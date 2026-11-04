package com.stockflow.assistant.internal.service;

/** Waits between the attempts of a provider call ({@link GeminiCall}, {@link OpenAiCompatibleCall}); replaced in tests. */
@FunctionalInterface
interface Sleeper {

    void sleep(long millis) throws InterruptedException;
}
