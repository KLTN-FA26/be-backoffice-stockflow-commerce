package com.stockflow.order;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-02 r6, owner question 1: the WF-05 demo seed ({@code V20261222000500}) holds quotes of 5 cartons whose only price
 * break starts at 20, accepted at 1,200 a piece. The MOQ is a rule for writing a quote, so reading these must not answer
 * {@code QUOTE_BELOW_MOQ}: the staff list, unfiltered, and the quote itself show what the quote holds.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.provider=smtp", "stockflow.notification.email.customer.enabled=false"})
class Wf05SeedQuoteViewIntegrationTest {

    /** WF5-H: 5 cartons of 1,000 cups, one break from 20 cartons at 1,200, accepted at 1,200. */
    private static final UUID WF5_H = DemoData.demoId("demo:wf05:quote:WF5-H");
    private static final UUID MOC = DemoData.demoId("demo:wf05:customer:MOC");

    @Autowired Quotes quotes;
    @MockitoBean JavaMailSenderImpl mail;

    private final CurrentUser manager = new CurrentUser(UUID.randomUUID(), "manager", Set.of(Role.SALES_MANAGER),
            Set.of(), DataScope.ALL, Set.of(), Set.of());

    @Test
    @DisplayName("a seeded quote below its break opens at the price it holds")
    void aSeededQuoteBelowItsBreakOpens() {
        Quotes.QuoteView quote = quotes.get(manager, WF5_H);

        Quotes.LineView line = quote.lines().getFirst();
        assertThat(line.cartons()).isEqualTo(5);
        assertThat(line.moq()).isEqualTo(20);
        assertThat(line.proposedUnitPrice()).isEqualByComparingTo("1200");
        assertThat(line.proposedLineTotal()).isEqualByComparingTo("6000000");
        assertThat(line.acceptedUnitPrice()).isEqualByComparingTo("1200");
        assertThat(line.acceptedLineTotal()).isEqualByComparingTo("6000000");
    }

    @Test
    @DisplayName("the staff list, unfiltered, lists the seeded quotes below their break")
    void theUnfilteredStaffListLists() {
        PageResponse<Quotes.Row> all = quotes.list(manager, new Quotes.Criteria(null, null, null, 0, 100, null));
        assertThat(all.items()).isNotEmpty();

        PageResponse<Quotes.Row> page = quotes.list(manager, new Quotes.Criteria(MOC, null, null, 0, 100, null));
        Quotes.Row row = page.items().stream().filter(r -> r.id().equals(WF5_H)).findFirst().orElseThrow();
        assertThat(row.total()).isPositive();
    }
}
