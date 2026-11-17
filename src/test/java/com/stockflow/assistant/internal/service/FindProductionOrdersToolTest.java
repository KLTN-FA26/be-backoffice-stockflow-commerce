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
import com.stockflow.order.api.ProductionOrderQueries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * findProductionOrders: the production orders screen as a list - the screen's permission and warehouse rule, the
 * filters it is asked with, and rows that say how late a producer is without the model comparing dates.
 */
class FindProductionOrdersToolTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    /** 16/11/2026 10:00 in Vietnam. */
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final Instant SENT = Instant.parse("2026-11-02T02:00:00Z");

    /** Reads production orders, not sales orders: the warehouse manager as seeded. */
    private static final CurrentUser WAREHOUSE = staff(Role.WAREHOUSE_MANAGER, "production-orders:READ");
    private static final CurrentUser SALES = staff(Role.SALES_STAFF, "production-orders:READ", "sales-orders:READ");

    private final ProductionOrderQueries queries = mock(ProductionOrderQueries.class);
    private final CurrentUserProvider currentUsers = mock(CurrentUserProvider.class);
    private final FindProductionOrdersTool tool = new FindProductionOrdersTool(queries, currentUsers,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("the screen's guard: production-orders:READ at the screen's scope, mirroring its list, and a "
            + "warehouse-limited user gets only their warehouses' rows")
    void guard() throws Exception {
        RequiresPermission guard = FindProductionOrdersTool.class.getMethod("run", ToolArguments.class)
                .getAnnotation(RequiresPermission.class);

        assertThat(guard.resource()).isEqualTo("production-orders").isEqualTo(ToolPermissions.PRODUCTION_ORDERS);
        assertThat(guard.action()).isEqualTo(Action.READ);
        assertThat(guard.scope()).isEqualTo(DataScope.ALL);
        assertThat(tool.mirrors()).isEqualTo("GET /api/v1/production-orders");
        assertThat(tool.scopeLimit()).isEqualTo(ScopeLimit.PARTIAL);
    }

    @Test
    @DisplayName("a portal account is refused, as the screen's controller refuses it, before anything is read")
    void backOfficeOnly() {
        as(new CurrentUser(UUID.randomUUID(), "customer", Set.of(Role.CUSTOMER),
                Set.of(PermissionCode.parse("production-orders:READ")), DataScope.OWN, Set.of()));

        assertThatThrownBy(() -> tool.run(arguments("ANY", "", "", 5))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("OPEN is every status still to bring goods, ANY every status, one status itself; the number and the "
            + "code are passed on trimmed, empty is no filter")
    void filters() {
        as(SALES);
        listed(0);

        tool.run(arguments("OPEN", "", "", 30));
        tool.run(arguments("ANY", " SO-20261020-000042 ", "", 30));
        tool.run(arguments("SAMPLE_PENDING", "", " PRD-2026 ", 20));

        verify(queries).productionOrders(ProductionOrderQueries.OPEN_STATUSES, null, null, 30);
        verify(queries).productionOrders(List.of(), "SO-20261020-000042", null, 30);
        verify(queries).productionOrders(List.of("SAMPLE_PENDING"), null, "PRD-2026", 20);
    }

    @Test
    @DisplayName("every status of the api is offered, after OPEN and ANY; anything else is refused before a lookup")
    void offered() {
        assertThat(FindProductionOrdersTool.STATUSES).containsExactly("OPEN", "ANY", "DRAFT", "PENDING_APPROVAL",
                "SENT", "ACCEPTED", "SAMPLE_PENDING", "IN_PRODUCTION", "PARTIALLY_RECEIVED", "RECEIVED", "CANCELLED",
                "CLOSED");
        assertThatThrownBy(() -> tool.run(arguments("LATE", "", "", 5))).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("status must be one of");
        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("a row: the producer, cartons received of those ordered, the latest sample round, and overdue and "
            + "atRisk worked out from its own dates in Vietnam")
    void rows() {
        as(SALES);
        listed(3,
                row("PRD-20261102-0001", "IN_PRODUCTION", LocalDate.parse("2026-11-15"),
                        LocalDate.parse("2026-11-20"), null),
                row("PRD-20261102-0002", "SAMPLE_PENDING", LocalDate.parse("2026-11-16"),
                        LocalDate.parse("2026-11-14"), new ProductionOrderQueries.SampleRound(2, null, SENT, null)),
                row("PRD-20261102-0003", "RECEIVED", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-05"),
                        new ProductionOrderQueries.SampleRound(1, "APPROVED", SENT, SENT)));

        FindProductionOrdersTool.Answer answer = tool.run(arguments("ANY", "", "", 30));

        FindProductionOrdersTool.Row late = answer.productionOrders().getFirst();
        assertThat(late.overdue()).as("open, needed yesterday").isTrue();
        assertThat(late.atRisk()).as("open, expected after the need-by day").isTrue();
        assertThat(late.cartons()).isEqualTo(30);
        assertThat(late.receivedCartons()).isEqualTo(12);
        assertThat(late.skus()).isEqualTo("CUP-12OZ, LID-90");
        assertThat(late.producerName()).isEqualTo("Xưởng in Việt");
        assertThat(late.producerLeadDays()).isEqualTo(14);
        assertThat(late.warehouseCode()).isEqualTo("HCM");
        assertThat(late.deliveryBatchSeq()).isEqualTo(2);
        assertThat(late.sampleStatus()).isNull();

        FindProductionOrdersTool.Row today = answer.productionOrders().get(1);
        assertThat(today.overdue()).as("needed today is not late yet").isFalse();
        assertThat(today.atRisk()).isFalse();
        assertThat(today.sampleStatus()).isEqualTo("WAITING");
        assertThat(today.sampleRounds()).isEqualTo(2);

        FindProductionOrdersTool.Row received = answer.productionOrders().get(2);
        assertThat(received.overdue()).as("received: nothing is late any more").isFalse();
        assertThat(received.atRisk()).isFalse();
        assertThat(received.sampleStatus()).isEqualTo("APPROVED");
        assertThat(answer.total()).isEqualTo(3);
        assertThat(answer.truncated()).isFalse();
    }

    @Test
    @DisplayName("the order number goes only to a caller who may read orders, or who typed it")
    void orderNumberOnlyForThoseWhoMayReadOrders() throws Exception {
        listed(1, row("PRD-20261102-0001", "IN_PRODUCTION", LocalDate.parse("2026-11-20"), null, null));

        as(WAREHOUSE);
        assertThat(tool.run(arguments("ANY", "", "", 5)).productionOrders().getFirst().salesOrderNumber()).isNull();
        assertThat(JSON.writeValueAsString(tool.run(arguments("ANY", "", "", 5)))).doesNotContain("SO-20261020");
        assertThat(tool.run(arguments("ANY", "SO-20261020-000042", "", 5)).productionOrders().getFirst()
                .salesOrderNumber()).isEqualTo("SO-20261020-000042");

        as(SALES);
        assertThat(tool.run(arguments("ANY", "", "", 5)).productionOrders().getFirst().salesOrderNumber())
                .isEqualTo("SO-20261020-000042");
    }

    @Test
    @DisplayName("more production orders than returned is said: total counts them all")
    void truncated() {
        as(SALES);
        listed(73, row("PRD-20261102-0001", "IN_PRODUCTION", LocalDate.parse("2026-11-20"), null, null));

        FindProductionOrdersTool.Answer answer = tool.run(arguments("OPEN", "", "", 1));

        assertThat(answer.total()).isEqualTo(73);
        assertThat(answer.truncated()).isTrue();
    }

    @Test
    @DisplayName("nothing of the customer, the price or the print files, and no ids, reach the model")
    void noIds() throws Exception {
        as(SALES);
        ProductionOrderQueries.ProductionOrderRow row = row("PRD-20261102-0001", "IN_PRODUCTION",
                LocalDate.parse("2026-11-20"), null, null);
        listed(1, row);

        String result = JSON.writeValueAsString(tool.run(arguments("ANY", "", "", 5)));

        assertThat(result).doesNotContain(row.id().toString(), row.salesOrderId().toString());
    }

    @Test
    @DisplayName("the most production orders one call returns, of long producer names and many SKUs, stay well "
            + "under the result limit; more than that is refused before a lookup")
    void fiftyRowsFit() {
        as(SALES);
        String longName = "Công ty TNHH Sản xuất Thương mại và Dịch vụ Bao bì Giấy Kraft Miền Nam ".repeat(3);
        List<ProductionOrderQueries.Line> lines = IntStream.range(0, 12)
                .mapToObj(i -> new ProductionOrderQueries.Line("CUSTOM-PRINTED-CUP-%02d".formatted(i), 100, 50, 20))
                .toList();
        ProductionOrderQueries.ProductionOrderRow[] rows = IntStream.range(0, FindProductionOrdersTool.MAX_ROWS)
                .mapToObj(i -> new ProductionOrderQueries.ProductionOrderRow(UUID.randomUUID(),
                        "PRD-20261102-%04d".formatted(i), "IN_PRODUCTION", UUID.randomUUID(),
                        "SO-20261020-%06d".formatted(i), 3, longName, 14, "HCM", LocalDate.parse("2026-11-20"),
                        LocalDate.parse("2026-11-25"), SENT, SENT, true, 3,
                        new ProductionOrderQueries.SampleRound(3, "APPROVED", SENT, SENT), lines))
                .toArray(ProductionOrderQueries.ProductionOrderRow[]::new);
        listed(200, rows);
        AssistantTools tools = new AssistantTools(List.of(tool), JsonMapper.builder()
                .addModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL).build());

        ToolExecution execution = tools.execute(new ToolCall("toolu_1", "findProductionOrders",
                "{\"status\":\"ANY\",\"salesOrderNumber\":\"\",\"code\":\"\",\"limit\":"
                        + FindProductionOrdersTool.MAX_ROWS + "}"));

        assertThat(execution.outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(execution.result()).contains("…");
        assertThat(execution.result().length())
                .as("well under the limit above which the model gets RESULT_TOO_LARGE and no row at all")
                .isLessThan(ToolExecution.MAX_RESULT_LENGTH * 9 / 10);
        assertThatThrownBy(() -> tool.run(arguments("ANY", "", "", FindProductionOrdersTool.MAX_ROWS + 1)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("the description tells the model how to read lateness, the sample and a cut list")
    void description() {
        assertThat(tool.spec().description()).contains("overdue", "atRisk", "needByDate", "sampleStatus",
                "OPEN asks for", "truncated", "warehouses");
    }

    // ------------------------------------------------------------------------------------------------------------

    private static ToolArguments arguments(String status, String salesOrderNumber, String code, int limit) {
        return ToolArguments.parse(JSON, "{\"status\":\"" + status + "\",\"salesOrderNumber\":\"" + salesOrderNumber
                + "\",\"code\":\"" + code + "\",\"limit\":" + limit + "}");
    }

    private void listed(long total, ProductionOrderQueries.ProductionOrderRow... rows) {
        when(queries.productionOrders(anyCollection(), any(), any(), anyInt()))
                .thenReturn(PageResponse.of(List.of(rows), 0, 50, total));
    }

    /** Two lines, 30 cartons ordered and 12 received, for batch 2 of SO-20261020-000042. */
    private static ProductionOrderQueries.ProductionOrderRow row(String code, String status, LocalDate needBy,
                                                                 LocalDate expected,
                                                                 ProductionOrderQueries.SampleRound latest) {
        return new ProductionOrderQueries.ProductionOrderRow(UUID.randomUUID(), code, status, UUID.randomUUID(),
                "SO-20261020-000042", 2, "Xưởng in Việt", 14, "HCM", needBy, expected, SENT, null, latest != null,
                latest == null ? 0 : latest.roundNo(), latest,
                List.of(new ProductionOrderQueries.Line("CUP-12OZ", 20, 50, 12),
                        new ProductionOrderQueries.Line("LID-90", 10, 100, 0)));
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
