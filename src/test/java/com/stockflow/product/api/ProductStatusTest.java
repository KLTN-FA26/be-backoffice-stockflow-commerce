package com.stockflow.product.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.stockflow.product.api.ProductStatus.APPROVED;
import static com.stockflow.product.api.ProductStatus.DISCONTINUED;
import static com.stockflow.product.api.ProductStatus.DRAFT;
import static com.stockflow.product.api.ProductStatus.INACTIVE;
import static com.stockflow.product.api.ProductStatus.PENDING_APPROVAL;
import static com.stockflow.product.api.ProductStatus.PUBLISHED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole transition table, every ordered pair of the six statuses (36 cases), written out so that adding
 * a status or an edge fails here until the table is revisited (kltn-docs 01 §5.1, SCRUM-461).
 */
class ProductStatusTest {

    private static final Map<ProductStatus, Set<ProductStatus>> ALLOWED = new EnumMap<>(ProductStatus.class);

    static {
        ALLOWED.put(DRAFT, EnumSet.of(PENDING_APPROVAL));
        ALLOWED.put(PENDING_APPROVAL, EnumSet.of(APPROVED, DRAFT));
        ALLOWED.put(APPROVED, EnumSet.of(PUBLISHED, DISCONTINUED, INACTIVE));
        ALLOWED.put(PUBLISHED, EnumSet.of(APPROVED, DISCONTINUED, INACTIVE));
        ALLOWED.put(INACTIVE, EnumSet.of(APPROVED, DISCONTINUED));
        ALLOWED.put(DISCONTINUED, EnumSet.noneOf(ProductStatus.class));
    }

    @Test
    @DisplayName("there are six statuses, and the table below covers each")
    void sixStatuses() {
        assertThat(ProductStatus.values()).containsExactlyInAnyOrder(
                DRAFT, PENDING_APPROVAL, APPROVED, PUBLISHED, INACTIVE, DISCONTINUED);
        assertThat(ALLOWED.keySet()).containsExactlyInAnyOrder(ProductStatus.values());
    }

    @Test
    @DisplayName("canTransitionTo answers exactly the table, for all 36 pairs")
    void everyPair() {
        for (ProductStatus from : ProductStatus.values()) {
            for (ProductStatus to : ProductStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    @DisplayName("nothing leaves DISCONTINUED, and no status moves to itself")
    void terminalAndNoSelfLoops() {
        for (ProductStatus status : ProductStatus.values()) {
            assertThat(status.canTransitionTo(status)).as("%s -> itself", status).isFalse();
            assertThat(DISCONTINUED.canTransitionTo(status)).isFalse();
        }
    }

    @Test
    @DisplayName("INACTIVE comes from APPROVED or PUBLISHED only, and leaves to APPROVED or DISCONTINUED")
    void inactiveEdges() {
        for (ProductStatus from : ProductStatus.values()) {
            assertThat(from.canTransitionTo(INACTIVE)).isEqualTo(from == APPROVED || from == PUBLISHED);
        }
        assertThat(INACTIVE.canTransitionTo(PUBLISHED)).as("resume goes to APPROVED, publishing is the catalog's").isFalse();
    }
}
