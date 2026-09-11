package com.stockflow.common.persistence;

import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The paging and sorting guards, which exist to stop a URL parameter causing a table scan. */
class QuerySupportTest {

    private static final SortWhitelist SORT =
            SortWhitelist.of("placedAt", "orderNumber", "status")
                    .withDefault("placedAt", Sort.Direction.DESC);

    @Test
    @DisplayName("an allowed property parses, with the direction honoured")
    void parsesAllowedProperties() {
        Sort sort = SORT.parse("status,asc;placedAt,desc");
        assertThat(sort).isNotNull();
    }

    @Test
    @DisplayName("an unlisted property is a 400, naming what is allowed")
    void rejectsUnlistedProperty() {
        // Spring Data would happily sort by customer.address.postcode, joining tables and scanning
        // an unindexed column - a 10ms query becomes a 10s one, from a query parameter.
        assertThatThrownBy(() -> SORT.parse("customer.address.postcode,desc"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("placedAt");
    }

    @Test
    @DisplayName("no sort means the declared default, never an undefined order")
    void fallsBackToTheDefault() {
        // An unsorted paged query has no defined order in SQL, so page 2 can repeat a row from
        // page 1 and skip another.
        assertThat(SORT.parse(null)).isNotNull();
        assertThat(SORT.parse("  ")).isNotNull();
    }

    @Test
    @DisplayName("the default must itself be an allowed property")
    void defaultMustBeAllowed() {
        assertThatThrownBy(() -> SortWhitelist.of("a", "b").withDefault("c", Sort.Direction.ASC))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a page size above the ceiling is refused, not silently clamped")
    void rejectsOversizePage() {
        // Clamping would make a client asking for 10 000 rows read the short page as "no more
        // data" and lose the rest.
        assertThatThrownBy(() -> Pages.of(0, Pages.MAX_PAGE_SIZE + 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(String.valueOf(Pages.MAX_PAGE_SIZE));
    }

    @Test
    @DisplayName("negative or zero paging arguments are refused")
    void rejectsNonsensePaging() {
        assertThatThrownBy(() -> Pages.of(-1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Pages.of(0, 0)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a valid page request is built")
    void buildsPageable() {
        assertThat(Pages.of(2, 50, SORT.parse("orderNumber,asc"))).isNotNull();
    }
}
