package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.CartonFit;
import com.stockflow.inventory.internal.domain.PutawayRanker.Need;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.warehouse.api.BinSize;
import com.stockflow.warehouse.api.PutawayCandidate;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.StorageLocationView.AreaType;
import com.stockflow.warehouse.api.StorageLocationView.Kind;
import com.stockflow.warehouse.api.StorageLocationView.Status;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * kltn-docs 07 BR-12 (a carton of a cancelled order goes back onto a bin it fits, or into OVERFLOW when no bin takes it),
 * 05 BR-13 and 06 BR-01: where a put-back may go, and which cartons may go to OVERFLOW.
 */
class CartonPlacementTest {

    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID CARTON = UUID.randomUUID();
    private static final String SMALL_BIN = "HCM-A01-1-A";
    private static final String BIG_BIN = "HCM-A01-1-B";
    private static final String OVERFLOW = "HCM-OVF01";
    private static final String QUARANTINE = "HCM-QUA01";

    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final BinOccupancyQuery space = mock(BinOccupancyQuery.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final CartonPlacement placement = new CartonPlacement(warehouses, space, stockPackages);

    /** A carton 0.6 x 0.4 x 0.4 m, which may not be turned. */
    private void cartonIsLarge() {
        when(space.needsOf(any())).thenReturn(Map.of(CARTON, new Need(new CartonFit.Carton(new BigDecimal("60"),
                new BigDecimal("40"), new BigDecimal("40"), false), null)));
    }

    private static BinSize size(String width, String length, String height) {
        return new BinSize(new BigDecimal(width), new BigDecimal(length), new BigDecimal(height));
    }

    private void binsInTheWarehouse(BinSize... sizes) {
        List<PutawayCandidate> candidates = new java.util.ArrayList<>();
        for (BinSize size : sizes) {
            candidates.add(new PutawayCandidate(UUID.randomUUID(), "BIN", size, null));
        }
        when(warehouses.putawayCandidates(WAREHOUSE)).thenReturn(candidates);
    }

    private void location(String code, Kind kind, AreaType area, BinSize size) {
        when(warehouses.findLocation(code)).thenReturn(Optional.of(new StorageLocationView(UUID.randomUUID(), kind,
                WAREHOUSE, code, Status.ACTIVE, Status.ACTIVE, true, area, size)));
    }

    private BusinessException refusal(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }

    @Test
    @DisplayName("a bin the carton fits takes it; one it does not fit is CARTON_DOES_NOT_FIT_BIN, naming the carton and the bin")
    void aBinMustTakeTheCarton() {
        cartonIsLarge();
        location(BIG_BIN, Kind.BIN, null, size("0.5", "0.7", "0.5"));
        location(SMALL_BIN, Kind.BIN, null, size("0.45", "0.45", "1.4"));
        when(stockPackages.findById(any())).thenReturn(Optional.empty());

        placement.requireFits(BIG_BIN, Map.of(CARTON, "LPN-1"));
        BusinessException refused = refusal(() -> placement.requireFits(SMALL_BIN, Map.of(CARTON, "LPN-1")));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.CARTON_DOES_NOT_FIT_BIN);
    }

    @Test
    @DisplayName("OVERFLOW takes a carton no bin of the warehouse takes - and it is refused while one does: "
            + "PUT_BACK_OVERFLOW_NOT_ALLOWED")
    void overflowOnlyWhenNoBinTakesIt() {
        cartonIsLarge();
        location(OVERFLOW, Kind.AREA, AreaType.OVERFLOW, null);
        com.stockflow.inventory.internal.domain.StockPackageId id =
                new com.stockflow.inventory.internal.domain.StockPackageId(CARTON);

        binsInTheWarehouse(size("0.45", "0.45", "1.4"), size("0.3", "0.3", "0.3"));
        placement.requirePutBackTarget(OVERFLOW, id);

        binsInTheWarehouse(size("0.45", "0.45", "1.4"), size("0.5", "0.7", "0.5"));
        assertThat(refusal(() -> placement.requirePutBackTarget(OVERFLOW, id)).errorCode())
                .isEqualTo(ErrorCode.PUT_BACK_OVERFLOW_NOT_ALLOWED);
    }

    @Test
    @DisplayName("a warehouse with no bin to store in leaves every carton nowhere to go but OVERFLOW")
    void noBinsAtAll() {
        cartonIsLarge();
        binsInTheWarehouse();

        assertThat(placement.fittingNoBin(WAREHOUSE, List.of(CARTON))).containsExactly(CARTON);
    }

    @Test
    @DisplayName("a carton without a full size cannot be judged, so it counts as fitting - a bin takes it, as CartonFit lets it through")
    void anUnmeasuredCartonFitsAnyBin() {
        when(space.needsOf(any())).thenReturn(Map.of(CARTON, Need.UNMEASURED));
        binsInTheWarehouse(size("0.1", "0.1", "0.1"));

        assertThat(placement.fittingNoBin(WAREHOUSE, List.of(CARTON))).isEmpty();
    }

    @Test
    @DisplayName("a carton fits-no-bin is told apart from one that does: the one a bin takes is not named")
    void onlyTheCartonsNoBinTakesAreNamed() {
        UUID small = UUID.randomUUID();
        when(space.needsOf(any())).thenReturn(Map.of(
                CARTON, new Need(new CartonFit.Carton(new BigDecimal("60"), new BigDecimal("40"),
                        new BigDecimal("40"), false), null),
                small, new Need(new CartonFit.Carton(new BigDecimal("20"), new BigDecimal("20"),
                        new BigDecimal("20"), true), null)));
        binsInTheWarehouse(size("0.45", "0.45", "1.4"));

        assertThat(placement.fittingNoBin(WAREHOUSE, List.of(CARTON, small))).isEqualTo(Set.of(CARTON));
    }

    @Test
    @DisplayName("any other area (quarantine) is not judged here; a missing location is let be")
    void otherAreasAreNotJudged() {
        cartonIsLarge();
        location(QUARANTINE, Kind.AREA, AreaType.QUARANTINE, null);
        when(warehouses.findLocation("NOWHERE")).thenReturn(Optional.empty());
        when(stockPackages.findById(any())).thenReturn(Optional.empty());
        com.stockflow.inventory.internal.domain.StockPackageId id =
                new com.stockflow.inventory.internal.domain.StockPackageId(CARTON);

        placement.requirePutBackTarget(QUARANTINE, id);
        placement.requirePutBackTarget("NOWHERE", id);
    }
}
