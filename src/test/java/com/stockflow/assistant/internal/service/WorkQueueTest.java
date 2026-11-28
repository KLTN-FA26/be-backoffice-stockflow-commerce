package com.stockflow.assistant.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The work queue: which sections a user sees, how loud each is, and how a warehouse narrows it (contract 3.1). */
class WorkQueueTest {

    private static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();

    private final WorkQueueReads reads = mock(WorkQueueReads.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final WorkQueue queue = new WorkQueue(reads, warehouses, new Messages(new StaticMessageSource()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static CurrentUser user(DataScope scope, Set<UUID> warehouseIds, PermissionCode... permissions) {
        return new CurrentUser(UUID.randomUUID(), "u", Set.of(Role.WAREHOUSE_MANAGER), Set.of(permissions), scope,
                Set.of(), warehouseIds);
    }

    private static PermissionCode read(String resource) {
        return PermissionCode.of(resource, Action.READ);
    }

    private static WorkQueueReads.Read some(long count, boolean urgent) {
        return new WorkQueueReads.Read(count, List.of(new WorkQueueReads.Item("X-1", null, null, "/admin/x")), urgent);
    }

    @Test
    @DisplayName("a section appears only for the permission of the screen it counts, read from its read's own guard")
    void sectionsFollowTheScreensPermissions() {
        when(reads.receiptsToPutAway(any())).thenReturn(some(2, false));
        when(reads.pendingAdjustments(any())).thenReturn(some(0, false));
        CurrentUser keeper = user(DataScope.ALL, Set.of(), read(ToolPermissions.GOODS_RECEIPTS),
                read(ToolPermissions.STOCK_ADJUSTMENTS), PermissionCode.of("warehouse-putaway-tasks", Action.UPDATE),
                PermissionCode.of(ToolPermissions.STOCK_ADJUSTMENTS, Action.APPROVE));

        WorkQueue.Queue found = queue.of(keeper, null);

        assertThat(found.sections()).extracting(WorkQueue.Section::key)
                .containsExactly("receiptsToPutAway", "pendingAdjustments");
        verify(reads, never()).heldOrders(any());
        verify(reads, never()).bankTransactionsToReview(any());
        assertThat(found.warehouse()).isNull();
        assertThat(found.generatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("severity: INFO when nothing waits, the section's own when something does, CRITICAL when urgent")
    void severity() {
        when(reads.heldOrders(any())).thenReturn(some(3, false));
        when(reads.awaitingPayment(any())).thenReturn(some(0, true));
        when(reads.awaitingStock(any())).thenReturn(some(1, false));
        when(reads.productionOrdersLate(any())).thenReturn(some(1, true));
        CurrentUser sales = user(DataScope.ALL, Set.of(), read(ToolPermissions.SALES_ORDERS),
                read(ToolPermissions.PRODUCTION_ORDERS), PermissionCode.of(ToolPermissions.PRODUCTION_ORDERS,
                        Action.UPDATE));

        List<WorkQueue.Section> sections = queue.of(sales, null).sections();

        assertThat(sections).extracting(WorkQueue.Section::key, WorkQueue.Section::severity).containsExactly(
                org.assertj.core.groups.Tuple.tuple("heldOrders", WorkQueue.Severity.WARN),
                org.assertj.core.groups.Tuple.tuple("awaitingPayment", WorkQueue.Severity.INFO),
                org.assertj.core.groups.Tuple.tuple("awaitingStock", WorkQueue.Severity.INFO),
                org.assertj.core.groups.Tuple.tuple("productionOrdersLate", WorkQueue.Severity.CRITICAL));
        assertThat(sections.getFirst().count()).isEqualTo(3);
        assertThat(sections.getFirst().href()).isEqualTo("/admin/orders?status=ON_HOLD");
    }

    @Test
    @DisplayName("an approval queue is shown to who may approve it: Sales Staff reading quotes and credit holds get "
            + "neither section; the Sales Manager gets the quotes, the accountant the credit reviews")
    void approvalQueuesNeedTheActingPermission() {
        when(reads.quotesAwaitingPriceApproval(any())).thenReturn(some(2, false));
        when(reads.creditReviewsPending(any())).thenReturn(some(1, false));
        CurrentUser salesStaff = user(DataScope.ALL, Set.of(), read(ToolPermissions.QUOTES),
                read(ToolPermissions.CREDIT_HOLDS));
        CurrentUser salesManager = user(DataScope.ALL, Set.of(), read(ToolPermissions.QUOTES),
                read(ToolPermissions.CREDIT_HOLDS),
                PermissionCode.of(ToolPermissions.QUOTE_PRICE_APPROVALS, Action.APPROVE));
        CurrentUser accountant = user(DataScope.ALL, Set.of(), read(ToolPermissions.CREDIT_HOLDS),
                PermissionCode.of(ToolPermissions.CREDIT_HOLDS, Action.APPROVE));

        assertThat(queue.of(salesStaff, null).sections()).isEmpty();
        assertThat(queue.of(salesManager, null).sections()).extracting(WorkQueue.Section::key)
                .containsExactly("quotesAwaitingPriceApproval");
        assertThat(queue.of(accountant, null).sections()).extracting(WorkQueue.Section::key,
                WorkQueue.Section::severity).containsExactly(
                org.assertj.core.groups.Tuple.tuple("creditReviewsPending", WorkQueue.Severity.WARN));
    }

    @Test
    @DisplayName("a reader who cannot do a queue's work does not get it: the Accountant reading delivery batches, goods "
            + "receipts, adjustments and production orders sees none of those sections (rehearsal 27/11)")
    void operationalQueuesGoToWhoWorksThem() {
        CurrentUser accountant = user(DataScope.ALL, Set.of(), read("sales-order-delivery-batches"),
                read(ToolPermissions.GOODS_RECEIPTS), read(ToolPermissions.STOCK_ADJUSTMENTS),
                read(ToolPermissions.PRODUCTION_ORDERS));

        assertThat(queue.of(accountant, null).sections()).isEmpty();
        verify(reads, never()).batchesDueToday(any());
        verify(reads, never()).receiptsToPutAway(any());
        verify(reads, never()).pendingAdjustments(any());
        verify(reads, never()).productionOrdersLate(any());
    }

    @Test
    @DisplayName("production orders late link to no list - no screen shows that set - while each item opens its own")
    void productionOrdersLateHasNoListLink() {
        when(reads.productionOrdersLate(any())).thenReturn(some(1, false));
        CurrentUser coordinator = user(DataScope.ALL, Set.of(), read(ToolPermissions.PRODUCTION_ORDERS),
                PermissionCode.of(ToolPermissions.PRODUCTION_ORDERS, Action.UPDATE));

        WorkQueue.Section late = queue.of(coordinator, null).sections().getFirst();

        assertThat(late.key()).isEqualTo("productionOrdersLate");
        assertThat(late.href()).isNull();
        assertThat(late.items().getFirst().href()).isEqualTo("/admin/x");
    }

    @Test
    @DisplayName("amounts are whole VND as the screens show them, never the database's four decimals")
    void amountsInWholeVnd() {
        com.stockflow.payment.api.PaymentQueues payments = mock(com.stockflow.payment.api.PaymentQueues.class);
        com.stockflow.customer.api.CustomerService customers = mock(com.stockflow.customer.api.CustomerService.class);
        WorkQueueReads real = new WorkQueueReads(null, null, null, null, null, payments, null, customers);
        UUID customer = UUID.randomUUID();
        when(payments.transfersToReview(WorkQueueReads.ITEMS)).thenReturn(com.stockflow.common.api.PageResponse.of(
                List.of(new com.stockflow.payment.api.PaymentQueues.TransferToReview(UUID.randomUUID(),
                        new java.math.BigDecimal("50000000.0000"), NOW, NOW, "OVERPAYMENT")), 0, 5, 1));
        when(payments.overdueInstallments(WorkQueueReads.ITEMS)).thenReturn(com.stockflow.common.api.PageResponse.of(
                List.of(new com.stockflow.payment.api.PaymentQueues.OverdueInstallment(UUID.randomUUID(),
                        UUID.randomUUID(), "SO-20261101-000001", "HD-20261101-000001-1", customer, 2,
                        LocalDate.of(2026, 11, 20), new java.math.BigDecimal("12500000.5000"), "VND")), 0, 5, 1));
        when(customers.labels(any())).thenReturn(java.util.Map.of());
        WorkQueueReads.Narrowing narrowing = new WorkQueueReads.Narrowing(user(DataScope.ALL, Set.of()),
                LocalDate.of(2026, 11, 27), NOW, null, null);

        assertThat(real.bankTransactionsToReview(narrowing).items().getFirst().label()).isEqualTo("50.000.000 VND");
        assertThat(real.overdueInstallments(narrowing).items().getFirst().detail())
                .isEqualTo("2026-11-20 · 12.500.001 VND");
    }

    @Test
    @DisplayName("one section failing is one section: failed, no count, and the others still answer")
    void oneFailureIsOneSection() {
        when(reads.heldOrders(any())).thenThrow(new IllegalStateException("database gone"));
        when(reads.awaitingPayment(any())).thenReturn(some(1, false));
        when(reads.awaitingStock(any())).thenReturn(some(0, false));
        CurrentUser sales = user(DataScope.ALL, Set.of(), read(ToolPermissions.SALES_ORDERS));

        List<WorkQueue.Section> sections = queue.of(sales, null).sections();

        assertThat(sections.getFirst().failed()).isTrue();
        assertThat(sections.getFirst().count()).isNull();
        assertThat(sections.get(1).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("narrowed to a warehouse: only the sections that belong to one, read for that one; money is left out")
    void narrowedToAWarehouse() {
        when(warehouses.activeWarehouses()).thenReturn(List.of(new WarehouseView(HCM, "HCM", "Kho HCM", null, true)));
        when(reads.receiptsToPutAway(any())).thenReturn(some(1, false));
        CurrentUser manager = user(DataScope.ALL, Set.of(), read(ToolPermissions.GOODS_RECEIPTS),
                read(ToolPermissions.BANK_TRANSACTIONS), PermissionCode.of("warehouse-putaway-tasks", Action.UPDATE));

        WorkQueue.Queue found = queue.of(manager, "hcm");

        assertThat(found.warehouse()).isEqualTo("HCM");
        assertThat(found.sections()).extracting(WorkQueue.Section::key).containsExactly("receiptsToPutAway");
        ArgumentCaptor<WorkQueueReads.Narrowing> narrowing = ArgumentCaptor.forClass(WorkQueueReads.Narrowing.class);
        verify(reads).receiptsToPutAway(narrowing.capture());
        assertThat(narrowing.getValue().warehouseId()).isEqualTo(HCM);
        assertThat(narrowing.getValue().warehousePrefix()).isEqualTo("HCM");
        assertThat(narrowing.getValue().today()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(found.sections().getFirst().href()).contains("warehouseId=" + HCM);
        verify(reads, never()).bankTransactionsToReview(any());
    }

    @Test
    @DisplayName("a warehouse the caller is not assigned to is not found, as one that does not exist")
    void anotherWarehouseIsNotFound() {
        when(warehouses.activeWarehouses()).thenReturn(List.of(new WarehouseView(HCM, "HCM", "Kho HCM", null, true),
                new WarehouseView(HN, "HN", "Kho HN", null, true)));
        CurrentUser limited = user(DataScope.WAREHOUSE, Set.of(HN), read(ToolPermissions.GOODS_RECEIPTS));

        assertThatThrownBy(() -> queue.of(limited, "HCM")).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND));
        assertThatThrownBy(() -> queue.of(limited, "XYZ")).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND));
        verify(reads, never()).receiptsToPutAway(any());
    }

    @Test
    @DisplayName("the order list's warehouse rule: the warehouse asked for, else a limited user's own, else no limit")
    void orderWarehouses() {
        CurrentUser limited = user(DataScope.WAREHOUSE, Set.of(HN));
        CurrentUser everyone = user(DataScope.ALL, Set.of());

        assertThat(new WorkQueueReads.Narrowing(limited, null, NOW, HN, "HN").orderWarehouses()).containsExactly(HN);
        assertThat(new WorkQueueReads.Narrowing(limited, null, NOW, null, null).orderWarehouses())
                .containsExactly(HN);
        assertThat(new WorkQueueReads.Narrowing(everyone, null, NOW, null, null).orderWarehouses()).isNull();
        assertThat(new WorkQueueReads.Narrowing(user(DataScope.WAREHOUSE, null), null, NOW, null, null)
                .orderWarehouses()).as("limited and assigned none: none, never every order").isEmpty();
    }

    @Test
    @DisplayName("the digest lists what waits, once a day by its key, and nothing for a user with nothing waiting")
    void digestMessage() {
        WorkQueueDigestJob digest = new WorkQueueDigestJob(queue, null, null, null,
                new Messages(new StaticMessageSource()), Clock.fixed(NOW, ZoneOffset.UTC));
        LocalDate today = LocalDate.of(2026, 11, 27);
        WorkQueue.Queue busy = new WorkQueue.Queue(NOW, null, List.of(
                new WorkQueue.Section("heldOrders", "Orders on hold", 3L, WorkQueue.Severity.WARN, "/a", List.of(),
                        false),
                new WorkQueue.Section("awaitingStock", "Awaiting stock", 0L, WorkQueue.Severity.INFO, "/b", List.of(),
                        false),
                new WorkQueue.Section("productionOrdersLate", "Late", 1L, WorkQueue.Severity.CRITICAL, "/c",
                        List.of(), false),
                new WorkQueue.Section("einvoicesToIssue", "E-invoices", null, WorkQueue.Severity.INFO, "/d",
                        List.of(), true)));

        InboxMessage message = digest.message(busy, today);

        assertThat(message.body()).isEqualTo("- Orders on hold: 3\n- Late: 1");
        assertThat(message.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(message.dedupeKey()).isEqualTo("assistant:work-queue:2026-11-27");
        assertThat(message.link()).isEqualTo("/admin");
        assertThat(digest.message(new WorkQueue.Queue(NOW, null, List.of(busy.sections().get(1))), today)).isNull();
    }
}
