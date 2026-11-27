package com.stockflow.assistant.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the question context the model resolves periods and access against, word for word: other parts of
 * the assistant (the tool descriptions, the system prompt, the golden set) refer to these sentences.
 */
class AssistantServiceContextTest {

    @Test
    @DisplayName("the calendar names today and the time, yesterday, this week and month up to today, and last week "
            + "(Monday to Sunday) and last month whole")
    void calendar() {
        assertThat(AssistantService.calendar(Instant.parse("2026-10-28T07:05:00Z")))
                .isEqualTo(" Calendar (Vietnam time): today Wednesday 2026-10-28, now 14:05; yesterday 2026-10-27; "
                        + "this week 2026-10-26 to 2026-10-28 so far; last week 2026-10-19 to 2026-10-25; "
                        + "this month 2026-10-01 to 2026-10-28 so far; last month 2026-09-01 to 2026-09-30.");
    }

    @Test
    @DisplayName("the Vietnam date decides: at 01:30 on 1 November there it is still 31 October in UTC")
    void vietnameseDay() {
        assertThat(AssistantService.calendar(Instant.parse("2026-10-31T18:30:00Z")))
                .isEqualTo(" Calendar (Vietnam time): today Sunday 2026-11-01, now 01:30; yesterday 2026-10-31; "
                        + "this week 2026-10-26 to 2026-11-01 so far; last week 2026-10-19 to 2026-10-25; "
                        + "this month 2026-11-01 to 2026-11-01 so far; last month 2026-10-01 to 2026-10-31.");
    }

    @Test
    @DisplayName("a Monday starts its own week; New Year's Day's last month is December of the year before")
    void boundaries() {
        assertThat(AssistantService.calendar(Instant.parse("2026-10-26T01:00:00Z")))
                .contains("today Monday 2026-10-26", "this week 2026-10-26 to 2026-10-26 so far",
                        "last week 2026-10-19 to 2026-10-25");
        assertThat(AssistantService.calendar(Instant.parse("2027-01-01T03:00:00Z")))
                .contains("today Friday 2027-01-01", "yesterday 2026-12-31",
                        "this week 2026-12-28 to 2027-01-01 so far", "this month 2027-01-01 to 2027-01-01 so far",
                        "last month 2026-12-01 to 2026-12-31");
        assertThat(AssistantService.calendar(Instant.parse("2028-02-15T05:00:00Z")))
                .contains("this month 2028-02-01 to 2028-02-15 so far", "last month 2028-01-01 to 2028-01-31");
    }

    @Test
    @DisplayName("the tools a user may use, and nothing about warehouses for a user who is not limited to some")
    void tools() {
        assertThat(AssistantService.access(List.of("findOrder", "searchCatalog", "searchKnowledge"),
                user(DataScope.ALL, Set.of())))
                .isEqualTo(" Tools this user may use: findOrder, searchCatalog, searchKnowledge. Every other tool "
                        + "refuses them.");
        assertThat(AssistantService.access(List.of(), user(DataScope.ALL, Set.of())))
                .isEqualTo(" Tools this user may use: none. Every tool refuses them.");
    }

    @Test
    @DisplayName("a user limited to warehouses is told which, and that nothing company-wide about stock reaches them")
    void warehouseLimit() {
        assertThat(AssistantService.access(List.of("getStockLevels"), user(DataScope.WAREHOUSE, Set.of("HN", "HCM"))))
                .isEqualTo(" Tools this user may use: getStockLevels. Every other tool refuses them. They are "
                        + "limited to warehouses HCM, HN: they see no other warehouse and no company-wide stock "
                        + "figure.");
        assertThat(AssistantService.access(List.of("getStockLevels"), user(DataScope.WAREHOUSE, Set.of())))
                .endsWith(" They are limited to warehouses but assigned to none: they see no warehouse and no "
                        + "company-wide stock figure.");
    }

    private static CurrentUser user(DataScope scope, Set<String> warehouses) {
        return new CurrentUser(UUID.randomUUID(), "tester", Set.of(), Set.of(), scope, warehouses);
    }
}
