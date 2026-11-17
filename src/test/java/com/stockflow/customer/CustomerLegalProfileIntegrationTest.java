package com.stockflow.customer;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerChangeEntry;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.internal.service.CustomerLegalProfileService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4B-4 (kltn-docs 18 §3, BR-05, §6; QD-14): Sales records a customer's registered business address and legal
 * representative, whole and against the version read; every change is kept in the customer's change history, and the
 * sales contract reads it through {@code customer :: api}.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class CustomerLegalProfileIntegrationTest {

    @Autowired CustomerLegalProfileService service;
    @Autowired CustomerLegalProfiles profiles;
    @Autowired CustomerService customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("recorded, read through the api, changed against the version read; every change kept")
    void recordedAndChanged() {
        UUID sales = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO customer.customer (id, full_name, email, status, "
                + "version, created_at) VALUES (?, 'Cong ty Phap Ly', ?, 'ACTIVE', 0, NOW())", customerId,
                "legal-" + customerId + "@example.com"));
        assertThat(profiles.legalProfile(customerId)).as("not recorded yet: no contract (18 BR-05)").isEmpty();
        assertThatThrownBy(() -> profiles.legalProfile(Identifiers.newId()))
                .extracting(CustomerLegalProfileIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);
        assertThatThrownBy(() -> service.save(new CustomerLegalProfileService.Save(customerId, " ", "Tran Van A",
                "Director", null, sales)))
                .extracting(CustomerLegalProfileIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);

        CustomerLegalProfile first = service.save(new CustomerLegalProfileService.Save(customerId,
                " 12 Nguyen Hue, Ho Chi Minh City ", "Tran Van A", "Director", null, sales));
        assertThat(first.registeredAddress()).as("trimmed").isEqualTo("12 Nguyen Hue, Ho Chi Minh City");
        assertThat(first.companyName()).isEqualTo("Cong ty Phap Ly");
        assertThat(profiles.legalProfile(customerId)).contains(first);
        assertThatThrownBy(() -> service.save(new CustomerLegalProfileService.Save(customerId, "x", "y", "z", null,
                sales)))
                .as("a second first profile: the caller has not read the one saved")
                .extracting(CustomerLegalProfileIntegrationTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        CustomerLegalProfile changed = service.save(new CustomerLegalProfileService.Save(customerId,
                "12 Nguyen Hue, Ho Chi Minh City", "Tran Van B", "General Director", first.version(), sales));
        assertThat(changed.representativeName()).isEqualTo("Tran Van B");
        assertThatThrownBy(() -> service.save(new CustomerLegalProfileService.Save(customerId, "x", "y", "z",
                first.version(), sales)))
                .as("a stale version").extracting(CustomerLegalProfileIntegrationTest::code)
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        // 18 §6: the history keeps each field's change once, with the value before.
        assertThat(customers.changeHistory(customerId)).extracting(CustomerChangeEntry::field, CustomerChangeEntry::oldValue,
                CustomerChangeEntry::newValue).contains(
                org.assertj.core.groups.Tuple.tuple("REGISTERED_ADDRESS", null, "12 Nguyen Hue, Ho Chi Minh City"),
                org.assertj.core.groups.Tuple.tuple("REPRESENTATIVE", null, "Tran Van A"),
                org.assertj.core.groups.Tuple.tuple("REPRESENTATIVE", "Tran Van A", "Tran Van B"),
                org.assertj.core.groups.Tuple.tuple("REPRESENTATIVE_TITLE", "Director", "General Director"));
        assertThat(customers.changeHistory(customerId)).filteredOn(e -> e.field().equals("REGISTERED_ADDRESS"))
                .as("unchanged the second time: not recorded again").hasSize(1);
    }

    @Test
    @DisplayName("WF-05 E1: the invoice e-mail defaults to the contact address, the bank account is all or nothing, "
            + "and every change of either is kept")
    void invoiceEmailAndBankAccount() {
        UUID accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID customerId = Identifiers.newId();
        String contact = "Invoice-" + customerId + "@Example.com";
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO customer.customer (id, full_name, email, status, "
                + "version, created_at) VALUES (?, 'Cong ty Hoa Don', ?, 'ACTIVE', 0, NOW())", customerId, contact));

        CustomerLegalProfile first = service.save(new CustomerLegalProfileService.Save(customerId, "1 Le Loi, TP HCM",
                "Tran Van A", "Director", null, null, null, accountant));
        assertThat(first.invoiceEmail()).as("18 §3: its default is the contact e-mail, lower-cased")
                .isEqualTo(contact.toLowerCase(java.util.Locale.ROOT));
        assertThat(first.bankAccount()).isNull();

        for (String wrong : new String[] {"ke.toan", "ke toan@example.com", "a@b", "a@b.vn@c.vn"}) {
            assertThatThrownBy(() -> service.save(new CustomerLegalProfileService.Save(customerId, "1 Le Loi, TP HCM",
                    "Tran Van A", "Director", wrong, null, first.version(), accountant)))
                    .as("not an address: %s", wrong)
                    .extracting(CustomerLegalProfileIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThatThrownBy(() -> service.save(new CustomerLegalProfileService.Save(customerId, "1 Le Loi, TP HCM",
                "Tran Van A", "Director", null, new CustomerLegalProfile.BankAccount("0123 456 789", "Vietcombank", " "),
                first.version(), accountant)))
                .as("a bank account names its holder")
                .extracting(CustomerLegalProfileIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.save(new CustomerLegalProfileService.Save(customerId, "1 Le Loi, TP HCM",
                "Tran Van A", "Director", null, new CustomerLegalProfile.BankAccount("12#", "Vietcombank", "CONG TY"),
                first.version(), accountant)))
                .as("an account number is digits and letters")
                .extracting(CustomerLegalProfileIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);

        CustomerLegalProfile changed = service.save(new CustomerLegalProfileService.Save(customerId, "1 Le Loi, TP HCM",
                "Tran Van A", "Director", " Ke.Toan@hoadon.example.com ",
                new CustomerLegalProfile.BankAccount("0123 456 789", " Vietcombank ", "CONG TY HOA DON"),
                first.version(), accountant));
        assertThat(changed.invoiceEmail()).isEqualTo("ke.toan@hoadon.example.com");
        assertThat(changed.bankAccount()).isEqualTo(
                new CustomerLegalProfile.BankAccount("0123456789", "Vietcombank", "CONG TY HOA DON"));
        assertThat(profiles.legalProfile(customerId)).as("read through customer :: api").contains(changed);

        CustomerLegalProfile cleared = service.save(new CustomerLegalProfileService.Save(customerId, "1 Le Loi, TP HCM",
                "Tran Van A", "Director", "ke.toan@hoadon.example.com", null, changed.version(), accountant));
        assertThat(cleared.bankAccount()).as("written whole: no account given, none kept").isNull();

        assertThat(customers.changeHistory(customerId)).extracting(CustomerChangeEntry::field,
                CustomerChangeEntry::oldValue, CustomerChangeEntry::newValue).contains(
                org.assertj.core.groups.Tuple.tuple("INVOICE_EMAIL", null, contact.toLowerCase(java.util.Locale.ROOT)),
                org.assertj.core.groups.Tuple.tuple("INVOICE_EMAIL", contact.toLowerCase(java.util.Locale.ROOT),
                        "ke.toan@hoadon.example.com"),
                org.assertj.core.groups.Tuple.tuple("BANK_ACCOUNT", null, "0123456789 | Vietcombank | CONG TY HOA DON"),
                org.assertj.core.groups.Tuple.tuple("BANK_ACCOUNT", "0123456789 | Vietcombank | CONG TY HOA DON", null));
        assertThat(customers.changeHistory(customerId)).filteredOn(e -> e.field().equals("INVOICE_EMAIL"))
                .as("unchanged the third time: not recorded again").hasSize(2);
        assertThat(customers.changeHistory(customerId)).allSatisfy(e -> assertThat(e.changedBy()).isEqualTo(accountant));
    }
}
