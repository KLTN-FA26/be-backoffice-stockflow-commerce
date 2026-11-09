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
        assertThatThrownBy(() -> command(StockDisposition.AVAILABLE, MoveReference.MOVE_TASK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the putaway of a released carton is a putaway to AVAILABLE and nothing else (team plan 4D-1)")
    void quarantineReleaseIsAPutaway() {
        assertThat(new ReclassifyStockPackagesCommand("LPN-20261026-000001", "HCM-A01-2-B", StockDisposition.AVAILABLE,
                MoveReference.PUTAWAY_TASK, Identifiers.newId(), Identifiers.newId(), null, true).quarantineRelease())
                .isTrue();
        assertThatThrownBy(() -> new ReclassifyStockPackagesCommand("LPN-20261026-000001", "HCM-QC01",
                StockDisposition.QUARANTINE, MoveReference.PUTAWAY_TASK, Identifiers.newId(), Identifiers.newId(), null,
                true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(command(StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK).quarantineRelease()).isFalse();
    }

    @Test
    @DisplayName("a carton may still be quarantined or blocked under another reference")
    void cartonsAreStillQuarantined() {
        assertThat(command(StockDisposition.QUARANTINE, MoveReference.MOVE_TASK).disposition())
                .isEqualTo(StockDisposition.QUARANTINE);
        assertThat(command(StockDisposition.BLOCKED, MoveReference.MOVE_TASK).disposition())
                .isEqualTo(StockDisposition.BLOCKED);
    }
}
