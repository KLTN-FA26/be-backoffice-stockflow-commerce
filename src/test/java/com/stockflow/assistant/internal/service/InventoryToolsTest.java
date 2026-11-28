package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.LotTraceQueries;
import com.stockflow.inventory.api.LotTraceView;
import com.stockflow.inventory.api.StockAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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
    @DisplayName("getStockLots leaves out a bin a pick emptied, though its lot goes out first, and counts what it lists")
    void stockLotsLeaveOutEmptiedRows() {
        known(TABLE);
        when(inventory.availabilityOf(new Sku(TABLE))).thenReturn(List.of(
                stock("HCM-B01-1-A", "EVAL-LOT-2401", 0, 0),
                stock("HCM-B01-1-A", "LOT-2026-03", 12, 0)));

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
        for (int i = 0; i < 60; i++) {
            if (i % 10 == 0) {
                rows.add(stock("HCM-A01-1-B", "EMPTIED-" + i, 0, 0));
            }
            rows.add(stock("HCM-A01-1-B", "LOT-" + i, 5, 1));
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
    @DisplayName("getStockLots keeps a row under a blind count with no figures and the flag, even at zero, as the "
            + "stock-items screen hides it from a counter (20 BR-07)")
    void stockLotsHideTheBlindCountsBooks() {
        known(TABLE);
        StockAvailability counted = stock("HCM-B01-1-A", "LOT-A", 40, 4);
        StockAvailability countedEmpty = stock("HCM-B01-1-B", "LOT-B", 0, 0);
        StockAvailability free = stock("HCM-B02-1-A", "LOT-C", 12, 0);
        when(inventory.availabilityOf(new Sku(TABLE))).thenReturn(List.of(counted, countedEmpty, free));
        com.stockflow.inventory.api.BlindCountQueries blind = mock(com.stockflow.inventory.api.BlindCountQueries.class);
        when(blind.hiddenFromCaller(anyCollection()))
                .thenReturn(Set.of(counted.stockPackageId(), countedEmpty.stockPackageId()));

        GetStockLotsTool.Answer answer = new GetStockLotsTool(inventory, blind)
                .run(args("{\"sku\":\"" + TABLE + "\"}"));

        assertThat(answer.total()).isEqualTo(3);
        assertThat(answer.locations()).extracting(GetStockLotsTool.Row::lotNumber, GetStockLotsTool.Row::onHand,
                        GetStockLotsTool.Row::held, GetStockLotsTool.Row::unheld,
                        GetStockLotsTool.Row::hiddenDuringCount)
                .containsExactly(tuple("LOT-A", null, null, null, true), tuple("LOT-B", null, null, null, true),
                        tuple("LOT-C", 12, 0, 12, null));
        assertThat(answer.locations().getFirst().onHandPacked()).isNull();
        verify(blind).hiddenFromCaller(List.of(counted.stockPackageId(), countedEmpty.stockPackageId(),
                free.stockPackageId()));
    }

    @Test
    @DisplayName("getStockLots asks inventory who is blind only when there are rows, and shows a seeing user every "
            + "figure")
    void stockLotsForWhoMayDecideCounts() {
        known(TABLE);
        com.stockflow.inventory.api.BlindCountQueries blind = mock(com.stockflow.inventory.api.BlindCountQueries.class);
        when(blind.hiddenFromCaller(anyCollection())).thenReturn(Set.of());
        when(inventory.availabilityOf(new Sku(TABLE))).thenReturn(List.of(stock("HCM-B01-1-A", "LOT-A", 40, 4)));

        GetStockLotsTool.Answer seen = new GetStockLotsTool(inventory, blind).run(args("{\"sku\":\"" + TABLE + "\"}"));

        assertThat(seen.locations()).extracting(GetStockLotsTool.Row::onHand, GetStockLotsTool.Row::hiddenDuringCount)
                .containsExactly(tuple(40, null));

        when(inventory.availabilityOf(new Sku(TABLE))).thenReturn(List.of());
        com.stockflow.inventory.api.BlindCountQueries unused =
                mock(com.stockflow.inventory.api.BlindCountQueries.class);
        assertThat(new GetStockLotsTool(inventory, unused).run(args("{\"sku\":\"" + TABLE + "\"}")).locations())
                .isEmpty();
        verifyNoInteractions(unused);
    }

    @Test
    @DisplayName("getStockLevels: held on stock is reserved plus allocated, allocated to picks apart, and an order's "
            + "hold at the warehouse - on no stock row - beside them")
    void stockLevelsNameEveryHold() {
        known("EVAL-CUP-16OZ");
        Sku sku = new Sku("EVAL-CUP-16OZ");
        // HCM: 500 in available status, 60 allocated to a pick, 100 held by a contract's order there -> 340 to promise.
        when(inventory.levelsOf(sku)).thenReturn(List.of(
                new com.stockflow.inventory.api.StockLevel(sku.code(), "HCM", 520, 500, 0, 60, 340, 26, 25, 0, 3, 17),
                new com.stockflow.inventory.api.StockLevel(sku.code(), "HN", 80, 80, 5, 0, 75, 16, 16, 1, 0, 15)));
        when(inventory.orderHoldsByWarehouse(sku)).thenReturn(java.util.Map.of("HCM", 100));
        when(inventory.availableToPromise(sku)).thenReturn(415);

        GetStockLevelsTool.Answer answer = new GetStockLevelsTool(inventory).run(args("{\"sku\":\"EVAL-CUP-16OZ\"}"));

        assertThat(answer.companyAvailableToPromise()).isEqualTo(415);
        assertThat(answer.warehouses()).extracting(GetStockLevelsTool.Row::warehouseCode,
                        GetStockLevelsTool.Row::inAvailableStatus, GetStockLevelsTool.Row::heldOnStock,
                        GetStockLevelsTool.Row::allocatedToPicks, GetStockLevelsTool.Row::heldForOrders,
                        GetStockLevelsTool.Row::availableToPromise)
                .containsExactly(tuple("HCM", 500, 60, 60, 100, 340), tuple("HN", 80, 5, 0, 0, 75));
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
        verify(inventory, never()).orderHoldsByWarehouse(any());
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
        holds.add(new LotTraceView.Hold(order(38), "HCM-B01-2-A", 2, "HELD", start, null));
        when(lots.traceLot("LOT-A", null, TraceLotTool.MAX_LOTS)).thenReturn(new LotTraceQueries.Traced(
                List.of(lot(TABLE, "LOT-A", orders, holds, 2)), 1));

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
                .containsExactly(tuple(order(36), 4), tuple(order(38), 2));
        assertThat(traced.balancesTotal()).isEqualTo(2);
        assertThat(traced.balancesTruncated()).isFalse();
        assertThat(traced.balances()).hasSize(2);
        verifyNoInteractions(inventory);
    }

    @Test
    @DisplayName("ten fully loaded lots share the rows out, each list still counted, and the answer fits in one result")
    void traceLotOfManyLotsFitsInOneResult() throws Exception {
        LotTraceQueries lots = mock(LotTraceQueries.class);
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
            traced.add(lot("CUP-12OZ-KRAFT-DOUBLE-WALL-" + n, "2410", orders, holds, 60));
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

    @Test
    @DisplayName("traceLot passes a location under a blind count on with no figures and the flag, never as zero")
    void traceLotKeepsTheBlindCountsSecret() {
        LotTraceQueries lots = mock(LotTraceQueries.class);
        LotTraceView view = new LotTraceView(TABLE, "LOT-A", Instant.parse("2026-08-30T03:04:05Z"), "GOODS_RECEIPT",
                "LSX-0001", 100, 60, 60, 0,
                List.of(new LotTraceView.Balance("HCM-B01-1-A", "AVAILABLE", 60, 0, false),
                        new LotTraceView.Balance("HCM-B02-1-A", "AVAILABLE", null, null, true)),
                List.of(), List.of());
        when(lots.traceLot("LOT-A", null, TraceLotTool.MAX_LOTS)).thenReturn(new LotTraceQueries.Traced(List.of(view),
                1));

        TraceLotTool.Lot traced = new TraceLotTool(lots, inventory).run(args("{\"lotNumber\":\"LOT-A\",\"sku\":\"\"}"))
                .lots().getFirst();

        assertThat(traced.productionOrderCode()).isEqualTo("LSX-0001");
        assertThat(traced.balances()).extracting(TraceLotTool.Balance::onHand, TraceLotTool.Balance::hiddenDuringCount)
                .containsExactly(tuple(60, null), tuple(null, true));
    }

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

    private static StockAvailability stock(String location, String lot, int onHand, int held) {
        return new StockAvailability(UUID.randomUUID(), TABLE, location, lot, onHand, held, 0, onHand - held);
    }

    private static String order(int number) {
        return "SO-20260901-%06d".formatted(number);
    }

    /** A lot received against a production order, with {@code balances} locations holding some of it. */
    private static LotTraceView lot(String sku, String lotNumber, List<LotTraceView.OrderLeg> orders,
                                    List<LotTraceView.Hold> holds, int balances) {
        List<LotTraceView.Balance> where = new ArrayList<>();
        for (int i = 0; i < balances; i++) {
            where.add(new LotTraceView.Balance("HCM-B%02d-1-A".formatted(i + 1), "AVAILABLE", 120, 12, false));
        }
        return new LotTraceView(sku, lotNumber, Instant.parse("2026-08-30T03:04:05.123456Z"), "GOODS_RECEIPT",
                "LSX-HCM-20260815-0001", 100_000, 54_321, 40_000, 14_321, where, holds,
                orders);
    }
}
