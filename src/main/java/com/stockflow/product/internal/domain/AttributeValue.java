package com.stockflow.product.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * One allowed value of an attribute. Owned by {@link Attribute}: its mutators are package-visible
 * so that every change goes through the aggregate, which knows the attribute's type.
 */
public final class AttributeValue {

    /** The order values are offered in: the dictionary's sort order, then the code. One definition, used by every view and by the variant matrix. */
    public static final Comparator<AttributeValue> DICTIONARY_ORDER =
            Comparator.<AttributeValue>comparingInt(v -> v.details().sortOrder()).thenComparing(AttributeValue::code);

    private final UUID id;
    private final String code;
    private AttributeValueDetails details;
    private boolean active;
    private final long version;

    /** Rehydration from storage. New values go through {@link Attribute#addValue}. */
    public AttributeValue(UUID id, String code, AttributeValueDetails details, boolean active, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = Objects.requireNonNull(code, "code");
        this.details = Objects.requireNonNull(details, "details");
        this.active = active;
        this.version = version;
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public AttributeValueDetails details() {
        return details;
    }

    public boolean active() {
        return active;
    }

    public long version() {
        return version;
    }

    void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Attribute value %s changed meanwhile (version %d, action based on %d)"
                            .formatted(id, version, expectedVersion));
        }
    }

    void change(AttributeValueDetails details, boolean active) {
        this.details = Objects.requireNonNull(details, "details");
        this.active = active;
    }
}
