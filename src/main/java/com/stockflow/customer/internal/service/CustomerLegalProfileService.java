package com.stockflow.customer.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerChange;
import com.stockflow.customer.internal.domain.CustomerChangeLog;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.entity.CustomerLegalProfileJpaEntity;
import com.stockflow.customer.internal.repository.CustomerLegalProfileJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A customer's legal profile (kltn-docs 18 §3, BR-05, BR-12, §6; QD-14; team plan 4B-4; WF-05 E1): Sales records the
 * registered business address, the legal representative and their title before a contract is drawn up for the
 * customer; Sales or the accountant records the e-mail address its e-invoices are sent to and its bank account. Every
 * change is kept in the customer's change history, as a change of its company name is. The company name and the tax
 * code are the customer record's own and change there, with the same history.
 */
@Service
@Transactional
public class CustomerLegalProfileService implements CustomerLegalProfiles {

    /**
     * The profile as staff enter it, whole; {@code expectedVersion} null only for a customer that has none yet.
     *
     * @param invoiceEmail blank takes the customer's contact e-mail address (18 §3: its default)
     * @param bankAccount  null records none
     */
    public record Save(UUID customerId, String registeredAddress, String representativeName,
                       String representativeTitle, String invoiceEmail, CustomerLegalProfile.BankAccount bankAccount,
                       Long expectedVersion, UUID by) {

        /** Without an invoice e-mail or a bank account (callers written before WF-05 E1). */
        public Save(UUID customerId, String registeredAddress, String representativeName,
                    String representativeTitle, Long expectedVersion, UUID by) {
            this(customerId, registeredAddress, representativeName, representativeTitle, null, null, expectedVersion,
                    by);
        }
    }

    /** What an address must look like to be mailed an invoice: one {@code @}, a dot in the domain, no spaces. */
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    /** An account number as banks print it, without the spaces: digits and letters (an IBAN is at most 34). */
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("[0-9A-Za-z]{4,34}");

    private final CustomerRepository customers;
    private final CustomerLegalProfileJpaRepository profiles;
    private final CustomerChangeLog changes;
    private final Clock clock;

    CustomerLegalProfileService(CustomerRepository customers, CustomerLegalProfileJpaRepository profiles,
                                CustomerChangeLog changes, Clock clock) {
        this.customers = customers;
        this.profiles = profiles;
        this.changes = changes;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerLegalProfile> legalProfile(UUID customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        return profiles.findByCustomerId(customerId).map(row -> view(customer, row));
    }

    /**
     * Records the profile, whole. The customer row is locked first, so two saves of one profile are decided one after
     * the other and the second, holding a stale version, is refused.
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND}; {@code VALIDATION_FAILED} for a blank field, an invoice
     *                           e-mail that is not an address, or a bank account missing its number, bank or holder;
     *                           {@code OPTIMISTIC_LOCK} when the profile changed since it was read
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-legal-profile", resourceId = "#command.customerId()")
    public CustomerLegalProfile save(Save command) {
        Customer customer = customers.findForUpdate(command.customerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        String address = required(command.registeredAddress(), "registeredAddress", 500);
        String representative = required(command.representativeName(), "representativeName", 200);
        String title = required(command.representativeTitle(), "representativeTitle", 100);
        String invoiceEmail = invoiceEmail(command.invoiceEmail(), customer.email());
        CustomerLegalProfile.BankAccount bank = bankAccount(command.bankAccount());
        Optional<CustomerLegalProfileJpaEntity> existing = profiles.findByCustomerId(command.customerId());
        if (existing.isPresent() ? command.expectedVersion() == null
                || existing.get().getVersion() != command.expectedVersion()
                : command.expectedVersion() != null && command.expectedVersion() != -1L) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "The legal profile of customer %s changed since it was read".formatted(command.customerId()));
        }
        CustomerLegalProfileJpaEntity row = existing.orElseGet(() ->
                new CustomerLegalProfileJpaEntity(Identifiers.newId(), command.customerId()));
        Instant now = clock.instant();
        record(customer.id(), CustomerChange.Field.REGISTERED_ADDRESS, row.getRegisteredAddress(), address,
                command.by(), now);
        record(customer.id(), CustomerChange.Field.REPRESENTATIVE, row.getRepresentativeName(), representative,
                command.by(), now);
        record(customer.id(), CustomerChange.Field.REPRESENTATIVE_TITLE, row.getRepresentativeTitle(), title,
                command.by(), now);
        record(customer.id(), CustomerChange.Field.INVOICE_EMAIL, row.getInvoiceEmail(), invoiceEmail, command.by(),
                now);
        record(customer.id(), CustomerChange.Field.BANK_ACCOUNT, bankLine(bankOf(row)), bankLine(bank), command.by(),
                now);
        row.apply(address, representative, title);
        row.applyInvoiceEmail(invoiceEmail);
        row.applyBankAccount(bank == null ? null : bank.accountNumber(), bank == null ? null : bank.bankName(),
                bank == null ? null : bank.accountHolder());
        return view(customer, profiles.saveAndFlush(row));
    }

    /**
     * The address invoices go to, lower-cased as the customer's own is; blank takes the customer's contact address,
     * which is what 18 §3 makes its default.
     */
    private static String invoiceEmail(String entered, String contactEmail) {
        String value = entered == null || entered.isBlank() ? contactEmail : entered;
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 320 || !EMAIL.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "invoiceEmail: not an e-mail address");
        }
        return value;
    }

    /** Null for no account; otherwise its number without spaces, its bank and its holder, all three. */
    private static CustomerLegalProfile.BankAccount bankAccount(CustomerLegalProfile.BankAccount entered) {
        if (entered == null || (blank(entered.accountNumber()) && blank(entered.bankName())
                && blank(entered.accountHolder()))) {
            return null;
        }
        String number = entered.accountNumber() == null ? "" : entered.accountNumber().replaceAll("[\\s.-]", "");
        if (!ACCOUNT_NUMBER.matcher(number).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "bankAccount.accountNumber: 4 to 34 digits or letters");
        }
        return new CustomerLegalProfile.BankAccount(number, required(entered.bankName(), "bankAccount.bankName", 200),
                required(entered.accountHolder(), "bankAccount.accountHolder", 200));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static CustomerLegalProfile.BankAccount bankOf(CustomerLegalProfileJpaEntity row) {
        return row.getBankAccountNumber() == null ? null : new CustomerLegalProfile.BankAccount(
                row.getBankAccountNumber(), row.getBankName(), row.getBankAccountHolder());
    }

    /** A bank account on one line of the change history: number, bank, holder. */
    private static String bankLine(CustomerLegalProfile.BankAccount bank) {
        return bank == null ? null : bank.accountNumber() + " | " + bank.bankName() + " | " + bank.accountHolder();
    }

    private void record(UUID customerId, CustomerChange.Field field, String before, String after, UUID by,
                        Instant at) {
        if (!Objects.equals(before, after)) {
            changes.record(new CustomerChange(Identifiers.newId(), customerId, field, before, after, by, at));
        }
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + ": required, at most " + max
                    + " characters");
        }
        return value.strip();
    }

    private static CustomerLegalProfile view(Customer customer, CustomerLegalProfileJpaEntity row) {
        return new CustomerLegalProfile(customer.id(), customer.fullName(), customer.taxCode(),
                row.getRegisteredAddress(), row.getRepresentativeName(), row.getRepresentativeTitle(),
                row.getInvoiceEmail(), bankOf(row), row.getVersion());
    }
}
