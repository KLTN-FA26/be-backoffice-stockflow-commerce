package com.stockflow.customer.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.CustomerStatus;
import com.stockflow.identity.api.InviteCustomerAccountCommand;
import com.stockflow.identity.api.PortalAccountService;
import com.stockflow.identity.api.PortalInvitationLink;
import com.stockflow.identity.api.PortalInvitationStatus;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.notification.api.PortalInvitationMail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * B2B onboarding (SCRUM-450, kltn-docs 18 §4.1): Sales creates the business customer, the system
 * invites its contact, the contact activates the portal account. No one signs up on their own.
 *
 * <h2>Write, commit, then mail</h2>
 *
 * <p>{@link #create} and {@link #resendInvitation} commit the customer and its invitation first and
 * only then send the e-mail, so a link never reaches an inbox for rows that rolled back. That is why
 * they run their own transaction instead of carrying {@code @Transactional}: the e-mail is the step
 * after the commit, not an afterthought of it. The e-mail is not an event either: it carries the only
 * copy of the secret, and an event is persisted until a listener processes it. If the mail server
 * fails, the customer still exists, the response says {@link InvitationDelivery#FAILED}, and Sales
 * resends - which issues a new secret and kills the unsent one.</p>
 *
 * <h2>Lock order</h2>
 *
 * <p>Resend and activation both lock the customer row first and the account second, so the two
 * cannot deadlock on one customer, and a resend racing an activation is decided by whichever takes
 * the customer row first.</p>
 */
@Service
public class CustomerOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(CustomerOnboardingService.class);

    private final CustomerRepository customers;
    private final PortalAccountService accounts;
    private final NotificationService notifications;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String activationUrl;

    CustomerOnboardingService(CustomerRepository customers, PortalAccountService accounts,
                              NotificationService notifications, PlatformTransactionManager transactionManager,
                              Clock clock,
                              @Value("${stockflow.customer.portal-activation-url:}") String activationUrl) {
        this.customers = customers;
        this.accounts = accounts;
        this.notifications = notifications;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.activationUrl = validated(activationUrl);
    }

    /** What Sales enters. {@code companyName} names the customer; {@code contactName} the person invited. */
    public record CreateBusinessCustomer(String companyName, String contactName, String email, String phone) {
    }

    public enum InvitationDelivery { SENT, FAILED }

    public record Invited(CustomerSummary customer, PortalInvitationStatus invitation, InvitationDelivery delivery) {
    }

    public enum InvitationState { PENDING, EXPIRED, ACTIVATED, NO_ACCOUNT }

    public record InvitationView(UUID customerId, InvitationState state, Instant issuedAt, Instant expiresAt) {
    }

    /**
     * @throws BusinessException {@code CUSTOMER_EMAIL_ALREADY_EXISTS} for a customer with this e-mail;
     *         {@code USER_EMAIL_ALREADY_EXISTS} for any account - staff or customer - with it;
     *         {@code VALIDATION_FAILED}; {@code PORTAL_INVITATION_UNAVAILABLE} when no activation
     *         address is configured, before anything is written
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "customer", resourceId = "#result?.customer()?.customerId()")
    public Invited create(CreateBusinessCustomer command) {
        requireConfigured();
        String email = command.email().trim().toLowerCase(Locale.ROOT);
        record Created(CustomerSummary customer, PortalInvitationLink link) {
        }
        Created created = writeRejectingDuplicates(() -> transactions.execute(status -> {
            if (customers.findByEmail(email).isPresent()) {
                throw new BusinessException(ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS);
            }
            PortalInvitationLink link = accounts.invite(new InviteCustomerAccountCommand(email,
                    command.contactName().trim()));
            Customer customer;
            try {
                customer = Customer.onboard(Identifiers.newId(), link.userId(), command.companyName(), email,
                        command.phone());
            } catch (IllegalArgumentException invalid) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
            }
            return new Created(CustomerServiceImpl.toSummary(customers.save(customer)), link);
        }));
        return new Invited(created.customer(), created.link().status(),
                deliver(created.link(), created.customer().fullName()));
    }

    /**
     * Sends a new invitation; the previous link stops working when this commits.
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND}; {@code CONFLICT} for a customer without a
     *         portal account or a BLOCKED one; {@code PORTAL_ACCOUNT_ALREADY_ACTIVE};
     *         {@code RATE_LIMITED} within the resend cooldown; {@code PORTAL_INVITATION_UNAVAILABLE}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-portal-invitation", resourceId = "#customerId")
    public Invited resendInvitation(UUID customerId) {
        requireConfigured();
        record Reissued(CustomerSummary customer, PortalInvitationLink link) {
        }
        Reissued reissued = inNewTransaction(() -> transactions.execute(status -> {
            Customer customer = customers.findForUpdate(customerId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
            if (customer.userId() == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "This customer has no portal account to invite");
            }
            if (customer.status() == CustomerStatus.BLOCKED) {
                throw new BusinessException(ErrorCode.CONFLICT, "A blocked customer cannot be invited");
            }
            return new Reissued(CustomerServiceImpl.toSummary(customer), accounts.reissue(customer.userId()));
        }));
        return new Invited(reissued.customer(), reissued.link().status(),
                deliver(reissued.link(), reissued.customer().fullName()));
    }

    /**
     * The contact redeems the invitation and chooses a password; the customer becomes ACTIVE in the
     * same transaction, or neither happens.
     *
     * @return the activated customer's sign-in e-mail
     * @throws BusinessException {@code PORTAL_INVITATION_INVALID} for every unusable link alike,
     *         including one whose customer is BLOCKED; {@code VALIDATION_FAILED} for a weak password
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "customer-portal-account", resourceId = "#result")
    public String activate(String token, String password) {
        return inNewTransaction(() -> transactions.execute(status -> {
            UUID userId = accounts.accountNamedBy(token)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID));
            Customer customer = customers.findByUserIdForUpdate(userId)
                    .filter(found -> found.status() != CustomerStatus.BLOCKED)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID));
            accounts.activate(token, password);
            customer.activatePortal();
            return customers.save(customer).email();
        }));
    }

    @Transactional(readOnly = true)
    public InvitationView invitation(UUID customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        if (customer.userId() == null) {
            return new InvitationView(customerId, InvitationState.NO_ACCOUNT, null, null);
        }
        return accounts.invitation(customer.userId())
                .map(open -> new InvitationView(customerId,
                        open.expiredAt(clock.instant()) ? InvitationState.EXPIRED : InvitationState.PENDING,
                        open.issuedAt(), open.expiresAt()))
                .orElseGet(() -> new InvitationView(customerId, InvitationState.ACTIVATED, null, null));
    }

    /**
     * Runs after the commit. Logs who and when, never the link. A failure is reported, not thrown:
     * the customer exists either way, and the caller must learn that the e-mail did not go out.
     */
    private InvitationDelivery deliver(PortalInvitationLink link, String companyName) {
        String url = activationUrl + "#token=" + link.token();
        try {
            notifications.sendPortalInvitation(new PortalInvitationMail(
                    link.email(), link.fullName(), companyName, url, link.expiresAt()));
            log.info("Portal invitation e-mailed to account {} (expires {})", link.userId(), link.expiresAt());
            return InvitationDelivery.SENT;
        } catch (RuntimeException failed) {
            log.warn("Portal invitation e-mail to account {} failed: {}", link.userId(),
                    failed.getClass().getSimpleName());
            return InvitationDelivery.FAILED;
        }
    }

    /**
     * The e-mail must follow the commit, so these methods must not join a caller's transaction: if
     * they did, "committed" would mean nothing until the caller committed.
     */
    private static <T> T inNewTransaction(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Onboarding runs its own transaction; call it outside one");
        }
        return work.get();
    }

    /** Two Sales screens creating the same customer at once: the unique indexes decide, as a 409. */
    private static <T> T writeRejectingDuplicates(Supplier<T> work) {
        try {
            return inNewTransaction(work);
        } catch (DataIntegrityViolationException race) {
            if (isUniqueViolation(race)) throw new BusinessException(ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS);
            throw race;
        }
    }

    private static boolean isUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private void requireConfigured() {
        if (activationUrl == null) {
            throw new BusinessException(ErrorCode.PORTAL_INVITATION_UNAVAILABLE,
                    "stockflow.customer.portal-activation-url is not configured");
        }
    }

    /**
     * Blank leaves invitations switched off (503) rather than failing startup, so a deployment that
     * has not configured the portal yet still starts. A present value must be an absolute http(s)
     * address without a fragment: the token is appended as one, which browsers never send to a
     * server or put in a Referer header.
     */
    private static String validated(String url) {
        if (url == null || url.isBlank()) return null;
        URI uri = URI.create(url.trim());
        if (!uri.isAbsolute() || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException(
                    "stockflow.customer.portal-activation-url must be an absolute http(s) URL without a fragment");
        }
        return uri.toString();
    }
}
