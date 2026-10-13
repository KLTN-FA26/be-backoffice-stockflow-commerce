package com.stockflow.product.internal.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One axis along which a product's variants differ, with the options on offer. Owned by {@link ProductAttributeSet}. */
public final class ProductAxis {

    private final UUID id;
    private final UUID attributeId;
    private final int sortOrder;
    private final List<AxisOption> options;

    /** Rehydration from storage; new axes are made by {@link ProductAttributeSet#replaceAxes}. */
    public ProductAxis(UUID id, UUID attributeId, int sortOrder, List<AxisOption> options) {
        this.id = Objects.requireNonNull(id, "id");
        this.attributeId = Objects.requireNonNull(attributeId, "attributeId");
        this.sortOrder = sortOrder;
        this.options = new ArrayList<>(options);
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

    public List<AxisOption> options() {
        return Collections.unmodifiableList(options);
    }
}
