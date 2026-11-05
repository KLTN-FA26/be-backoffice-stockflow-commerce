package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.McpTokenRepository;
import com.stockflow.contracts.CredentialsReset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Revokes every live personal MCP token of an account whose password an administrator reset, or whose owner changed
 * it and signed out everywhere else ({@link CredentialsReset}, ADR-0013 as amended on 5/11/2026).
 *
 * <p><b>Why.</b> A reset says the old password may be known to someone else. Identity ends the sign-in sessions it
 * opened; a token minted in one of them would otherwise outlive them by up to 90 days. The forced-change gate refuses
 * a token only while the change is pending, so without this a token minted by whoever knew the old password would work
 * again the moment the real owner set a new one.</p>
 *
 * <p><b>Not conditional on MCP being on.</b> Tokens outlive a switch that is turned off and on again; a reset while it
 * was off must still count. After commit, retried from the event publication registry; revoking twice changes
 * nothing. The short gap between the reset's commit and this run is covered by the gate, which refuses the token while
 * the reset's forced change is pending.</p>
 */
@Component
public class PersonalTokenRevocationListener {

    private static final Logger log = LoggerFactory.getLogger(PersonalTokenRevocationListener.class);

    private final McpTokenRepository tokens;
    private final Clock clock;

    PersonalTokenRevocationListener(McpTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(CredentialsReset reset) {
        int revoked = tokens.revokeAllOf(reset.userId(), clock.instant());
        if (revoked > 0) {
            log.info("{} MCP token(s) of user {} revoked: {}", revoked, reset.userId(), reset.cause());
        }
    }
}
