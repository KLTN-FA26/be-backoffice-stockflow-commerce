package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.Specs;
import com.stockflow.fulfillment.internal.entity.VehicleTypeJpaEntity;
import com.stockflow.fulfillment.internal.repository.VehicleTypeJpaRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * See {@link VehicleTypes}. The rules are the table's ({@code ck_vehicle_type_*}), checked here first for a readable
 * answer (the request DTO already refuses most of them with a field error; this is the same rule for a caller that
 * does not come through the controller).
 */
@Service
@Transactional
class VehicleTypeService implements VehicleTypes {

    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> KINDS = Set.of("CONTAINER", "TRUCK", "PICKUP");
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,31}$");
    /** Sanity bounds: 30 m on any side, 100 t, 10 000 vehicles. */
    private static final int MAX_SIDE_MM = 30_000;
    private static final int MAX_GUSSET_MM = 1_000;
    private static final BigDecimal MAX_PAYLOAD_KG = new BigDecimal("100000");
    private static final int MAX_COUNT = 10_000;

    private final VehicleTypeJpaRepository types;

    VehicleTypeService(VehicleTypeJpaRepository types) {
        this.types = types;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<VehicleTypeView> list(String status, String search, int page, int size) {
        Specification<VehicleTypeJpaEntity> spec = Specs.all();
        if (status != null && !status.isBlank()) {
            spec = spec.and(Specs.eq("status", status(status)));
        }
        if (search != null && !search.isBlank()) {
            Specification<VehicleTypeJpaEntity> byCode = Specs.contains("code", search.trim());
            spec = spec.and(byCode.or(Specs.contains("name", search.trim())));
        }
        return Pages.toResponse(types.findAll(spec, Pages.of(page, size, Sort.by("name", "code"))),
                VehicleTypeService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public VehicleTypeView get(UUID id) {
        return view(require(id));
    }

    @Override
    public VehicleTypeView create(SaveVehicleType type) {
        String code = code(type.code());
        types.findByCode(code).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.VEHICLE_TYPE_CODE_EXISTS,
                    "Vehicle type code %s is taken".formatted(code));
        });
        VehicleTypeJpaEntity row = new VehicleTypeJpaEntity(Identifiers.newId(), code);
        fill(row, type, "ACTIVE", 0);
        return view(types.saveAndFlush(row));
    }

    @Override
    public VehicleTypeView update(UUID id, SaveVehicleType type) {
        VehicleTypeJpaEntity row = require(id);
        if (type.code() != null && !type.code().isBlank()
                && !type.code().trim().toUpperCase(Locale.ROOT).equals(row.getCode())) {
            throw invalid("code", "cannot be changed");
        }
        if (type.version() == null || type.version() != row.getVersion()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Vehicle type %s changed meanwhile; reload it".formatted(row.getCode()));
        }
        fill(row, type, row.getStatus(), row.getGussetMm());
        return view(types.saveAndFlush(row));
    }

    private static void fill(VehicleTypeJpaEntity row, SaveVehicleType type, String defaultStatus, int defaultGusset) {
        List<FieldError> problems = new ArrayList<>();
        String name = trimmed(type.name());
        if (name == null || name.length() > 120) {
            problems.add(FieldError.of("name", "required, at most 120 characters"));
        }
        String kind = type.kind() == null ? "" : type.kind().trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            problems.add(FieldError.of("kind", "CONTAINER, TRUCK or PICKUP"));
        }
        int length = side(type.innerLengthMm(), "innerLengthMm", problems);
        int width = side(type.innerWidthMm(), "innerWidthMm", problems);
        int height = side(type.innerHeightMm(), "innerHeightMm", problems);
        int gusset = type.gussetMm() == null ? defaultGusset : type.gussetMm();
        if (gusset < 0 || gusset > MAX_GUSSET_MM) {
            problems.add(FieldError.of("gussetMm", "0 to " + MAX_GUSSET_MM + " mm"));
        }
        BigDecimal payload = type.maxPayloadKg();
        if (payload == null || payload.signum() <= 0 || payload.compareTo(MAX_PAYLOAD_KG) > 0
                || payload.scale() > 3) {
            problems.add(FieldError.of("maxPayloadKg", "more than 0 and at most 100000 kg, 3 decimals at most"));
            payload = BigDecimal.ONE;
        }
        int count = type.availableCount() == null ? 0 : type.availableCount();
        if (type.availableCount() == null || count < 0 || count > MAX_COUNT) {
            problems.add(FieldError.of("availableCount", "0 to " + MAX_COUNT));
        }
        BigDecimal price = type.rentalPrice();
        if (price != null && (price.signum() < 0 || price.scale() > 4 || price.precision() - price.scale() > 15)) {
            problems.add(FieldError.of("rentalPrice", "0 or more, 4 decimals at most"));
        }
        String status = type.status() == null || type.status().isBlank() ? defaultStatus : type.status().trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            problems.add(FieldError.of("status", "ACTIVE or INACTIVE"));
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid vehicle type", problems);
        }
        row.apply(name, kind, length, width, height, gusset, payload, count, price, status);
    }

    private static int side(Integer mm, String field, List<FieldError> problems) {
        if (mm == null || mm <= 0 || mm > MAX_SIDE_MM) {
            problems.add(FieldError.of(field, "1 to " + MAX_SIDE_MM + " mm"));
            return 1;
        }
        return mm;
    }

    private VehicleTypeJpaEntity require(UUID id) {
        return types.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_TYPE_NOT_FOUND));
    }

    static VehicleTypeView view(VehicleTypeJpaEntity row) {
        return new VehicleTypeView(row.getId(), row.getCode(), row.getName(), row.getKind(), row.getInnerLengthMm(),
                row.getInnerWidthMm(), row.getInnerHeightMm(), row.getGussetMm(), row.getMaxPayloadKg(), row.getAvailableCount(),
                row.getRentalPrice(), row.isPreset(), row.getStatus(), row.getVersion());
    }

    private static String code(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw invalid("code", "2-32 letters, digits, '-' or '_', starting with a letter or digit");
        }
        return code;
    }

    private static String status(String raw) {
        String status = raw.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw invalid("status", "ACTIVE or INACTIVE");
        }
        return status;
    }

    private static BusinessException invalid(String field, String rule) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid vehicle type " + field,
                List.of(FieldError.of(field, rule)));
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
