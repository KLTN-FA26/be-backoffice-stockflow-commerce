package com.stockflow.product.internal.repository;

import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.internal.domain.Attribute;
import com.stockflow.product.internal.domain.AttributeRepository;
import com.stockflow.product.internal.entity.AttributeJpaEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the {@link AttributeRepository} port. An attribute is loaded with its values in a single
 * fetch-join statement.
 */
@Repository
class AttributeRepositoryAdapter implements AttributeRepository {

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final AttributeJpaRepository jpa;

    AttributeRepositoryAdapter(AttributeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Attribute> findById(UUID id) {
        return jpa.findWithValuesById(id).map(AttributePersistenceMapper::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return jpa.existsById(id);
    }

    @Override
    public boolean existsByCode(String code) {
        return jpa.existsByCode(code);
    }

    @Override
    public boolean isInUse(UUID attributeId) {
        return jpa.isReferenced(attributeId);
    }

    @Override
    public boolean isValueInUse(UUID valueId) {
        return jpa.isValueReferenced(valueId);
    }

    /**
     * Merges onto the managed row (values initialised by the fetch join, so appends go to a loaded
     * list) or persists a new tree, then flushes so a unique violation surfaces here, where it can be
     * named. The result is built from the flushed rows, so versions are current.
     */
    @Override
    public Attribute save(Attribute attribute) {
        AttributeJpaEntity row = jpa.findWithValuesById(attribute.id()).orElse(null);
        if (row != null) {
            AttributePersistenceMapper.merge(attribute, row);
        } else {
            row = jpa.save(AttributePersistenceMapper.newEntity(attribute));
        }
        try {
            jpa.flush();
        } catch (DataIntegrityViolationException ex) {
            if (ConstraintViolations.isViolationOf(ex, "uk_attributes_code")
                    || ConstraintViolations.isViolationOf(ex, "uk_attribute_values_code")) {
                throw new ConflictException(ErrorCode.ATTRIBUTE_CODE_ALREADY_EXISTS,
                        "Attribute %s: code already exists".formatted(attribute.code()));
            }
            throw ex;
        }
        return AttributePersistenceMapper.toDomain(row);
    }

    /**
     * Cascades to the value rows. A reference from a product or category is a foreign-key violation
     * (RESTRICT); the service checks {@link #isInUse} first, this is the net for a reference added in
     * between.
     */
    @Override
    public void delete(Attribute attribute) {
        jpa.findById(attribute.id()).ifPresent(jpa::delete);
        try {
            jpa.flush();
        } catch (DataIntegrityViolationException ex) {
            if (isForeignKeyViolation(ex)) {
                throw new ConflictException(ErrorCode.ATTRIBUTE_IN_USE,
                        "Attribute %s is still referenced".formatted(attribute.code()));
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
