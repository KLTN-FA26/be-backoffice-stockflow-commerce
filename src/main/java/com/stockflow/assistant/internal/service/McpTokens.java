package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.McpToken;
import com.stockflow.assistant.internal.domain.McpTokenRepository;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PersonalTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The personal tokens of the assistant's MCP endpoint (design §7, ADR-0013): made, listed and revoked by their owner,
 * and checked for the API chain ({@link PersonalTokens}).
 *
 * <p><b>What a token is.</b> {@value #PREFIX} and 32 random bytes in URL-safe Base64, 256 bits that nobody guesses.
 * It is shown once, in the answer that makes it, and stored as its SHA-256: a copy of the table opens nothing, and a
 * token can be checked by one indexed lookup of its hash, with nothing to compare in variable time. A fast hash is
 * right here, unlike for a password: the token's own randomness is what a slow hash would make up for.</p>
 *
 * <p><b>What it opens.</b> The MCP endpoint and nothing else ({@link #paths}), with what its owner may read at each
 * call. Made, listed and revoked through the token endpoints, which take the sign-in token only: a personal token
 * cannot make another, so a client that leaked one cannot keep itself alive past its expiry or revocation.</p>
 *
 * <p><b>Bounded.</b> At most {@value #MAX_LIVE} live tokens per user and {@link #MAX_LIFETIME} each, which
 * {@code ck_mcp_token_lifetime} repeats: a forgotten token stops on its own. The owner's tokens are counted under a
 * lock of theirs, so two made at once do not both pass the limit.</p>
 *
 * <p><b>Made only just after signing in.</b> The controller refuses a sign-in token issued more than
 * {@link #RECENT_SIGN_IN} ago: a script running in the back office's page (an XSS, a bad dependency) can call the API
 * with the user's session, but cannot turn that session into a credential that outlives it without the user signing in
 * again first.</p>
 *
 * <p><b>Audited.</b> Making and revoking a token are GRANT and REVOKE entries of the audit trail, which are kept past
 * the usual retention as security-relevant: a credential that existed can be shown to have existed after its row is
 * gone. Never the token itself: the entry names the token's id.</p>
 *
 * <p><b>What stops a token</b>, besides its owner revoking it and its expiry: an administrator revoking every token of
 * a user ({@link #revokeAllOf}), locking or disabling the account, or withdrawing {@code assistant-mcp:CREATE} from the
 * role; each applies to the next call. A password change does not, as a personal access token elsewhere does not
 * (GitHub, GitLab): the owner revokes the tokens they no longer trust.</p>
 *
 * <p>Off with the MCP endpoint ({@link #ENABLED}): no bean, so the API chain accepts no personal token at all.</p>
 */
@Service
@ConditionalOnProperty(name = McpTokens.ENABLED, havingValue = "true")
public class McpTokens implements PersonalTokens {

    private static final Logger log = LoggerFactory.getLogger(McpTokens.class);

    /** The property that opens the MCP endpoint, its tokens with it. */
    public static final String ENABLED = "stockflow.assistant.mcp.enabled";

    /** Tells an MCP token apart from a JWT, and from any other secret a user might paste somewhere. */
    public static final String PREFIX = "sfmcp_";

    /** The only path a token is accepted on. */
    public static final String MCP_PATH = "/api/v1/assistant/mcp";

    /** Who acts for the user during a call: rows written meanwhile read "username via assistant-mcp". */
    static final String ACTOR = "assistant-mcp";

    static final Duration MAX_LIFETIME = Duration.ofDays(90);

    /** How recent the sign-in that makes a token must be. */
    public static final Duration RECENT_SIGN_IN = Duration.ofMinutes(15);
    static final int DEFAULT_DAYS = 30;
    static final int MAX_LIVE = 10;
    static final int NAME_MAX = 80;

    /** At most this many of a user's tokens are listed, newest first; the retention job removes the dead ones. */
    static final int LISTED = 50;

    private static final int RANDOM_BYTES = 32;
    private static final Pattern TOKEN = Pattern.compile("^" + PREFIX + "[A-Za-z0-9_-]{43}$");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private final McpTokenRepository repository;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnly;
    private final SecureRandom random = new SecureRandom();

    McpTokens(McpTokenRepository repository, Clock clock, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** A token just made: {@link #token} is shown this once and never again. */
    public record Created(McpToken view, String token) {
    }

    /**
     * Makes a token for {@code user}.
     *
     * @param name what they call it, 1 to {@value #NAME_MAX} characters once trimmed, no control characters
     * @param days how long it lives, 1 to 90; null for {@value #DEFAULT_DAYS}
     * @throws BusinessException {@code VALIDATION_FAILED} for a bad name or lifetime,
     *                           {@code ASSISTANT_MCP_TOKEN_LIMIT} when they already have {@value #MAX_LIVE} live
     */
    @Transactional
    @Auditable(action = AuditAction.GRANT, resourceType = "mcp_token", resourceId = "#result.view().id()")
    public Created create(CurrentUser user, String name, Integer days) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX || CONTROL.matcher(trimmed).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "name must be 1 to " + NAME_MAX + " characters, with no control characters");
        }
        int lifetime = days == null ? DEFAULT_DAYS : days;
        if (lifetime < 1 || lifetime > MAX_LIFETIME.toDays()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "expiresInDays must be between 1 and " + MAX_LIFETIME.toDays());
        }
        repository.lockOwner(user.userId());
        Instant now = clock.instant();
        if (repository.countLive(user.userId(), now) >= MAX_LIVE) {
            throw new BusinessException(ErrorCode.ASSISTANT_MCP_TOKEN_LIMIT, "You already have " + MAX_LIVE
                    + " live MCP tokens; revoke one you no longer use first");
        }
        String token = newToken();
        UUID id = Identifiers.newId();
        Instant expires = now.plus(Duration.ofDays(lifetime));
        String hint = token.substring(token.length() - 4);
        repository.insert(id, user.userId(), trimmed, hash(token), hint, now, expires);
        log.info("MCP token {} made by user {}, expires {}", id, user.userId(), expires);
        return new Created(new McpToken(id, trimmed, hint, now, expires, null, null), token);
    }

    /** The user's tokens, newest first: live, revoked and expired, until the retention job removes the dead. */
    @Transactional(readOnly = true)
    public List<McpToken> list(CurrentUser user) {
        return repository.ownedBy(user.userId(), LISTED);
    }

    /**
     * Revokes one of the user's tokens; revoking it again changes nothing.
     *
     * @throws NotFoundException when the user has no such token: another user's token is not theirs to know
     */
    @Transactional
    @Auditable(action = AuditAction.REVOKE, resourceType = "mcp_token", resourceId = "#id")
    public McpToken revoke(CurrentUser user, UUID id) {
        McpToken revoked = repository.revoke(id, user.userId(), clock.instant())
                .orElseThrow(() -> new NotFoundException("McpToken", id));
        log.info("MCP token {} revoked by user {}", id, user.userId());
        return revoked;
    }

    /**
     * Revokes every live token of {@code userId}, for an administrator acting on a lost laptop or a leaver's clients;
     * the user keeps their account and their sign-in. Returns how many were live.
     */
    @Transactional
    @Auditable(action = AuditAction.REVOKE, resourceType = "user", resourceId = "#userId")
    public int revokeAllOf(UUID userId) {
        int revoked = repository.revokeAllOf(userId, clock.instant());
        log.info("MCP tokens of user {} revoked by an administrator: {}", userId, revoked);
        return revoked;
    }

    /** Every token of {@code userId}, live ones first, for an administrator looking into an account. */
    @Transactional(readOnly = true)
    public List<McpToken> tokensOf(UUID userId) {
        return repository.ownedBy(userId, LISTED);
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public List<String> paths() {
        return List.of(MCP_PATH);
    }

    @Override
    public String actor() {
        return ACTOR;
    }

    /**
     * The live token and its owner, by one indexed read of its hash in a read-only transaction of its own: the API
     * chain asks before any transaction of the request's.
     */
    @Override
    public Optional<Holder> holderOf(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        String hash = hash(token);
        return readOnly.execute(status -> repository.live(hash, clock.instant())
                .map(live -> new Holder(live.owner(), live.tokenId())));
    }

    /**
     * Its own transaction: with auto-commit off, a write outside one would be rolled back when the connection went
     * back to the pool.
     */
    @Override
    public void used(UUID tokenId) {
        tx.executeWithoutResult(status -> repository.used(tokenId, clock.instant()));
    }

    private String newToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM has SHA-256", impossible);
        }
    }
}
