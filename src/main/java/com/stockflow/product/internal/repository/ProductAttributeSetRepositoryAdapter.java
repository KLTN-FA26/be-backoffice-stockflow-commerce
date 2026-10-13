package com.stockflow.product.internal.repository;

import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.ProductAttributeSet;
import com.stockflow.product.internal.domain.ProductAttributeSetRepository;
import com.stockflow.product.internal.entity.ProductAttributeJpaEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Implements the {@link ProductAttributeSetRepository} port. Loading is two statements (options,
 * then descriptive values) because two bags cannot be fetch-joined at once; both land on the same
 * managed rows, so the mapper sees them fully initialised.
 *
 * <p>Saving is in two flushed phases (see {@link ProductAttributeSetPersistenceMapper}): Hibernate
 * runs inserts before deletes, so a set that swaps {A,B} for {B,C} would otherwise insert C while
 * A's row still holds the unique key. Both flushes go through the Spring Data repository so a
 * database exception arrives translated.</p>
 */
@Repository
class ProductAttributeSetRepositoryAdapter implements ProductAttributeSetRepository {

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final ProductAttributeJpaRepository jpa;

    ProductAttributeSetRepositoryAdapter(ProductAttributeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ProductAttributeSet load(UUID productId, ProductStatus status) {
        return ProductAttributeSetPersistenceMapper.toDomain(productId, status, rows(productId));
    }

    @Override
    public ProductAttributeSet save(ProductAttributeSet set) {
        List<ProductAttributeJpaEntity> rows = rows(set.productId());
        List<ProductAttributeJpaEntity> gone = ProductAttributeSetPersistenceMapper.removeDropped(set, rows);
        gone.forEach(jpa::delete);
        flush();
        List<ProductAttributeJpaEntity> kept = rows.stream().filter(r -> !gone.contains(r)).toList();
        ProductAttributeSetPersistenceMapper.applyKept(set, kept, jpa);
        flush();
        return load(set.productId(), set.status());
    }

    @Override
    public Set<UUID> valueIdsUsedByVariants(UUID productId) {
        return jpa.valueIdsUsedByVariants(productId);
    }

    private List<ProductAttributeJpaEntity> rows(UUID productId) {
        List<ProductAttributeJpaEntity> rows = jpa.findWithOptionsByProductId(productId);
        jpa.findWithDescriptiveValuesByProductId(productId);
        return rows;
    }

    /**
     * A foreign-key violation here is an option a variant started using after the service checked
     * (the variant row references the option, RESTRICT); the service's check is the ordinary path,
     * this is the net for the race.
     */
    private void flush() {
        try {
            jpa.flush();
        } catch (DataIntegrityViolationException ex) {
            if (isForeignKeyViolation(ex)) {
                throw new ConflictException(ErrorCode.ATTRIBUTE_IN_USE,
                        "A variant of this product uses a value that was removed");
            }
            throw ex;
        }
    }

    private static boolean isForeignKeyViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
