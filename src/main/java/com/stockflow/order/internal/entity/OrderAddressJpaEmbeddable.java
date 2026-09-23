package com.stockflow.order.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class OrderAddressJpaEmbeddable {
    @Column(name = "recipient_name", length = 200) private String recipientName;
    @Column(name = "phone", length = 32) private String phone;
    @Column(name = "line1", length = 255) private String line1;
    @Column(name = "line2", length = 255) private String line2;
    @Column(name = "ward_code", length = 20) private String wardCode;
    @Column(name = "ward_name", length = 120) private String wardName;
    @Column(name = "province_code", length = 20) private String provinceCode;
    @Column(name = "province_name", length = 120) private String provinceName;
    @Column(name = "country_code", length = 2) private String countryCode;
    @Column(name = "postal_code", length = 20) private String postalCode;

    protected OrderAddressJpaEmbeddable() {
    }

    public OrderAddressJpaEmbeddable(String recipientName, String phone, String line1, String line2,
                                     String wardCode, String wardName, String provinceCode,
                                     String provinceName, String countryCode, String postalCode) {
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
    }

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
}
