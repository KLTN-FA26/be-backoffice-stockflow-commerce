package com.stockflow.product.internal.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A non-variant attribute of a product with its value(s). Owned by {@link ProductAttributeSet}. */
public final class DescriptiveAttribute {

    private final UUID id;
    private final UUID attributeId;
    private final int sortOrder;
    private final List<DescriptiveValue> values;

    /** Rehydration from storage; new ones are made by {@link ProductAttributeSet#replaceDescriptives}. */
    public DescriptiveAttribute(UUID id, UUID attributeId, int sortOrder, List<DescriptiveValue> values) {
        this.id = Objects.requireNonNull(id, "id");
        this.attributeId = Objects.requireNonNull(attributeId, "attributeId");
        this.sortOrder = sortOrder;
        this.values = new ArrayList<>(values);
    }

    public UUID id() {
        return id;
    }

    public UUID attributeId() {
        return attributeId;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public List<DescriptiveValue> values() {
        return Collections.unmodifiableList(values);
    }
}
