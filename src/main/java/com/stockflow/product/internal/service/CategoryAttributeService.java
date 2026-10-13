package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.product.internal.domain.AttributeDataType;
import com.stockflow.product.internal.domain.AttributeRole;
import com.stockflow.product.internal.entity.AttributeJpaEntity;
import com.stockflow.product.internal.entity.CategoryAttributeJpaEntity;
import com.stockflow.product.internal.repository.AttributeJpaRepository;
import com.stockflow.product.internal.repository.CategoryAttributeJpaRepository;
import com.stockflow.product.internal.repository.CategoryJpaRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The attributes a category's products are expected to carry, and in which role by default.
 *
 * <p>The set is a suggestion that pre-fills the product form; it is deliberately not enforced on
 * products (spec D9), so a product may carry attributes the category does not list. Nothing here
 * therefore needs to look at products.</p>
 *
 * <p>{@link #replace} swaps the whole set, but a row whose attribute stays is updated in place rather
 * than deleted and re-inserted. Hibernate flushes INSERTs before DELETEs, so a delete-then-insert of
 * the same (category, attribute) pair would hit {@code uk_category_attributes_pair} before the old row
 * went away. Updating keeps the row id stable as well. Rows that leave the set are deleted and flushed
 * before any new row is inserted, which keeps the ordering independent of that flush rule.</p>
 */
@Service
@Transactional
public class CategoryAttributeService {

    private final CategoryJpaRepository categories;
    private final AttributeJpaRepository attributes;
    private final CategoryAttributeJpaRepository rows;
    private final EntityManager entityManager;

    public CategoryAttributeService(CategoryJpaRepository categories, AttributeJpaRepository attributes,
                                    CategoryAttributeJpaRepository rows, EntityManager entityManager) {
        this.categories = categories;
        this.attributes = attributes;
        this.rows = rows;
        this.entityManager = entityManager;
    }

    public record CategoryAttributeEntry(UUID attributeId, AttributeRole defaultRole, boolean required, int sortOrder) {
    }

    public record CategoryAttributeView(UUID attributeId, String attributeCode, String attributeName,
                                        AttributeDataType dataType, AttributeRole defaultRole, boolean required,
                                        int sortOrder) {
    }

    @Transactional(readOnly = true)
    public List<CategoryAttributeView> list(UUID categoryId) {
        requireCategory(categoryId);
        return views(rows.findByCategoryId(categoryId));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product-category-attributes", resourceId = "#categoryId")
    public List<CategoryAttributeView> replace(UUID categoryId, List<CategoryAttributeEntry> entries) {
        requireCategory(categoryId);
        Set<UUID> requested = new HashSet<>();
        for (CategoryAttributeEntry entry : entries) {
            if (!requested.add(entry.attributeId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Attribute " + entry.attributeId() + " is listed more than once");
            }
        }
        Map<UUID, AttributeJpaEntity> found = attributes.findAllById(requested).stream()
                .collect(Collectors.toMap(AttributeJpaEntity::getId, Function.identity()));
        Map<UUID, CategoryAttributeJpaEntity> existing = rows.findByCategoryId(categoryId).stream()
                .collect(Collectors.toMap(CategoryAttributeJpaEntity::getAttributeId, Function.identity()));

        for (CategoryAttributeEntry entry : entries) {
            AttributeJpaEntity attribute = found.get(entry.attributeId());
            if (attribute == null) {
                throw new BusinessException(ErrorCode.ATTRIBUTE_NOT_FOUND, "No attribute " + entry.attributeId());
            }
            if (entry.defaultRole() == AttributeRole.VARIANT_AXIS && !attribute.getDataType().canBeVariantAxis()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Attribute " + attribute.getCode() + " is of type " + attribute.getDataType()
                                + " and cannot be a variant axis");
            }
            // An attribute retired after it was added may stay; a retired one may not be picked afresh.
            if (!attribute.isActive() && !existing.containsKey(entry.attributeId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Attribute " + attribute.getCode() + " is inactive and cannot be added");
            }
        }

        List<CategoryAttributeJpaEntity> removed = existing.values().stream()
                .filter(row -> !requested.contains(row.getAttributeId())).toList();
        if (!removed.isEmpty()) {
            rows.deleteAll(removed);
            entityManager.flush();
        }
        List<CategoryAttributeJpaEntity> added = new ArrayList<>();
        for (CategoryAttributeEntry entry : entries) {
            CategoryAttributeJpaEntity row = existing.get(entry.attributeId());
            if (row == null) {
                row = new CategoryAttributeJpaEntity(Identifiers.newId(), categoryId, entry.attributeId());
                added.add(row);
            }
            row.apply(entry.defaultRole(), entry.required(), entry.sortOrder());
        }
        rows.saveAll(added);
        entityManager.flush();
        return views(rows.findByCategoryId(categoryId));
    }

    private void requireCategory(UUID categoryId) {
        if (!categories.existsById(categoryId)) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "No category " + categoryId);
        }
    }

    /** The attributes of all rows come from one {@code findAllById}, not one query per row. */
    private List<CategoryAttributeView> views(List<CategoryAttributeJpaEntity> set) {
        Map<UUID, AttributeJpaEntity> byId = attributes
                .findAllById(set.stream().map(CategoryAttributeJpaEntity::getAttributeId).toList()).stream()
                .collect(Collectors.toMap(AttributeJpaEntity::getId, Function.identity()));
        return set.stream()
                .map(row -> {
                    AttributeJpaEntity a = byId.get(row.getAttributeId());
                    return new CategoryAttributeView(a.getId(), a.getCode(), a.getName(), a.getDataType(),
                            row.getDefaultRole(), row.isRequired(), row.getSortOrder());
                })
                .sorted(Comparator.comparingInt(CategoryAttributeView::sortOrder)
                        .thenComparing(CategoryAttributeView::attributeCode))
                .toList();
    }
}
