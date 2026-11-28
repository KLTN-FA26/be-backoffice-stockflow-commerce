package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The self-issued test certificate the company signs contracts with and its private key, sealed (V20270129000200).
 * One row per purpose; the private key is never stored in clear.
 */
@Entity
@Table(name = "contract_signing_key", schema = "ordering")
public class ContractSigningKeyJpaEntity extends BaseEntity {

    @Column(name = "purpose", nullable = false, updatable = false, length = 20) private String purpose;
    @Column(name = "certificate_der", nullable = false, updatable = false) private byte[] certificateDer;
    @Column(name = "private_key_sealed", nullable = false, updatable = false) private String privateKeySealed;

    protected ContractSigningKeyJpaEntity() { }

    public ContractSigningKeyJpaEntity(UUID id, String purpose, byte[] certificateDer, String privateKeySealed) {
        super(id);
        this.purpose = purpose;
        this.certificateDer = certificateDer.clone();
        this.privateKeySealed = privateKeySealed;
    }

    public String getPurpose() { return purpose; }
    public byte[] getCertificateDer() { return certificateDer.clone(); }
    public String getPrivateKeySealed() { return privateKeySealed; }
}
