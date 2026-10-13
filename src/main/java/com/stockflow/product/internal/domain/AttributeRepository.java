package com.stockflow.product.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.UUID;

/** Persistence port for {@link Attribute}. Lookups by id come from {@link AggregateRepository}. */
public interface AttributeRepository extends AggregateRepository<Attribute, UUID> {

    boolean existsByCode(String code);

    /** Whether a product or category refers to the attribute; a used attribute cannot be deleted. */
    boolean isInUse(UUID attributeId);

    /** Whether a variant or product refers to the value; a used value cannot be removed. */
    boolean isValueInUse(UUID valueId);

    void delete(Attribute attribute);
}
