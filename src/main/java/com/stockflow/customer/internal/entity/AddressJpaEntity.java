package com.stockflow.customer.internal.entity;

import com.stockflow.customer.internal.domain.AddressType;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA mapping of a customer address row (table {@code customer.address}). Not the domain model.
 *
 * <p>STARTER ENTITY. A Vietnamese postal address (ward / district / city / province), plus the
 * recipient it is addressed to. {@code customerId} is a same-schema reference to
 * {@code customer.customer}.</p>
 */
@Entity
@Table(name = "address", schema = "customer")
public class AddressJpaEntity extends BaseEntity {

    /** Same-schema reference to {@code customer.customer}. */
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private AddressType type;

    @Column(name = "recipient_name", length = 200)
    private String recipientName;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "line1", nullable = false, length = 255)
    private String line1;

    @Column(name = "line2", length = 255)
    private String line2;

    @Column(name = "ward", length = 120)
    private String ward;

    @Column(name = "district", length = 120)
    private String district;

    @Column(name = "city", nullable = false, length = 120)
    private String city;

    @Column(name = "province", length = 120)
    private String province;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    /** Required by JPA. Application code uses the id-taking constructor. */
    protected AddressJpaEntity() {
    }

    public AddressJpaEntity(UUID id, UUID customerId, AddressType type, String recipientName,
                            String phone, String line1, String line2, String ward, String district,
                            String city, String province, String country, String postalCode,
                            boolean isDefault) {
        super(id);
        this.customerId = customerId;
        this.type = type;
        this.recipientName = recipientName;
        this.phone = phone;
        this.line1 = line1;
        this.line2 = line2;
        this.ward = ward;
        this.district = district;
        this.city = city;
        this.province = province;
        this.country = country;
        this.postalCode = postalCode;
        this.isDefault = isDefault;
    }

    public UUID getCustomerId() { return customerId; }
    public AddressType getType() { return type; }
    public String getRecipientName() { return recipientName; }
    public String getPhone() { return phone; }
    public String getLine1() { return line1; }
    public String getLine2() { return line2; }
    public String getWard() { return ward; }
    public String getDistrict() { return district; }
    public String getCity() { return city; }
    public String getProvince() { return province; }
    public String getCountry() { return country; }
    public String getPostalCode() { return postalCode; }
    public boolean isDefault() { return isDefault; }
}
