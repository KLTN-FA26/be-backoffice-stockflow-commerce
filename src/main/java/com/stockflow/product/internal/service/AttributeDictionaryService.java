package com.stockflow.product.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.persistence.Specs;
import com.stockflow.product.internal.domain.Attribute;
import com.stockflow.product.internal.domain.AttributeDataType;
import com.stockflow.product.internal.domain.AttributeDetails;
import com.stockflow.product.internal.domain.AttributeRepository;
import com.stockflow.product.internal.domain.AttributeValue;
import com.stockflow.product.internal.domain.AttributeValueDetails;
import com.stockflow.product.internal.entity.AttributeJpaEntity;
import com.stockflow.product.internal.repository.AttributeJpaRepository;
import com.stockflow.product.internal.repository.AttributePersistenceMapper;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The attribute dictionary: attributes and the values a SELECT, MULTISELECT or COLOR attribute allows
 * (WBS 3.1.2). Writes go through the {@link Attribute} aggregate and its port, so the value rules
 * live in one place; the list reads entities directly, as {@link ProductTaxonomyService} does, and
 * loads the values of the whole page in one statement.
 *
 * <p>Every view is built from the aggregate, the list's included: its rows are mapped with the same
 * mapper the repository uses, so there is one path from storage to a view. Hard deletes take the
 * version the caller saw, and an attribute or value a product still uses is refused rather than
 * deleted, since deactivating is how one is retired.</p>
 */
@Service
@Transactional
public class AttributeDictionaryService {

    private static final SortWhitelist SORT = SortWhitelist.of("sortOrder", "code", "name", "createdAt")
            .withDefault("sortOrder", Sort.Direction.ASC);

    private final AttributeRepository attributes;
    private final AttributeJpaRepository rows;

    public AttributeDictionaryService(AttributeRepository attributes, AttributeJpaRepository rows) {
        this.attributes = attributes;
        this.rows = rows;
    }

    public record AttributeView(UUID id, String code, String name, AttributeDataType dataType, String unit,
                                boolean filterable, boolean searchable, int sortOrder, boolean active, long version,
                                List<AttributeValueView> values) {
    }

    public record AttributeValueView(UUID id, String code, String label, BigDecimal valueNumber, String hexColor,
                                     int sortOrder, boolean active, long version) {
    }

    @Transactional(readOnly = true)
    public PageResponse<AttributeView> list(String search, AttributeDataType dataType, Boolean active,
                                            int page, int size, String sort) {
        Specification<AttributeJpaEntity> spec = Specification
                .<AttributeJpaEntity>where(Specs.<AttributeJpaEntity>contains("code", search)
                        .or(Specs.contains("name", search)))
                .and(Specs.eq("dataType", dataType))
                .and(Specs.eq("active", active));
        var found = rows.findAll(spec, Pages.of(page, size, SORT.parse(sort)));
        List<UUID> ids = found.getContent().stream().map(AttributeJpaEntity::getId).toList();
        Map<UUID, Attribute> loaded = ids.isEmpty() ? Map.of() : rows.findWithValuesByIdIn(ids).stream()
                .map(AttributePersistenceMapper::toDomain)
                .collect(Collectors.toMap(Attribute::id, Function.identity()));
        return Pages.toResponse(found.map(row -> view(loaded.get(row.getId()))));
    }

    @Transactional(readOnly = true)
    public AttributeView get(UUID attributeId) {
        return view(require(attributeId));
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "product-attribute", resourceId = "#result?.id()")
    public AttributeView create(String code, AttributeDataType dataType, AttributeDetails details) {
        String normalised = Attribute.normaliseCode(code);
        if (attributes.existsByCode(normalised)) {
            throw new BusinessException(ErrorCode.ATTRIBUTE_CODE_ALREADY_EXISTS, "Attribute " + normalised + " exists");
        }
        return view(attributes.save(Attribute.create(Identifiers.newId(), normalised, dataType, details)));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product-attribute", resourceId = "#attributeId")
    public AttributeView update(UUID attributeId, long version, AttributeDetails details, boolean active) {
        Attribute attribute = require(attributeId);
        attribute.update(version, details, active);
        return view(attributes.save(attribute));
    }

    /** Values go with the attribute (cascade); a product or category that uses it blocks the delete. */
    @Auditable(action = AuditAction.DELETE, resourceType = "product-attribute", resourceId = "#attributeId")
    public void delete(UUID attributeId, long version) {
        Attribute attribute = require(attributeId);
        attribute.requireVersion(version);
        if (attributes.isInUse(attributeId)) {
            throw new BusinessException(ErrorCode.ATTRIBUTE_IN_USE, "Attribute " + attribute.code() + " is in use");
        }
        attributes.delete(attribute);
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "product-attribute", resourceId = "#attributeId")
    public AttributeView addValue(UUID attributeId, String code, AttributeValueDetails details) {
        Attribute attribute = require(attributeId);
        attribute.addValue(Identifiers.newId(), code, details);
        return view(attributes.save(attribute));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product-attribute", resourceId = "#attributeId")
    public AttributeView updateValue(UUID attributeId, UUID valueId, long version, AttributeValueDetails details,
                                     boolean active) {
        Attribute attribute = require(attributeId);
        attribute.updateValue(valueId, version, details, active);
        return view(attributes.save(attribute));
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "product-attribute", resourceId = "#attributeId")
    public AttributeView deleteValue(UUID attributeId, UUID valueId, long version) {
        Attribute attribute = require(attributeId);
        attribute.value(valueId);
        if (attributes.isValueInUse(valueId)) {
            throw new BusinessException(ErrorCode.ATTRIBUTE_IN_USE, "Value " + valueId + " is in use");
        }
        attribute.removeValue(valueId, version);
        return view(attributes.save(attribute));
    }

    private Attribute require(UUID attributeId) {
        return attributes.findById(attributeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTRIBUTE_NOT_FOUND, "No attribute " + attributeId));
    }

    private static AttributeView view(Attribute a) {
        AttributeDetails d = a.details();
        return new AttributeView(a.id(), a.code(), d.name(), a.dataType(), d.unit(), d.filterable(), d.searchable(),
                d.sortOrder(), a.active(), a.version(),
                a.values().stream().map(AttributeDictionaryService::view).toList());
    }

    private static AttributeValueView view(AttributeValue v) {
        AttributeValueDetails d = v.details();
        return new AttributeValueView(v.id(), v.code(), d.label(), d.valueNumber(), d.hexColor(), d.sortOrder(),
                v.active(), v.version());
    }
}
