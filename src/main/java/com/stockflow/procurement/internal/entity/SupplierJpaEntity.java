package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a supplier (table {@code procurement.suppliers}). Not the domain model.
 *
 * <p>The supplier's primary contact is a row of {@code supplier_contacts}
 * ({@link SupplierContactJpaEntity}), written alongside by the repository adapter. {@code code} is
 * fixed at creation ({@code tg_suppliers_immutable}). Columns no screen edits yet (legal name, bank
 * account, incoterms, free-text payment terms, note) are not mapped, so nothing here overwrites them.</p>
 */
@Entity
@Table(name = "suppliers", schema = "procurement")
public class SupplierJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 30, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "tax_id", length = 30)
    private String taxCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SupplierStatus status;

    @Column(name = "payment_term_days", nullable = false)
    private int paymentTermDays;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "communication_channel", nullable = false, length = 16)
    private SupplierCommunicationChannel communicationChannel;

    @Column(name = "api_endpoint", length = 500)
    private String apiEndpoint;

    @Column(name = "over_receipt_tolerance", precision = 5, scale = 2)
    private BigDecimal overReceiptTolerancePercent;

    @Column(name = "is_print_subcontractor", nullable = false)
    private boolean printSubcontractor;

    @Column(name = "loss_tolerance_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal lossTolerancePercent;

    protected SupplierJpaEntity() {
    }

    public SupplierJpaEntity(UUID id, String code) {
        super(id);
        this.code = code;
    }

    public void update(String name, String taxCode, SupplierStatus status, int paymentTermDays, int leadTimeDays,
                       SupplierCommunicationChannel communicationChannel, String apiEndpoint,
                       BigDecimal overReceiptTolerancePercent, boolean printSubcontractor,
                       BigDecimal lossTolerancePercent) {
        this.name = name;
        this.taxCode = taxCode;
        this.status = status;
        this.paymentTermDays = paymentTermDays;
        this.leadTimeDays = leadTimeDays;
        this.communicationChannel = communicationChannel;
        this.apiEndpoint = apiEndpoint;
        this.overReceiptTolerancePercent = overReceiptTolerancePercent;
        this.printSubcontractor = printSubcontractor;
        this.lossTolerancePercent = lossTolerancePercent;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getTaxCode() { return taxCode; }
    public SupplierStatus getStatus() { return status; }
    public int getPaymentTermDays() { return paymentTermDays; }
    public int getLeadTimeDays() { return leadTimeDays == null ? 0 : leadTimeDays; }
    public SupplierCommunicationChannel getCommunicationChannel() { return communicationChannel; }
    public String getApiEndpoint() { return apiEndpoint; }
    public BigDecimal getOverReceiptTolerancePercent() { return overReceiptTolerancePercent; }
    public boolean isPrintSubcontractor() { return printSubcontractor; }
    public BigDecimal getLossTolerancePercent() { return lossTolerancePercent; }
}
