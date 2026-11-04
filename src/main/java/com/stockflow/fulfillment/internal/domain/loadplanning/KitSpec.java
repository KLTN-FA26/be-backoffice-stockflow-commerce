package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A set ("bộ") whose parts must travel together in equal numbers per vehicle: a table's top and its base, or the N
 * cartons of one multi-box unit (design v2 §3, "Kits").
 *
 * @param members part code to how many of it one set needs, e.g. {@code {"T-MB": 1, "T-CB": 2}}. Order is kept: it
 *                decides the order the parts are offered to the packer
 */
public record KitSpec(String name, Map<String, Integer> members) {

    public KitSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(members, "members");
        members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }
}
