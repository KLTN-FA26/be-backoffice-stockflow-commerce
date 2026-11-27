package com.stockflow.assistant.internal.controller;

/**
 * The permission resources of the assistant's endpoints, seeded by V20270120000200, V20270120001100 and
 * V20270120001300, and their shared rate limits.
 */
final class AssistantResources {

    static final String CONVERSATIONS = "assistant-conversations";

    /** The MCP endpoint and its personal tokens (ADR-0017), granted per role apart from the chat (AQ-17). */
    static final String MCP = "assistant-mcp";

    /**
     * The answers people marked wrong, with other people's questions (contract §5.1, AQ-16): System Admin only
     * (V20270120001300).
     */
    static final String FEEDBACK = "assistant-feedback";

    /** How often each assistant feature is used, counts only (contract §5.2): System Admin and Sales Manager. */
    static final String USAGE = "assistant-usage";

    /**
     * The rate-limit bucket of the assistant's deterministic reads (contract §0 rule 6): no model, no question spent,
     * a bucket of their own and a generous limit, {@link #READS_PER_MINUTE}.
     */
    static final String READS_BUCKET = "assistant-reads";

    /** Deterministic reads one user may make a minute from {@link #READS_BUCKET}. */
    static final int READS_PER_MINUTE = 60;

    /**
     * The rate-limit bucket of every way of spending the assistant's model calls or tools: the plain and the streamed
     * question, the MCP endpoint and a voice recording. One bucket, so switching endpoints or clients buys no
     * extra calls; the limiter bounds a bucket by the capacity each call passes, so every user of it passes
     * {@link #QUESTIONS_PER_MINUTE}.
     */
    static final String QUESTIONS_BUCKET = "assistant-questions";

    /** Calls one user may make a minute from {@link #QUESTIONS_BUCKET}, whichever endpoint they use (design §3.1). */
    static final int QUESTIONS_PER_MINUTE = 10;

    /**
     * The work features' screens (contract §1): each endpoint carries the permission of the screen its button sits on,
     * copied by value as {@code ToolPermissions} copies the tools': {@code OrderResources.ORDERS} (the order detail),
     * {@code ChatCallers.CONVERSATIONS} (the staff chat) and {@code OrderResources.QUOTE_PRICE_APPROVALS} (the quote
     * price approval, whose only action is APPROVE).
     */
    static final String SALES_ORDERS = "sales-orders";
    static final String CHAT_CONVERSATIONS = "chat-conversations";
    static final String QUOTE_PRICE_APPROVALS = "sales-quote-price-approvals";

    /**
     * The rate-limit bucket of the deterministic work features: their own, never {@link #QUESTIONS_BUCKET}, since they
     * ask no model (contract §0 rule 6), and generous, since a panel is opened as often as its screen.
     */
    static final String WORK_BUCKET = "assistant-work";
    static final int WORK_PER_MINUTE = 60;

    private AssistantResources() {
    }
}
