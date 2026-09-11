package com.stockflow.common.security;

/**
 * One cell of the permission matrix: a resource plus an action, rendered as
 * {@code payroll-runs:CREATE}.
 *
 * <p>The resource is a stable code, NOT a frontend route. Routes belong in the catalog as display
 * metadata; keying permissions on them means a frontend URL refactor silently invalidates granted
 * permissions in the database.</p>
 */
public record PermissionCode(String resource, Action action) {

    public static final char SEPARATOR = ':';

    public PermissionCode {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("Permission resource must not be blank");
        }
        resource = resource.trim().toLowerCase();
        if (!resource.matches("[a-z0-9\\-]{2,64}")) {
            throw new IllegalArgumentException("Malformed permission resource: " + resource);
        }
        if (action == null) {
            throw new IllegalArgumentException("Permission action must not be null");
        }
    }

    public static PermissionCode of(String resource, Action action) {
        return new PermissionCode(resource, action);
    }

    /** Parses {@code payroll-runs:CREATE}. Unknown actions are rejected, never silently ignored. */
    public static PermissionCode parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Permission code must not be null");
        }
        int separator = raw.indexOf(SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException("Permission code must be resource:ACTION, got: " + raw);
        }
        String resource = raw.substring(0, separator);
        String action = raw.substring(separator + 1);
        try {
            return new PermissionCode(resource, Action.valueOf(action.trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown action in permission code: " + raw, ex);
        }
    }

    @Override
    public String toString() {
        return resource + SEPARATOR + action.name();
    }
}
