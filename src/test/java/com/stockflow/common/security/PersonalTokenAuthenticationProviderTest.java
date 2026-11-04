package com.stockflow.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A personal token authenticated: the user's authorization as {@link RunAs} builds it, 401 for anything that is not a
 * live token of an account that may sign in, and 503 when that cannot be known.
 */
class PersonalTokenAuthenticationProviderTest {

    private static final String TOKEN = "sfmcp_" + "A".repeat(43);

    private final PersonalTokens tokens = mock(PersonalTokens.class);
    private final RunAs runAs = mock(RunAs.class);
    private final PersonalTokenAuthenticationProvider provider = new PersonalTokenAuthenticationProvider(tokens, runAs);

    PersonalTokenAuthenticationProviderTest() {
        when(tokens.actor()).thenReturn("assistant-mcp");
        when(tokens.paths()).thenReturn(List.of("/api/v1/assistant/mcp"));
    }

    private static PersonalTokens.Holder holder(UUID user) {
        return new PersonalTokens.Holder(user, UUID.randomUUID());
    }

    @Test
    @DisplayName("a live token is its holder's authorization, built by RunAs and marked with the token's actor; only "
            + "then is it marked used")
    void aLiveTokenIsItsHoldersAuthorization() {
        UUID user = UUID.randomUUID();
        PersonalTokens.Holder holder = holder(user);
        StockflowAuthenticationToken built = mock(StockflowAuthenticationToken.class);
        when(tokens.holderOf(TOKEN)).thenReturn(Optional.of(holder));
        when(runAs.authenticationFor(user, "assistant-mcp")).thenReturn(built);

        Authentication authenticated = provider.authenticate(new BearerTokenAuthenticationToken(TOKEN));

        assertThat(authenticated).isSameAs(built);
        verify(tokens).used(holder.tokenId());
    }

    @Test
    @DisplayName("failing to note the use refuses nothing")
    void aUseNotRecordedIsNoRefusal() {
        UUID user = UUID.randomUUID();
        PersonalTokens.Holder holder = holder(user);
        StockflowAuthenticationToken built = mock(StockflowAuthenticationToken.class);
        when(tokens.holderOf(TOKEN)).thenReturn(Optional.of(holder));
        when(runAs.authenticationFor(user, "assistant-mcp")).thenReturn(built);
        doThrow(new IllegalStateException("database down")).when(tokens).used(any());

        assertThat(provider.authenticate(new BearerTokenAuthenticationToken(TOKEN))).isSameAs(built);
    }

    @Test
    @DisplayName("a token with no live holder is 401, and nobody's authorization is built")
    void aDeadTokenIsRefused() {
        when(tokens.holderOf(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.authenticate(new BearerTokenAuthenticationToken(TOKEN)))
                .isInstanceOf(InvalidBearerTokenException.class);
        verifyNoInteractions(runAs);
    }

    @Test
    @DisplayName("an account that may not sign in any more is 401 with the same words as a dead token")
    void aLockedAccountIsRefused() {
        UUID holder = UUID.randomUUID();
        when(tokens.holderOf(TOKEN)).thenReturn(Optional.of(holder(holder)));
        when(runAs.authenticationFor(holder, "assistant-mcp"))
                .thenThrow(new RunAs.RefusedException(RunAs.Refusal.INACTIVE, holder, null));
        when(tokens.holderOf("sfmcp_" + "B".repeat(43))).thenReturn(Optional.empty());

        Throwable locked = org.assertj.core.api.Assertions.catchThrowable(
                () -> provider.authenticate(new BearerTokenAuthenticationToken(TOKEN)));
        Throwable dead = org.assertj.core.api.Assertions.catchThrowable(
                () -> provider.authenticate(new BearerTokenAuthenticationToken("sfmcp_" + "B".repeat(43))));

        assertThat(locked).isInstanceOf(InvalidBearerTokenException.class);
        assertThat(locked.getMessage()).isEqualTo(dead.getMessage());
        verify(tokens, never()).used(any());
    }

    @Test
    @DisplayName("a store or lookup that cannot answer is a service error, which the chain answers 503, not 401")
    void notKnowingIsNotRefusing() {
        UUID holder = UUID.randomUUID();
        when(tokens.holderOf(TOKEN)).thenThrow(new IllegalStateException("database down"));
        assertThatThrownBy(() -> provider.authenticate(new BearerTokenAuthenticationToken(TOKEN)))
                .isInstanceOf(AuthenticationServiceException.class);

        PersonalTokens other = mock(PersonalTokens.class);
        when(other.actor()).thenReturn("assistant-mcp");
        when(other.holderOf(TOKEN)).thenReturn(Optional.of(holder(holder)));
        when(runAs.authenticationFor(holder, "assistant-mcp"))
                .thenThrow(new RunAs.RefusedException(RunAs.Refusal.UNAVAILABLE, holder, null));
        assertThatThrownBy(() -> new PersonalTokenAuthenticationProvider(other, runAs)
                .authenticate(new BearerTokenAuthenticationToken(TOKEN)))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    @DisplayName("it authenticates bearer tokens only")
    void bearerTokensOnly() {
        assertThat(provider.supports(BearerTokenAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .class)).isFalse();
    }
}
