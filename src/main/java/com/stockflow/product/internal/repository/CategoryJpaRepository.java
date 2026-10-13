package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.CategoryJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CategoryJpaRepository extends BaseJpaRepository<CategoryJpaEntity> {

    /**
     * Takes the row lock that serialises writers of one category's attribute set: two overlapping
     * replacements would otherwise read the same committed set and collide on the pair constraint
     * or on a row version.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CategoryJpaEntity c where c.id = :id")
    Optional<CategoryJpaEntity> lockById(@Param("id") UUID id);

    boolean existsByCode(String code);

    boolean existsBySlug(String slug);
}
