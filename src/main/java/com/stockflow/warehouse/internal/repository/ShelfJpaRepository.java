package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.entity.ShelfJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link ShelfJpaEntity}, the root of the Shelf aggregate. */
interface ShelfJpaRepository extends BaseJpaRepository<ShelfJpaEntity> {

    /**
     * The shelf with its levels, in one query; the bins follow in one more, batched by
     * {@code @BatchSize} on {@code ShelfLevelJpaEntity.bins}.
     *
     * <p>Only {@code levels} is fetch-joined. Joining {@code levels.bins} as well would fetch two
     * nested lists at once, which Hibernate refuses ({@code MultipleBagFetchException}); either way,
     * the number of queries to load a shelf does not grow with its bins.</p>
     */
    @Query("""
            select s from ShelfJpaEntity s
            left join fetch s.levels
            where s.id = :id
            """)
    Optional<ShelfJpaEntity> findByIdWithLevels(@Param("id") UUID id);
}
