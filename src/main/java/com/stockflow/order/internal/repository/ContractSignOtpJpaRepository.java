package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.ContractSignOtpJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractSignOtpJpaRepository extends BaseJpaRepository<ContractSignOtpJpaEntity> {

    /**
     * Serialises the codes asked for on one contract until the transaction ends, so two requests cannot both pass the
     * resend and hourly limits. Wrapped in a sub-select because {@code pg_advisory_xact_lock} returns {@code void}.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS locked",
            nativeQuery = true)
    Integer lockContract(@Param("key") String key);

    /** The code that can still be entered, its row locked: attempts are counted under this lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ContractSignOtpJpaEntity o where o.contractId = :contractId "
            + "and o.consumedAt is null and o.supersededAt is null")
    Optional<ContractSignOtpJpaEntity> findLiveForUpdate(@Param("contractId") UUID contractId);

    Optional<ContractSignOtpJpaEntity> findFirstByContractIdOrderByCreatedAtDesc(UUID contractId);

    /**
     * Wrong codes entered for one revision since they were last forgiven, over every code issued for it: the lifetime
     * count {@code ContractSignOtps} caps.
     */
    @Query(value = "SELECT COALESCE(SUM(attempts), 0) FROM ordering.contract_sign_otp "
            + "WHERE contract_id = :contractId AND revision = :revision AND forgiven_at IS NULL", nativeQuery = true)
    long wrongTries(@Param("contractId") UUID contractId, @Param("revision") int revision);

    /**
     * Forgives the wrong codes counted so far for one revision (Sales extended the signing deadline). The live code is
     * superseded with them: a code whose earlier tries no longer count must not take more, or its later wrong tries
     * would escape the count too. The customer asks for a new one.
     */
    @Modifying
    @Query(value = "UPDATE ordering.contract_sign_otp SET forgiven_at = :at, superseded_at = CASE "
            + "WHEN consumed_at IS NULL AND superseded_at IS NULL THEN :at ELSE superseded_at END "
            + "WHERE contract_id = :contractId AND revision = :revision AND forgiven_at IS NULL", nativeQuery = true)
    int forgive(@Param("contractId") UUID contractId, @Param("revision") int revision, @Param("at") Instant at);

    /** The codes issued since {@code since}, oldest first. */
    List<ContractSignOtpJpaEntity> findByContractIdAndCreatedAtAfterOrderByCreatedAtAsc(UUID contractId,
                                                                                         Instant since);
}
