package com.stockflow.customer.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.CustomerStatus;
import com.stockflow.customer.internal.domain.CustomerStatusChange;
import com.stockflow.customer.internal.domain.CustomerStatusHistory;
import com.stockflow.customer.internal.domain.Lead;
import com.stockflow.customer.internal.domain.TaxCode;
import com.stockflow.customer.internal.domain.TaxCodeExemptions;
import com.stockflow.identity.api.InviteCustomerAccountCommand;
import com.stockflow.identity.api.PortalAccountService;
import com.stockflow.identity.api.PortalInvitationLink;
import com.stockflow.identity.api.PortalInvitationStatus;
import com.stockflow.identity.api.SalesStaff;
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
 * copy of the secret, and an event is persisted until a listener processes it. The outcome is stored
 * on the invitation (SENT / FAILED) and returned; a failed e-mail is recovered by a resend, which
 * issues a new secret and revokes the unsent one.</p>
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
    private final CustomerStatusHistory history;
    private final SalesOwnershipService ownership;
    private final LeadService leads;
    private final TaxCodeExemptions exemptions;
    private final SalesStaff salesStaff;
    private final PortalAccountService accounts;
    private final NotificationService notifications;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String activationUrl;

    CustomerOnboardingService(CustomerRepository customers, CustomerStatusHistory history,
                              SalesOwnershipService ownership, LeadService leads, TaxCodeExemptions exemptions,
                              SalesStaff salesStaff, PortalAccountService accounts,
                              NotificationService notifications, PlatformTransactionManager transactionManager,
                              Clock clock,
                              @Value("${stockflow.customer.portal-activation-url:}") String activationUrl) {
        this.customers = customers;
        this.history = history;
        this.ownership = ownership;
        this.leads = leads;
        this.exemptions = exemptions;
        this.salesStaff = salesStaff;
        this.accounts = accounts;
        this.notifications = notifications;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.activationUrl = validated(activationUrl);
    }

    /**
     * What Sales enters. {@code companyName} names the customer; {@code taxCode} identifies it (BR-01) and is required
     * (sales-owner plan BR-S14, D4) unless a Sales Manager exempts the customer ({@code taxCodeExemption}, with
     * {@code taxCodeExemptionReason}); {@code contactName} is the person invited; {@code actorUserId} is the Sales
     * account, recorded on the invitation and in the status history.
     *
     * <p>{@code salesOwnerId} (sales-owner plan BR-S02): null for the default - the creating salesperson owns the
     * customer, a Sales Manager (a holder of {@code customer-sales-owners:UPDATE}) leaves it unassigned - or the
     * salesperson a manager chose. {@code leadId} (plan 10.7): the lead the customer is made from; its salesperson
     * is the owner (LEAD_CONVERTED) and the lead becomes CONVERTED in the same transaction.</p>
     */
    public record CreateBusinessCustomer(String companyName, String taxCode, String contactName, String email,
                                         String phone, UUID actorUserId, UUID salesOwnerId, boolean taxCodeExemption,
                                         String taxCodeExemptionReason, UUID leadId) {

        public CreateBusinessCustomer(String companyName, String taxCode, String contactName, String email,
                                      String phone, UUID actorUserId) {
            this(companyName, taxCode, contactName, email, phone, actorUserId, null);
        }

        public CreateBusinessCustomer(String companyName, String taxCode, String contactName, String email,
                                      String phone, UUID actorUserId, UUID salesOwnerId) {
            this(companyName, taxCode, contactName, email, phone, actorUserId, salesOwnerId, false, null, null);
        }
    }

    public enum InvitationDelivery { SENT, FAILED }

    public record Invited(CustomerSummary customer, PortalInvitationStatus invitation, InvitationDelivery delivery) {
    }

    /**
     * {@code UNAVAILABLE}: the customer is blocked or closed - an open link no longer activates and a resend is refused
     * (audit of #109: it used to read PENDING).
     */
    public enum InvitationState { PENDING, EXPIRED, ACTIVATED, NO_ACCOUNT, UNAVAILABLE }

    /** {@code delivery} is the recorded outcome of the open invitation's e-mail, null if none. */
    public record InvitationView(UUID customerId, InvitationState state, Instant issuedAt, Instant expiresAt,
                                 String delivery) {
    }

    /**
     * @throws BusinessException {@code VALIDATION_FAILED} (malformed tax code when one is given, or phone; an exemption
     *         with a tax code or without a reason; a lead with another owner named); {@code CUSTOMER_TAX_CODE_REQUIRED}
     *         without a tax code or an exemption; {@code FORBIDDEN} for an exemption asked by someone who is not a
     *         manager; the lead's refusals ({@code LEAD_NOT_FOUND}, {@code LEAD_ALREADY_HANDLED},
     *         {@code LEAD_NOT_ASSIGNED}, {@code FORBIDDEN} for another salesperson's lead);
     *         {@code CUSTOMER_TAX_CODE_ALREADY_EXISTS} for a pending or active customer with this tax
     *         code; {@code CUSTOMER_EMAIL_ALREADY_EXISTS}; {@code USER_EMAIL_ALREADY_EXISTS} for any
     *         account - staff or customer - with this e-mail; {@code PORTAL_INVITATION_UNAVAILABLE} when
     *         no activation address is configured, before anything is written; {@code FORBIDDEN} for a
     *         salesperson naming another owner; {@code SALES_OWNER_NOT_ASSIGNABLE} for an owner who is not an
     *         active salesperson
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "customer", resourceId = "#result?.customer()?.customerId()")
    public Invited create(CreateBusinessCustomer command) {
        requireConfigured();
        String email = command.email().trim().toLowerCase(Locale.ROOT);
        String taxCode;
        try {
            taxCode = command.taxCode() == null || command.taxCode().isBlank()
                    ? null : TaxCode.normalise(command.taxCode());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
        String exemptionReason = exemption(command, taxCode);
        record Created(CustomerSummary customer, PortalInvitationLink link) {
        }
        Created created = writeRejectingDuplicates(() -> transactions.execute(status -> {
            if (taxCode != null && customers.isTaxCodeTaken(taxCode, null)) {
                throw new BusinessException(ErrorCode.CUSTOMER_TAX_CODE_ALREADY_EXISTS);
            }
            if (customers.findByEmail(email).isPresent()) {
                throw new BusinessException(ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS);
            }
            Lead lead = command.leadId() == null ? null : leads.lockForConversion(command.leadId(),
                    command.actorUserId());
            if (lead != null && command.salesOwnerId() != null && !command.salesOwnerId().equals(lead.assignedTo())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "A customer made from a lead is looked after by the lead's salesperson");
            }
            UUID owner = lead != null ? lead.assignedTo() : firstOwner(command);
            PortalInvitationLink link = accounts.invite(new InviteCustomerAccountCommand(email,
                    command.contactName().trim(), command.actorUserId()));
            Customer customer;
            try {
                customer = Customer.onboardBusiness(Identifiers.newId(), link.userId(), command.companyName(), email,
                        command.phone(), taxCode, exemptionReason != null);
            } catch (IllegalArgumentException invalid) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
            }
            Customer stored = customers.save(customer);
            history.record(new CustomerStatusChange(Identifiers.newId(), customer.id(), null,
                    CustomerStatus.PENDING_ACTIVATION, null, command.actorUserId(), clock.instant()));
            if (exemptionReason != null) {
                exemptions.grant(stored.id(), exemptionReason, command.actorUserId(), clock.instant());
            }
            if (owner != null) {
                stored = ownership.assignAtCreation(stored, owner, lead != null ? SalesOwnerChangeReason.LEAD_CONVERTED
                        : SalesOwnerChangeReason.CREATED_BY_SALES, command.actorUserId());
            }
            if (lead != null) {
                leads.converted(lead, stored.id(), command.actorUserId());
            }
            CustomerSummary saved = CustomerServiceImpl.toSummary(stored);
            return new Created(saved, link);
        }));
        return invited(created.customer(), created.link());
    }

    /**
     * BR-S14 / D4: without a tax code, a Sales Manager's exemption with its reason; checked before anything is written.
     *
     * @return the exemption's reason, or null when the customer has a tax code
     * @throws BusinessException {@code CUSTOMER_TAX_CODE_REQUIRED}; {@code FORBIDDEN}; {@code VALIDATION_FAILED}
     */
    private String exemption(CreateBusinessCustomer command, String taxCode) {
        if (taxCode != null) {
            if (command.taxCodeExemption()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "An exemption is for a customer without a tax code");
            }
            return null;
        }
        if (!command.taxCodeExemption()) {
            throw new BusinessException(ErrorCode.CUSTOMER_TAX_CODE_REQUIRED);
        }
        UUID actor = command.actorUserId();
        if (actor == null || !salesStaff.holdsPermission(actor, SalesOwnershipService.MANAGES_OWNERS)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Only a Sales Manager creates a customer without a tax code");
        }
        String reason = command.taxCodeExemptionReason();
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Say why the customer has no tax code");
        }
        if (reason.strip().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The reason is at most 500 characters");
        }
        return reason.strip();
    }

    /**
     * The customer's first owner (sales-owner plan BR-S02, plan §4 cases 1 and 2): the salesperson a manager named, or
     * the creating salesperson, or nobody when a manager (or anyone who is not a salesperson) creates it without naming
     * one. Only a manager names someone other than themselves; whether the one named may own customers (BR-S04) is
     * checked when the owner is assigned.
     *
     * @throws BusinessException {@code FORBIDDEN} for a salesperson naming someone else
     */
    private UUID firstOwner(CreateBusinessCustomer command) {
        UUID actor = command.actorUserId();
        boolean managesOwners = actor != null && salesStaff.holdsPermission(actor, SalesOwnershipService.MANAGES_OWNERS);
        if (command.salesOwnerId() != null) {
            if (!managesOwners && !command.salesOwnerId().equals(actor)) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "Only a Sales Manager chooses another salesperson to look after a new customer");
            }
            return command.salesOwnerId();
        }
        return actor != null && !managesOwners && salesStaff.isActiveSalesAgent(actor) ? actor : null;
    }

    /**
     * Sends a new invitation; the previous link stops working when this commits.
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND}; {@code PORTAL_ACCOUNT_ALREADY_ACTIVE} once
     *         the contact activated; {@code CONFLICT} for a customer that is not pending (blocked,
     *         closed, or a legacy profile without a portal account); {@code RATE_LIMITED} within the
     *         resend cooldown; {@code PORTAL_INVITATION_UNAVAILABLE}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-portal-invitation", resourceId = "#customerId")
    public Invited resendInvitation(UUID customerId, UUID actorUserId) {
        requireConfigured();
        record Reissued(CustomerSummary customer, PortalInvitationLink link) {
        }
        Reissued reissued = inNewTransaction(() -> transactions.execute(status -> {
            Customer customer = customers.findForUpdate(customerId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
            if (customer.status() == CustomerStatus.ACTIVE && customer.userId() != null) {
                throw new BusinessException(ErrorCode.PORTAL_ACCOUNT_ALREADY_ACTIVE);
            }
            if (customer.status() != CustomerStatus.PENDING_ACTIVATION) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "Only a customer pending activation can be invited, not " + customer.status());
            }
            return new Reissued(CustomerServiceImpl.toSummary(customer),
                    accounts.reissue(customer.userId(), actorUserId));
        }));
        return invited(reissued.customer(), reissued.link());
    }

    /**
     * The contact redeems the invitation and chooses a password; the customer becomes ACTIVE in the
     * same transaction, or neither happens.
     *
     * @return the activated customer's sign-in e-mail
     * @throws BusinessException {@code PORTAL_INVITATION_INVALID} for every unusable link alike,
     *         including one whose customer is no longer pending (blocked or closed meanwhile);
     *         {@code VALIDATION_FAILED} for a weak password
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "customer-portal-account", resourceId = "#result")
    public String activate(String token, String password) {
        return inNewTransaction(() -> transactions.execute(status -> {
            UUID userId = accounts.accountNamedBy(token)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID));
            Customer customer = customers.findByUserIdForUpdate(userId)
                    .filter(found -> found.status() == CustomerStatus.PENDING_ACTIVATION)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID));
            accounts.activate(token, password);
            customer.activatePortal();
            String email = customers.save(customer).email();
            history.record(new CustomerStatusChange(Identifiers.newId(), customer.id(),
                    CustomerStatus.PENDING_ACTIVATION, CustomerStatus.ACTIVE, null, userId, clock.instant()));
            return email;
        }));
    }

    @Transactional(readOnly = true)
    public InvitationView invitation(UUID customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        if (customer.userId() == null) {
            return new InvitationView(customerId, InvitationState.NO_ACCOUNT, null, null, null);
        }
        boolean closedOrBlocked = customer.status() == com.stockflow.customer.internal.domain.CustomerStatus.BLOCKED
                || customer.status() == com.stockflow.customer.internal.domain.CustomerStatus.INACTIVE;
        return accounts.invitation(customer.userId())
                .map(open -> new InvitationView(customerId, closedOrBlocked ? InvitationState.UNAVAILABLE
                        : open.expiredAt(clock.instant()) ? InvitationState.EXPIRED : InvitationState.PENDING,
                        open.issuedAt(), open.expiresAt(), open.delivery()))
                .orElseGet(() -> new InvitationView(customerId, InvitationState.ACTIVATED, null, null, null));
    }

    private Invited invited(CustomerSummary customer, PortalInvitationLink link) {
        InvitationDelivery delivery = deliver(link, customer.fullName());
        return new Invited(customer, new PortalInvitationStatus(link.issuedAt(), link.expiresAt(), delivery.name()),
                delivery);
    }

    /**
     * Runs after the commit. Logs who and when, never the link. A failure is reported, not thrown:
     * the customer exists either way, and the caller must learn that the e-mail did not go out. The
     * outcome is stored on the invitation; if even that write fails, the response still tells the truth.
     */
    private InvitationDelivery deliver(PortalInvitationLink link, String companyName) {
        String url = activationUrl + "#token=" + link.token();
        InvitationDelivery outcome;
        try {
            notifications.sendPortalInvitation(new PortalInvitationMail(
                    link.email(), link.fullName(), companyName, url, link.expiresAt()));
            log.info("Portal invitation {} e-mailed to account {} (expires {})", link.invitationId(), link.userId(),
                    link.expiresAt());
            outcome = InvitationDelivery.SENT;
        } catch (RuntimeException failed) {
            log.warn("Portal invitation {} e-mail to account {} failed: {}", link.invitationId(), link.userId(),
                    failed.getClass().getSimpleName());
            outcome = InvitationDelivery.FAILED;
        }
        try {
            accounts.recordDelivery(link.invitationId(), outcome == InvitationDelivery.SENT);
        } catch (RuntimeException unrecorded) {
            log.warn("Could not record the delivery of portal invitation {}: {}", link.invitationId(),
                    unrecorded.getClass().getSimpleName());
        }
        return outcome;
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

    /** Two Sales screens creating one customer at once: the unique indexes decide, as a 409 saying why. */
    private static <T> T writeRejectingDuplicates(Supplier<T> work) {
        try {
            return inNewTransaction(work);
        } catch (DataIntegrityViolationException race) {
            if (CustomerConstraints.isTaxCodeTaken(race)) {
                throw new BusinessException(ErrorCode.CUSTOMER_TAX_CODE_ALREADY_EXISTS);
            }
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
