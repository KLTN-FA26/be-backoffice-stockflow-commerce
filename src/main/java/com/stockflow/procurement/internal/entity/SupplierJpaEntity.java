package com.stockflow.procurement.internal.entity;

import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/** JPA mapping of a supplier (table {@code procurement.supplier}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "supplier", schema = "procurement",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_code", columnNames = "code"))
public class SupplierJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "tax_code", length = 32)
    private String taxCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SupplierStatus status;

    @Column(name = "contact_name", length = 200)
    private String contactName;

    @Column(name = "payment_term_days", nullable = false)
    private int paymentTermDays;

    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "communication_channel", nullable = false, length = 16)
    private SupplierCommunicationChannel communicationChannel;

    @Column(name = "api_endpoint", length = 500)
    private String apiEndpoint;

    protected SupplierJpaEntity() {
    }

    public SupplierJpaEntity(UUID id, String code, String name, String email, String phone,
                             String taxCode, SupplierStatus status, String contactName,
                             int paymentTermDays, int leadTimeDays,
                             SupplierCommunicationChannel communicationChannel, String apiEndpoint) {
        super(id);
        this.code = code;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.taxCode = taxCode;
        this.status = status;
        this.contactName = contactName;
        this.paymentTermDays = paymentTermDays;
        this.leadTimeDays = leadTimeDays;
        this.communicationChannel = communicationChannel;
        this.apiEndpoint = apiEndpoint;
    }

    public void update(String name, String email, String phone, String taxCode, SupplierStatus status,
                       String contactName, int paymentTermDays, int leadTimeDays,
                       SupplierCommunicationChannel communicationChannel, String apiEndpoint) {
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.taxCode = taxCode;
        this.status = status;
        this.contactName = contactName;
        this.paymentTermDays = paymentTermDays;
        this.leadTimeDays = leadTimeDays;
        this.communicationChannel = communicationChannel;
        this.apiEndpoint = apiEndpoint;
    }

    public void deactivate() { this.status = SupplierStatus.INACTIVE; }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getTaxCode() { return taxCode; }
    public SupplierStatus getStatus() { return status; }
    public String getContactName() { return contactName; }
    public int getPaymentTermDays() { return paymentTermDays; }
    public int getLeadTimeDays() { return leadTimeDays; }
    public SupplierCommunicationChannel getCommunicationChannel() { return communicationChannel; }
    public String getApiEndpoint() { return apiEndpoint; }
}
