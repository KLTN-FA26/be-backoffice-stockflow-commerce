/**
 * The application layer: the agent loop, the tools, the adapters to the model provider (the chat's
 * {@code LanguageModel} and the {@code DocumentReader}), and the draft goods receipt read from a delivery-note photo
 * ({@code ReceiptDraftService}).
 *
 * <p><b>Transactions.</b> The loop itself is deliberately not transactional: a model call takes seconds and must not
 * hold a pooled connection. Each tool calls an {@code api} method that opens and closes its own transaction, and the
 * transcript is written in short transactions before and after the loop.</p>
 *
 * <p><b>Tools.</b> Every tool's {@code run} method is public and carries {@code @RequiresPermission}: it is invoked
 * through its Spring proxy, so the permission aspect checks the caller and sets the data scope exactly as it does
 * for the screen the tool mirrors.</p>
 */
package com.stockflow.assistant.internal.service;
