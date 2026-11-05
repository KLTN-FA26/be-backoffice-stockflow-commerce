package com.stockflow.assistant.internal.controller;

/**
 * The permission resources of the assistant's endpoints, seeded by V20261105000200 and V20261105001100, and their
 * shared rate limit.
 */
final class AssistantResources {

    static final String CONVERSATIONS = "assistant-conversations";

    /** The MCP endpoint and its personal tokens (ADR-0013), granted per role apart from the chat (AQ-17). */
    static final String MCP = "assistant-mcp";

    /**
     * The rate-limit bucket of every way of spending the assistant's model calls or tools: the plain and the streamed
     * question, the MCP endpoint and a delivery-note reading. One bucket, so switching endpoints or clients buys no
     * extra calls; the limiter bounds a bucket by the capacity each call passes, so every user of it passes
     * {@link #QUESTIONS_PER_MINUTE}.
     */
    static final String QUESTIONS_BUCKET = "assistant-questions";

    /** Calls one user may make a minute from {@link #QUESTIONS_BUCKET}, whichever endpoint they use (design §3.1). */
    static final int QUESTIONS_PER_MINUTE = 10;

    private AssistantResources() {
    }
}
