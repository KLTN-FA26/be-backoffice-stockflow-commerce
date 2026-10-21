package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A declared carrier (table {@code fulfillment.carrier}; kltn-docs 09 §3: "chọn từ danh sách hãng đã khai báo: tên,
 * liên hệ"). A small catalogue the warehouse manager keeps; an INACTIVE carrier stays on the shipments and loads it
 * already has, and takes no new load.
 */
@Entity
@Table(name = "carrier", schema = "fulfillment")
public class CarrierJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 32) private String code;
    @Column(name = "name", nullable = false, length = 120) private String name;
    @Column(name = "contact_name", length = 120) private String contactName;
    @Column(name = "contact_phone", length = 32) private String contactPhone;
    @Column(name = "status", nullable = false, length = 16) private String status;

    protected CarrierJpaEntity() {
    }

    public CarrierJpaEntity(UUID id, String code) {
        super(id);
        this.code = code;
        this.status = "ACTIVE";
    }

    public void apply(String code, String name, String contactName, String contactPhone, String status) {
        this.code = code;
        this.name = name;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.status = status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getContactName() { return contactName; }
    public String getContactPhone() { return contactPhone; }
    public String getStatus() { return status; }
}
