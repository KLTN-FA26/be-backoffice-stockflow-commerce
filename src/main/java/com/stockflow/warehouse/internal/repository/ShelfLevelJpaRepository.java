package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.entity.ShelfLevelJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Read access to shelf levels, which are otherwise written only through their shelf.
 *
 * <p>A repository of its own rather than a method on {@code ShelfJpaRepository}: Spring Data treats
 * a query method whose result type is not the repository's own entity as a DTO projection and
 * rewrites the JPQL into {@code select new ShelfLevelJpaEntity(...)}. A bare {@link Repository}
 * exposes no save or delete - levels are never written from here.</p>
 */
interface ShelfLevelJpaRepository extends Repository<ShelfLevelJpaEntity, UUID> {

    /**
     * Every level of a shelf with its bins and each bin's location, in one query. Only one
     * collection ({@code bins}) is fetched - the location is a to-one - so this is not a
     * {@code MultipleBagFetchException}, and loading a shelf costs two queries whatever its number of
     * bins (with the shelf row itself).
     */
    @Query("""
            select l from ShelfLevelJpaEntity l
            left join fetch l.bins b
            left join fetch b.location
            where l.shelf.id = :shelfId
            order by l.levelIndex, b.code
            """)
    List<ShelfLevelJpaEntity> findWithBinsByShelfId(@Param("shelfId") UUID shelfId);
}
