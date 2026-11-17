/**
 * The assistant's domain: conversations and their turns, tool calls and outcomes, the ports the application layer
 * drives — the language model, the speech transcriber, the conversation store, the knowledge store and the store of
 * the users' verdicts on answers. The delivery-note reader and its draft receipt went with purchasing (ADR-0010).
 * No Spring, no JPA, no Jackson: a turn is plain records, and the provider's own JSON travels through it as an opaque
 * string.
 */
package com.stockflow.assistant.internal.domain;
