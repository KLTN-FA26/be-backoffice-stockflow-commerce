/**
 * The assistant's domain: conversations and their turns, tool calls and outcomes, and the two ports the application
 * layer drives — the language model and the conversation store. No Spring, no JPA, no Jackson: a turn is plain
 * records, and the provider's own JSON travels through it as an opaque string.
 */
package com.stockflow.assistant.internal.domain;
