package com.stockflow.order.internal.service.signing;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.notification.api.ContractSignOtpMail;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.order.internal.entity.ContractSignOtpJpaEntity;
import com.stockflow.order.internal.repository.ContractSignOtpJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one-time code a customer enters, besides their name and consent, to sign a contract on the portal: the proof
 * that the person at the keyboard can read the mailbox the contract was sent to.
 *
 * <p><b>Rules.</b> Six digits from {@link SecureRandom}; valid {@code stockflow.order.contract-signing.otp-lifetime}
 * (10 minutes); only its HMAC-SHA256 is stored, keyed by a secret and bound to the contract, revision and customer, so a
 * leaked table does not give codes and a code of one contract is no use on another; one live code per contract (asking
 * again supersedes the old one); the next one no sooner than {@code otp-resend-after} (60 s) and at most
 * {@code otp-per-hour} (5) an hour for one contract; five wrong tries spend a code; and
 * {@value #MAX_WRONG_TRIES} wrong tries in all, over every code of one revision, lock the revision
 * ({@code CONTRACT_OTP_LOCKED}): no code is issued or checked until Sales extends the signing deadline
 * ({@link #forgiveWrongTries}) or the contract is redrawn, whose new revision starts from nothing. The hourly limit
 * alone would let a guesser try 25 codes an hour, every hour, for as long as the contract waits; a six-digit code
 * guessed at that pace is a matter of weeks, not of luck.</p>
 *
 * <p><b>Transactions.</b> {@link #issue} joins the caller's, and sends the mail inside it: a mail that fails rolls the
 * new code back, so a code nobody received is neither live nor counted against the hourly limit. {@link #verify} runs
 * in a transaction of its own, always: a wrong try must stay counted even when the caller's transaction rolls back
 * because it was refused, or a customer could try again and again by making the request fail. A consumed code stays
 * consumed even if what the caller did next fails; the customer asks for another.</p>
 *
 * <p><b>Secrets.</b> The code is in the mail and the caller's response never has it; it is not logged, not in an
 * event (the registry would keep it), not in the audit trail.</p>
 */
@Service
public class ContractSignOtps {

    private static final Logger log = LoggerFactory.getLogger(ContractSignOtps.class);
    /** Wrong tries a code allows (the CHECK of {@code ck_contract_sign_otp_attempts}). */
    static final int MAX_ATTEMPTS = 5;
    /** Wrong tries over every code of one revision before it is locked, until a person forgives them. */
    public static final int MAX_WRONG_TRIES = 20;
    private static final Duration HOUR = Duration.ofHours(1);
    private static final int IP_MAX = 45;

    /**
     * What the portal shows after asking for a code.
     *
     * @param sentTo      the address, masked ({@link #masked})
     * @param resendAfter the earliest a new code may be asked for
     */
    public record Issued(String sentTo, Instant expiresAt, Instant resendAfter) {
    }

    private enum Outcome { OK, INVALID, EXPIRED, LOCKED }

    private record Checked(Outcome outcome, int attemptsLeft) {
    }

    private final ContractSignOtpJpaRepository otps;
    private final NotificationService notifications;
    private final ContractSigningProperties properties;
    private final Clock clock;
    private final TransactionTemplate own;
    private final byte[] hashKey;
    private final SecureRandom random = new SecureRandom();

    ContractSignOtps(ContractSignOtpJpaRepository otps, NotificationService notifications,
                     ContractSigningProperties properties, SigningSecrets secrets, Clock clock,
                     PlatformTransactionManager transactions) {
        this.otps = otps;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.hashKey = secrets.derive("otp-hash");
    }

    /**
     * Makes a code and mails it to {@code email}.
     *
     * @param contractNumber named in the mail
     * @param ip             the caller's address, kept with the code; may be null
     * @throws BusinessException 429 {@code CONTRACT_OTP_LOCKED} when the revision's wrong tries reached
     *         {@value #MAX_WRONG_TRIES}; 429 {@code CONTRACT_OTP_TOO_MANY} ({@code details.retryAfterSeconds}) when asked
     *         for again too soon or too often; 502 {@code EXTERNAL_SERVICE_ERROR} when the mail could not be sent (nothing
     *         was kept)
     */
    @Transactional
    public Issued issue(UUID contractId, String contractNumber, int revision, UUID customerUserId, String email,
                        String ip) {
        otps.lockContract("contract-sign-otp:" + contractId);
        if (otps.wrongTries(contractId, revision) >= MAX_WRONG_TRIES) {
            throw locked();
        }
        Instant now = clock.instant();
        var last = otps.findFirstByContractIdOrderByCreatedAtDesc(contractId);
        if (last.isPresent()) {
            Instant next = last.get().getCreatedAt().plus(properties.otpResendAfter());
            if (next.isAfter(now)) {
                throw tooMany(next, now);
            }
        }
        List<ContractSignOtpJpaEntity> lastHour = otps.findByContractIdAndCreatedAtAfterOrderByCreatedAtAsc(contractId,
                now.minus(HOUR));
        if (lastHour.size() >= properties.otpPerHour()) {
            throw tooMany(lastHour.get(lastHour.size() - properties.otpPerHour()).getCreatedAt().plus(HOUR), now);
        }
        otps.findLiveForUpdate(contractId).ifPresent(old -> {
            old.supersede(now);
            otps.saveAndFlush(old);
        });
        String code = "%06d".formatted(random.nextInt(1_000_000));
        Instant expiresAt = now.plus(properties.otpLifetime());
        otps.save(new ContractSignOtpJpaEntity(Identifiers.newId(), contractId, revision, customerUserId, email,
                hash(contractId, revision, customerUserId, code), expiresAt, truncated(ip)));
        try {
            notifications.sendContractSignOtp(new ContractSignOtpMail(email, contractNumber, code, expiresAt));
        } catch (RuntimeException failed) {
            // The exception, never the mail: its text holds the code. The rollback drops the row just saved.
            log.warn("A contract signing code could not be e-mailed (contract {}): {}", contractId,
                    failed.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The confirmation code could not be e-mailed; try again in a moment");
        }
        return new Issued(masked(email), expiresAt, now.plus(properties.otpResendAfter()));
    }

    /**
     * Checks the code and, when it is right, uses it up.
     *
     * @throws BusinessException 422 {@code CONTRACT_OTP_INVALID} ({@code details.attemptsLeft}: 0 once the code is
     *         spent, or the revision locked) for a wrong code; 422 {@code CONTRACT_OTP_EXPIRED} when there is no
     *         live code for this contract, revision and customer, it timed out, or its tries are used up; 429
     *         {@code CONTRACT_OTP_LOCKED} once the revision's wrong tries reached {@value #MAX_WRONG_TRIES}, before
     *         the code is even compared
     */
    public void verify(UUID contractId, int revision, UUID customerUserId, String code) {
        Checked checked = own.execute(status -> check(contractId, revision, customerUserId, code));
        if (checked != null && checked.outcome() == Outcome.LOCKED) {
            throw locked();
        }
        if (checked == null || checked.outcome() == Outcome.EXPIRED) {
            throw new BusinessException(ErrorCode.CONTRACT_OTP_EXPIRED);
        }
        if (checked.outcome() == Outcome.INVALID) {
            throw new BusinessException(ErrorCode.CONTRACT_OTP_INVALID, ErrorCode.CONTRACT_OTP_INVALID.defaultMessage(),
                    List.of(), Map.of("attemptsLeft", checked.attemptsLeft()));
        }
    }

    private Checked check(UUID contractId, int revision, UUID customerUserId, String code) {
        Instant now = clock.instant();
        var live = otps.findLiveForUpdate(contractId);
        // Read under the live code's row lock: two tries at once are counted one after the other.
        long wrong = otps.wrongTries(contractId, revision);
        if (wrong >= MAX_WRONG_TRIES) {
            return new Checked(Outcome.LOCKED, 0);
        }
        // Another customer's or another revision's code is not this request's: no try is counted against it.
        if (live.isEmpty() || live.get().getRevision() != revision
                || !live.get().getCustomerUserId().equals(customerUserId)
                || !live.get().getExpiresAt().isAfter(now) || live.get().getAttempts() >= MAX_ATTEMPTS) {
            return new Checked(Outcome.EXPIRED, 0);
        }
        ContractSignOtpJpaEntity otp = live.get();
        byte[] given = hash(contractId, revision, customerUserId, code == null ? "" : code.strip())
                .getBytes(StandardCharsets.US_ASCII);
        if (MessageDigest.isEqual(given, otp.getCodeHash().getBytes(StandardCharsets.US_ASCII))) {
            otp.consume(now);
            otps.save(otp);
            return new Checked(Outcome.OK, MAX_ATTEMPTS - otp.getAttempts());
        }
        otp.recordWrongAttempt();
        otps.save(otp);
        return new Checked(Outcome.INVALID, (int) Math.min(MAX_ATTEMPTS - otp.getAttempts(),
                MAX_WRONG_TRIES - (wrong + 1)));
    }

    /**
     * Forgives the wrong tries counted against one revision, in the caller's transaction: Sales extended the signing
     * deadline, which is a person deciding the customer may try again. The live code is superseded with them.
     */
    @Transactional
    public void forgiveWrongTries(UUID contractId, int revision, Instant at) {
        otps.lockContract("contract-sign-otp:" + contractId);
        int forgiven = otps.forgive(contractId, revision, at);
        if (forgiven > 0) {
            log.info("Contract {} revision {}: {} signing code(s) forgiven with the new deadline", contractId,
                    revision, forgiven);
        }
    }

    /** {@code m***@domain}: enough for a customer to recognise the mailbox, not for anyone else to learn it. */
    public static String masked(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static BusinessException locked() {
        return new BusinessException(ErrorCode.CONTRACT_OTP_LOCKED, ("%d wrong confirmation codes were entered for "
                + "this revision of the contract").formatted(MAX_WRONG_TRIES), List.of(),
                Map.of("maxWrongTries", MAX_WRONG_TRIES));
    }

    private BusinessException tooMany(Instant until, Instant now) {
        long seconds = Math.max(1, Duration.between(now, until).toSeconds() + 1);
        return new BusinessException(ErrorCode.CONTRACT_OTP_TOO_MANY, ErrorCode.CONTRACT_OTP_TOO_MANY.defaultMessage(),
                List.of(), Map.of("retryAfterSeconds", seconds));
    }

    private String hash(UUID contractId, int revision, UUID customerUserId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    (contractId + "|" + revision + "|" + customerUserId + "|" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    private static String truncated(String ip) {
        return ip == null || ip.isBlank() ? null : ip.length() > IP_MAX ? ip.substring(0, IP_MAX) : ip;
    }
}
