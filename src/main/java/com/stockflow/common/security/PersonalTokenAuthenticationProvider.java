package com.stockflow.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

/**
 * Authenticates a {@link PersonalTokens personal token}: finds whose it is, then builds that user's authorization as it
 * stands now ({@link RunAs#authenticationFor}). The API chain hands it a bearer token only on the token kind's own
 * paths and only when the token carries its prefix; every other bearer token goes to the JWT decoder.
 *
 * <p>A token that is not live (unknown, revoked, expired) and an account that may not sign in any more are both 401,
 * with the same message, so a caller learns nothing about which tokens exist. A store or lookup that cannot answer
 * is an {@link AuthenticationServiceException}, which the chain answers 503: not knowing is not refusing. Its cause is
 * logged here, since the entry point logs only the message.</p>
 *
 * <p>The token is marked used only once the authorization is built: a refused attempt with a token is not a use.</p>
 */
final class PersonalTokenAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(PersonalTokenAuthenticationProvider.class);

    private static final String NOT_VALID = "The personal token is not valid";

    private final PersonalTokens tokens;
    private final RunAs runAs;

    PersonalTokenAuthenticationProvider(PersonalTokens tokens, RunAs runAs) {
        this.tokens = tokens;
        this.runAs = runAs;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String token = ((BearerTokenAuthenticationToken) authentication).getToken();
        PersonalTokens.Holder holder;
        try {
            holder = tokens.holderOf(token).orElse(null);
        } catch (RuntimeException unavailable) {
            log.error("Cannot check a personal token; answering 503", unavailable);
            throw new AuthenticationServiceException("The personal token could not be checked", unavailable);
        }
        if (holder == null) {
            throw new InvalidBearerTokenException(NOT_VALID);
        }
        StockflowAuthenticationToken built;
        try {
            built = runAs.authenticationFor(holder.userId(), tokens.actor());
        } catch (RunAs.RefusedException refused) {
            if (refused.refusal() == RunAs.Refusal.UNAVAILABLE) {
                throw new AuthenticationServiceException("The personal token's user could not be resolved", refused);
            }
            throw new InvalidBearerTokenException(NOT_VALID);
        }
        try {
            tokens.used(holder.tokenId());
        } catch (RuntimeException notRecorded) {
            // When it was last used is for its owner's list; failing to note it is no reason to refuse the call.
            log.warn("Could not record the use of personal token {}: {}", holder.tokenId(), notRecorded.toString());
        }
        return built;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
