package com.stockflow.customer.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * {@code customer.customer_legal_profile} (4B-4; invoice e-mail and bank account since WF-05 E1): one row per customer,
 * written whole.
 */
@Entity
@Table(name = "customer_legal_profile", schema = "customer")
public class CustomerLegalProfileJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "registered_address", nullable = false, length = 500)
    private String registeredAddress;

    @Column(name = "representative_name", nullable = false, length = 200)
    private String representativeName;

    @Column(name = "representative_title", nullable = false, length = 100)
    private String representativeTitle;

    @Column(name = "invoice_email", length = 320)
    private String invoiceEmail;

    @Column(name = "bank_account_number", length = 34)
    private String bankAccountNumber;

    @Column(name = "bank_name", length = 200)
    private String bankName;

    @Column(name = "bank_account_holder", length = 200)
    private String bankAccountHolder;

    protected CustomerLegalProfileJpaEntity() {
    }

    public CustomerLegalProfileJpaEntity(UUID id, UUID customerId) {
        super(id);
        this.customerId = customerId;
    }

    public void apply(String registeredAddress, String representativeName, String representativeTitle) {
        this.registeredAddress = registeredAddress;
        this.representativeName = representativeName;
        this.representativeTitle = representativeTitle;
    }

    public void applyInvoiceEmail(String invoiceEmail) {
        this.invoiceEmail = invoiceEmail;
    }

    /** All three, or all null: {@code ck_customer_legal_profile_bank}. */
    public void applyBankAccount(String accountNumber, String bankName, String accountHolder) {
        this.bankAccountNumber = accountNumber;
        this.bankName = bankName;
        this.bankAccountHolder = accountHolder;
    }

    public UUID getCustomerId() { return customerId; }
    public String getRegisteredAddress() { return registeredAddress; }
    public String getRepresentativeName() { return representativeName; }
    public String getRepresentativeTitle() { return representativeTitle; }
    public String getInvoiceEmail() { return invoiceEmail; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public String getBankName() { return bankName; }
    public String getBankAccountHolder() { return bankAccountHolder; }
}
