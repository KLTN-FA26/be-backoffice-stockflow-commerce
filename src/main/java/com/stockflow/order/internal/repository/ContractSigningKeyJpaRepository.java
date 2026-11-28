package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.ContractSigningKeyJpaEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ContractSigningKeyJpaRepository extends BaseJpaRepository<ContractSigningKeyJpaEntity> {

    Optional<ContractSigningKeyJpaEntity> findByPurpose(String purpose);

    /**
     * Creates the row unless one exists for the purpose. Two instances starting together both get here: the second
     * INSERT waits for the first to commit, then does nothing, and a read after it finds the first row.
     *
     * @return 1 when this call created it, 0 when another had
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO ordering.contract_signing_key (id, purpose, certificate_der, private_key_sealed)
            VALUES (:id, :purpose, :certificate, :sealed)
            ON CONFLICT (purpose) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("purpose") String purpose,
                       @Param("certificate") byte[] certificate, @Param("sealed") String sealed);
}
