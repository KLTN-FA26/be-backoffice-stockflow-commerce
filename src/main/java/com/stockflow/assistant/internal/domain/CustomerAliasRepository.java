package com.stockflow.assistant.internal.domain;

import java.util.Map;
import java.util.UUID;

/**
 * Which customer each alias of a conversation stands for ({@code assistant.customer_alias}, ADR-0014): kept in the
 * company's database, never sent to a model provider.
 */
public interface CustomerAliasRepository {

    /** The conversation's aliases: the n of "KH#n" to the customer it stands for. */
    Map<Integer, UUID> aliasesOf(UUID conversationId);

    /** Stores the aliases a question gave out, in the transaction that stores its answer. */
    void save(UUID conversationId, Map<Integer, UUID> added);
}
