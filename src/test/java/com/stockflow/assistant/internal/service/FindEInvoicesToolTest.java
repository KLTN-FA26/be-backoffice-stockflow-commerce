package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.invoicing.api.EInvoiceListing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * findEInvoices: the accountant's e-invoice list - the screen's permission, its queue (still to issue, overdue), the
 * filters it is asked with, and rows that name the buyer as an alias and keep its tax code and address in the system.
 */
class FindEInvoicesToolTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final UUID BUYER = UUID.randomUUID();

    private static final CurrentUser ACCOUNTANT = staff(Role.ACCOUNTANT, "einvoices:READ", "customer-customers:READ");

    private final EInvoiceListing invoices = mock(EInvoiceListing.class);
    private final CurrentUserProvider currentUsers = mock(CurrentUserProvider.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final FindEInvoicesTool tool = new FindEInvoicesTool(invoices, currentUsers, customers,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("the screen's guard: einvoices:READ at the screen's scope, mirroring its list, the same for every "
            + "warehouse")
    void guard() throws Exception {
        RequiresPermission guard = FindEInvoicesTool.class.getMethod("run", ToolArguments.class)
                .getAnnotation(RequiresPermission.class);

        assertThat(guard.resource()).isEqualTo("einvoices").isEqualTo(ToolPermissions.EINVOICES);
        assertThat(guard.action()).isEqualTo(Action.READ);
        assertThat(guard.scope()).isEqualTo(DataScope.ALL);
        assertThat(tool.mirrors()).isEqualTo("GET /api/v1/einvoices");
        assertThat(tool.scopeLimit()).isEqualTo(ScopeLimit.NONE);
    }

    @Test
    @DisplayName("a portal account, which holds einvoices:READ for its own invoices, is refused here as on the screen")
    void backOfficeOnly() {
        as(new CurrentUser(UUID.randomUUID(), "customer", Set.of(Role.CUSTOMER),
                Set.of(PermissionCode.parse("einvoices:READ")), DataScope.OWN, Set.of()));

        assertThatThrownBy(() -> tool.run(arguments("ANY", "", "", "", "", 5)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(invoices);
    }

    @Test
    @DisplayName("TO_ISSUE is drafts, sent and refused invoices; OVERDUE the same past their deadline at the call's "
            + "instant; ANY every status; one status itself; the order number and the dates are passed on")
    void filters() {
        as(ACCOUNTANT);
        listed(0);

        tool.run(arguments("TO_ISSUE", "", "", "", "", 20));
        tool.run(arguments("OVERDUE", "", "", "", "", 20));
        tool.run(arguments("ANY", " SO-20261020-000042 ", "", "2026-11-01", "2026-11-30", 10));
        tool.run(arguments("REJECTED", "", "", "", "", 10));

        ArgumentCaptor<EInvoiceListing.Filter> filters = ArgumentCaptor.forClass(EInvoiceListing.Filter.class);
        verify(invoices, org.mockito.Mockito.times(4)).einvoices(filters.capture(), eq(NOW), anyInt());
        List<EInvoiceListing.Filter> asked = filters.getAllValues();
        assertThat(asked.get(0).statuses()).containsExactlyElementsOf(EInvoiceListing.TO_ISSUE_STATUSES);
        assertThat(asked.get(0).overdueAt()).isNull();
        assertThat(asked.get(1).statuses()).containsExactlyElementsOf(EInvoiceListing.TO_ISSUE_STATUSES);
        assertThat(asked.get(1).overdueAt()).isEqualTo(NOW);
        assertThat(asked.get(2).statuses()).isEmpty();
        assertThat(asked.get(2).orderNumber()).isEqualTo("SO-20261020-000042");
        assertThat(asked.get(2).issuedFrom()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(asked.get(2).issuedTo()).isEqualTo(LocalDate.parse("2026-11-30"));
        assertThat(asked.get(3).statuses()).containsExactly("REJECTED");
        verifyNoInteractions(customers);
    }

    @Test
    @DisplayName("every status of the api is offered after the three queues; anything else, or a malformed day, is "
            + "refused before a lookup")
    void offered() {
        as(ACCOUNTANT);
        assertThat(FindEInvoicesTool.STATUSES).containsExactly("TO_ISSUE", "OVERDUE", "ANY", "DRAFT", "SUBMITTING",
                "ISSUED", "REJECTED", "VOIDED_DRAFT", "REPLACED");
        assertThatThrownBy(() -> tool.run(arguments("CANCELLED", "", "", "", "", 5)))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("status must be one of");
        assertThatThrownBy(() -> tool.run(arguments("ANY", "", "", "16/11/2026", "", 5)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("YYYY-MM-DD");
        verifyNoInteractions(invoices);
    }

    @Test
    @DisplayName("a row: number and series once issued, the delivery it invoices, the buyer as a customer name, the "
            + "overdue flag as the list computed it; no id")
    void rows() throws Exception {
        as(ACCOUNTANT);
        listed(2, invoice("ISSUED", "1", "C26TSB", 42, false), invoice("DRAFT", null, null, null, true));

        FindEInvoicesTool.Answer answer = tool.run(arguments("ANY", "", "", "", "", 20));

        FindEInvoicesTool.Row issued = answer.einvoices().getFirst();
        assertThat(issued.series()).isEqualTo("1C26TSB");
        assertThat(issued.invoiceNumber()).isEqualTo(42);
        assertThat(issued.shipmentCode()).isEqualTo("SHP-0001");
        assertThat(issued.buyer()).isEqualTo(new CustomerName(BUYER, "Công ty ABC"));
        assertThat(issued.grandTotal()).isEqualByComparingTo("11000000");
        assertThat(issued.overdue()).isFalse();
        FindEInvoicesTool.Row draft = answer.einvoices().get(1);
        assertThat(draft.series()).isNull();
        assertThat(draft.invoiceNumber()).isNull();
        assertThat(draft.overdue()).isTrue();
        assertThat(answer.total()).isEqualTo(2);
        assertThat(answer.truncated()).isFalse();
        assertThat(JSON.writeValueAsString(answer)).as("the buyer is its name, never an id")
                .doesNotContain(BUYER.toString());
    }

    @Test
    @DisplayName("more invoices than returned is said: total counts them all")
    void truncated() {
        as(ACCOUNTANT);
        listed(73, invoice("DRAFT", null, null, null, false));

        FindEInvoicesTool.Answer answer = tool.run(arguments("TO_ISSUE", "", "", "", "", 1));

        assertThat(answer.total()).isEqualTo(73);
        assertThat(answer.truncated()).isTrue();
    }

    @Test
    @DisplayName("a customer named exactly filters by it; one the text does not name exactly returns candidates, no "
            + "invoices and no count")
    void byCustomer() {
        as(ACCOUNTANT);
        when(customers.list(any())).thenReturn(PageResponse.of(List.of(summary(BUYER, "Công ty ABC")), 0, 50, 1));
        listed(1, invoice("ISSUED", "1", "C26TSB", 42, false));

        FindEInvoicesTool.Answer answer = tool.run(arguments("ANY", "", "công ty abc", "", "", 20));

        ArgumentCaptor<EInvoiceListing.Filter> filter = ArgumentCaptor.forClass(EInvoiceListing.Filter.class);
        verify(invoices).einvoices(filter.capture(), eq(NOW), eq(20));
        assertThat(filter.getValue().customerId()).isEqualTo(BUYER);
        assertThat(answer.customer().name()).isEqualTo("Công ty ABC");

        when(customers.list(any())).thenReturn(PageResponse.of(List.of(summary(UUID.randomUUID(),
                "Công ty ABC Hà Nội"), summary(UUID.randomUUID(), "Công ty ABC Sài Gòn")), 0, 50, 2));
        FindEInvoicesTool.Answer vague = tool.run(arguments("ANY", "", "ABC", "", "", 20));
        assertThat(vague.einvoices()).isEmpty();
        assertThat(vague.candidates()).hasSize(2);
        assertThat(vague.total()).as("nothing was counted, so no 0 to read as none").isNull();
        assertThat(vague.truncated()).isNull();
        verify(invoices).einvoices(any(), any(), anyInt());
    }

    @Test
    @DisplayName("a caller who may not list customers is offered no candidates for a name typed in part")
    void noCandidatesWithoutTheCustomerList() {
        as(staff(Role.SALES_MANAGER, "einvoices:READ"));
        when(customers.list(any())).thenReturn(PageResponse.of(List.of(summary(UUID.randomUUID(),
                "Công ty ABC Hà Nội"), summary(UUID.randomUUID(), "Công ty ABC Sài Gòn")), 0, 50, 2));

        FindEInvoicesTool.Answer answer = tool.run(arguments("ANY", "", "ABC", "", "", 20));

        assertThat(answer.candidates()).isEmpty();
        assertThat(answer.note()).contains("may not search the customer list");
        verify(invoices, never()).einvoices(any(), any(), anyInt());
    }

    @Test
    @DisplayName("the most invoices one call returns, with long buyer names and every field set, stay well under "
            + "the result limit; more than that is refused")
    void maxRowsFit() {
        as(ACCOUNTANT);
        String longName = "Công ty TNHH Sản xuất Thương mại và Dịch vụ Bao bì Giấy Kraft Miền Nam ".repeat(3)
                .substring(0, 200);
        EInvoiceListing.EInvoiceRow[] rows = IntStream.range(0, FindEInvoicesTool.MAX_ROWS)
                .mapToObj(i -> new EInvoiceListing.EInvoiceRow("EINV-20261116-%06d".formatted(i), "ADJUSTMENT",
                        "REJECTED", true, "SO-20261020-%06d".formatted(i), "SHP-20261116-%06d-01".formatted(i),
                        "HD-20261020-%04d".formatted(i), UUID.randomUUID(), longName, LocalDate.parse("2026-11-16"),
                        NOW, true, "1", "C26TSB", 123_456, "VND", new BigDecimal("111111110.1000"),
                        new BigDecimal("12345678.9000"), new BigDecimal("123456789.0000"), "INVALID_TAX_CODE_123",
                        NOW))
                .toArray(EInvoiceListing.EInvoiceRow[]::new);
        listed(200, rows);
        AssistantTools tools = new AssistantTools(List.of(tool), JsonMapper.builder()
                .addModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL).build());

        ToolExecution execution = tools.execute(new ToolCall("toolu_1", "findEInvoices",
                "{\"status\":\"ANY\",\"orderNumber\":\"\",\"customer\":\"\",\"issuedFrom\":\"\",\"issuedTo\":\"\","
                        + "\"limit\":" + FindEInvoicesTool.MAX_ROWS + "}"));

        assertThat(execution.outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(execution.result()).contains("+07:00");
        assertThat(execution.result().length())
                .as("well under the limit above which the model gets RESULT_TOO_LARGE and no invoice at all")
                .isLessThan(ToolExecution.MAX_RESULT_LENGTH * 9 / 10);
        assertThatThrownBy(() -> tool.run(arguments("ANY", "", "", "", "", FindEInvoicesTool.MAX_ROWS + 1)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("the description tells the model the queues, the overdue rule and how to read a cut list")
    void description() {
        assertThat(tool.spec().description()).contains("TO_ISSUE", "OVERDUE", "overdue", "series", "buyer",
                "truncated", "no invoice was found");
    }

    // ------------------------------------------------------------------------------------------------------------

    private static ToolArguments arguments(String status, String orderNumber, String customer, String from,
                                           String to, int limit) {
        return ToolArguments.parse(JSON, "{\"status\":\"" + status + "\",\"orderNumber\":\"" + orderNumber
                + "\",\"customer\":\"" + customer + "\",\"issuedFrom\":\"" + from + "\",\"issuedTo\":\"" + to
                + "\",\"limit\":" + limit + "}");
    }

    private void listed(long total, EInvoiceListing.EInvoiceRow... rows) {
        when(invoices.einvoices(any(), any(), anyInt())).thenReturn(PageResponse.of(List.of(rows), 0, 25, total));
    }

    /** An invoice of 11,000,000 VND with VAT for a delivered shipment of SO-20261020-000042. */
    private static EInvoiceListing.EInvoiceRow invoice(String status, String template, String symbol, Integer number,
                                                       boolean overdue) {
        return new EInvoiceListing.EInvoiceRow("EINV-0001", "ORIGINAL", status, false, "SO-20261020-000042",
                "SHP-0001", "HD-0042", BUYER, "Công ty ABC", LocalDate.parse("2026-11-15"),
                Instant.parse("2026-11-16T16:59:59Z"), overdue, template, symbol, number, "VND",
                new BigDecimal("10000000"), new BigDecimal("1000000"), new BigDecimal("11000000"), null,
                number == null ? null : NOW);
    }

    private static CustomerSummary summary(UUID id, String name) {
        return new CustomerSummary(id, null, name, null, null, "0101234567", null, "ACTIVE", 0, Instant.now());
    }

    private void as(CurrentUser user) {
        when(currentUsers.current()).thenReturn(Optional.of(user));
    }

    private static CurrentUser staff(Role role, String... permissions) {
        return new CurrentUser(UUID.randomUUID(), role.name().toLowerCase(java.util.Locale.ROOT), Set.of(role),
                Arrays.stream(permissions).map(PermissionCode::parse).collect(Collectors.toSet()), DataScope.ALL,
                Set.of());
    }
}
