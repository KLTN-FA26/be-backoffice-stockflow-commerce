package com.stockflow.customer.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A customer group (kltn-docs 18 §4.3 step 10: "Quản trị bán hàng gán nhóm"), the key of a group price
 * (13 §3: a customer without its own price list pays its group's price). Managed by the E-commerce Admin
 * (user decision P7).
 *
 * <p>The code is the group's identity in price rules and reports, so it is fixed once created: upper-case
 * letters, digits, {@code _} and {@code -}, at most 64. Name and description may change.
 * {@code ck_segment_code} and {@code ck_segment_name} say the same in the database (V20261015000600).</p>
 */
public final class CustomerSegment {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,63}");
    private static final int MAX_NAME = 200;
    private static final int MAX_DESCRIPTION = 1000;

    private final UUID id;
    private final String code;
    private String name;
    private String description;
    private final long version;

    private CustomerSegment(UUID id, String code, String name, String description, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = code;
        this.name = name;
        this.description = description;
        this.version = version;
    }

    /** @throws BusinessException {@code VALIDATION_FAILED} */
    public static CustomerSegment create(UUID id, String code, String name, String description) {
        return new CustomerSegment(id, code(code), name(name), description(description), 0L);
    }

    /** Rehydration from the row, as stored. */
    public static CustomerSegment restore(UUID id, String code, String name, String description, long version) {
        return new CustomerSegment(id, code, name, description, version);
    }

    /** @throws BusinessException {@code OPTIMISTIC_LOCK} for a stale editor, {@code VALIDATION_FAILED} */
    public void rename(String nextName, String nextDescription, long expectedVersion) {
        if (expectedVersion != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Customer group %s changed since it was read".formatted(code));
        }
        this.name = name(nextName);
        this.description = description(nextDescription);
    }

    /** The canonical form of a code as typed: trimmed, upper case. */
    public static String code(String raw) {
        String code = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A group code is 1-64 letters, digits, '_' or '-', starting with a letter or digit");
        }
        return code;
    }

    private static String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A group name is 1-" + MAX_NAME + " characters");
        }
        return name;
    }

    private static String description(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String description = raw.strip();
        if (description.length() > MAX_DESCRIPTION) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A group description is at most " + MAX_DESCRIPTION + " characters");
        }
        return description;
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public String description() { return description; }
    public long version() { return version; }
}
