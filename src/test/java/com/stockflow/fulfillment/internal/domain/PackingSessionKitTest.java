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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Kits ("bộ", SCRUM-287 LP2-5/LP2-6) on the packing session alone, without Spring. */
class PackingSessionKitTest {

    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
    private static final UUID PACKER = UUID.randomUUID();
    private static final UUID TABLES = UUID.randomUUID();
    private static final UUID DESKS = UUID.randomUUID();
    private static final UUID CUPS = UUID.randomUUID();

    /**
     * 2 tables (top and base: 2 boxes a unit, not rotatable), 1 desk (3 boxes, fragile), 5 cups (one box, the
     * ordinary case).
     */
    private static PackingSession session() {
        var tables = new PackingSession.Line(TABLES, "TABLE-OAK-160", null, 2, 2, false, null, Map.of(), Set.of(),
                false, false, 2);
        var desks = new PackingSession.Line(DESKS, "DESK-3P", null, 1, 1, false, null, Map.of(), Set.of(), true, true,
                3);
        var cups = new PackingSession.Line(CUPS, "CUP-12OZ", null, 5, 5, false, null, Map.of(), Set.of(), true, false);
        var session = new PackingSession(UUID.randomUUID(), "SO-KIT", PackStatus.PENDING, null, null, null, null,
                List.of(tables, desks, cups), List.of(), List.of());
        session.start(PACKER, false, NOW);
        return session;
    }

    private static List<UUID> ids(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }

    private static List<PackingSession.Parcel> kit(PackingSession session, UUID line, int parts) {
        return session.openKit(UUID.randomUUID(), ids(parts), line, parts, null, NOW);
    }

    private static void put(PackingSession session, PackingSession.Parcel parcel, String sku) {
        session.scan(UUID.randomUUID(), parcel.id(), sku, null, null, 1, null, PACKER, NOW);
    }

    private static void seal(PackingSession session, PackingSession.Parcel parcel) {
        session.seal(parcel.id(), box(), null, null, null, 6000, PACKER, NOW);
    }

    private static PackingSession.Measures box() {
        return new PackingSession.Measures(new BigDecimal("20"), new BigDecimal("120"), new BigDecimal("80"),
                new BigDecimal("10"));
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("a kit opens all its parts at once: numbered after the last parcel, one group, part n of N")
    void openingAKit() {
        var session = session();
        session.openPackage(UUID.randomUUID(), null, NOW);
        UUID group = UUID.randomUUID();
        var parts = session.openKit(group, ids(2), TABLES, null, PackageType.CARTON, NOW);
        assertThat(parts).extracting(PackingSession.Parcel::packageNo).containsExactly(2, 3);
        assertThat(parts).extracting(PackingSession.Parcel::labelCode).containsExactly("PKG-SO-KIT-2", "PKG-SO-KIT-3");
        assertThat(parts).extracting(PackingSession.Parcel::kit)
                .containsExactly(new KitPart(group, 1, 2), new KitPart(group, 2, 2));
        assertThat(session.changedParcels()).hasSize(3);
    }

    @Test
    @DisplayName("LP2-5: the kit's size is the item's package_count - another size 422, a one-box item has no kit, a "
            + "line not on the order 409, a line packed in full 409")
    void theKitIsTheItemsSize() {
        var session = session();
        BusinessException wrongSize = (BusinessException) catchThrowable(() -> session.openKit(UUID.randomUUID(),
                ids(3), TABLES, 3, null, NOW));
        assertThat(wrongSize.errorCode()).isEqualTo(ErrorCode.KIT_PART_COUNT_MISMATCH);
        assertThat(wrongSize.fieldErrors()).extracting("field").containsExactly("partCount");
        assertThat(code(catchThrowable(() -> session.openKit(UUID.randomUUID(), ids(1), CUPS, null, null, NOW))))
                .isEqualTo(ErrorCode.KIT_PART_COUNT_MISMATCH);
        assertThat(code(catchThrowable(() -> session.openKit(UUID.randomUUID(), ids(2), UUID.randomUUID(), null, null,
                NOW)))).isEqualTo(ErrorCode.PACK_ITEM_NOT_IN_ORDER);
        var desk = kit(session, DESKS, 3);
        put(session, desk.get(0), "DESK-3P");
        assertThat(code(catchThrowable(() -> kit(session, DESKS, 3)))).isEqualTo(ErrorCode.PACK_QUANTITY_EXCEEDS_ORDER);
        assertThat(session.parcels()).hasSize(3);
    }

    @Test
    @DisplayName("LP2-6: part 1 takes one unit of the kit's item and nothing else; parts 2..N take no goods")
    void onlyPartOneHoldsTheUnit() {
        var session = session();
        var parts = kit(session, TABLES, 2);
        assertThat(code(catchThrowable(() -> put(session, parts.get(1), "TABLE-OAK-160"))))
                .isEqualTo(ErrorCode.KIT_CONTENTS_INVALID);
        assertThat(code(catchThrowable(() -> put(session, parts.get(0), "CUP-12OZ"))))
                .as("a one-box item in a table kit").isEqualTo(ErrorCode.KIT_PART_COUNT_MISMATCH);
        assertThat(code(catchThrowable(() -> session.scan(UUID.randomUUID(), parts.get(0).id(), "TABLE-OAK-160", null,
                null, 2, null, PACKER, NOW)))).isEqualTo(ErrorCode.KIT_CONTENTS_INVALID);
        put(session, parts.get(0), "TABLE-OAK-160");
        assertThat(code(catchThrowable(() -> put(session, parts.get(0), "TABLE-OAK-160"))))
                .as("one unit a kit").isEqualTo(ErrorCode.KIT_CONTENTS_INVALID);
        assertThat(session.packedOf(TABLES)).isEqualTo(1);
    }

    @Test
    @DisplayName("each part is sealed with its own size; parts 2..N wait for the unit in part 1 and take its flags")
    void sealingTheParts() {
        var session = session();
        var parts = kit(session, TABLES, 2);
        assertThat(code(catchThrowable(() -> seal(session, parts.get(1))))).isEqualTo(ErrorCode.PACKAGE_EMPTY);
        assertThat(code(catchThrowable(() -> seal(session, parts.get(0))))).isEqualTo(ErrorCode.PACKAGE_EMPTY);
        put(session, parts.get(0), "TABLE-OAK-160");
        var base = session.seal(parts.get(1).id(), new PackingSession.Measures(new BigDecimal("12"),
                new BigDecimal("80"), new BigDecimal("80"), new BigDecimal("70")), null, null, null, 6000, PACKER, NOW);
        assertThat(base.status()).isEqualTo(PackageStatus.SEALED);
        assertThat(base.rotatable()).as("the table may not be turned").isFalse();
        assertThat(base.rotatableOverridden()).isFalse();
        assertThat(base.measures().heightCm()).isEqualByComparingTo("70");
        var top = session.seal(parts.get(0).id(), box(), null, null, null, 6000, PACKER, NOW);
        assertThat(top.measures().heightCm()).isEqualByComparingTo("10");
        assertThat(top.lineTotals()).isEqualTo(Map.of(TABLES, 1));
        assertThat(base.lineTotals()).isEmpty();
    }

    @Test
    @DisplayName("a kit of 3 ships whole: three parcels in the shipment, the desk counted once")
    void aKitOfThree() {
        var session = session();
        var parts = kit(session, DESKS, 3);
        put(session, parts.get(0), "DESK-3P");
        parts.forEach(part -> seal(session, part));
        assertThat(parts.get(2).fragile()).as("from the desk in part 1").isTrue();
        var completion = session.complete(true, "the desk first", true, PACKER, NOW);
        assertThat(completion.parcels()).hasSize(3);
        assertThat(completion.lines()).isEqualTo(Map.of(DESKS, 1));
        assertThat(session.packedOf(DESKS)).isEqualTo(1);
    }

    @Test
    @DisplayName("KIT_INCOMPLETE while a part is open - whole or partial completion alike - naming the parts missing")
    void anIncompleteKitBlocksCompletion() {
        var session = session();
        var parts = kit(session, DESKS, 3);
        put(session, parts.get(0), "DESK-3P");
        seal(session, parts.get(0));
        seal(session, parts.get(2));
        BusinessException refused = (BusinessException) catchThrowable(() -> session.complete(false, null, true,
                PACKER, NOW));
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.KIT_INCOMPLETE);
        assertThat(refused.fieldErrors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("kits[" + parts.get(0).kit().groupId() + "]");
            assertThat(error.message()).contains("part 2/3", parts.get(1).labelCode()).doesNotContain("part 3/3");
        });
        assertThat(code(catchThrowable(() -> session.complete(true, "the rest later", true, PACKER, NOW))))
                .as("a partial shipment never splits a kit").isEqualTo(ErrorCode.KIT_INCOMPLETE);
        seal(session, parts.get(1));
        assertThat(session.complete(true, "the desk first", true, PACKER, NOW).parcels()).hasSize(3);
    }

    @Test
    @DisplayName("an opened kit with no unit scanned blocks completion too; voiding one part voids the whole kit")
    void voidingTakesTheKitApart() {
        var session = session();
        var parts = kit(session, TABLES, 2);
        var cups = session.openPackage(UUID.randomUUID(), null, NOW);
        session.scan(UUID.randomUUID(), cups.id(), "CUP-12OZ", null, null, 5, null, PACKER, NOW);
        seal(session, cups);
        BusinessException refused = (BusinessException) catchThrowable(() -> session.complete(true, "cups first", true,
                PACKER, NOW));
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.KIT_INCOMPLETE);
        assertThat(refused.fieldErrors().getFirst().message()).contains("no unit scanned");
        session.voidPackage(parts.get(1).id(), PACKER, NOW);
        assertThat(parts).allSatisfy(p -> assertThat(p.status()).isEqualTo(PackageStatus.VOIDED));
        var completion = session.complete(true, "cups first", true, PACKER, NOW);
        assertThat(completion.parcels()).containsExactly(cups);
        assertThat(completion.lines()).isEqualTo(Map.of(CUPS, 5));
    }

    @Test
    @DisplayName("no double counting: two table kits are 4 parcels and 2 tables; with the cups the order is complete")
    void unitsAreCountedOnce() {
        var session = session();
        for (int unit = 0; unit < 2; unit++) {
            var parts = kit(session, TABLES, 2);
            put(session, parts.get(0), "TABLE-OAK-160");
            parts.forEach(part -> seal(session, part));
        }
        var desk = kit(session, DESKS, 3);
        put(session, desk.get(0), "DESK-3P");
        desk.forEach(part -> seal(session, part));
        var cups = session.openPackage(UUID.randomUUID(), null, NOW);
        session.scan(UUID.randomUUID(), cups.id(), "CUP-12OZ", null, null, 5, null, PACKER, NOW);
        seal(session, cups);
        assertThat(session.packedOf(TABLES)).isEqualTo(2);
        var completion = session.complete(false, null, false, PACKER, NOW);
        assertThat(completion.partial()).isFalse();
        assertThat(completion.parcels()).hasSize(8);
        assertThat(completion.lines()).isEqualTo(Map.of(TABLES, 2, DESKS, 1, CUPS, 5));
        session.ship(completion, UUID.randomUUID(), PACKER, NOW);
        assertThat(session.status()).isEqualTo(PackStatus.PACKED);
        assertThat(session.shippedOf(TABLES)).isEqualTo(2);
    }

    @Test
    @DisplayName("a shipped kit part is neither voided nor re-opened")
    void aShippedKitStaysWhole() {
        var session = session();
        var parts = kit(session, TABLES, 2);
        put(session, parts.get(0), "TABLE-OAK-160");
        parts.forEach(part -> seal(session, part));
        session.ship(session.complete(true, "one table now", true, PACKER, NOW), UUID.randomUUID(), PACKER, NOW);
        assertThat(code(catchThrowable(() -> session.voidPackage(parts.get(1).id(), PACKER, NOW))))
                .isEqualTo(ErrorCode.PACKAGE_SHIPPED);
        assertThat(code(catchThrowable(() -> session.reopen(parts.get(0).id()))))
                .isEqualTo(ErrorCode.PACKAGE_SHIPPED);
    }
}
