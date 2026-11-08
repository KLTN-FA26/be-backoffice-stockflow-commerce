package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.WaybillChangeJpaEntity;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.WaybillChangeJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

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
     * @throws BusinessException 409 {@code WAYBILL_DUPLICATE} when another live load of the same carrier has
     *         {@code number} (BR-23; E19: within one carrier); the other load is named only when it is of a warehouse
     *         the caller may see
     */
    void requireUnique(UUID carrierId, String number, UUID loadId) {
        if (number == null) {
            return;
        }
        List<DispatchLoadJpaEntity> others = loads.findLiveByCarrierAndWaybill(carrierId, number, loadId);
        if (!others.isEmpty()) {
            DispatchLoadJpaEntity other = others.get(0);
            boolean visible = WarehouseScope.restriction().map(r -> r.ids().contains(other.getWarehouseId()))
                    .orElse(true);
            String holder = visible ? "load " + other.getCode() : "another load of this carrier";
            throw new BusinessException(ErrorCode.WAYBILL_DUPLICATE, "Waybill %s is %s's already"
                    .formatted(number, holder), List.of(FieldError.of("waybillNo", holder + " has it")));
        }
    }

    /**
     * Saves through {@code save}; two loads given the same number at once, each under its own lock, pass
     * {@link #requireUnique} together and the second meets {@code uk_dispatch_load_waybill}: that is the same refusal,
     * 409 {@code WAYBILL_DUPLICATE}, not an integrity error.
     */
    <T> T saving(Supplier<T> save) {
        try {
            return save.get();
        } catch (DataIntegrityViolationException raced) {
            String message = raced.getMostSpecificCause().getMessage();
            if (message != null && message.contains("\"uk_dispatch_load_waybill\"")) {
                throw new BusinessException(ErrorCode.WAYBILL_DUPLICATE,
                        "Another load of this carrier took the waybill number meanwhile");
            }
            throw raced;
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
