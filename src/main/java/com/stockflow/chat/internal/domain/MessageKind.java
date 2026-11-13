package com.stockflow.chat.internal.domain;

/**
 * What a message is. Mapped {@code EnumType.STRING}; {@code ck_message_kind} holds the same list.
 *
 * <ul>
 *   <li>{@code TEXT} - written by the customer or an agent;</li>
 *   <li>{@code ATTACHMENT} - one or more files, with an optional caption;</li>
 *   <li>{@code CARD} - a card the system draws from a document (16 §3 Output: quote request, quote, contract,
 *       transfer details, sample approval, invoice...), with a kind, a payload and a state; package 3;</li>
 *   <li>{@code SYSTEM} - a line the system writes ("the conversation was handed to ...");</li>
 *   <li>{@code INTERNAL_NOTE} - the staff's own note, never shown to the customer (16 BR-07).</li>
 * </ul>
 */
public enum MessageKind { TEXT, ATTACHMENT, CARD, SYSTEM, INTERNAL_NOTE }
