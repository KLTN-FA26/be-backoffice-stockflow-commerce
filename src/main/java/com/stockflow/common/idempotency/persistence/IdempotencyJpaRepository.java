package com.stockflow.common.idempotency.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Spring Data access to {@code platform.idempotency_record}. Package-private by design. */
interface IdempotencyJpaRepository extends JpaRepository<IdempotencyRecordEntity, UUID> {

    @Query("""
            select r from IdempotencyRecordEntity r
            where r.idempotencyKey = :key and r.caller = :caller
            """)
    Optional<IdempotencyRecordEntity> findByKeyAndCaller(@Param("key") String key,
                                                         @Param("caller") String caller);

    @Modifying
    @Query("""
            delete from IdempotencyRecordEntity r
            where r.idempotencyKey = :key and r.caller = :caller
            """)
    int deleteByKeyAndCaller(@Param("key") String key, @Param("caller") String caller);

    /**
     * Housekeeping, in bounded batches.
     *
     * <p>The subquery with a limit exists because a single {@code delete ... where expires_at < now}
     * after an outage could touch millions of rows in one statement — holding locks and a huge WAL
     * write while live requests wait behind it. Deleting a few thousand at a time, repeatedly, gets
     * the same result without a stall.</p>
     */
    @Modifying
    @Query(value = """
            delete from platform.idempotency_record
            where id in (
                select id from platform.idempotency_record
                where expires_at < :now
                limit :batchSize
            )
            """, nativeQuery = true)
    int deleteExpired(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
