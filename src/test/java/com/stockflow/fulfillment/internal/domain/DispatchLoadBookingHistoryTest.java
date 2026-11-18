package com.stockflow.fulfillment.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cancelled booking leaves the load but stays in its history (kltn-docs 09 §4.2 step 6: "thông tin đặt xe (hãng, mã
 * vận đơn, biển số, tài xế) bị xoá khỏi chuyến, vẫn lưu trong lịch sử"; RTM 09A-125).
 */
class DispatchLoadBookingHistoryTest {

    private static final Instant BOOKED = Instant.parse("2026-11-18T08:00:00Z");
    private static final Instant CANCELLED = Instant.parse("2026-11-18T09:30:00Z");
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID DISPATCHER = UUID.randomUUID();

    private static DispatchLoad booked() {
        DispatchLoad load = DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-20261118-0001", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        load.book(CARRIER, "51C-123.45", "Le Van Lai", "0901234567", "WB-0001", DISPATCHER, BOOKED);
        return load;
    }

    @Test
    @DisplayName("the booking as it stood - carrier, waybill number, plate, driver, when and by whom booked and "
            + "cancelled, why - is handed to the store once, while the load itself is cleared")
    void theCancelledBookingIsKept() {
        DispatchLoad load = booked();

        assertThat(load.cancelBooking("Xe không tới", DISPATCHER, CANCELLED)).isTrue();

        assertThat(load.status()).isEqualTo(LoadStatus.PLANNED);
        assertThat(load.carrierId()).isNull();
        assertThat(load.waybillNo()).isNull();
        assertThat(load.takeCancelledBookings()).containsExactly(new DispatchLoad.CancelledBooking(CARRIER, "WB-0001",
                "51C-123.45", "Le Van Lai", "0901234567", BOOKED, DISPATCHER, CANCELLED, DISPATCHER, "Xe không tới"));
        assertThat(load.takeCancelledBookings()).as("handed over once").isEmpty();
    }

    @Test
    @DisplayName("the system's cancellation (a carrier's webhook) is kept with nobody as who cancelled it; a load not "
            + "booked has nothing to keep")
    void bySystemOrNothing() {
        DispatchLoad load = booked();
        load.cancelBooking("The carrier cancelled order AHA-1", null, CANCELLED);
        assertThat(load.takeCancelledBookings()).singleElement()
                .satisfies(b -> assertThat(b.cancelledBy()).isNull());

        assertThat(load.cancelBooking("again", DISPATCHER, CANCELLED)).as("PLANNED already").isFalse();
        assertThat(load.takeCancelledBookings()).isEmpty();
    }
}
