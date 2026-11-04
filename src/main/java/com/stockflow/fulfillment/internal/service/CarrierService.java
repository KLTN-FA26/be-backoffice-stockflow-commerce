package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.Specs;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** See {@link Carriers}. The rules are the table's ({@code ck_carrier_*}), checked here first for a readable answer. */
@Service
@Transactional
class CarrierService implements Carriers {

    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,31}$");

    private final CarrierJpaRepository carriers;

    CarrierService(CarrierJpaRepository carriers) {
        this.carriers = carriers;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CarrierView> list(String status, String search, int page, int size) {
        Specification<CarrierJpaEntity> spec = Specs.all();
        if (status != null && !status.isBlank()) {
            spec = spec.and(Specs.eq("status", status(status)));
        }
        if (search != null && !search.isBlank()) {
            Specification<CarrierJpaEntity> byCode = Specs.contains("code", search.trim());
            spec = spec.and(byCode.or(Specs.contains("name", search.trim())));
        }
        return Pages.toResponse(carriers.findAll(spec, Pages.of(page, size, Sort.by("name", "code"))),
                CarrierService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public CarrierView get(UUID id) {
        return view(require(id));
    }

    @Override
    public CarrierView create(SaveCarrier carrier) {
        String code = code(carrier.code());
        carriers.findByCode(code).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.CARRIER_CODE_EXISTS, "Carrier code %s is taken".formatted(code));
        });
        CarrierJpaEntity row = new CarrierJpaEntity(Identifiers.newId(), code);
        row.apply(code, name(carrier.name()), trimmed(carrier.contactName()), trimmed(carrier.contactPhone()),
                carrier.status() == null ? "ACTIVE" : status(carrier.status()));
        return view(carriers.saveAndFlush(row));
    }

    @Override
    public CarrierView update(UUID id, SaveCarrier carrier) {
        CarrierJpaEntity row = require(id);
        if (carrier.version() == null || carrier.version() != row.getVersion()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Carrier %s changed meanwhile; reload it".formatted(row.getCode()));
        }
        String code = code(carrier.code());
        carriers.findByCode(code).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw new BusinessException(ErrorCode.CARRIER_CODE_EXISTS, "Carrier code %s is taken".formatted(code));
        });
        row.apply(code, name(carrier.name()), trimmed(carrier.contactName()), trimmed(carrier.contactPhone()),
                carrier.status() == null ? row.getStatus() : status(carrier.status()));
        return view(carriers.saveAndFlush(row));
    }

    private CarrierJpaEntity require(UUID id) {
        return carriers.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND));
    }

    static CarrierView view(CarrierJpaEntity row) {
        return new CarrierView(row.getId(), row.getCode(), row.getName(), row.getContactName(), row.getContactPhone(),
                row.getStatus(), row.getVersion());
    }

    private static String code(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw invalid("code", "2-32 letters, digits, '-' or '_', starting with a letter or digit");
        }
        return code;
    }

    private static String name(String raw) {
        String name = trimmed(raw);
        if (name == null || name.length() > 120) {
            throw invalid("name", "required, at most 120 characters");
        }
        return name;
    }

    private static String status(String raw) {
        String status = raw.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw invalid("status", "ACTIVE or INACTIVE");
        }
        return status;
    }

    private static BusinessException invalid(String field, String rule) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid carrier " + field,
                List.of(FieldError.of(field, rule)));
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
