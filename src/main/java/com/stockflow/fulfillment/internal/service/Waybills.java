package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.WaybillChangeJpaEntity;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.WaybillChangeJpaRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What every way of setting a load's waybill number shares (kltn-docs 09 §4.4, BR-23, BR-24): the number is unique
 * within its carrier among loads not cancelled, an API_BOOKING carrier's number comes from its booking API and from
 * nothing else, and every change is written to the history. Runs in its caller's transaction, under the load's row lock
 * the caller holds.
 */
@Component
class Waybills {

    private final DispatchLoadJpaRepository loads;
    private final WaybillChangeJpaRepository changes;

    Waybills(DispatchLoadJpaRepository loads, WaybillChangeJpaRepository changes) {
        this.loads = loads;
        this.changes = changes;
    }

    /**
     * A number typed or imported for a load of {@code carrier} (09 §4.4 "(4) Từ API đặt xe ... không nhập tay / file;
     * muốn đổi thì huỷ đơn và đặt lại").
     *
     * @throws BusinessException 409 {@code WAYBILL_FROM_CARRIER_API} for an API_BOOKING carrier
     */
    void requireTypeable(CarrierJpaEntity carrier) {
        if (CarrierConnectionType.API_BOOKING.name().equals(carrier.getConnectionType())) {
            throw new BusinessException(ErrorCode.WAYBILL_FROM_CARRIER_API, ("Carrier %s gives the waybill number "
                    + "when the vehicle is booked through its API: cancel the booking and book again to change it")
                    .formatted(carrier.getCode()));
        }
    }

    /**
     * @throws BusinessException 409 {@code WAYBILL_DUPLICATE} naming the other load when another live load of the same
     *         carrier has {@code number} (BR-23; E19: within one carrier)
     */
    void requireUnique(UUID carrierId, String number, UUID loadId) {
        if (number == null) {
            return;
        }
        List<DispatchLoadJpaEntity> others = loads.findLiveByCarrierAndWaybill(carrierId, number, loadId);
        if (!others.isEmpty()) {
            throw new BusinessException(ErrorCode.WAYBILL_DUPLICATE, "Waybill %s is load %s's already"
                    .formatted(number, others.get(0).getCode()),
                    List.of(FieldError.of("waybillNo", "load " + others.get(0).getCode() + " has it")));
        }
    }

    /** Writes the change to the history when the number changed; the same number again writes nothing. */
    void record(UUID loadId, String before, String after, WaybillSource source, UUID by, Instant at, String reason) {
        if (Objects.equals(before, after)) {
            return;
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        changes.saveAndFlush(new WaybillChangeJpaEntity(Identifiers.newId(), loadId, before, after, source.name(),
                source == WaybillSource.WEBHOOK ? null : Objects.requireNonNull(by, "who changed it"), at,
                why == null ? null : why.length() <= 500 ? why : why.substring(0, 497) + "..."));
    }
}
