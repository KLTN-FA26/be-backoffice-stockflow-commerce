package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.DataScopeContext;
import com.stockflow.inventory.api.ExpiryAlertView;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.LotTraceQueries;
import com.stockflow.inventory.api.LotTraceView;
import com.stockflow.inventory.api.LowStockItem;
import com.stockflow.inventory.api.ReplenishmentEligibility;
import com.stockflow.inventory.api.StockAlertQueries;
import com.stockflow.inventory.api.StockAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The inventory tools' own logic, with the inventory module stubbed (design §4.1): what a list leaves out, how it is
 * cut and counted, and that a SKU nobody has is NOT_FOUND instead of an answer that reads as "out of stock".
 */
class InventoryToolsTest {

    private static final ObjectMapper ARGUMENTS = new ObjectMapper();
    /** As the application writes a tool's answer: ISO dates, null fields left out. */
    private static final ObjectMapper RESULTS = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    private static final String TABLE = "TABLE-OAK-160";

    private final InventoryService inventory = mock(InventoryService.class);

    // ------------------------------------------------------------------------------------------------ stock tools

    @Test
    @DisplayName("getStockLots leaves out a bin a pick emptied, though its lot expires first, and counts what it lists")
    void stockLotsLeaveOutEmptiedRows() {
        known(TABLE);
        when(inventory.availabilityOf(new Sku(TABLE))).thenReturn(List.of(
                stock("HCM-B01-1-A", "EVAL-LOT-2401", LocalDate.of(2026, 11, 17), 0, 0),
                stock("HCM-B01-1-A", "LOT-2026-03", LocalDate.of(2027, 3, 31), 12, 0)));

        GetStockLotsTool.Answer answer = new GetStockLotsTool(inventory).run(args("{\"sku\":\"table-oak-160\"}"));

        assertThat(answer.sku()).isEqualTo(TABLE);
        assertThat(answer.locations()).extracting(GetStockLotsTool.Row::lotNumber).containsExactly("LOT-2026-03");
        assertThat(answer.total()).isEqualTo(1);
        assertThat(answer.truncated()).isFalse();
    }

    @Test
    @DisplayName("getStockLots lists the first 50 stocked rows in picking order and says how many there are")
    void stockLotsAreCappedAndCounted() {
        known(TABLE);
        List<StockAvailability> rows = new ArrayList<>();
        LocalDate first = LocalDate.of(2027, 1, 1);
        for (int i = 0; i < 60; i++) {
            if (i % 10 == 0) {
                rows.add(stock("HCM-A01-1-B", "EMPTIED-" + i, first.plusDays(i), 0, 0));
            }
            rows.add(stock("HCM-A01-1-B", "LOT-" + i, first.plusDays(i), 5, 1));
        }
        when(inventory.availabilityOf(new Sku(TABLE))).thenReturn(rows);

        GetStockLotsTool.Answer answer = new GetStockLotsTool(inventory).run(args("{\"sku\":\"" + TABLE + "\"}"));

        assertThat(answer.total()).isEqualTo(60);
        assertThat(answer.truncated()).isTrue();
        assertThat(answer.locations()).hasSize(GetStockLotsTool.MAX_ROWS);
        assertThat(answer.locations().get(0).lotNumber()).isEqualTo("LOT-0");
        assertThat(answer.locations().get(GetStockLotsTool.MAX_ROWS - 1).lotNumber()).isEqualTo("LOT-49");
        assertThat(answer.locations()).extracting(GetStockLotsTool.Row::lotNumber)
                .noneMatch(lot -> lot.startsWith("EMPTIED"));
        assertThat(answer.locations().get(0)).extracting(GetStockLotsTool.Row::held, GetStockLotsTool.Row::unheld)
                .containsExactly(1, 4);
    }

    @Test
    @DisplayName("a SKU no inventory item has is NOT_FOUND in both stock tools, and no stock is read for it")
    void unknownSkuIsNotFound() {
        known();

        assertThatThrownBy(() -> new GetStockLevelsTool(inventory).run(args("{\"sku\":\"EVAL-CUP-12\"}")))
                .isInstanceOfSatisfying(NotFoundException.class, refused -> {
                    assertThat(refused.resourceType()).isEqualTo("SKU");
                    assertThat(refused.resourceId()).isEqualTo("EVAL-CUP-12");
                });
        assertThatThrownBy(() -> new GetStockLotsTool(inventory).run(args("{\"sku\":\"eval-cup-12\"}")))
                .isInstanceOfSatisfying(NotFoundException.class,
                        refused -> assertThat(refused.resourceId()).isEqualTo("EVAL-CUP-12"));
        verify(inventory, never()).levelsOf(any());
        verify(inventory, never()).availableToPromise(any(Sku.class));
        verify(inventory, never()).availabilityOf(any());
    }

    @Test
    @DisplayName("a real SKU with no stock is still answered with its zero, which is now never a typo")
    void knownSkuWithoutStockIsZero() {
        known("EVAL-CUP-16OZ");
        when(inventory.levelsOf(new Sku("EVAL-CUP-16OZ"))).thenReturn(List.of());
        when(inventory.availableToPromise(new Sku("EVAL-CUP-16OZ"))).thenReturn(0);

        GetStockLevelsTool.Answer answer = new GetStockLevelsTool(inventory).run(args("{\"sku\":\"EVAL-CUP-16OZ\"}"));

        assertThat(answer.companyAvailableToPromise()).isZero();
        assertThat(answer.warehouses()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------- traceLot

    @Test
    @DisplayName("traceLot lists the 30 most recent of 35 orders, counts each list apart, and keeps live holds only")
    void traceLotCutsEachListAndSaysSo() {
        LotTraceQueries lots = mock(LotTraceQueries.class);
        Instant start = Instant.parse("2026-09-01T02:00:00Z");
        List<LotTraceView.OrderLeg> orders = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            Instant at = start.plus(Duration.ofHours(i));
            orders.add(new LotTraceView.OrderLeg(order(i), 3, 0, at, at));
        }
        List<LotTraceView.Hold> holds = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            Instant at = start.plus(Duration.ofHours(i));
            holds.add(new LotTraceView.Hold(order(i), "HCM-B01-1-A", 3, "CONSUMED", at, at.plusSeconds(600)));
        }
        holds.add(new LotTraceView.Hold(order(36), "HCM-B01-1-A", 4, "HELD", start, null));
        holds.add(new LotTraceView.Hold(order(37), "HCM-B01-1-A", 5, "RELEASED", start, start.plusSeconds(60)));
        holds.add(new LotTraceView.Hold(null, "HCM-B01-2-A", 2, "HELD", start, null));
        when(lots.traceLot("LOT-A", null, TraceLotTool.MAX_LOTS)).thenReturn(new LotTraceQueries.Traced(
                List.of(lot(TABLE, "LOT-A", "Xưởng gỗ Việt", orders, holds, 2)), 1));

        TraceLotTool.Answer answer = new TraceLotTool(lots, inventory)
                .run(args("{\"lotNumber\":\"LOT-A\",\"sku\":\"\"}"));

        TraceLotTool.Lot traced = answer.lots().get(0);
        assertThat(traced.ordersTotal()).isEqualTo(35);
        assertThat(traced.ordersTruncated()).isTrue();
        assertThat(traced.orders()).hasSize(TraceLotTool.MAX_ROWS);
        assertThat(traced.orders().get(0).orderNumber()).as("the most recent first").isEqualTo(order(35));
        assertThat(traced.orders()).extracting(TraceLotTool.OrderLeg::orderNumber)
                .as("the cut drops the oldest").doesNotContain(order(1), order(5)).contains(order(6));
        assertThat(traced.holdsTotal()).isEqualTo(2);
        assertThat(traced.holdsTruncated()).isFalse();
        assertThat(traced.holds()).extracting(TraceLotTool.Hold::orderNumber, TraceLotTool.Hold::quantity)
                .containsExactly(tuple(order(36), 4), tuple(null, 2));
        assertThat(traced.balancesTotal()).isEqualTo(2);
        assertThat(traced.balancesTruncated()).isFalse();
        assertThat(traced.balances()).hasSize(2);
        verifyNoInteractions(inventory);
    }

    @Test
    @DisplayName("ten fully loaded lots share the rows out, each list still counted, and the answer fits in one result")
    void traceLotOfManyLotsFitsInOneResult() throws Exception {
        LotTraceQueries lots = mock(LotTraceQueries.class);
        // 188 characters, near the 200 a supplier name may hold.
        String supplier = "Công ty TNHH Sản xuất Thương mại Bao bì Giấy và Nhựa Xuất khẩu "
                .repeat(3).strip();
        List<LotTraceView> traced = new ArrayList<>();
        for (int n = 0; n < TraceLotTool.MAX_LOTS; n++) {
            Instant start = Instant.parse("2026-01-01T01:02:03.123456Z").plus(Duration.ofDays(n));
            List<LotTraceView.OrderLeg> orders = new ArrayList<>();
            List<LotTraceView.Hold> holds = new ArrayList<>();
            for (int i = 1; i <= 60; i++) {
                Instant at = start.plus(Duration.ofMinutes(i));
                orders.add(new LotTraceView.OrderLeg(order(n * 100 + i), 12_345, 1_234, at, at.plusSeconds(3600)));
                holds.add(new LotTraceView.Hold(order(n * 100 + i), "HCM-B01-1-A", 12_345, "HELD", at, null));
            }
            traced.add(lot("CUP-12OZ-KRAFT-DOUBLE-WALL-" + n, "2410", supplier, orders, holds, 60));
        }
        when(lots.traceLot(eq("2410"), isNull(), eq(TraceLotTool.MAX_LOTS)))
                .thenReturn(new LotTraceQueries.Traced(traced, 25));

        TraceLotTool.Answer answer = new TraceLotTool(lots, inventory)
                .run(args("{\"lotNumber\":\"2410\",\"sku\":\"\"}"));

        int each = TraceLotTool.MAX_ROWS / TraceLotTool.MAX_LOTS;
        assertThat(answer.totalLots()).isEqualTo(25);
        assertThat(answer.lots()).hasSize(TraceLotTool.MAX_LOTS).allSatisfy(lot -> {
            assertThat(lot.orders()).hasSize(each);
            assertThat(lot.balances()).hasSize(each);
            assertThat(lot.holds()).hasSize(each);
            assertThat(List.of(lot.ordersTotal(), lot.balancesTotal(), lot.holdsTotal())).containsOnly(60);
            assertThat(List.of(lot.ordersTruncated(), lot.balancesTruncated(), lot.holdsTruncated()))
                    .containsOnly(true);
        });
        assertThat(RESULTS.writeValueAsString(answer).length())
                .as("well under the limit above which the model gets RESULT_TOO_LARGE and no trace at all")
                .isLessThan(ToolExecution.MAX_RESULT_LENGTH * 9 / 10);
    }

    @Test
    @DisplayName("traceLot narrowed to a SKU nobody has is NOT_FOUND, and traces nothing")
    void traceLotOfAnUnknownSkuIsNotFound() {
        LotTraceQueries lots = mock(LotTraceQueries.class);
        known();

        assertThatThrownBy(() -> new TraceLotTool(lots, inventory)
                .run(args("{\"lotNumber\":\"LOT-A\",\"sku\":\"TABLE-OAK-16\"}")))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(lots);
    }

    // --------------------------------------------------------------------------------------------- findLowStock

    @Test
    @DisplayName("findLowStock counts every low SKU that can be bought, on every page, though it lists only the limit")
    void lowStockCountsEveryLowSku() {
        List<LowStockItem> low = new ArrayList<>();
        for (int i = 0; i < FindLowStockTool.PAGE + 30; i++) {
            low.add(new LowStockItem("LOW-%03d".formatted(i), 5, 10, 2, true, false));
        }
        when(inventory.lowStockBatch(any(), anyInt())).thenAnswer(call -> {
            String after = call.getArgument(0);
            int limit = call.getArgument(1);
            return low.stream().filter(item -> after == null || item.sku().compareTo(after) > 0).limit(limit).toList();
        });
        when(inventory.availableToPromise(anyCollection())).thenAnswer(call -> {
            Collection<Sku> skus = call.getArgument(0);
            return skus.stream().collect(Collectors.toMap(Function.identity(), sku -> 1));
        });
        // Every SKU ending in 7 is discontinued: 13 of the 130.
        ReplenishmentEligibility eligibility = skus -> skus.stream().filter(sku -> !sku.endsWith("7"))
                .collect(Collectors.toSet());

        FindLowStockTool.Answer answer = new FindLowStockTool(inventory, provider(eligibility))
                .run(args("{\"limit\":10}"));

        assertThat(answer.total()).isEqualTo(117);
        assertThat(answer.truncated()).isTrue();
        assertThat(answer.skus()).extracting(FindLowStockTool.Row::sku).containsExactly("LOW-000", "LOW-001",
                "LOW-002", "LOW-003", "LOW-004", "LOW-005", "LOW-006", "LOW-008", "LOW-009", "LOW-010");
        assertThat(answer.note()).isNull();
        verify(inventory).lowStockBatch("LOW-099", FindLowStockTool.PAGE);
    }

    @Test
    @DisplayName("findLowStock is not truncated when every low SKU fits")
    void lowStockThatFitsIsComplete() {
        when(inventory.lowStockBatch(any(), anyInt())).thenAnswer(call -> call.getArgument(0) == null
                ? List.of(new LowStockItem("EVAL-CUP-12OZ", 40, 100, 30, true, false),
                new LowStockItem("EVAL-LID-90", 43, 60, 50, true, true))
                : List.of());
        when(inventory.availableToPromise(anyCollection())).thenAnswer(call -> {
            Collection<Sku> skus = call.getArgument(0);
            return skus.stream().collect(Collectors.toMap(Function.identity(), sku -> 0));
        });

        FindLowStockTool.Answer answer = new FindLowStockTool(inventory, provider(null)).run(args("{\"limit\":50}"));

        assertThat(answer.total()).isEqualTo(2);
        assertThat(answer.truncated()).isFalse();
        assertThat(answer.skus()).hasSize(2);
    }

    @Test
    @DisplayName("a warehouse-limited user gets no low-stock rows and no count that would read as 'none is low'")
    void lowStockIsWithheldFromAWarehouseLimitedUser() throws Exception {
        DataScopeContext.set(DataScope.ALL, new CurrentUser(UUID.randomUUID(), "manager", Set.of(), Set.of(),
                DataScope.WAREHOUSE, Set.of("HCM")));
        FindLowStockTool.Answer answer;
        try {
            answer = new FindLowStockTool(inventory, provider(null)).run(args("{\"limit\":50}"));
        } finally {
            DataScopeContext.clear();
        }

        assertThat(answer.total()).isNull();
        assertThat(answer.skus()).isEmpty();
        assertThat(answer.note()).contains("company-wide");
        assertThat(RESULTS.writeValueAsString(answer)).doesNotContain("total");
        verify(inventory, never()).lowStockBatch(any(), anyInt());
    }

    // ----------------------------------------------------------------------------------------- findExpiringLots

    @Test
    @DisplayName("findExpiringLots says which list was cut, names the quantity for what it is, and notes the window")
    void expiringLotsFlagCutListsAndTheWindow() throws Exception {
        StockAlertQueries alerts = mock(StockAlertQueries.class);
        InventoryControlService control = mock(InventoryControlService.class);
        when(control.expiryWarningDays()).thenReturn(30);
        ExpiryAlertView lidA = new ExpiryAlertView("EVAL-LID-90", "HCM", "EVAL-LID-A", LocalDate.of(2026, 11, 7),
                10L, 20, false, false, Instant.parse("2026-10-28T19:00:00Z"), false);
        ExpiryAlertView gone = new ExpiryAlertView("EVAL-LID-90", "HCM", "EVAL-LID-OLD", LocalDate.of(2026, 10, 1),
                -27L, 6, true, true, Instant.parse("2026-10-28T19:00:00Z"), true);
        when(alerts.openExpiryAlerts(anyInt(), eq(1))).thenReturn(
                new StockAlertQueries.ExpiryAlerts(List.of(lidA), 3, List.of(gone), 1));
        FindExpiringLotsTool tool = new FindExpiringLotsTool(alerts, control);

        FindExpiringLotsTool.Answer within = tool.run(args("{\"withinDays\":30,\"limit\":1}"));
        FindExpiringLotsTool.Answer beyond = tool.run(args("{\"withinDays\":90,\"limit\":1}"));

        assertThat(within.expiringTotal()).isEqualTo(3);
        assertThat(within.expiringTruncated()).isTrue();
        assertThat(within.expiredTotal()).isEqualTo(1);
        assertThat(within.expiredTruncated()).isFalse();
        assertThat(within.note()).isNull();
        assertThat(within.expiring().get(0).quantityAtLastCheck()).isEqualTo(20);
        assertThat(RESULTS.writeValueAsString(within)).contains("\"quantityAtLastCheck\":20")
                .doesNotContain("\"quantity\":", "\"note\"");
        assertThat(beyond.note()).contains("30 days");
        assertThat(beyond.warningWindowDays()).isEqualTo(30);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static ToolArguments args(String json) {
        return ToolArguments.parse(ARGUMENTS, json);
    }

    /** The inventory items that exist: {@code itemIdsOf} answers for these SKUs only. */
    private void known(String... skus) {
        Set<String> existing = Set.of(skus);
        when(inventory.itemIdsOf(anyCollection())).thenAnswer(call -> {
            Collection<String> asked = call.getArgument(0);
            return asked.stream().filter(existing::contains)
                    .collect(Collectors.toMap(Function.identity(), sku -> UUID.randomUUID()));
        });
    }

    private static StockAvailability stock(String location, String lot, LocalDate expiry, int onHand, int held) {
        return new StockAvailability(UUID.randomUUID(), TABLE, location, lot, expiry, onHand, held, 0, onHand - held);
    }

    private static String order(int number) {
        return "SO-20260901-%06d".formatted(number);
    }

    /** A lot received through a goods receipt, with {@code balances} locations holding some of it. */
    private static LotTraceView lot(String sku, String lotNumber, String supplier, List<LotTraceView.OrderLeg> orders,
                                    List<LotTraceView.Hold> holds, int balances) {
        List<LotTraceView.Balance> where = new ArrayList<>();
        for (int i = 0; i < balances; i++) {
            where.add(new LotTraceView.Balance("HCM-B%02d-1-A".formatted(i + 1), "AVAILABLE", 120, 12));
        }
        return new LotTraceView(sku, lotNumber, LocalDate.of(2027, 3, 31),
                Instant.parse("2026-08-30T03:04:05.123456Z"), "GOODS_RECEIPT", "GR-HCM-20260830-0001",
                "PO-HCM-20260815-0001", supplier, 100_000, 54_321, 40_000, 14_321, where, holds, orders);
    }

    /** Without a product module, {@code eligibility} is null and every SKU counts as purchasable. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReplenishmentEligibility> provider(ReplenishmentEligibility eligibility) {
        ObjectProvider<ReplenishmentEligibility> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(eligibility);
        return provider;
    }
}
