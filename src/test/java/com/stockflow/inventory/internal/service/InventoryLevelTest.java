package com.stockflow.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.StockLevel;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockLevelLine;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

/**
 * Inventory Level is a roll-up, so the test feeds it subtotals and checks the glossary's
 * arithmetic: on-hand counts every status, available only {@code AVAILABLE}, ATP is available minus
 * reservations, and a warehouse is whatever precedes the first hyphen of a location code.
 */
class InventoryLevelTest {

    private static final Sku SKU = new Sku("LY-GIAY-DEMO-12OZ-XANH");

    private final StockPackageRepository repository = mock(StockPackageRepository.class);
    private final com.stockflow.inventory.internal.domain.LocationDirectory locations =
            mock(com.stockflow.inventory.internal.domain.LocationDirectory.class);
    private final com.stockflow.inventory.internal.domain.SkuReservationRepository skuReservations =
            mock(com.stockflow.inventory.internal.domain.SkuReservationRepository.class);
    private final InventoryServiceImpl service =
            new InventoryServiceImpl(
                    mock(InventoryEventPublisher.class),
                    mock(StockOperations.class),
                    mock(com.stockflow.inventory.internal.domain.InventoryItemDirectory.class),
                    Clock.systemUTC(),
                    mock(InventoryPolicyRepository.class),
                        skuReservations,
                        locations,
                        mock(com.stockflow.inventory.internal.domain.CycleCountRepository.class),
                    repository, mock(com.stockflow.common.security.CurrentUserProvider.class));

    /**
     * Review C4 P-1: a warehouse promises what a release there could allocate - the units of the free cartons on
     * pickable bins, which the repository offers as {@code Allocatable} cartons. Every location here is a pickable bin.
     */
    private void allocatable(java.util.Map<String, Integer> freeUnits) {
        when(repository.findAllocatable(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(freeUnits.entrySet()
                .stream().map(e -> new StockPackageRepository.Allocatable(
                        new StockPackageId(java.util.UUID.randomUUID()), SKU.code(),
                        e.getKey(), e.getValue())).toList());
        when(locations.pickable(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(c ->
                c.<java.util.Collection<String>>getArgument(0).stream().collect(java.util.stream.Collectors.toMap(
                        code -> code, code -> java.util.UUID.randomUUID())));
    }

    private static StockLevelLine line(
            String location, StockStatus status, int onHand, int reserved, int cartons, int heldCartons) {
        return new StockLevelLine(new LocationId(location), status, onHand, reserved, cartons, heldCartons);
    }

    @Test
    @DisplayName(
            "sums locations per warehouse; only AVAILABLE counts as available, ATP subtracts holds")
    void rollsUpPerWarehouse() {
        when(repository.findLevelLinesBySku(SKU))
                .thenReturn(
                        List.of(
                                line("HN-A01-2-03", StockStatus.AVAILABLE, 10, 4, 2, 1),
                                line("HN-A01-2-04", StockStatus.AVAILABLE, 5, 0, 1, 0),
                                line("HN-QC01", StockStatus.QUARANTINE, 7, 0, 1, 0),
                                line("HCM-A01-1-01", StockStatus.AVAILABLE, 3, 1, 3, 1)));
        allocatable(java.util.Map.of("HN-A01-2-03", 6, "HN-A01-2-04", 5, "HCM-A01-1-01", 2));

        List<StockLevel> levels = service.levelsOf(SKU);

        assertThat(levels).extracting(StockLevel::warehouseCode).containsExactly("HCM", "HN");
        assertThat(levels.get(0)).isEqualTo(
                new StockLevel("LY-GIAY-DEMO-12OZ-XANH", "HCM", 3, 3, 1, 0, 2, 3, 3, 1, 0, 1));
        assertThat(levels.get(1)).isEqualTo(
                new StockLevel("LY-GIAY-DEMO-12OZ-XANH", "HN", 22, 15, 4, 0, 11, 4, 3, 1, 0, 2));
    }

    @Test
    @DisplayName("allocations are shown apart from the other holds of their warehouse; ATP is unchanged")
    void allocatedIsSplitFromReserved() {
        when(repository.findLevelLinesBySku(SKU))
                .thenReturn(
                        List.of(
                                line("HN-A01-2-03", StockStatus.AVAILABLE, 10, 4, 2, 1),
                                line("HN-A01-2-04", StockStatus.AVAILABLE, 5, 2, 1, 1),
                                line("HCM-A01-1-01", StockStatus.AVAILABLE, 3, 1, 3, 1)));
        when(repository.findAllocatedBySku(SKU))
                .thenReturn(java.util.Map.of("HN-A01-2-03", new StockPackageRepository.Allocated(3, 1),
                        "HN-A01-2-04", new StockPackageRepository.Allocated(2, 1)));
        allocatable(java.util.Map.of("HN-A01-2-03", 6, "HN-A01-2-04", 3, "HCM-A01-1-01", 2));

        List<StockLevel> levels = service.levelsOf(SKU);

        assertThat(levels.get(0)).isEqualTo(
                new StockLevel("LY-GIAY-DEMO-12OZ-XANH", "HCM", 3, 3, 1, 0, 2, 3, 3, 1, 0, 1));
        assertThat(levels.get(1)).isEqualTo(
                new StockLevel("LY-GIAY-DEMO-12OZ-XANH", "HN", 15, 15, 1, 5, 9, 3, 3, 0, 2, 2));
    }

    @Test
    @DisplayName("ADR-0011 3e: cartons are counted, and ATP in cartons is worked out per count as a hold is promised")
    void cartonsOfTwoCounts() {
        when(repository.findLevelLinesBySku(SKU)).thenReturn(List.of(
                line("HN-A01-2-03", StockStatus.AVAILABLE, 2000, 0, 2, 0),
                line("HN-A01-2-04", StockStatus.AVAILABLE, 500, 0, 1, 0)));
        when(repository.findAllocatable(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(
                new StockPackageRepository.Allocatable(new StockPackageId(java.util.UUID.randomUUID()), SKU.code(),
                        "HN-A01-2-03", 1000),
                new StockPackageRepository.Allocatable(new StockPackageId(java.util.UUID.randomUUID()), SKU.code(),
                        "HN-A01-2-03", 1000),
                new StockPackageRepository.Allocatable(new StockPackageId(java.util.UUID.randomUUID()), SKU.code(),
                        "HN-A01-2-04", 500)));
        when(locations.pickable(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(c ->
                c.<java.util.Collection<String>>getArgument(0).stream().collect(java.util.stream.Collectors.toMap(
                        code -> code, code -> java.util.UUID.randomUUID())));
        // Two holds of cartons of 1000 at HN take both of that count: the carton of 500 is all that is left to
        // promise, although 2500 - 2000 units would divide into no whole number of either count.
        when(skuReservations.heldCartonsOf(SKU.code())).thenReturn(List.of(
                new com.stockflow.inventory.internal.domain.SkuReservationRepository.HeldCartons("HN", 1000, 2)));
        when(skuReservations.heldBySkuAt(SKU.code())).thenReturn(java.util.Map.of("HN", 2000L));

        StockLevel level = service.levelsOf(SKU).getFirst();

        assertThat(level.onHand()).isEqualTo(2500);
        assertThat(level.onHandCartons()).isEqualTo(3);
        assertThat(level.availableCartons()).isEqualTo(3);
        assertThat(level.atp()).isEqualTo(500);
        assertThat(level.atpCartons()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-0011: a line is promised only cartons of its own count, per warehouse and system-wide")
    void cartonsToPromiseOfOneCount() {
        // HCM: 10 cartons of 100, 40 of 25, 4 of 50; HN: 2 of 50, one of them held there by SKU.
        List<StockPackageRepository.Allocatable> free = new java.util.ArrayList<>();
        java.util.function.BiConsumer<String, int[]> cartons = (location, countAndHowMany) -> {
            for (int i = 0; i < countAndHowMany[1]; i++) {
                free.add(new StockPackageRepository.Allocatable(new StockPackageId(java.util.UUID.randomUUID()),
                        SKU.code(), location, countAndHowMany[0]));
            }
        };
        cartons.accept("HCM-A01-1-01", new int[] {100, 10});
        cartons.accept("HCM-A01-1-02", new int[] {25, 40});
        cartons.accept("HCM-A01-1-03", new int[] {50, 4});
        cartons.accept("HN-A01-2-03", new int[] {50, 2});
        when(repository.findAllocatable(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(free);
        when(locations.pickable(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(c ->
                c.<java.util.Collection<String>>getArgument(0).stream().collect(java.util.stream.Collectors.toMap(
                        code -> code, code -> java.util.UUID.randomUUID())));
        var hold = new com.stockflow.inventory.internal.domain.SkuReservationRepository.HeldCartons("HN", 50, 1);
        when(skuReservations.heldCartonsOf(SKU.code())).thenReturn(List.of(hold));
        when(skuReservations.heldCartonsBySku(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(java.util.Map.of(SKU.code(), List.of(hold)));

        // 2,200 free units at HCM would divide into 44 cartons of 50; only 4 such cartons exist there.
        assertThat(service.cartonsToPromiseByWarehouse(SKU, 50))
                .containsExactlyEntriesOf(new java.util.TreeMap<>(java.util.Map.of("HCM", 4, "HN", 1)));
        assertThat(service.cartonsToPromiseByWarehouse(SKU, 100)).containsExactlyEntriesOf(java.util.Map.of("HCM", 10));
        assertThat(service.cartonsToPromiseByWarehouse(SKU, 1000)).isEmpty();

        // System-wide, every SKU asked is answered in the order asked: 6 cartons of 50 less the one held.
        Sku other = new Sku("LY-GIAY-DEMO-16OZ-DO");
        java.util.Map<Sku, Integer> asked = new java.util.LinkedHashMap<>();
        asked.put(other, 50);
        asked.put(SKU, 50);
        assertThat(service.cartonsToPromise(asked)).containsExactly(
                java.util.Map.entry(other, 0), java.util.Map.entry(SKU, 5));
        assertThat(service.cartonsToPromise(java.util.Map.of(SKU, 25))).isEqualTo(java.util.Map.of(SKU, 40));
        assertThat(service.cartonsToPromise(java.util.Map.of(SKU, 10))).isEqualTo(java.util.Map.of(SKU, 0));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cartonsToPromiseByWarehouse(SKU, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ATP of one warehouse ignores the others; an unknown warehouse promises nothing")
    void atpPerWarehouse() {
        when(repository.findLevelLinesBySku(SKU))
                .thenReturn(
                        List.of(
                                line("HN-A01-2-03", StockStatus.AVAILABLE, 10, 4, 2, 1),
                                line("HCM-A01-1-01", StockStatus.AVAILABLE, 3, 1, 3, 1)));
        allocatable(java.util.Map.of("HN-A01-2-03", 6, "HCM-A01-1-01", 2));

        assertThat(service.availableToPromise(SKU, "hn")).isEqualTo(6);
        assertThat(service.availableToPromise(SKU, "HCM")).isEqualTo(2);
        assertThat(service.availableToPromise(SKU, "DN")).isZero();
    }

    @Test
    @DisplayName("a SKU with no stock has no levels")
    void emptyWhenNoStock() {
        when(repository.findLevelLinesBySku(SKU)).thenReturn(List.of());

        assertThat(service.levelsOf(SKU)).isEmpty();
    }

    @Test
    @DisplayName("warehouse code is the segment before the first hyphen, for bins and areas alike")
    void warehouseCodeFromLocation() {
        assertThat(new LocationId("hn-a01-2-03").warehouseCode()).isEqualTo("HN");
        assertThat(new LocationId("HN-RCV01").warehouseCode()).isEqualTo("HN");
        assertThat(new LocationId("STAGING").warehouseCode()).isEqualTo("STAGING");
    }
}
