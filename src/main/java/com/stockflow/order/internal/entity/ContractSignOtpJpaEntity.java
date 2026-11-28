package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A one-time code asked for to sign a contract (V20270129000200): only its HMAC is kept. Live while neither consumed nor
 * superseded; the rows stay as the record of how often codes were asked for.
 */
@Entity
@Table(name = "contract_sign_otp", schema = "ordering")
public class ContractSignOtpJpaEntity extends BaseEntity {

    @Column(name = "contract_id", nullable = false, updatable = false) private UUID contractId;
    @Column(name = "revision", nullable = false, updatable = false) private int revision;
    @Column(name = "customer_user_id", nullable = false, updatable = false) private UUID customerUserId;
    @Column(name = "email", nullable = false, updatable = false, length = 320) private String email;
    @Column(name = "code_hash", nullable = false, updatable = false, length = 64) private String codeHash;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "attempts", nullable = false) private int attempts;
    @Column(name = "consumed_at") private Instant consumedAt;
    @Column(name = "superseded_at") private Instant supersededAt;
    @Column(name = "ip", updatable = false, length = 45) private String ip;

    protected ContractSignOtpJpaEntity() { }

    public ContractSignOtpJpaEntity(UUID id, UUID contractId, int revision, UUID customerUserId, String email,
                                    String codeHash, Instant expiresAt, String ip) {
        super(id);
        this.contractId = contractId;
        this.revision = revision;
        this.customerUserId = customerUserId;
        this.email = email;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.ip = ip;
    }

    public UUID getContractId() { return contractId; }
    public int getRevision() { return revision; }
    public UUID getCustomerUserId() { return customerUserId; }
    public String getEmail() { return email; }
    public String getCodeHash() { return codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getAttempts() { return attempts; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getSupersededAt() { return supersededAt; }

    public void recordWrongAttempt() { attempts++; }
    public void consume(Instant now) { consumedAt = now; }
    public void supersede(Instant now) { supersededAt = now; }
}
