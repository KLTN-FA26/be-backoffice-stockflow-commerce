package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.fulfillment.api.ShipmentQueries;
import com.stockflow.fulfillment.api.ShipmentSummary;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockAdjustmentQueries;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.payment.api.OverdueCustomer;
import com.stockflow.payment.api.OverdueReceivables;
import com.stockflow.support.TestUsers;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The four tools of P8 without a database: what each asks of its module, and what each row says. The guards, the
 * warehouse limits and the screens they mirror are held end to end by {@code AssistantIntegrationTest} and
 * {@code AssistantCapabilitiesIntegrationTest}; getCustomerCredit's credit left by {@link GetCustomerCreditToolTest}.
 */
class P8ToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    /** 09:00 in Vietnam on 5 November 2026. */
    private static final Instant NOW = Instant.parse("2026-11-05T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** The tool-result mapper, as AssistantTools writes every answer. */
    private static final ObjectMapper RESULTS = AssistantTools.resultMapper(
            org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                    .serializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build());

    private static ToolArguments arguments(String json) {
        return ToolArguments.parse(JSON, json);
    }

    /** {@code text} padded with {@code c} to {@code length} characters: the longest a column holds. */
    private static String longest(String text, int length) {
        return (text + "X".repeat(length)).substring(0, length);
    }

    private static int written(Object answer) throws Exception {
        return RESULTS.writeValueAsString(answer).length();
    }

    @Nested
    @DisplayName("findPendingAdjustments")
    class PendingAdjustments {

        private final StockAdjustmentQueries queries = mock(StockAdjustmentQueries.class);
        private final InventoryService inventory = mock(InventoryService.class);
        private final CurrentUserProvider users = mock(CurrentUserProvider.class);
        private final FindPendingAdjustmentsTool tool = new FindPendingAdjustmentsTool(queries, inventory, users, CLOCK);
        private final UUID me = UUID.randomUUID();
        private final UUID colleague = UUID.randomUUID();

        private StockAdjustmentSummary adjustment(String number, UUID requestedBy, Instant at, UUID carton,
                                                  String note) {
            return new StockAdjustmentSummary(UUID.randomUUID(), number, "HCM-A01-1-B", "CUP-12OZ", "L-7", -20,
                    StockAdjustmentReason.DAMAGED, note, StockAdjustmentStatus.PENDING_APPROVAL, requestedBy, at,
                    null, null, null, null, null, 0, carton, null);
        }

        private void signedIn(boolean approver) {
            TestUsers.Builder user = TestUsers.builder().id(me).role(Role.WAREHOUSE_MANAGER).scope(DataScope.ALL)
                    .can("inventory-stock-adjustments", Action.READ);
            if (approver) {
                user.can("inventory-stock-adjustments", Action.APPROVE);
            }
            when(users.current()).thenReturn(Optional.of(user.build()));
        }

        @Test
        @DisplayName("the approver's queue, oldest first, of the SKU asked for; an empty SKU is every SKU")
        void asksForTheQueue() {
            signedIn(true);
            when(inventory.itemIdsOf(List.of("CUP-12OZ"))).thenReturn(Map.of("CUP-12OZ", UUID.randomUUID()));
            when(queries.adjustments(any(), any(), any(), eq(0), eq(10), any()))
                    .thenReturn(PageResponse.of(List.of(), 0, 10, 0));

            tool.run(arguments("{\"sku\":\"CUP-12OZ\",\"limit\":10}"));
            tool.run(arguments("{\"sku\":\"\",\"limit\":10}"));

            verify(queries).adjustments(StockAdjustmentStatus.PENDING_APPROVAL, "CUP-12OZ", null, 0, 10,
                    "createdAt,asc");
            verify(queries).adjustments(StockAdjustmentStatus.PENDING_APPROVAL, null, null, 0, 10, "createdAt,asc");
        }

        @Test
        @DisplayName("a SKU nobody stocks is NOT_FOUND, never an empty queue read as nothing waiting")
        void anUnknownSkuIsNotFound() {
            signedIn(true);
            when(inventory.itemIdsOf(any())).thenReturn(Map.of());

            assertThatThrownBy(() -> tool.run(arguments("{\"sku\":\"NO-SUCH\",\"limit\":10}")))
                    .isInstanceOf(NotFoundException.class);
            verifyNoInteractions(queries);
        }

        @Test
        @DisplayName("thirty rows of the longest values stay under the result limit")
        void thirtyRowsFit() throws Exception {
            signedIn(true);
            List<StockAdjustmentSummary> rows = new java.util.ArrayList<>();
            for (int i = 0; i < FindPendingAdjustmentsTool.MAX_ROWS; i++) {
                rows.add(new StockAdjustmentSummary(UUID.randomUUID(), longest("ADJ-" + i, 32),
                        longest("HCM-A01-1-B", 64), longest("CUP", 64), longest("L", 64), -2_000_000_000,
                        StockAdjustmentReason.SUBCONTRACT_LOSS, longest("Ghi chú ", 500),
                        StockAdjustmentStatus.PENDING_APPROVAL, colleague, NOW, null, null, null, null, null, 0,
                        UUID.randomUUID(), UUID.randomUUID()));
            }
            when(queries.adjustments(any(), any(), any(), eq(0), eq(30), any()))
                    .thenReturn(PageResponse.of(rows, 0, 30, 1_000_000));

            assertThat(written(tool.run(arguments("{\"sku\":\"\",\"limit\":30}"))))
                    .isLessThan(com.stockflow.assistant.internal.domain.ToolExecution.MAX_RESULT_LENGTH);
        }

        @Test
        @DisplayName("each row: the warehouse from its bin, days waiting in Vietnam, whether it is the caller's own and "
                + "a carton's, no colleague's name; the note masked")
        void rows() throws Exception {
            signedIn(true);
            UUID carton = UUID.randomUUID();
            when(queries.adjustments(any(), any(), any(), eq(0), eq(30), any())).thenReturn(PageResponse.of(List.of(
                    adjustment("ADJ-1", colleague, Instant.parse("2026-11-01T16:30:00Z"), null,
                            "Rơi vỡ khi bốc, gọi 0903 123 456"),
                    adjustment("ADJ-2", me, Instant.parse("2026-11-05T01:00:00Z"), carton, null)), 0, 30, 7));

            FindPendingAdjustmentsTool.Answer answer = tool.run(arguments("{\"sku\":\"\",\"limit\":30}"));

            assertThat(answer.total()).isEqualTo(7);
            assertThat(answer.truncated()).isTrue();
            assertThat(answer.callerMayApprove()).isTrue();
            assertThat(RESULTS.writeValueAsString(answer)).doesNotContain(colleague.toString(), me.toString());
            FindPendingAdjustmentsTool.Row first = answer.adjustments().get(0);
            assertThat(first.warehouseCode()).isEqualTo("HCM");
            // Requested 23:30 on 1 November in Vietnam: four days by 5 November.
            assertThat(first.daysWaiting()).isEqualTo(4);
            assertThat(first.requestedByMe()).isFalse();
            assertThat(first.carton()).isFalse();
            assertThat(first.quantityDelta()).isEqualTo(-20);
            assertThat(first.reason()).isEqualTo("DAMAGED");
            assertThat(first.note()).doesNotContain("0903").contains(TypedText.PHONE);
            FindPendingAdjustmentsTool.Row second = answer.adjustments().get(1);
            assertThat(second.requestedByMe()).isTrue();
            assertThat(second.carton()).isTrue();
            assertThat(second.daysWaiting()).isZero();
        }

        @Test
        @DisplayName("a user without APPROVE is told so: the model says who must approve")
        void notAnApprover() {
            signedIn(false);
            when(queries.adjustments(any(), any(), any(), eq(0), eq(5), any()))
                    .thenReturn(PageResponse.of(List.of(), 0, 5, 0));

            assertThat(tool.run(arguments("{\"sku\":\"\",\"limit\":5}")).callerMayApprove()).isFalse();
        }
    }

    @Nested
    @DisplayName("findShipments")
    class FindShipments {

        private final ShipmentQueries queries = mock(ShipmentQueries.class);
        private final WarehouseService warehouses = mock(WarehouseService.class);
        private final FindShipmentsTool tool = new FindShipmentsTool(queries, warehouses);
        private final UUID hcm = UUID.randomUUID();

        private ShipmentSummary shipment(String code, String status, BigDecimal cod, String failure) {
            return new ShipmentSummary(UUID.randomUUID(), code, UUID.randomUUID(), code.substring(0, code.indexOf("-S")),
                    hcm, status, "PARCEL", 2, cod, "VND", "Giao Hàng Nhanh", null, false, NOW.minusSeconds(7_200),
                    NOW.minusSeconds(3_600), "DELIVERY_FAILED".equals(status) ? NOW : null, failure);
        }

        @Test
        @DisplayName("ANY is no status filter, newest first; a queue's status is listed the longest waiting first")
        void filters() {
            when(queries.shipments(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), eq(0), eq(20)))
                    .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

            tool.run(arguments("{\"status\":\"ANY\",\"search\":\"\",\"limit\":20}"));
            tool.run(arguments("{\"status\":\"DELIVERY_FAILED\",\"search\":\"SO-20261105\",\"limit\":20}"));
            tool.run(arguments("{\"status\":\"DELIVERED\",\"search\":\"\",\"limit\":20}"));

            verify(queries).shipments(null, null, false, 0, 20);
            verify(queries).shipments("DELIVERY_FAILED", "SO-20261105", true, 0, 20);
            verify(queries).shipments("DELIVERED", null, false, 0, 20);
        }

        @Test
        @DisplayName("thirty rows of the longest values stay under the result limit")
        void thirtyRowsFit() throws Exception {
            when(warehouses.findWarehouse(hcm)).thenReturn(Optional.of(new WarehouseView(hcm, "HCM", "Kho HCM",
                    "Q7", true)));
            List<ShipmentSummary> rows = new java.util.ArrayList<>();
            for (int i = 0; i < FindShipmentsTool.MAX_ROWS; i++) {
                rows.add(new ShipmentSummary(UUID.randomUUID(), longest("SO-20261105-000123-S" + i, 48),
                        UUID.randomUUID(), longest("SO-20261105-000123", 32), hcm, "DELIVERY_FAILED", "PARCEL",
                        999, new BigDecimal("123456789012.3456"), "VND", longest("Hãng vận chuyển ", 120),
                        longest("LD-", 30), true, NOW, NOW, NOW, "WRONG_ADDRESS"));
            }
            when(queries.shipments(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), eq(0), eq(30)))
                    .thenReturn(PageResponse.of(rows, 0, 30, 1_000_000));

            assertThat(written(tool.run(arguments("{\"status\":\"ANY\",\"search\":\"\",\"limit\":30}"))))
                    .isLessThan(com.stockflow.assistant.internal.domain.ToolExecution.MAX_RESULT_LENGTH);
        }

        @Test
        @DisplayName("each row names its warehouse by code, looked up once per warehouse; a COD amount and its "
                + "currency only when the carrier collects something; the carrier's name cut")
        void rows() {
            when(warehouses.findWarehouse(hcm)).thenReturn(Optional.of(new WarehouseView(hcm, "HCM", "Kho HCM",
                    "Q7", true)));
            ShipmentSummary longName = new ShipmentSummary(UUID.randomUUID(), "SO-20261105-000103-S1",
                    UUID.randomUUID(), "SO-20261105-000103", hcm, "READY_TO_DISPATCH", "PARCEL", 1, BigDecimal.ZERO,
                    "VND", "Công ty Cổ phần Giao Nhận Vận Tải Liên Tỉnh Miền Nam", null, false, NOW, null, null, null);
            when(queries.shipments(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), eq(0), eq(30)))
                    .thenReturn(PageResponse.of(List.of(
                            shipment("SO-20261105-000101-S1", "DELIVERY_FAILED", new BigDecimal("1500000"),
                                    "CUSTOMER_ABSENT"),
                            shipment("SO-20261105-000102-S1", "HANDED_OVER", BigDecimal.ZERO, null), longName),
                            0, 30, 3));

            FindShipmentsTool.Answer answer = tool.run(arguments("{\"status\":\"ANY\",\"search\":\"\",\"limit\":30}"));

            assertThat(answer.total()).isEqualTo(3);
            assertThat(answer.truncated()).isFalse();
            FindShipmentsTool.Row failed = answer.shipments().get(0);
            assertThat(failed.warehouseCode()).isEqualTo("HCM");
            assertThat(failed.orderNumber()).isEqualTo("SO-20261105-000101");
            assertThat(failed.failureReason()).isEqualTo("CUSTOMER_ABSENT");
            assertThat(failed.currency()).isEqualTo("VND");
            assertThat(failed.outcomeAt()).isEqualTo(NOW);
            assertThat(answer.shipments().get(1).codAmount()).isNull();
            assertThat(answer.shipments().get(1).currency()).isNull();
            assertThat(answer.shipments().get(2).carrierName()).hasSizeLessThanOrEqualTo(41).endsWith("…");
            verify(warehouses, times(1)).findWarehouse(hcm);
        }

        @Test
        @DisplayName("the description says there is no tracking number, so none is made up, that a failure reason on "
                + "a delivered shipment is an earlier attempt's, and that there is no date filter")
        void whatTheDescriptionSays() {
            assertThat(tool.spec().description()).contains("There are no tracking numbers", "never give one",
                    "an earlier attempt's, not the outcome", "there is no date filter");
        }
    }

    @Nested
    @DisplayName("findOverdueCustomers")
    class OverdueCustomers {

        private final OverdueReceivables receivables = mock(OverdueReceivables.class);
        private final CustomerService customers = mock(CustomerService.class);
        private final CurrentUserProvider users = mock(CurrentUserProvider.class);
        private final FindOverdueCustomersTool tool = new FindOverdueCustomersTool(receivables, customers, users,
                CLOCK);

        private final UUID abc = UUID.randomUUID();

        private void owing() {
            Map<String, BigDecimal> totals = new LinkedHashMap<>();
            totals.put("VND", new BigDecimal("72000000"));
            when(receivables.overdueCustomers(false, 10)).thenReturn(new OverdueReceivables.OverdueCustomers(
                    List.of(new OverdueCustomer(abc, new BigDecimal("15000000"), "VND", LocalDate.of(2026, 10, 14),
                            5, List.of("SO-1", "SO-2", "SO-3", "SO-4", "SO-5"))), 4, 4, totals));
            when(receivables.overdueCustomers(true, 10)).thenReturn(new OverdueReceivables.OverdueCustomers(
                    List.of(), 0, 0, Map.of()));
            when(customers.fullNames(anyCollection())).thenReturn(Map.of(abc, "Công ty In Ly ABC"));
        }

        @Test
        @DisplayName("Sales, who may list customers: each customer by name, what it owes past due, since when and for "
                + "how many days in Vietnam, its first orders; the totals; largest first unless the oldest is asked")
        void rows() {
            when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.SALES_STAFF)
                    .scope(DataScope.ALL).can("payment-receivables", Action.READ)
                    .can("customer-customers", Action.READ).build()));
            owing();

            FindOverdueCustomersTool.Answer answer = tool.run(arguments("{\"order\":\"LARGEST_FIRST\",\"limit\":10}"));
            tool.run(arguments("{\"order\":\"OLDEST_FIRST\",\"limit\":10}"));

            verify(receivables).overdueCustomers(true, 10);
            assertThat(answer.customersNamed()).isTrue();
            assertThat(answer.totalCustomers()).isEqualTo(4);
            assertThat(answer.truncated()).isTrue();
            assertThat(answer.totalOverdue()).singleElement().satisfies(total -> {
                assertThat(total.currency()).isEqualTo("VND");
                assertThat(total.amount()).isEqualByComparingTo("72000000");
            });
            FindOverdueCustomersTool.Row row = answer.customers().getFirst();
            assertThat(row.customerName().name()).isEqualTo("Công ty In Ly ABC");
            assertThat(row.orderNumbers()).isEqualTo("SO-1, SO-2, SO-3 (+2)");
            assertThat(row.overdueAmount()).isEqualByComparingTo("15000000");
            assertThat(row.oldestDueDate()).isEqualTo(LocalDate.of(2026, 10, 14));
            assertThat(row.daysOverdue()).isEqualTo(22);
            assertThat(row.overdueReceivables()).isEqualTo(5);
        }

        @Test
        @DisplayName("the accountant, who may not list customers, gets the debts by their orders and no name: a list "
                + "of debtors by name would be the customer list their grants leave out")
        void noNamesForWhoMayNotListCustomers() {
            when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.ACCOUNTANT)
                    .scope(DataScope.ALL).can("payment-receivables", Action.READ).build()));
            owing();

            FindOverdueCustomersTool.Answer answer = tool.run(arguments("{\"order\":\"LARGEST_FIRST\",\"limit\":10}"));

            assertThat(answer.customersNamed()).isFalse();
            assertThat(answer.customers().getFirst().customerName()).isNull();
            assertThat(answer.customers().getFirst().orderNumbers()).startsWith("SO-1");
            verifyNoInteractions(customers);
        }

        @Test
        @DisplayName("a portal customer, who holds the same READ for its own receivables, is refused before anything "
                + "is read")
        void notForCustomers() {
            CurrentUser customer = TestUsers.customer();
            when(users.current()).thenReturn(Optional.of(customer));

            assertThatThrownBy(() -> tool.run(arguments("{\"order\":\"LARGEST_FIRST\",\"limit\":10}")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            verifyNoInteractions(receivables, customers);
        }
    }
}
