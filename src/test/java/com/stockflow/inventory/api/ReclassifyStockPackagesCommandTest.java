package com.stockflow.inventory.api;

import com.stockflow.common.id.Identifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which carton decisions the api accepts: AVAILABLE is putaway's alone (SCRUM-92). */
class ReclassifyStockPackagesCommandTest {

    private static ReclassifyStockPackagesCommand command(StockDisposition disposition, MoveReference reference) {
        return new ReclassifyStockPackagesCommand("LPN-20261026-000001", "HCM-A01-2-B", disposition, reference,
                Identifiers.newId(), Identifiers.newId(), null);
    }

    @Test
    @DisplayName("a putaway task makes a carton AVAILABLE")
    void putawayMakesAvailable() {
        assertThat(command(StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK).disposition())
                .isEqualTo(StockDisposition.AVAILABLE);
    }

    @Test
    @DisplayName("any other reference cannot make a carton AVAILABLE, which would skip every bin rule")
    void otherReferencesCannotMakeAvailable() {
        assertThatThrownBy(() -> command(StockDisposition.AVAILABLE, MoveReference.QC_INSPECTION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("QC still quarantines or blocks a carton")
    void qcStillQuarantines() {
        assertThat(command(StockDisposition.QUARANTINE, MoveReference.QC_INSPECTION).disposition())
                .isEqualTo(StockDisposition.QUARANTINE);
        assertThat(command(StockDisposition.BLOCKED, MoveReference.QC_INSPECTION).disposition())
                .isEqualTo(StockDisposition.BLOCKED);
    }
}
