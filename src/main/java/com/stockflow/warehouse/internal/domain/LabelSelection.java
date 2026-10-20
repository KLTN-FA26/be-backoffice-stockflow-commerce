package com.stockflow.warehouse.internal.domain;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which locations of one warehouse a label print covers (SCRUM-97). Exactly one of four shapes, so
 * "a shelf and a zone at once" cannot be expressed.
 */
public sealed interface LabelSelection {

    /** Every bin and storage area of the warehouse. */
    record WholeWarehouse() implements LabelSelection {
    }

    /** The bins of one shelf; of only some of its levels when {@code levelIndexes} is not empty. */
    record OfShelf(UUID shelfId, Set<Integer> levelIndexes) implements LabelSelection {

        public OfShelf {
            Objects.requireNonNull(shelfId, "shelfId");
            levelIndexes = levelIndexes == null ? Set.of() : Set.copyOf(levelIndexes);
        }
    }

    /** The bins of a zone's shelves. A storage area belongs to no zone, so none is included. */
    record OfZone(UUID zoneId) implements LabelSelection {

        public OfZone {
            Objects.requireNonNull(zoneId, "zoneId");
        }
    }

    /**
     * Named locations, matched as a scanner would: stripped and upper-cased, so {@code hcm-a01-1-a} and
     * {@code HCM-A01-1-A} are one label, not two.
     */
    record OfCodes(Set<String> codes) implements LabelSelection {

        public OfCodes {
            codes = normalise(Objects.requireNonNull(codes, "codes"));
        }

        private static Set<String> normalise(Collection<String> raw) {
            return raw.stream().map(code -> code.strip().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
