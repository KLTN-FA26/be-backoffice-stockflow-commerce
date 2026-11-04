package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.PageResponse;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.api.ListCustomersQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A customer named as people name one: never a guess, and the choice shown only to whoever may list customers.
 */
class CustomerMatchTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final CustomerService customers = mock(CustomerService.class);

    private static CustomerSummary customer(String name, String taxCode) {
        UUID id = UUID.randomUUID();
        return new CustomerSummary(id, null, name, "mua-hang-" + id + "@example.vn", "0903123456", taxCode, null,
                "ACTIVE", 0, Instant.now());
    }

    private void found(CustomerSummary... rows) {
        found(rows.length, rows);
    }

    private void found(long total, CustomerSummary... rows) {
        when(customers.list(any())).thenReturn(PageResponse.of(List.of(rows), 0, CustomerMatch.SEARCHED, total));
    }

    @Test
    @DisplayName("the customer called exactly that wins over the others the text is part of, case and blanks aside")
    void exactNameWins() {
        CustomerSummary abc = customer("Công ty ABC", null);
        found(customer("Công ty ABC Hà Nội", null), abc, customer("ABC Bao Bì", null));

        CustomerMatch match = CustomerMatch.find(customers, "  công ty  abc ", true);

        assertThat(match.found()).isTrue();
        assertThat(match.customerId()).isEqualTo(abc.customerId());
        assertThat(match.name()).isEqualTo("Công ty ABC");
        assertThat(match.status()).isEqualTo("ACTIVE");
        assertThat(match.note()).isNull();
    }

    @Test
    @DisplayName("a tax code typed with blanks or dots finds its customer, for anyone; it is never repeated")
    void taxCodeTyped() {
        CustomerSummary abc = customer("Công ty ABC", "0312345678");
        found(abc);

        CustomerMatch match = CustomerMatch.find(customers, "0312 345.678", false);

        assertThat(match.customerId()).isEqualTo(abc.customerId());
        assertThat(match.toString()).doesNotContain("0312345678");
        assertThat(CustomerMatch.taxCodeOf("0312345678001")).isEqualTo("0312345678-001");
        assertThat(CustomerMatch.taxCodeOf("0312345678-001")).isEqualTo("0312345678-001");
        assertThat(CustomerMatch.taxCodeOf("079 123 456 789")).isEqualTo("079123456789");
        assertThat(CustomerMatch.taxCodeOf("ABC")).isNull();
        assertThat(CustomerMatch.taxCodeOf("12345")).isNull();
    }

    @Test
    @DisplayName("one customer matching in part is that customer, for a caller who may list customers")
    void singleMatch() {
        CustomerSummary abc = customer("Công ty TNHH In Ly ABC", null);
        found(abc);

        assertThat(CustomerMatch.find(customers, "In Ly ABC", true).customerId()).isEqualTo(abc.customerId());
    }

    @Test
    @DisplayName("several matches come back as candidates by name and status, never with an e-mail, phone or tax code")
    void severalMatches() throws Exception {
        CustomerSummary first = customer("Công ty ABC Hà Nội", "0312345678");
        CustomerSummary second = customer("Công ty ABC Sài Gòn", "0312345679");
        found(first, second);

        CustomerMatch match = CustomerMatch.find(customers, "ABC", true);

        assertThat(match.found()).isFalse();
        assertThat(match.candidates()).extracting(CustomerMatch.Candidate::customerName)
                .containsExactly("Công ty ABC Hà Nội", "Công ty ABC Sài Gòn");
        assertThat(match.note()).contains("Several customers match 'ABC'", "2 in all", "ask which one");
        String sent = JSON.writeValueAsString(match.candidates()) + match.note();
        assertThat(sent).doesNotContain("0312345678", "0312345679", "0903123456", "@example.vn",
                first.customerId().toString());
    }

    @Test
    @DisplayName("at most five candidates, and the note says how many matched in all")
    void cappedCandidates() {
        CustomerSummary[] rows = IntStream.range(0, 7).mapToObj(i -> customer("Công ty ABC " + i, null))
                .toArray(CustomerSummary[]::new);
        found(12, rows);

        CustomerMatch match = CustomerMatch.find(customers, "ABC", true);

        assertThat(match.candidates()).hasSize(CustomerMatch.MAX_CANDIDATES);
        assertThat(match.note()).contains("12 in all", "the first 5 by name");
    }

    @Test
    @DisplayName("a caller who may not list customers is told to name one exactly, and shown no names")
    void exactOnlyWithoutTheCustomerList() {
        found(customer("Công ty ABC Hà Nội", null), customer("Công ty ABC Sài Gòn", null));

        CustomerMatch fragment = CustomerMatch.find(customers, "ABC", false);

        assertThat(fragment.found()).isFalse();
        assertThat(fragment.candidates()).isEmpty();
        assertThat(fragment.note()).contains("full name exactly as registered", "tax code")
                .doesNotContain("Hà Nội", "Sài Gòn");

        // Even a single partial match is not chosen: it would say which customer the fragment belongs to.
        found(customer("Công ty ABC Hà Nội", null));
        assertThat(CustomerMatch.find(customers, "ABC", false).found()).isFalse();

        CustomerSummary exact = customer("Công ty ABC Sài Gòn", null);
        found(customer("Công ty ABC Sài Gòn 2", null), exact);
        assertThat(CustomerMatch.find(customers, "Công ty ABC Sài Gòn", false).customerId())
                .isEqualTo(exact.customerId());
    }

    @Test
    @DisplayName("a customer found only through its e-mail or phone is not identified to a caller who may not list")
    void contactMatchesStayHidden() {
        // The customer list also matches e-mails and phone numbers by part: a phone typed in finds this row.
        found(customer("Công ty ABC", null));

        CustomerMatch match = CustomerMatch.find(customers, "0903123456", false);

        assertThat(match.found()).isFalse();
        assertThat(match.note()).doesNotContain("Công ty ABC");
    }

    @Test
    @DisplayName("two customers registered under the same name: the tax code is asked for, no guess")
    void sameNameTwice() {
        found(customer("Công ty ABC", "0312345678"), customer("Công ty ABC", "0312345679"));

        CustomerMatch match = CustomerMatch.find(customers, "Công ty ABC", true);

        assertThat(match.found()).isFalse();
        assertThat(match.candidates()).isEmpty();
        assertThat(match.note()).contains("Several customers are registered under exactly the name", "tax code");
    }

    @Test
    @DisplayName("nothing found says so")
    void noMatch() {
        found();

        CustomerMatch match = CustomerMatch.find(customers, "XYZ", true);

        assertThat(match.found()).isFalse();
        assertThat(match.candidates()).isEmpty();
        assertThat(match.note()).isEqualTo("No customer matches 'XYZ'.");
    }

    @Test
    @DisplayName("the customer list is searched with the text as typed, by name, wide enough to find the exact one")
    void theSearch() {
        found();
        ArgumentCaptor<ListCustomersQuery> query = ArgumentCaptor.forClass(ListCustomersQuery.class);

        CustomerMatch.find(customers, " Công ty ABC ", true);

        verify(customers).list(query.capture());
        assertThat(query.getValue().search()).isEqualTo("Công ty ABC");
        assertThat(query.getValue().page()).isZero();
        assertThat(query.getValue().size()).isEqualTo(CustomerMatch.SEARCHED);
        assertThat(query.getValue().status()).as("every status: a blocked customer still has prices and debts")
                .isNull();
        assertThat(query.getValue().sort()).isEqualTo("fullName");
    }
}
