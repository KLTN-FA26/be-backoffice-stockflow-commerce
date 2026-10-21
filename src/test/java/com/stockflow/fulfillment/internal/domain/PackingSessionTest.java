package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** The packing rules of kltn-docs 08 (step 2, 5-9, BR-01/03/04/05/06) on the session alone, without Spring. */
class PackingSessionTest {

    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    private static final UUID PACKER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID CUPS = UUID.randomUUID();
    private static final UUID LIDS = UUID.randomUUID();
    private static final UUID PRINTED = UUID.randomUUID();
    private static final UUID SCANNER = UUID.randomUUID();

    /** 10 plain cups (lot L1 x6, L2 x4, picked), 5 lids (no lot, rotatable=false), 4 printed cups of the same SKU. */
    private PackingSession session() {
        var cups = new PackingSession.Line(CUPS, "CUP-12OZ", "8930000000101", 10, 10, false, null,
                Map.of("L1", 6, "L2", 4), Set.of(), true, false);
        var lids = new PackingSession.Line(LIDS, "LID-90", null, 5, 5, false, null, Map.of(), Set.of(), false, true);
        var printed = new PackingSession.Line(PRINTED, "CUP-12OZ", "8930000000101", 4, 4, true, UUID.randomUUID(),
                Map.of(), Set.of(), true, false);
        var session = new PackingSession(UUID.randomUUID(), "SO-20261021-000001", PackStatus.PENDING, null, null, null,
                null, List.of(cups, lids, printed), List.of(), List.of());
        session.start(PACKER, false, NOW);
        return session;
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static PackingSession.Measures box() {
        return new PackingSession.Measures(new BigDecimal("4.2"), new BigDecimal("40"), new BigDecimal("30"),
                new BigDecimal("25"));
    }

    @Test
    @DisplayName("packages are numbered and labelled PKG-<order>-<n>")
    void numbering() {
        var session = session();
        var first = session.openPackage(UUID.randomUUID(), null, NOW);
        var second = session.openPackage(UUID.randomUUID(), PackageType.BAG, NOW);
        assertThat(first.labelCode()).isEqualTo("PKG-SO-20261021-000001-1");
        assertThat(first.type()).isEqualTo(PackageType.CARTON);
        assertThat(second.packageNo()).isEqualTo(2);
        assertThat(second.type()).isEqualTo(PackageType.BAG);
    }

    @Test
    @DisplayName("a scan by barcode with a lot fills the package; the same scanId again changes nothing")
    void scanAndReplay() {
        var session = session();
        var box = session.openPackage(UUID.randomUUID(), null, NOW);
        UUID scan = UUID.randomUUID();
        assertThat(session.scan(scan, box.id(), "LID-90", null, null, 3, null, PACKER, NOW)).isTrue();
        assertThat(session.scan(scan, box.id(), "LID-90", null, null, 3, null, PACKER, NOW)).isFalse();
        assertThat(session.packedOf(LIDS)).isEqualTo(3);
        assertThat(session.pullScans()).singleElement().satisfies(s -> assertThat(s.quantity()).isEqualTo(3));
    }

    @Test
    @DisplayName("wrong item, over-scan of the order, over what was picked, unknown lot, missing lot: each refused")
    void refusals() {
        var session = session();
        var box = session.openPackage(UUID.randomUUID(), null, NOW);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "SOFA-3S-GREY", null, null, 1,
                null, PACKER, NOW)))).isEqualTo(ErrorCode.PACK_ITEM_NOT_IN_ORDER);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "LID-90", null, null, 6, null,
                PACKER, NOW)))).isEqualTo(ErrorCode.PACK_QUANTITY_EXCEEDS_ORDER);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "LID-90", "L9", null, 1, null,
                PACKER, NOW)))).as("a lot for goods without one").isEqualTo(ErrorCode.PACK_LOT_MISMATCH);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "CUP-12OZ", "L3", null, 1,
                CUPS, PACKER, NOW)))).isEqualTo(ErrorCode.PACK_LOT_MISMATCH);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "CUP-12OZ", "L2", null, 5,
                CUPS, PACKER, NOW)))).as("only 4 of L2 were picked").isEqualTo(ErrorCode.PACK_LOT_MISMATCH);
        BusinessException noLot = (BusinessException) catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(),
                "CUP-12OZ", null, null, 1, CUPS, PACKER, NOW));
        assertThat(noLot.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(noLot.fieldErrors()).extracting("field").containsExactly("lotNumber");
        assertThat(session.packedOf(CUPS) + session.packedOf(LIDS)).isZero();
        assertThat(session.pullScans()).isEmpty();
    }

    @Test
    @DisplayName("a code on two lines (plain and printed cup) needs the line; the line must carry that code")
    void ambiguity() {
        var session = session();
        var box = session.openPackage(UUID.randomUUID(), null, NOW);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "8930000000101", "L1", null, 1,
                null, PACKER, NOW)))).isEqualTo(ErrorCode.PACK_SCAN_AMBIGUOUS);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "CUP-12OZ", null, null, 1,
                LIDS, PACKER, NOW)))).isEqualTo(ErrorCode.PACK_ITEM_NOT_IN_ORDER);
        session.scan(UUID.randomUUID(), box.id(), "8930000000101", null, null, 4, PRINTED, PACKER, NOW);
        // The printed line is full: the code now means the plain cups.
        session.scan(UUID.randomUUID(), box.id(), "CUP-12OZ", "L1", null, 2, null, PACKER, NOW);
        assertThat(session.packedOf(CUPS)).isEqualTo(2);
    }

    @Test
    @DisplayName("over what reached packing: a stock line short-picked cannot be packed in full")
    void overPicked() {
        var shortPicked = new PackingSession.Line(CUPS, "CUP-12OZ", null, 10, 7, false, null, Map.of(), Set.of(), true,
                false);
        var session = new PackingSession(UUID.randomUUID(), "SO-1", PackStatus.PACKING, NOW, PACKER, null, null,
                List.of(shortPicked), List.of(), List.of());
        var box = session.openPackage(UUID.randomUUID(), null, NOW);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "CUP-12OZ", null, null, 8, null,
                PACKER, NOW)))).isEqualTo(ErrorCode.PACK_QUANTITY_EXCEEDS_PICKED);
    }

    @Test
    @DisplayName("serials: one unit per scan, only those picked, never twice")
    void serials() {
        var line = new PackingSession.Line(CUPS, "SCALE-1", null, 2, 2, false, null, Map.of(), Set.of("SN-1", "SN-2"),
                true, false);
        var session = new PackingSession(UUID.randomUUID(), "SO-1", PackStatus.PACKING, NOW, PACKER, null, null,
                List.of(line), List.of(), List.of());
        var box = session.openPackage(UUID.randomUUID(), null, NOW);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "SCALE-1", null, "SN-9", 1,
                null, PACKER, NOW)))).isEqualTo(ErrorCode.PACK_SERIAL_MISMATCH);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "SCALE-1", null, null, 1, null,
                PACKER, NOW)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        session.scan(UUID.randomUUID(), box.id(), "SCALE-1", null, "sn-1", 1, null, PACKER, NOW);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "SCALE-1", null, "SN-1", 1,
                null, PACKER, NOW)))).isEqualTo(ErrorCode.PACK_SERIAL_MISMATCH);
        assertThat(box.contents()).containsKey(new PackingSession.ContentKey(CUPS, null, "SN-1"));
    }

    @Test
    @DisplayName("another packer is refused, a manager is not; nothing is packed before the session starts")
    void twoPackers() {
        var session = session();
        assertThat(code(catchThrowable(() -> session.start(OTHER, false, NOW)))).isEqualTo(ErrorCode.PACKING_TAKEN);
        assertThat(code(catchThrowable(() -> session.requireWorkable(OTHER, false)))).isEqualTo(ErrorCode.PACKING_TAKEN);
        session.requireWorkable(OTHER, true);
        assertThat(session.start(PACKER, false, NOW)).as("taking it again").isFalse();
        var pending = new PackingSession(UUID.randomUUID(), "SO-1", PackStatus.PENDING, null, null, null, null,
                List.of(), List.of(), List.of());
        assertThat(code(catchThrowable(() -> pending.requireWorkable(PACKER, true))))
                .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
    }

    @Test
    @DisplayName("sealing needs contents and every measure; rotatable is the strictest of its SKUs unless overridden; "
            + "fragile from a FRAGILE SKU; dim weight L*W*H/6000")
    void sealing() {
        var session = session();
        var empty = session.openPackage(UUID.randomUUID(), null, NOW);
        assertThat(code(catchThrowable(() -> session.seal(empty.id(), box(), null, null, null, 6000, PACKER, NOW))))
                .isEqualTo(ErrorCode.PACKAGE_EMPTY);
        session.scan(UUID.randomUUID(), empty.id(), "CUP-12OZ", "L1", null, 2, CUPS, PACKER, NOW);
        session.scan(UUID.randomUUID(), empty.id(), "LID-90", null, null, 1, null, PACKER, NOW);
        BusinessException noHeight = (BusinessException) catchThrowable(() -> session.seal(empty.id(),
                new PackingSession.Measures(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, null), null, null, null,
                6000, PACKER, NOW));
        assertThat(noHeight.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(noHeight.fieldErrors()).extracting("field").containsExactly("heightCm");

        var sealed = session.seal(empty.id(), box(), null, null, null, 6000, PACKER, NOW);
        assertThat(sealed.status()).isEqualTo(PackageStatus.SEALED);
        assertThat(sealed.rotatable()).as("the lids may not be turned").isFalse();
        assertThat(sealed.rotatableOverridden()).isFalse();
        assertThat(sealed.fragile()).isTrue();
        assertThat(sealed.dimWeightKg()).isEqualByComparingTo("5.000");
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), empty.id(), "LID-90", null, null, 1, null,
                PACKER, NOW)))).isEqualTo(ErrorCode.PACKAGE_SEALED);

        session.reopen(empty.id());
        var overridden = session.seal(empty.id(), box(), true, false, PackageType.CRATE, 6000, PACKER, NOW);
        assertThat(overridden.rotatable()).isTrue();
        assertThat(overridden.rotatableOverridden()).isTrue();
        assertThat(overridden.fragileOverridden()).isTrue();
        assertThat(overridden.type()).isEqualTo(PackageType.CRATE);
    }

    @Test
    @DisplayName("taking units out again and voiding a package: the goods count as unpacked")
    void undo() {
        var session = session();
        var box = session.openPackage(UUID.randomUUID(), null, NOW);
        session.scan(UUID.randomUUID(), box.id(), "CUP-12OZ", "L1", null, 3, CUPS, PACKER, NOW);
        session.scan(UUID.randomUUID(), box.id(), "CUP-12OZ", "L2", null, 1, CUPS, PACKER, NOW);
        assertThat(code(catchThrowable(() -> session.remove(UUID.randomUUID(), box.id(), CUPS, null, null, 1, PACKER,
                NOW)))).as("two lots: name one").isEqualTo(ErrorCode.VALIDATION_FAILED);
        session.remove(UUID.randomUUID(), box.id(), CUPS, "L1", null, 2, PACKER, NOW);
        assertThat(session.packedOf(CUPS)).isEqualTo(2);
        assertThat(session.pullScans()).extracting(PackingSession.Scan::quantity).containsExactly(3, 1, -2);
        session.voidPackage(box.id(), PACKER, NOW);
        assertThat(session.packedOf(CUPS)).isZero();
        assertThat(box.status()).isEqualTo(PackageStatus.VOIDED);
    }

    @Test
    @DisplayName("completing: open packages stop it, everything must be packed unless partial with a reason")
    void completing() {
        var session = session();
        var a = session.openPackage(UUID.randomUUID(), null, NOW);
        session.scan(UUID.randomUUID(), a.id(), "CUP-12OZ", "L1", null, 6, CUPS, PACKER, NOW);
        assertThat(code(catchThrowable(() -> session.complete(false, null, true, PACKER, NOW))))
                .isEqualTo(ErrorCode.PACKAGE_NOT_SEALED);
        session.seal(a.id(), box(), null, null, null, 6000, PACKER, NOW);
        BusinessException incomplete = (BusinessException) catchThrowable(() -> session.complete(false, null, true,
                PACKER, NOW));
        assertThat(incomplete.errorCode()).isEqualTo(ErrorCode.PACKING_INCOMPLETE);
        assertThat(incomplete.fieldErrors()).hasSize(3);
        assertThat(code(catchThrowable(() -> session.complete(true, " ", true, PACKER, NOW))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> session.complete(true, "stock first", false, PACKER, NOW))))
                .isEqualTo(ErrorCode.PACK_PARTIAL_NOT_ALLOWED);

        var first = session.complete(true, "Sale ships the stock cups first", true, PACKER, NOW);
        assertThat(first.partial()).isTrue();
        assertThat(first.lines()).containsExactly(Map.entry(CUPS, 6));
        UUID shipment1 = UUID.randomUUID();
        session.ship(first, shipment1, PACKER, NOW);
        assertThat(session.status()).isEqualTo(PackStatus.PACKING);
        assertThat(session.shippedOf(CUPS)).isEqualTo(6);
        assertThat(code(catchThrowable(() -> session.complete(true, "again", true, PACKER, NOW))))
                .isEqualTo(ErrorCode.PACK_NOTHING_TO_SHIP);
        assertThat(code(catchThrowable(() -> session.voidPackage(a.id(), PACKER, NOW))))
                .isEqualTo(ErrorCode.PACKAGE_SHIPPED);
        assertThat(code(catchThrowable(() -> session.reopen(a.id())))).isEqualTo(ErrorCode.PACKAGE_SHIPPED);

        var b = session.openPackage(UUID.randomUUID(), null, NOW);
        session.scan(UUID.randomUUID(), b.id(), "CUP-12OZ", "L2", null, 4, CUPS, PACKER, NOW);
        session.scan(UUID.randomUUID(), b.id(), "LID-90", null, null, 5, null, PACKER, NOW);
        session.scan(UUID.randomUUID(), b.id(), "CUP-12OZ", null, null, 4, PRINTED, PACKER, NOW);
        session.seal(b.id(), box(), null, null, null, 6000, PACKER, NOW);
        var spare = session.openPackage(UUID.randomUUID(), null, NOW);
        var rest = session.complete(false, null, true, PACKER, NOW);
        assertThat(rest.partial()).isFalse();
        assertThat(spare.status()).as("an empty open package is voided").isEqualTo(PackageStatus.VOIDED);
        session.ship(rest, UUID.randomUUID(), PACKER, NOW);
        assertThat(session.status()).isEqualTo(PackStatus.PACKED);
        assertThat(session.packedBy()).isEqualTo(PACKER);
        assertThat(session.printLinesIn(rest.lines().keySet())).extracting(PackingSession.Line::orderLineId)
                .containsExactly(PRINTED);
    }

    @Test
    @DisplayName("a design hold stops packing until re-verified; cancelling voids what did not ship")
    void holdAndCancel() {
        var session = session();
        var a = session.openPackage(UUID.randomUUID(), null, NOW);
        session.scan(UUID.randomUUID(), a.id(), "LID-90", null, null, 1, null, PACKER, NOW);
        session.hold();
        assertThat(code(catchThrowable(() -> session.requireWorkable(PACKER, false))))
                .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
        assertThat(code(catchThrowable(() -> session.start(PACKER, false, NOW)))).isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
        session.resume();
        session.requireWorkable(PACKER, false);
        var voided = session.cancel(OTHER, NOW);
        assertThat(voided).containsExactly(a);
        assertThat(session.status()).isEqualTo(PackStatus.CANCELLED);
    }

    @Test
    @DisplayName("parcels read back keep their scans' lots: a sealed package of an earlier visit ships")
    void restored() {
        UUID scanned = UUID.randomUUID();
        var parcel = new PackingSession.Parcel(UUID.randomUUID(), 1, "PKG-SO-1-1", PackageType.CARTON,
                PackageStatus.SEALED, null, box(), new BigDecimal("5"), true, false, false, false, NOW, PACKER, null,
                null, Map.of(new PackingSession.ContentKey(LIDS, null, null), 5), NOW);
        var lids = new PackingSession.Line(LIDS, "LID-90", null, 5, 5, false, null, Map.of(), Set.of(), true, false);
        var session = new PackingSession(UUID.randomUUID(), "SO-1", PackStatus.PACKING, NOW, PACKER, null, null,
                List.of(lids), List.of(parcel), List.of(scanned));
        assertThat(session.scan(scanned, parcel.id(), "LID-90", null, null, 1, null, PACKER, NOW))
                .as("a scan read back is a replay, even on a sealed package").isFalse();
        var done = session.complete(false, null, true, SCANNER, NOW);
        assertThat(done.parcels()).containsExactly(parcel);
    }

    @Test
    @DisplayName("review: a parcel of a shipment that came back holds nothing - its units may be packed again")
    void returnedParcelsHoldNothing() {
        var lids = new PackingSession.Line(LIDS, "LID-90", null, 5, 5, false, null, Map.of(), Set.of(), true, false);
        var back = new PackingSession.Parcel(UUID.randomUUID(), 1, "PKG-SO-1-1", PackageType.CARTON,
                PackageStatus.SEALED, UUID.randomUUID(), box(), new BigDecimal("5"), true, false, false, false, NOW,
                PACKER, null, null, Map.of(new PackingSession.ContentKey(LIDS, null, null), 5), NOW).markReturned();
        var session = new PackingSession(UUID.randomUUID(), "SO-1", PackStatus.PACKING, NOW, PACKER, null, null,
                List.of(lids), List.of(back), List.of());
        assertThat(session.packedOf(LIDS)).isZero();
        assertThat(session.shippedOf(LIDS)).isZero();
        var again = session.openPackage(UUID.randomUUID(), null, NOW);
        session.scan(UUID.randomUUID(), again.id(), "LID-90", null, null, 5, null, PACKER, NOW);
        assertThat(session.packedOf(LIDS)).isEqualTo(5);
    }

    @Test
    @DisplayName("review: a removal retried with its scan id takes nothing out twice; quantities are bounded")
    void removalReplayAndBounds() {
        var session = session();
        var box = session.openPackage(UUID.randomUUID(), null, NOW);
        session.scan(UUID.randomUUID(), box.id(), "LID-90", null, null, 3, null, PACKER, NOW);
        UUID removal = UUID.randomUUID();
        assertThat(session.remove(removal, box.id(), LIDS, null, null, 1, PACKER, NOW)).isTrue();
        assertThat(session.remove(removal, box.id(), LIDS, null, null, 1, PACKER, NOW)).isFalse();
        assertThat(session.packedOf(LIDS)).isEqualTo(2);
        assertThat(session.knows(removal)).isTrue();
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), box.id(), "LID-90", null, null,
                Integer.MAX_VALUE, null, PACKER, NOW)))).as("no overflow").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> session.remove(UUID.randomUUID(), box.id(), LIDS, null, null,
                PackingSession.MAX_UNITS_PER_SCAN + 1, PACKER, NOW)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
