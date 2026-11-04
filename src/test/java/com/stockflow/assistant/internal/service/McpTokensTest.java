package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.McpToken;
import com.stockflow.assistant.internal.domain.McpTokenRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The MCP endpoint's personal tokens without a database: what a token looks like and what is kept of it, the limits,
 * and which texts find an owner.
 */
class McpTokensTest {

    private static final Instant NOW = Instant.parse("2026-11-05T02:00:00Z");
    private static final CurrentUser PLANNER = new CurrentUser(UUID.randomUUID(), "planner",
            Set.of(Role.INVENTORY_PLANNER), Set.of(), DataScope.ALL, Set.of());

    private final McpTokenRepository repository = mock(McpTokenRepository.class);
    private final McpTokens tokens = new McpTokens(repository, Clock.fixed(NOW, ZoneOffset.UTC),
            mock(PlatformTransactionManager.class));

    @Test
    @DisplayName("a token is sfmcp_ and 43 URL-safe characters, kept as its SHA-256 with its last four as the hint, "
            + "for 30 days by default, after the owner's lock and count")
    void aTokenIsShownOnceAndKeptAsAHash() {
        McpTokens.Created created = tokens.create(PLANNER, "  Claude Code on my laptop ", null);

        assertThat(created.token()).matches("^sfmcp_[A-Za-z0-9_-]{43}$");
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(repository);
        order.verify(repository).lockOwner(PLANNER.userId());
        order.verify(repository).countLive(PLANNER.userId(), NOW);
        order.verify(repository).insert(eq(created.view().id()), eq(PLANNER.userId()), eq("Claude Code on my laptop"),
                hash.capture(), eq(created.token().substring(created.token().length() - 4)), eq(NOW),
                eq(NOW.plus(Duration.ofDays(30))));
        assertThat(hash.getValue()).matches("^[0-9a-f]{64}$").isEqualTo(McpTokens.hash(created.token()))
                .doesNotContain(created.token().substring(6));
        assertThat(created.view().live(NOW)).isTrue();
        assertThat(created.view().hint()).isEqualTo(created.token().substring(created.token().length() - 4));
    }

    @Test
    @DisplayName("no two tokens are alike")
    void tokensAreRandom() {
        Set<String> made = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            made.add(tokens.create(PLANNER, "client " + i, 1).token());
        }
        assertThat(made).hasSize(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "tab\there", "new\nline"})
    @DisplayName("a blank name, or one with control characters, is refused and nothing is made")
    void aBadNameIsRefused(String name) {
        assertThatThrownBy(() -> tokens.create(PLANNER, name, 30))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a lifetime outside 1 to 90 days is refused; 90 is the longest")
    void theLifetimeIsBounded() {
        assertThatThrownBy(() -> tokens.create(PLANNER, "x", 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tokens.create(PLANNER, "x", 91)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tokens.create(PLANNER, "x".repeat(81), 30)).isInstanceOf(BusinessException.class);

        assertThat(tokens.create(PLANNER, "x", 90).view().expiresAt()).isEqualTo(NOW.plus(McpTokens.MAX_LIFETIME));
    }

    @Test
    @DisplayName("with ten live tokens an eleventh is refused, 409 ASSISTANT_MCP_TOKEN_LIMIT")
    void atMostTenLive() {
        when(repository.countLive(PLANNER.userId(), NOW)).thenReturn(McpTokens.MAX_LIVE);

        assertThatThrownBy(() -> tokens.create(PLANNER, "one more", 30))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MCP_TOKEN_LIMIT));
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a live token finds its owner, read only; its use is recorded apart; a text that is not a token is "
            + "not even looked up")
    void holderOf() {
        String token = "sfmcp_" + "A".repeat(43);
        UUID tokenId = UUID.randomUUID();
        when(repository.live(McpTokens.hash(token), NOW))
                .thenReturn(Optional.of(new McpTokenRepository.Holder(tokenId, PLANNER.userId())));

        assertThat(tokens.holderOf(token)).contains(new com.stockflow.common.security.PersonalTokens.Holder(
                PLANNER.userId(), tokenId));
        verify(repository, never()).used(any(), any());
        tokens.used(tokenId);
        verify(repository).used(tokenId, NOW);

        McpTokensTest fresh = new McpTokensTest();
        for (String notOne : new String[] {null, "", "sfmcp_short", "sfmcp_" + "A".repeat(44),
                "SFMCP_" + "A".repeat(43), "eyJhbGciOiJSUzI1NiJ9.e30.sig", "sfmcp_" + "A".repeat(42) + "="}) {
            assertThat(fresh.tokens.holderOf(notOne)).as("%s", notOne).isEmpty();
        }
        verifyNoInteractions(fresh.repository);
    }

    @Test
    @DisplayName("a token that is not live, or not known, has no owner and is not marked used")
    void aDeadTokenHasNoOwner() {
        when(repository.live(anyString(), any())).thenReturn(Optional.empty());

        assertThat(tokens.holderOf("sfmcp_" + "B".repeat(43))).isEmpty();
        verify(repository, never()).used(any(), any());
    }

    @Test
    @DisplayName("revoking another user's token, or one that does not exist, is not found")
    void revokeIsTheOwnersOnly() {
        UUID someone = UUID.randomUUID();
        when(repository.revoke(eq(someone), eq(PLANNER.userId()), eq(NOW))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokens.revoke(PLANNER, someone)).isInstanceOf(NotFoundException.class);

        UUID mine = UUID.randomUUID();
        McpToken revoked = new McpToken(mine, "x", "abcd", NOW.minusSeconds(60), NOW.plusSeconds(60), null, NOW);
        when(repository.revoke(mine, PLANNER.userId(), NOW)).thenReturn(Optional.of(revoked));
        assertThat(tokens.revoke(PLANNER, mine).live(NOW)).isFalse();
    }

    @Test
    @DisplayName("tokens are accepted on the MCP endpoint only, and act as assistant-mcp")
    void acceptedOnTheMcpEndpointOnly() {
        assertThat(tokens.paths()).containsExactly("/api/v1/assistant/mcp");
        assertThat(tokens.prefix()).isEqualTo("sfmcp_");
        assertThat(tokens.actor()).isEqualTo("assistant-mcp");
    }
}
