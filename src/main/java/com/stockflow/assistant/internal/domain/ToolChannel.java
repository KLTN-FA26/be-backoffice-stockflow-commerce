package com.stockflow.assistant.internal.domain;

/**
 * How a tool came to run, for the audit trail and the traces (design §5.5, §7): every channel runs the same tools
 * through the same guards, and each is recorded under its own name.
 */
public enum ToolChannel {

    /** A question in a conversation; the call belongs to the assistant turn that asked for it. */
    CHAT,

    /** A {@code tools/call} of an MCP client, under the signed-in user's rights; no conversation. */
    MCP,

    /** One of the "today" card's fixed reads; no conversation. */
    TODAY,

    /** A background check or triage run for one user, under that user's own rights. */
    PROACTIVE
}
