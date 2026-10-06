package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A named, coloured group of shelves on the map - for display and zone picking only; it carries no
 * storage rule (docs module 06 §1). Belongs to one warehouse for good ({@code tg_zone_immutable}).
 *
 * <p>The colour is stored upper-case because {@code ck_zone_color} accepts only {@code #[0-9A-F]};
 * a colour picker sending {@code #22c55e} is normalised here rather than refused by the database.</p>
 */
public final class Zone extends AggregateRoot {

    static final int NAME_MAX_LENGTH = 100;
    private static final Pattern COLOR = Pattern.compile("#[0-9A-F]{6}");

    private final UUID id;
    private final UUID warehouseId;
    private String name;
    private String color;
    private final long version;

    /** Rehydration from storage. New zones go through {@link #create}. */
    public Zone(UUID id, UUID warehouseId, String name, String color, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.name = name;
        this.color = color;
        this.version = version;
    }

    public static Zone create(UUID id, UUID warehouseId, String name, String color) {
        return new Zone(id, warehouseId, DomainChecks.requiredText("name", name, NAME_MAX_LENGTH),
                normaliseColor(color), 0L);
    }

    /** @param expectedVersion the version the caller's edit was based on */
    public void update(String name, String color, long expectedVersion) {
        if (expectedVersion != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Zone %s changed meanwhile (version %d, edit based on %d)"
                            .formatted(id, version, expectedVersion));
        }
        this.name = DomainChecks.requiredText("name", name, NAME_MAX_LENGTH);
        this.color = normaliseColor(color);
    }

    private static String normaliseColor(String color) {
        if (color == null || color.isBlank()) {
            return null;
        }
        String upper = color.strip().toUpperCase(Locale.ROOT);
        if (!COLOR.matcher(upper).matches()) {
            throw DomainChecks.invalid("A zone colour is #RRGGBB, got '" + color + "'");
        }
        return upper;
    }

    public UUID id() { return id; }
    public UUID warehouseId() { return warehouseId; }
    public String name() { return name; }
    public String color() { return color; }
    public long version() { return version; }
}
