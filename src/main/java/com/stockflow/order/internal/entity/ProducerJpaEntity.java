package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.order.internal.domain.Producer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/** {@code ordering.producer} (4A-1): the code is written once; the rest is the coordinator's to edit. */
@Entity
@Table(name = "producer", schema = "ordering")
public class ProducerJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "contact_name", length = 200)
    private String contactName;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "email", length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private Producer.Channel channel;

    @Column(name = "standard_lead_days", nullable = false)
    private int standardLeadDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Producer.Status status;

    @Column(name = "note", length = 500)
    private String note;

    protected ProducerJpaEntity() {
    }

    public ProducerJpaEntity(UUID id, String code) {
        super(id);
        this.code = code;
    }

    public void apply(String name, String contactName, String contactPhone, String email, Producer.Channel channel,
                      int standardLeadDays, Producer.Status status, String note) {
        this.name = name;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.email = email;
        this.channel = channel;
        this.standardLeadDays = standardLeadDays;
        this.status = status;
        this.note = note;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getContactName() { return contactName; }
    public String getContactPhone() { return contactPhone; }
    public String getEmail() { return email; }
    public Producer.Channel getChannel() { return channel; }
    public int getStandardLeadDays() { return standardLeadDays; }
    public Producer.Status getStatus() { return status; }
    public String getNote() { return note; }
}
