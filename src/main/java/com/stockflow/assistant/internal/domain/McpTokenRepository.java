package com.stockflow.assistant.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where the MCP endpoint's personal tokens are kept: by their hash, never the token (V20261105001100). */
public interface McpTokenRepository {

    /**
     * Holds the owner's token list for the rest of the transaction, so two tokens made at once are counted one after
     * the other against the limit of live tokens.
     */
    void lockOwner(UUID owner);

    void insert(UUID id, UUID owner, String name, String hash, String hint, Instant createdAt, Instant expiresAt);

    /** The owner's tokens, live ones first, each part newest first, at most {@code limit}. */
    List<McpToken> ownedBy(UUID owner, int limit);

    /** How many of the owner's tokens are live at {@code now}. */
    int countLive(UUID owner, Instant now);

    /**
     * Revokes the owner's token {@code id} at {@code at}, unless it already was; empty when the owner has no such
     * token. Returns the token as it now is.
     */
    Optional<McpToken> revoke(UUID id, UUID owner, Instant at);

    /** Revokes every token of the owner still live at {@code at}; returns how many. */
    int revokeAllOf(UUID owner, Instant at);

    /** The token with this hash and its owner, when it is live at {@code now}. */
    Optional<Holder> live(String hash, Instant now);

    /** Records that the token was used at {@code at}, unless it was recorded less than a minute before. */
    void used(UUID id, Instant at);

    /** Deletes up to {@code limit} tokens that expired or were revoked before {@code cutoff}; returns how many. */
    int deleteDeadBefore(Instant cutoff, int limit);

    /** A live token: which one, and whose. */
    record Holder(UUID tokenId, UUID owner) {
    }
}
