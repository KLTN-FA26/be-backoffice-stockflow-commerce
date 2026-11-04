/**
 * The assistant's domain: conversations and their turns, tool calls and outcomes, the ports the application layer
 * drives — the language model, the document reader (a photographed delivery note read into JSON, design §8.1), the
 * conversation store, the knowledge store and the store of the users' verdicts on answers — and the draft goods
 * receipt a reading becomes ({@code ReceiptDraft}).
 * No Spring, no JPA, no Jackson: a turn is plain records, and the provider's own JSON travels through it as an opaque
 * string.
 */
package com.stockflow.assistant.internal.domain;
