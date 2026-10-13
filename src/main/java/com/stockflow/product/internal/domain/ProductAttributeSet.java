package com.stockflow.product.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.ProductStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What a product is described by: the axes its variants differ along (with the options offered on
 * each) and the descriptive attributes that do not differ. One aggregate because every rule here
 * spans both lists: an attribute may appear once per product and in one role only.
 *
 * <p>The rules are stated a second time here, mirroring the triggers in
 * {@code V20260928001300__pim_integrity.sql}, so the caller gets a reason instead of a trigger
 * exception: {@code check_product_attribute} (an axis attribute must be SELECT or COLOR, a role is
 * fixed per attribute), {@code check_product_attribute_option} (an option's value belongs to the
 * axis's attribute, an axis needs an option) and {@code check_descriptive_value} (a value's shape
 * follows the attribute's type).</p>
 *
 * <p>Axes freeze when the product leaves DRAFT (spec D3): once there is live stock, a new axis
 * would leave every existing variant without a value on it, and a removed axis would orphan the
 * variants' combinations. Adding an option, reordering and editing descriptive attributes stay open
 * while the product accepts changes at all, which is the rule {@code Product.acceptsChanges()}
 * states and which is restated from the status here so this aggregate needs no {@code Product}.</p>
 *
 * <p>Whether a variant uses an option is not known here; the caller passes the used value ids.
 * Entries that survive a replacement keep their ids, so persistence can update rows in place
 * instead of deleting and re-inserting them.</p>
 */
public final class ProductAttributeSet extends AggregateRoot {

    private final UUID productId;
    private final ProductStatus status;
    private final List<ProductAxis> axes;
    private final List<DescriptiveAttribute> descriptives;

    public ProductAttributeSet(UUID productId, ProductStatus status, List<ProductAxis> axes,
                               List<DescriptiveAttribute> descriptives) {
        this.productId = Objects.requireNonNull(productId, "productId");
        this.status = Objects.requireNonNull(status, "status");
        this.axes = new ArrayList<>(axes);
        this.axes.sort(Comparator.comparingInt(ProductAxis::sortOrder));
        this.descriptives = new ArrayList<>(descriptives);
        this.descriptives.sort(Comparator.comparingInt(DescriptiveAttribute::sortOrder));
    }

    /**
     * Makes the axes exactly {@code specs}. Adding or removing an axis needs a DRAFT product; options
     * and order may change while the product accepts changes. Nothing is changed if any rule fails.
     *
     * @param valueIdsUsedByVariants values some variant of this product carries; their options must stay
     */
    public void replaceAxes(List<AxisSpec> specs, Map<UUID, Attribute> dictionary,
                            Set<UUID> valueIdsUsedByVariants, Supplier<UUID> newId) {
        requireAcceptsChanges();
        Map<UUID, ProductAxis> existing = new LinkedHashMap<>();
        axes.forEach(a -> existing.put(a.attributeId(), a));
        Set<UUID> seen = new HashSet<>();
        List<ProductAxis> result = new ArrayList<>();
        for (AxisSpec spec : specs) {
            Attribute attribute = attribute(dictionary, spec.attributeId());
            if (!seen.add(attribute.id())) {
                throw invalid("Attribute %s appears twice among the axes".formatted(attribute.code()));
            }
            if (descriptives.stream().anyMatch(d -> d.attributeId().equals(attribute.id()))) {
                throw invalid("Attribute %s is a descriptive attribute of this product; remove it from there first"
                        .formatted(attribute.code()));
            }
            if (!attribute.dataType().canBeVariantAxis()) {
                throw invalid("A %s attribute (%s) cannot be a variant axis"
                        .formatted(attribute.dataType(), attribute.code()));
            }
            ProductAxis before = existing.get(attribute.id());
            if (before == null && !attribute.active()) {
                throw invalid("Attribute %s is inactive and cannot be newly added".formatted(attribute.code()));
            }
            if (spec.valueIds().isEmpty()) {
                throw invalid("Axis %s needs at least one option".formatted(attribute.code()));
            }
            Map<UUID, AxisOption> keptOptions = new LinkedHashMap<>();
            if (before != null) {
                before.options().forEach(o -> keptOptions.put(o.valueId(), o));
            }
            Set<UUID> seenValues = new HashSet<>();
            List<AxisOption> options = new ArrayList<>();
            for (UUID valueId : spec.valueIds()) {
                if (!seenValues.add(valueId)) {
                    throw invalid("Axis %s lists the same value twice".formatted(attribute.code()));
                }
                AxisOption kept = keptOptions.get(valueId);
                if (kept == null) {
                    requireNewValue(attribute, valueId);
                }
                options.add(kept != null ? kept : new AxisOption(newId.get(), valueId));
            }
            result.add(new ProductAxis(before != null ? before.id() : newId.get(), attribute.id(),
                    spec.sortOrder(), options));
        }
        if (!seen.equals(existing.keySet())) {
            requireDraft();
        }
        for (ProductAxis before : axes) {
            Set<UUID> kept = result.stream().filter(a -> a.attributeId().equals(before.attributeId()))
                    .flatMap(a -> a.options().stream()).map(AxisOption::valueId).collect(java.util.stream.Collectors.toSet());
            for (AxisOption option : before.options()) {
                if (!kept.contains(option.valueId()) && valueIdsUsedByVariants.contains(option.valueId())) {
                    throw new BusinessException(ErrorCode.ATTRIBUTE_IN_USE,
                            "A variant of this product uses value %s; it cannot be removed".formatted(option.valueId()));
                }
            }
        }
        axes.clear();
        axes.addAll(result);
        axes.sort(Comparator.comparingInt(ProductAxis::sortOrder));
    }

    /** Makes the descriptive attributes exactly {@code specs}. Nothing is changed if any rule fails. */
    public void replaceDescriptives(List<DescriptiveSpec> specs, Map<UUID, Attribute> dictionary,
                                    Supplier<UUID> newId) {
        requireAcceptsChanges();
        Map<UUID, DescriptiveAttribute> existing = new LinkedHashMap<>();
        descriptives.forEach(d -> existing.put(d.attributeId(), d));
        Set<UUID> seen = new HashSet<>();
        List<DescriptiveAttribute> result = new ArrayList<>();
        for (DescriptiveSpec spec : specs) {
            Attribute attribute = attribute(dictionary, spec.attributeId());
            if (!seen.add(attribute.id())) {
                throw invalid("Attribute %s appears twice among the descriptive attributes".formatted(attribute.code()));
            }
            if (axes.stream().anyMatch(a -> a.attributeId().equals(attribute.id()))) {
                throw invalid("Attribute %s is a variant axis of this product; remove it from the axes first"
                        .formatted(attribute.code()));
            }
            DescriptiveAttribute before = existing.get(attribute.id());
            if (before == null && !attribute.active()) {
                throw invalid("Attribute %s is inactive and cannot be newly added".formatted(attribute.code()));
            }
            result.add(new DescriptiveAttribute(before != null ? before.id() : newId.get(), attribute.id(),
                    spec.sortOrder(), values(spec, attribute, before, newId)));
        }
        descriptives.clear();
        descriptives.addAll(result);
        descriptives.sort(Comparator.comparingInt(DescriptiveAttribute::sortOrder));
    }

    private List<DescriptiveValue> values(DescriptiveSpec spec, Attribute attribute, DescriptiveAttribute before,
                                          Supplier<UUID> newId) {
        String code = attribute.code();
        boolean hasDictionaryValues = !spec.valueIds().isEmpty();
        List<DescriptiveValue> previous = before == null ? List.of() : before.values();
        switch (attribute.dataType()) {
            case SELECT, COLOR, MULTISELECT -> {
                if (spec.text() != null || spec.number() != null || spec.bool() != null) {
                    throw invalid("Attribute %s takes dictionary values only".formatted(code));
                }
                boolean single = attribute.dataType() != AttributeDataType.MULTISELECT;
                if (spec.valueIds().isEmpty() || single && spec.valueIds().size() != 1) {
                    throw invalid(single
                            ? "Attribute %s takes exactly one value".formatted(code)
                            : "Attribute %s takes at least one value".formatted(code));
                }
                if (new HashSet<>(spec.valueIds()).size() != spec.valueIds().size()) {
                    throw invalid("Attribute %s lists the same value twice".formatted(code));
                }
                Map<UUID, DescriptiveValue> kept = new LinkedHashMap<>();
                previous.forEach(v -> kept.put(v.valueId(), v));
                List<DescriptiveValue> values = new ArrayList<>();
                for (UUID valueId : spec.valueIds()) {
                    DescriptiveValue same = kept.get(valueId);
                    if (same == null) {
                        requireNewValue(attribute, valueId);
                    }
                    UUID rowId = same != null ? same.id()
                            : single && !previous.isEmpty() ? previous.getFirst().id() : newId.get();
                    values.add(new DescriptiveValue(rowId, valueId, null, null, null));
                }
                return values;
            }
            case TEXT -> {
                requireOnly(code, hasDictionaryValues, spec.number() != null, spec.bool() != null);
                if (spec.text() == null || spec.text().isBlank()) {
                    throw invalid("Attribute %s needs a text".formatted(code));
                }
                return List.of(new DescriptiveValue(rowId(previous, newId), null, spec.text().strip(), null, null));
            }
            case NUMBER -> {
                requireOnly(code, hasDictionaryValues, spec.text() != null, spec.bool() != null);
                BigDecimal number = spec.number();
                if (number == null) {
                    throw invalid("Attribute %s needs a number".formatted(code));
                }
                return List.of(new DescriptiveValue(rowId(previous, newId), null, null, number, null));
            }
            case BOOLEAN -> {
                requireOnly(code, hasDictionaryValues, spec.text() != null, spec.number() != null);
                if (spec.bool() == null) {
                    throw invalid("Attribute %s needs true or false".formatted(code));
                }
                return List.of(new DescriptiveValue(rowId(previous, newId), null, null, null, spec.bool()));
            }
            default -> throw new IllegalStateException("Unhandled type " + attribute.dataType());
        }
    }

    private static UUID rowId(List<DescriptiveValue> previous, Supplier<UUID> newId) {
        return previous.isEmpty() ? newId.get() : previous.getFirst().id();
    }

    private static void requireOnly(String code, boolean... foreign) {
        for (boolean present : foreign) {
            if (present) {
                throw invalid("Attribute %s does not take that kind of value".formatted(code));
            }
        }
    }

    /** A value being added must exist on the attribute and be active; one that is kept is not asked. */
    private static void requireNewValue(Attribute attribute, UUID valueId) {
        AttributeValue value = attribute.values().stream().filter(v -> v.id().equals(valueId)).findFirst()
                .orElseThrow(() -> invalid("Value %s does not belong to attribute %s".formatted(valueId, attribute.code())));
        if (!value.active()) {
            throw invalid("Value %s of attribute %s is inactive and cannot be newly added"
                    .formatted(value.code(), attribute.code()));
        }
    }

    private static Attribute attribute(Map<UUID, Attribute> dictionary, UUID attributeId) {
        Attribute attribute = dictionary.get(attributeId);
        if (attribute == null) {
            throw new BusinessException(ErrorCode.ATTRIBUTE_NOT_FOUND, "Attribute %s not found".formatted(attributeId));
        }
        return attribute;
    }

    private void requireAcceptsChanges() {
        if (status == ProductStatus.PENDING_APPROVAL || status == ProductStatus.DISCONTINUED) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION,
                    "Product %s is %s and accepts no change".formatted(productId, status));
        }
    }

    private void requireDraft() {
        if (status != ProductStatus.DRAFT) {
            throw new BusinessException(ErrorCode.PRODUCT_AXES_LOCKED,
                    "Product %s is %s; its axes can no longer be added or removed".formatted(productId, status));
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    public UUID productId() {
        return productId;
    }

    public ProductStatus status() {
        return status;
    }

    public List<ProductAxis> axes() {
        return Collections.unmodifiableList(axes);
    }

    public List<DescriptiveAttribute> descriptives() {
        return Collections.unmodifiableList(descriptives);
    }
}
