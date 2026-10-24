package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.StockDamageDisposed;
import com.stockflow.contracts.StockDamageReported;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** BR-STK-003 and the four-eyes decision on damaged stock, as plain rules of the damage report. */
class StockDamageReportTest {

    private static final Sku SKU = new Sku("CUP-PP-500");
    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID REPORTER = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();

    private static StockDamageReport.Photo photo() {
        return new StockDamageReport.Photo(UUID.randomUUID(), "qc-evidence/2026/10/24/" + UUID.randomUUID() + ".jpg",
                "broken.jpg", "image/jpeg", 2048, "a".repeat(64));
    }

    private static StockDamageReport.DamagedGoods goods(String serial) {
        return new StockDamageReport.DamagedGoods(StockItemId.newId(), SKU, "L1", serial, NOW, null,
                new LocationId("HCM-A01-1-A"), null, WAREHOUSE);
    }

    private static StockDamageReport report(int quantity, DamageReason reason, String note,
                                            List<StockDamageReport.Photo> photos) {
        return StockDamageReport.report("DMG-20261024-0001", UUID.randomUUID(), goods(null), StockItemId.newId(),
                new LocationId("HCM-QC01"), Quantity.of(quantity), reason, note, photos, null, REPORTER, NOW, 0);
    }

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("BR-STK-003: no report without a photo, nor with more than five")
    void photosAreRequired() {
        assertThat(refusal(() -> report(2, DamageReason.BROKEN, null, List.of())))
                .isEqualTo(ErrorCode.DAMAGE_PHOTO_REQUIRED);
        assertThat(refusal(() -> report(2, DamageReason.BROKEN, null,
                IntStream.range(0, 6).mapToObj(i -> photo()).toList()))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(report(2, DamageReason.BROKEN, null, List.of(photo())).photos()).hasSize(1);
    }

    @Test
    @DisplayName("a reason; OTHER says what in a note")
    void otherNeedsANote() {
        assertThat(refusal(() -> report(1, DamageReason.OTHER, "  ", List.of(photo()))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(report(1, DamageReason.OTHER, "label torn off", List.of(photo())).note()).isEqualTo("label torn off");
    }

    @Test
    @DisplayName("a serial number is one unit")
    void serialIsOneUnit() {
        assertThat(refusal(() -> StockDamageReport.report("DMG-1", UUID.randomUUID(), goods("SN-1"),
                StockItemId.newId(), new LocationId("HCM-QC01"), Quantity.of(2), DamageReason.BROKEN, null,
                List.of(photo()), null, REPORTER, NOW, 0))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("the report announces the damage once, with the SKU reservations it leaves uncovered")
    void reportAnnouncesTheDamage() {
        StockDamageReport report = StockDamageReport.report("DMG-1", UUID.randomUUID(), goods(null),
                StockItemId.newId(), new LocationId("HCM-QC01"), Quantity.of(3), DamageReason.WET_OR_MOULDY, null,
                List.of(photo()), null, REPORTER, NOW, 2);

        assertThat(report.pullDomainEvents()).singleElement()
                .extracting(event -> ((StockEvent) event).payload())
                .isInstanceOfSatisfying(StockDamageReported.class, event -> {
                    assertThat(event.quantity()).isEqualTo(3);
                    assertThat(event.origin()).isEqualTo("STOCK_CHECK");
                    assertThat(event.reasonCode()).isEqualTo("WET_OR_MOULDY");
                    assertThat(event.orderOnHold()).isFalse();
                    assertThat(event.skuReservationShortfall()).isEqualTo(2);
                });
        assertThat(report.isOpen()).isTrue();
        assertThat(report.pending()).isEqualTo(Quantity.of(3));
    }

    @Test
    @DisplayName("four eyes: the reporter never decides; the manager decides no more than is pending")
    void fourEyesAndPending() {
        StockDamageReport report = report(5, DamageReason.BROKEN, null, List.of(photo()));

        assertThat(refusal(() -> report.requireDecidable(DamageDisposition.WRITE_OFF, Quantity.of(1), null, REPORTER)))
                .isEqualTo(ErrorCode.DAMAGE_SELF_DISPOSITION);
        assertThat(refusal(() -> report.writeOff(UUID.randomUUID(), Quantity.of(1), null, REPORTER, "WO-1",
                UUID.randomUUID(), NOW))).isEqualTo(ErrorCode.DAMAGE_SELF_DISPOSITION);
        assertThat(refusal(() -> report.requireDecidable(DamageDisposition.WRITE_OFF, Quantity.of(6), null, MANAGER)))
                .isEqualTo(ErrorCode.DAMAGE_DISPOSITION_EXCEEDS_PENDING);

        report.writeOff(UUID.randomUUID(), Quantity.of(3), null, MANAGER, "WO-20261024-0001", UUID.randomUUID(), NOW);
        assertThat(report.pending()).isEqualTo(Quantity.of(2));
        assertThat(report.isOpen()).isTrue();
        report.restore(UUID.randomUUID(), Quantity.of(2), "outer carton dented, cups sound", MANAGER,
                new LocationId("HCM-A01-1-B"), StockItemId.newId(), NOW);

        assertThat(report.isOpen()).isFalse();
        assertThat(report.decided()).isEqualTo(Quantity.of(5));
        // Restoring after everything was written off or restored: nothing is pending any more.
        assertThat(refusal(() -> report.restore(UUID.randomUUID(), Quantity.of(1), "again", MANAGER,
                new LocationId("HCM-A01-1-B"), StockItemId.newId(), NOW)))
                .isEqualTo(ErrorCode.DAMAGE_DISPOSITION_EXCEEDS_PENDING);
    }

    @Test
    @DisplayName("restoring or sending back says why; a write-off carries its destruction record and adjustment")
    void decisionsCarryTheirFacts() {
        StockDamageReport report = report(4, DamageReason.DEFORMED, null, List.of(photo()));

        assertThat(refusal(() -> report.requireDecidable(DamageDisposition.RESTORE, Quantity.of(1), " ", MANAGER)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> report.requireDecidable(DamageDisposition.RETURN_TO_SUPPLIER, Quantity.of(1), null,
                MANAGER))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        report.pullDomainEvents();

        StockDamageReport.Disposition written = report.writeOff(UUID.randomUUID(), Quantity.of(1), null, MANAGER,
                "WO-20261024-0002", UUID.randomUUID(), NOW);
        StockDamageReport.Disposition back = report.returnToSupplier(UUID.randomUUID(), Quantity.of(2),
                "supplier credit note agreed", MANAGER, null, null, NOW);

        assertThat(written.recordNumber()).isEqualTo("WO-20261024-0002");
        assertThat(back.type()).isEqualTo(DamageDisposition.RETURN_TO_SUPPLIER);
        assertThat(report.pullDomainEvents()).hasSize(2).allSatisfy(event ->
                assertThat(((StockEvent) event).payload()).isInstanceOf(StockDamageDisposed.class));
        assertThatThrownBy(() -> new StockDamageReport.Disposition(UUID.randomUUID(), UUID.randomUUID(),
                DamageDisposition.WRITE_OFF, Quantity.of(1), null, null, null, null, null, null, null, MANAGER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a return to the supplier goes in parts or is refused, never more than decided; refused units wait again")
    void supplierShipment() {
        StockDamageReport report = report(4, DamageReason.BROKEN, null, List.of(photo()));
        StockDamageReport.Disposition back = report.returnToSupplier(UUID.randomUUID(), Quantity.of(3),
                "supplier credit note agreed", MANAGER, null, null, NOW);
        StockDamageReport.Disposition written = report.writeOff(UUID.randomUUID(), Quantity.of(1), null, MANAGER,
                "WO-1", UUID.randomUUID(), NOW);
        assertThat(report.pending()).isEqualTo(Quantity.ZERO);
        assertThat(report.awaitingSupplier()).isEqualTo(Quantity.of(3));

        assertThat(refusal(() -> report.shipToSupplier(UUID.randomUUID(), back.id(), Quantity.of(1), " ", null,
                MANAGER, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> report.requireShippable(written.id())))
                .isEqualTo(ErrorCode.DAMAGE_RETURN_NOT_SHIPPABLE);
        assertThat(refusal(() -> report.requireShippable(UUID.randomUUID()))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(refusal(() -> report.shipToSupplier(UUID.randomUUID(), back.id(), Quantity.of(4), "PXT-01", null,
                MANAGER, NOW))).as("more than decided").isEqualTo(ErrorCode.DAMAGE_RETURN_NOT_SHIPPABLE);

        UUID request = UUID.randomUUID();
        StockDamageReport.SupplierShipment shipped = report.shipToSupplier(request, back.id(), Quantity.of(2),
                " PXT-01 ", null, MANAGER, NOW);
        assertThat(shipped.deliveryNote()).isEqualTo("PXT-01");
        assertThat(report.shipmentFor(request)).contains(shipped);
        assertThat(report.remainingToHandOver(back.id())).isEqualTo(Quantity.of(1));

        assertThat(refusal(() -> report.refuseReturn(UUID.randomUUID(), back.id(), Quantity.of(1), " ", MANAGER, NOW)))
                .as("the supplier's answer is recorded").isEqualTo(ErrorCode.VALIDATION_FAILED);
        report.refuseReturn(UUID.randomUUID(), back.id(), Quantity.of(1), "Nhà cung cấp không nhận lại 1 ly", MANAGER,
                NOW);
        assertThat(report.awaitingSupplier()).isEqualTo(Quantity.ZERO);
        assertThat(report.pending()).as("the refused unit waits for a decision again").isEqualTo(Quantity.of(1));
        assertThat(report.isOpen()).isTrue();
        assertThat(refusal(() -> report.requireShippable(back.id()))).as("nothing left of the decision")
                .isEqualTo(ErrorCode.DAMAGE_RETURN_NOT_SHIPPABLE);
        report.writeOff(UUID.randomUUID(), Quantity.of(1), null, MANAGER, "WO-2", UUID.randomUUID(), NOW);
        assertThat(report.isOpen()).isFalse();

        assertThatThrownBy(() -> new StockDamageReport(report.id(), report.number(), report.requestId(),
                report.origin(), report.goods(), report.damagedStockItem(), report.damageLocation(), report.quantity(),
                report.reason(), report.note(), report.photos(), report.pick(), report.reportedBy(),
                report.reportedAt(), report.dispositions(), List.of(new StockDamageReport.SupplierShipment(
                        UUID.randomUUID(), UUID.randomUUID(), written.id(), Quantity.of(1), "PXT-02", null, MANAGER,
                        NOW)), 0L)).as("a write-off is never shipped, not even from a corrupt row")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the same decision request is found again, not decided twice")
    void decisionsAreFoundByRequest() {
        StockDamageReport report = report(2, DamageReason.BROKEN, null, List.of(photo()));
        UUID request = UUID.randomUUID();
        report.writeOff(request, Quantity.of(1), null, MANAGER, "WO-1", UUID.randomUUID(), NOW);

        assertThat(report.dispositionFor(request)).isPresent();
        assertThatThrownBy(() -> report.writeOff(request, Quantity.of(1), null, MANAGER, "WO-2", UUID.randomUUID(),
                NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a row decided by its reporter cannot be loaded: the rule is checked on rehydration too")
    void rehydrationChecksTheRules() {
        StockDamageReport.Disposition self = new StockDamageReport.Disposition(UUID.randomUUID(), UUID.randomUUID(),
                DamageDisposition.WRITE_OFF, Quantity.of(1), null, "WO-1", UUID.randomUUID(), null, null, null, null,
                REPORTER, NOW);
        assertThatThrownBy(() -> new StockDamageReport(UUID.randomUUID(), "DMG-1", UUID.randomUUID(),
                StockDamageReport.Origin.STOCK_CHECK, goods(null), StockItemId.newId(), new LocationId("HCM-QC01"),
                Quantity.of(1), DamageReason.BROKEN, null, List.of(photo()), null, REPORTER, NOW, List.of(self), 0L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StockDamageReport(UUID.randomUUID(), "DMG-1", UUID.randomUUID(),
                StockDamageReport.Origin.PICKING, goods(null), StockItemId.newId(), new LocationId("HCM-QC01"),
                Quantity.of(1), DamageReason.BROKEN, null, List.of(photo()), null, REPORTER, NOW, List.of(), 0L))
                .as("a picker's report names its pick line").isInstanceOf(IllegalStateException.class);
    }
}
