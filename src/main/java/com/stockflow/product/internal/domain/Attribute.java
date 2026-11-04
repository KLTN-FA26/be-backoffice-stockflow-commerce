package com.stockflow.product.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A product attribute with its dictionary of allowed values. An aggregate, unlike a brand, because
 * the value rules depend on the attribute's type: only SELECT, MULTISELECT and COLOR hold values, a
 * COLOR value needs a hex colour and the others must not have one, and a value code is unique
 * within its attribute. Those rules need the attribute and its values together.
 *
 * <p>Code and colour formats are stated a second time here (CLAUDE.md section 7), mirroring
 * {@code ck_attributes_code}, {@code ck_attribute_values_code} and {@code ck_attribute_values_hex},
 * so the caller gets a reason instead of a constraint violation. The attribute code has no hyphen
 * because it is used as a key in expressions; a value code may have one ({@code 12OZ-HOT}).</p>
 *
 * <p>Whether an attribute or value is in use by a product or category is not known here; the
 * service asks {@link AttributeRepository} before a delete.</p>
 */
public final class Attribute extends AggregateRoot {

    private static final Pattern ATTRIBUTE_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_]{0,49}");
    private static final Pattern VALUE_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,49}");
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-F]{6}");
    private static final Comparator<AttributeValue> DISPLAY_ORDER =
            Comparator.<AttributeValue>comparingInt(v -> v.details().sortOrder()).thenComparing(AttributeValue::code);

    private final UUID id;
    private final String code;
    private final AttributeDataType dataType;
    private AttributeDetails details;
    private boolean active;
    private final long version;
    private final List<AttributeValue> values;

    /** Rehydration from storage. New attributes go through {@link #create}. */
    public Attribute(UUID id, String code, AttributeDataType dataType, AttributeDetails details,
                     boolean active, long version, List<AttributeValue> values) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = Objects.requireNonNull(code, "code");
        this.dataType = Objects.requireNonNull(dataType, "dataType");
        this.details = Objects.requireNonNull(details, "details");
        this.active = active;
        this.version = version;
        this.values = new ArrayList<>(values);
        this.values.sort(DISPLAY_ORDER);
    }

    public static Attribute create(UUID id, String code, AttributeDataType dataType, AttributeDetails details) {
        return new Attribute(id, normaliseCode(code), dataType, details, true, 0L, List.of());
    }

    /** Trims and upper-cases, then refuses anything the {@code ck_attributes_code} check would. */
    public static String normaliseCode(String raw) {
        return normalise(raw, ATTRIBUTE_CODE, "Attribute code must be A-Z, 0-9 or underscore, 1-50 characters");
    }

    /** As {@link #normaliseCode} but a hyphen is allowed, matching {@code ck_attribute_values_code}. */
    public static String normaliseValueCode(String raw) {
        return normalise(raw, VALUE_CODE, "Value code must be A-Z, 0-9, underscore or hyphen, 1-50 characters");
    }

    private static String normalise(String raw, Pattern pattern, String message) {
        String code = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!pattern.matcher(code).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message);
        }
        return code;
    }

    /** @param expectedVersion the version the caller's edit was based on */
    public void update(long expectedVersion, AttributeDetails details, boolean active) {
        requireVersion(expectedVersion);
        this.details = Objects.requireNonNull(details, "details");
        this.active = active;
    }

    /** Refuses an action based on an older read; also asked before a delete. */
    public void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Attribute %s changed meanwhile (version %d, action based on %d)"
                            .formatted(id, version, expectedVersion));
        }
    }

    public AttributeValue addValue(UUID valueId, String code, AttributeValueDetails details) {
        if (!dataType.holdsValues()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A %s attribute has no list of values".formatted(dataType));
        }
        String normalised = normaliseValueCode(code);
        requireShape(details);
        if (values.stream().anyMatch(v -> v.code().equals(normalised))) {
            throw new BusinessException(ErrorCode.ATTRIBUTE_CODE_ALREADY_EXISTS,
                    "Attribute %s already has a value %s".formatted(this.code, normalised));
        }
        var value = new AttributeValue(valueId, normalised, details, true, 0L);
        values.add(value);
        values.sort(DISPLAY_ORDER);
        return value;
    }

    public void updateValue(UUID valueId, long expectedVersion, AttributeValueDetails details, boolean active) {
        AttributeValue value = value(valueId);
        value.requireVersion(expectedVersion);
        requireShape(details);
        value.change(details, active);
        values.sort(DISPLAY_ORDER);
    }

    /** Throws unless the value exists and the caller's view of it is current; for a check ahead of a delete. */
    public void requireValueVersion(UUID valueId, long expectedVersion) {
        value(valueId).requireVersion(expectedVersion);
    }

    /**
     * Whether the value is still used by a product is the service's question; this only checks the
     * caller's view of the value is current, since removing is final.
     */
    public void removeValue(UUID valueId, long expectedVersion) {
        AttributeValue value = value(valueId);
        value.requireVersion(expectedVersion);
        values.remove(value);
    }

    public AttributeValue value(UUID valueId) {
        return values.stream().filter(v -> v.id().equals(valueId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTRIBUTE_VALUE_NOT_FOUND,
                        "Attribute %s has no value %s".formatted(code, valueId)));
    }

    private void requireShape(AttributeValueDetails details) {
        String hex = details.hexColor();
        if (dataType == AttributeDataType.COLOR && hex == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A colour value needs a hex colour");
        }
        if (dataType != AttributeDataType.COLOR && hex != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only a colour value has a hex colour");
        }
        if (hex != null && !HEX_COLOR.matcher(hex).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Hex colour must look like #RRGGBB");
        }
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public AttributeDataType dataType() {
        return dataType;
    }

    public AttributeDetails details() {
        return details;
    }

    public boolean active() {
        return active;
    }

    public long version() {
        return version;
    }

    public List<AttributeValue> values() {
        return Collections.unmodifiableList(values);
    }
}
