package com.stockflow.chat.internal.domain;

/**
 * Which side said or did something: the customer (their portal account), an agent (any staff member in the chat, the
 * supervisor included) or the system. A message's sender and a logged action's actor (16 BR-01) both use it. Mapped
 * {@code EnumType.STRING}.
 */
public enum SenderType { CUSTOMER, AGENT, SYSTEM }
