package com.stockflow.order.internal.service.signing;

import com.stockflow.common.id.Identifiers;
import com.stockflow.order.internal.entity.ContractSigningKeyJpaEntity;
import com.stockflow.order.internal.repository.ContractSigningKeyJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * The database side of the test certificate: reads the stored one, or stores the one {@code generator} makes when none
 * exists. A class of its own, and a bean, so that {@link TestCertificateSigner} reaches the transaction through the
 * proxy.
 */
@Service
class ContractSigningKeys {

    /** What is stored: the certificate in clear (it is public) and the private key sealed. */
    record Stored(byte[] certificate, String privateKeySealed) {
    }

    private final ContractSigningKeyJpaRepository keys;

    ContractSigningKeys(ContractSigningKeyJpaRepository keys) {
        this.keys = keys;
    }

    /**
     * Two instances starting together may both find none and both generate; only one INSERT wins (the table is unique
     * per purpose), and both return the winner's row, so every instance signs with the same certificate. The loser's
     * key is dropped unused.
     */
    @Transactional
    public Stored loadOrCreate(String purpose, Supplier<Stored> generator) {
        var existing = keys.findByPurpose(purpose);
        if (existing.isEmpty()) {
            Stored made = generator.get();
            keys.insertIfAbsent(Identifiers.newId(), purpose, made.certificate(), made.privateKeySealed());
            existing = keys.findByPurpose(purpose);
        }
        ContractSigningKeyJpaEntity row = existing.orElseThrow(
                () -> new IllegalStateException("The contract signing key was not stored"));
        return new Stored(row.getCertificateDer(), row.getPrivateKeySealed());
    }
}
