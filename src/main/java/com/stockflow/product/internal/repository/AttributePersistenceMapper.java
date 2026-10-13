package com.stockflow.product.internal.repository;

import com.stockflow.product.internal.domain.Attribute;
import com.stockflow.product.internal.domain.AttributeDetails;
import com.stockflow.product.internal.domain.AttributeValue;
import com.stockflow.product.internal.domain.AttributeValueDetails;
import com.stockflow.product.internal.entity.AttributeJpaEntity;
import com.stockflow.product.internal.entity.AttributeValueJpaEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Attribute aggregate to its rows and back.
 *
 * <p>{@link #merge} works child by child on the managed collection rather than replacing it: an
 * unchanged value row then issues no UPDATE (and keeps its version), a new one is appended so the
 * cascade inserts it, and one missing from the aggregate is removed from the collection so
 * orphan removal deletes it.</p>
 */
public final class AttributePersistenceMapper {

    private AttributePersistenceMapper() {
    }

    /** Through the rehydration constructor, so a stored row that breaks a rule fails on load. */
    public static Attribute toDomain(AttributeJpaEntity row) {
        return new Attribute(row.getId(), row.getCode(), row.getDataType(),
                new AttributeDetails(row.getName(), row.getUnit(), row.isFilterable(), row.isSearchable(),
                        row.getSortOrder()),
                row.isActive(), row.getVersion(),
                row.getValues().stream().map(AttributePersistenceMapper::toDomain).toList());
    }

    private static AttributeValue toDomain(AttributeValueJpaEntity row) {
        return new AttributeValue(row.getId(), row.getCode(),
                new AttributeValueDetails(row.getLabel(), row.getValueNumber(), row.getHexColor(),
                        row.getSortOrder()),
                row.isActive(), row.getVersion());
    }

    static AttributeJpaEntity newEntity(Attribute attribute) {
        AttributeJpaEntity row = new AttributeJpaEntity(attribute.id(), attribute.code(), attribute.dataType());
        applyAttribute(attribute, row);
        attribute.values().forEach(value -> row.addValue(newEntity(value)));
        return row;
    }

    /** @param row the managed attribute row with its values initialised */
    static void merge(Attribute attribute, AttributeJpaEntity row) {
        applyAttribute(attribute, row);
        Set<UUID> kept = attribute.values().stream().map(AttributeValue::id).collect(Collectors.toSet());
        row.getValues().removeIf(value -> !kept.contains(value.getId()));

        Map<UUID, AttributeValueJpaEntity> rows = new HashMap<>();
        row.getValues().forEach(value -> rows.put(value.getId(), value));
        for (AttributeValue value : attribute.values()) {
            AttributeValueJpaEntity valueRow = rows.get(value.id());
            if (valueRow == null) {
                row.addValue(newEntity(value));
            } else {
                applyValue(value, valueRow);
            }
        }
    }

    private static AttributeValueJpaEntity newEntity(AttributeValue value) {
        AttributeValueJpaEntity row = new AttributeValueJpaEntity(value.id(), value.code());
        applyValue(value, row);
        return row;
    }

    private static void applyAttribute(Attribute attribute, AttributeJpaEntity row) {
        AttributeDetails d = attribute.details();
        row.apply(d.name(), d.unit(), d.filterable(), d.searchable(), d.sortOrder(), attribute.active());
    }

    private static void applyValue(AttributeValue value, AttributeValueJpaEntity row) {
        AttributeValueDetails d = value.details();
        row.apply(d.label(), d.valueNumber(), d.hexColor(), d.sortOrder(), value.active());
    }
}
