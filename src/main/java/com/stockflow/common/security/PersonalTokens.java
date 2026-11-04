package com.stockflow.common.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Personal tokens: a credential a user creates for one client of theirs and accepted on a few paths only, such as the
 * assistant's MCP endpoint (ADR-0013). The module that issues them implements this; the API chain checks them
 * ({@link PersonalTokenAuthenticationProvider}).
 *
 * <p><b>A token says who, never what.</b> Holding one proves the bearer is a client the user set up; the grants are
 * the user's as they stand at each request, built by {@link RunAs#authenticationFor} exactly as a request of theirs or
 * a job acting for them gets them. A role revoked or an account locked applies to the next call, without anyone
 * revoking the token.</p>
 *
 * <p><b>Only on {@link #paths}.</b> Anywhere else a token of this kind goes to the JWT decoder and fails it, so a
 * token made for an AI client is not also a key to the rest of the API: it cannot create another token, or reach an
 * endpoint that writes.</p>
 */
public interface PersonalTokens {

    /** What every token of this kind starts with, which tells it apart from a JWT before anything is decoded. */
    String prefix();

    /** The request paths that accept these tokens, as path patterns ({@code /api/v1/assistant/mcp}). */
    List<String> paths();

    /**
     * Who is acting for the user, lower case with dashes, as {@link RunAs} names an actor: rows written during the
     * request read "{@code username via} this".
     */
    String actor();

    /**
     * The live token this text is, and whose: issued, not revoked, not expired. Empty for anything else, a malformed
     * text included; never throws for a token that does not match. Reads only.
     */
    Optional<Holder> holderOf(String token);

    /** Records that the token opened a request: called once its holder's authorization was built. */
    void used(UUID tokenId);

    /** A live token: whose it is, and which one. */
    record Holder(UUID userId, UUID tokenId) {
    }
}
