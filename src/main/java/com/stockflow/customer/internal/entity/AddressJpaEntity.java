package com.stockflow.customer.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.customer.internal.domain.AddressType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/** JPA row for a two-level Vietnamese shipping or billing address. */
@Entity
@Table(name = "address", schema = "customer")
public class AddressJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private AddressType type;

    @Column(name = "recipient_name", nullable = false, length = 200)
    private String recipientName;

    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "line1", nullable = false, length = 255)
    private String line1;

    @Column(name = "line2", length = 255)
    private String line2;

    @Column(name = "ward_code", nullable = false, length = 20)
    private String wardCode;

    @Column(name = "ward", nullable = false, length = 120)
    private String wardName;

    @Column(name = "province_code", nullable = false, length = 20)
    private String provinceCode;

    @Column(name = "province", nullable = false, length = 120)
    private String provinceName;

    @Column(name = "country", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    protected AddressJpaEntity() {
    }

    public AddressJpaEntity(UUID id, UUID customerId, AddressType type, String recipientName,
                            String phone, String line1, String line2, String wardCode,
                            String wardName, String provinceCode, String provinceName,
                            String countryCode, String postalCode, boolean defaultAddress) {
        super(id);
        this.customerId = customerId;
        apply(type, recipientName, phone, line1, line2, wardCode, wardName, provinceCode,
                provinceName, countryCode, postalCode, defaultAddress);
    }

    public void apply(AddressType type, String recipientName, String phone, String line1,
                      String line2, String wardCode, String wardName, String provinceCode,
                      String provinceName, String countryCode, String postalCode,
                      boolean defaultAddress) {
        this.type = type;
        this.recipientName = recipientName;
        this.phone = phone;
        this.line1 = line1;
        this.line2 = line2;
        this.wardCode = wardCode;
        this.wardName = wardName;
        this.provinceCode = provinceCode;
        this.provinceName = provinceName;
        this.countryCode = countryCode;
        this.postalCode = postalCode;
        this.defaultAddress = defaultAddress;
    }

    public UUID getCustomerId() { return customerId; }
    public AddressType getType() { return type; }
    public String getRecipientName() { return recipientName; }
    public String getPhone() { return phone; }
    public String getLine1() { return line1; }
    public String getLine2() { return line2; }
    public String getWardCode() { return wardCode; }
    public String getWardName() { return wardName; }
    public String getProvinceCode() { return provinceCode; }
    public String getProvinceName() { return provinceName; }
    public String getCountryCode() { return countryCode; }
    public String getPostalCode() { return postalCode; }
    public boolean isDefaultAddress() { return defaultAddress; }
}
