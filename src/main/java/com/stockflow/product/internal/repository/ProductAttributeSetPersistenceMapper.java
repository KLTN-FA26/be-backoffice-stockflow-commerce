package com.stockflow.product.internal.repository;

import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.AttributeRole;
import com.stockflow.product.internal.domain.AxisOption;
import com.stockflow.product.internal.domain.DescriptiveAttribute;
import com.stockflow.product.internal.domain.DescriptiveValue;
import com.stockflow.product.internal.domain.ProductAttributeSet;
import com.stockflow.product.internal.domain.ProductAxis;
import com.stockflow.product.internal.entity.ProductAttributeJpaEntity;
import com.stockflow.product.internal.entity.ProductAttributeOptionJpaEntity;
import com.stockflow.product.internal.entity.ProductDescriptiveValueJpaEntity;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A product's attribute set to its rows and back.
 *
 * <p>Saving is two phases because Hibernate flushes inserts, then updates, then deletes, whatever
 * order the code touched them in. A removal and an addition that share a unique key (an option's
 * product attribute and value, a SELECT descriptive's single row) would therefore insert before the
 * old row is gone. {@link #removeDropped} runs first and is flushed on its own; {@link #applyKept}
 * then updates rows in place and appends new ones. Rows that did not change are not touched at all,
 * so they keep their version.</p>
 */
final class ProductAttributeSetPersistenceMapper {

    private ProductAttributeSetPersistenceMapper() {
    }

    /**
     * @param rows the product's attribute rows with both collections initialised
     */
    static ProductAttributeSet toDomain(UUID productId, ProductStatus status, List<ProductAttributeJpaEntity> rows) {
        List<ProductAttributeJpaEntity> ordered = rows.stream()
                .sorted(Comparator.comparing(ProductAttributeJpaEntity::getId)).toList();
        List<ProductAxis> axes = ordered.stream().filter(r -> r.getRole() == AttributeRole.VARIANT_AXIS)
                .map(ProductAttributeSetPersistenceMapper::axis).toList();
        List<DescriptiveAttribute> descriptives = ordered.stream().filter(r -> r.getRole() == AttributeRole.DESCRIPTIVE)
                .map(ProductAttributeSetPersistenceMapper::descriptive).toList();
        return new ProductAttributeSet(productId, status, axes, descriptives);
    }

    private static ProductAxis axis(ProductAttributeJpaEntity row) {
        List<AxisOption> options = row.getOptions().stream()
                .sorted(Comparator.comparing(ProductAttributeOptionJpaEntity::getId))
                .map(o -> new AxisOption(o.getId(), o.getAttributeValueId())).toList();
        return new ProductAxis(row.getId(), row.getAttributeId(), row.getSortOrder(), options);
    }

    private static DescriptiveAttribute descriptive(ProductAttributeJpaEntity row) {
        List<DescriptiveValue> values = row.getDescriptiveValues().stream()
                .sorted(Comparator.comparing(ProductDescriptiveValueJpaEntity::getId))
                .map(v -> new DescriptiveValue(v.getId(), v.getAttributeValueId(), v.getValueText(),
                        v.getValueNumber(), v.getValueBoolean())).toList();
        return new DescriptiveAttribute(row.getId(), row.getAttributeId(), row.getSortOrder(), values);
    }

    /**
     * Phase one: drops every row the aggregate no longer has.
     *
     * @return the product attribute rows that are gone entirely, for the caller to delete
     */
    static List<ProductAttributeJpaEntity> removeDropped(ProductAttributeSet set, List<ProductAttributeJpaEntity> rows) {
        Map<UUID, ProductAxis> axes = set.axes().stream().collect(Collectors.toMap(ProductAxis::id, a -> a));
        Map<UUID, DescriptiveAttribute> descriptives = set.descriptives().stream()
                .collect(Collectors.toMap(DescriptiveAttribute::id, d -> d));
        List<ProductAttributeJpaEntity> gone = rows.stream()
                .filter(r -> !axes.containsKey(r.getId()) && !descriptives.containsKey(r.getId())).toList();
        for (ProductAttributeJpaEntity row : rows) {
            ProductAxis axis = axes.get(row.getId());
            if (axis != null) {
                Set<UUID> kept = axis.options().stream().map(AxisOption::id).collect(Collectors.toSet());
                row.getOptions().removeIf(o -> !kept.contains(o.getId()));
            }
            DescriptiveAttribute descriptive = descriptives.get(row.getId());
            if (descriptive != null) {
                Set<UUID> kept = descriptive.values().stream().map(DescriptiveValue::id).collect(Collectors.toSet());
                row.getDescriptiveValues().removeIf(v -> !kept.contains(v.getId()));
            }
        }
        return gone;
    }

    /**
     * Phase two: updates kept rows in place and adds the new ones. Setting a field to the value it
     * already has does not dirty the row, so an unchanged set issues no statement.
     */
    static void applyKept(ProductAttributeSet set, List<ProductAttributeJpaEntity> rows,
                          ProductAttributeJpaRepository jpa) {
        Map<UUID, ProductAttributeJpaEntity> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.getId(), r));
        for (ProductAxis axis : set.axes()) {
            ProductAttributeJpaEntity row = byId.get(axis.id());
            if (row == null) {
                row = new ProductAttributeJpaEntity(axis.id(), set.productId(), axis.attributeId(),
                        AttributeRole.VARIANT_AXIS, axis.sortOrder());
                ProductAttributeJpaEntity created = row;
                axis.options().forEach(o -> created.addOption(
                        new ProductAttributeOptionJpaEntity(o.id(), o.valueId())));
                jpa.save(row);
                continue;
            }
            if (row.getSortOrder() != axis.sortOrder()) {
                row.setSortOrder(axis.sortOrder());
            }
            Set<UUID> present = row.getOptions().stream().map(ProductAttributeOptionJpaEntity::getId)
                    .collect(Collectors.toSet());
            for (AxisOption option : axis.options()) {
                if (!present.contains(option.id())) {
                    row.addOption(new ProductAttributeOptionJpaEntity(option.id(), option.valueId()));
                }
            }
        }
        for (DescriptiveAttribute descriptive : set.descriptives()) {
            ProductAttributeJpaEntity row = byId.get(descriptive.id());
            boolean created = row == null;
            if (created) {
                row = new ProductAttributeJpaEntity(descriptive.id(), set.productId(), descriptive.attributeId(),
                        AttributeRole.DESCRIPTIVE, descriptive.sortOrder());
            } else if (row.getSortOrder() != descriptive.sortOrder()) {
                row.setSortOrder(descriptive.sortOrder());
            }
            Map<UUID, ProductDescriptiveValueJpaEntity> present = new HashMap<>();
            row.getDescriptiveValues().forEach(v -> present.put(v.getId(), v));
            for (DescriptiveValue value : descriptive.values()) {
                ProductDescriptiveValueJpaEntity valueRow = present.get(value.id());
                if (valueRow == null) {
                    valueRow = new ProductDescriptiveValueJpaEntity(value.id());
                    row.addDescriptiveValue(valueRow);
                }
                valueRow.apply(value.valueId(), value.text(), value.number(), value.bool());
            }
            if (created) {
                jpa.save(row);
            }
        }
    }
}
