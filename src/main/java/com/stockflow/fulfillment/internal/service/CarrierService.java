package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.Specs;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.fulfillment.internal.domain.CarrierApiEnvironment;
import com.stockflow.fulfillment.internal.domain.CarrierConnection;
import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.domain.DeliveryFailureReason;
import com.stockflow.fulfillment.internal.domain.FailureReasonMap;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * See {@link Carriers}. The rules are the table's ({@code ck_carrier_*}), checked here first for a readable answer.
 *
 * <p>Secrets arrive in clear, are checked, and are sealed by {@link SecretCipher} under a context naming the carrier
 * and the setting, so a sealed value moved to another row or column does not open. Every rule is checked before
 * anything is sealed: a request that would be refused anyway does not answer 503 because no key is configured.</p>
 */
@Service
@Transactional
class CarrierService implements Carriers {

    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,31}$");
    private static final int MAX_SECRET = 512;

    private final CarrierJpaRepository carriers;
    private final SecretCipher cipher;

    CarrierService(CarrierJpaRepository carriers, SecretCipher cipher) {
        this.carriers = carriers;
        this.cipher = cipher;
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
        if (carrier.connection() != null) {
            connect(row, carrier.connection());
        }
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
        if (carrier.connection() != null) {
            connect(row, carrier.connection());
        }
        return view(carriers.saveAndFlush(row));
    }

    private CarrierJpaEntity require(UUID id) {
        return carriers.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND));
    }

    /**
     * Replaces {@code row}'s connection settings with {@code input}. A secret left null keeps the one saved when the
     * new type still uses it, and so does a failure-reason table left null (an empty one empties it); everything the
     * new type does not use is cleared. An API key is issued to one account in one environment, so changing either
     * without a new key is refused rather than sending the old key where it was not issued.
     */
    private void connect(CarrierJpaEntity row, ConnectionInput input) {
        List<FieldError> errors = new ArrayList<>();
        CarrierConnectionType type = enumOf(CarrierConnectionType.class, input.type(), "connectionType",
                "MANUAL, WEBHOOK_LOAD or API_BOOKING", errors);
        CarrierApiEnvironment environment = input.apiEnvironment() == null ? null
                : enumOf(CarrierApiEnvironment.class, input.apiEnvironment(), "apiEnvironment",
                "STAGING or PRODUCTION", errors);
        String webhookSecret = secret(input.webhookSecret(), "webhookSecret", 16, errors);
        String authValue = secret(input.webhookAuthValue(), "webhookAuthValue", 16, errors);
        String apiKey = secret(input.apiKey(), "apiKey", 8, errors);
        FailureReasonMap reasons = input.failureReasonMap() == null ? null : reasons(input.failureReasonMap(), errors);
        if (type == null) {
            throw invalid(errors);
        }

        boolean signed = type == CarrierConnectionType.WEBHOOK_LOAD;
        boolean booking = type == CarrierConnectionType.API_BOOKING;
        String keptSecret = signed ? row.getWebhookSecretSealed() : null;
        String keptAuth = booking ? row.getWebhookAuthValueSealed() : null;
        String keptKey = booking ? row.getApiKeySealed() : null;
        CarrierApiEnvironment env = booking ? environment : null;
        String account = booking ? trimmed(input.apiAccount()) : null;
        if (booking && apiKey == null && keptKey != null && env != null
                && (!env.name().equals(row.getApiEnvironment()) || !java.util.Objects.equals(account,
                row.getApiAccount()))) {
            errors.add(FieldError.of("apiKey", "a key is issued to one account in one environment: give the key of "
                    + "this account in " + env));
        }
        boolean enabled = input.webhookEnabled() != null ? input.webhookEnabled()
                : type.sendsWebhooks() && (!CarrierConnectionType.valueOf(row.getConnectionType()).sendsWebhooks()
                || row.isWebhookEnabled());
        CarrierConnection connection = new CarrierConnection(type, enabled,
                signed && (webhookSecret != null || keptSecret != null),
                booking ? trimmed(input.webhookAuthHeader()) : null,
                booking && (authValue != null || keptAuth != null), env,
                account, booking && (apiKey != null || keptKey != null));
        // One error per field: a malformed value already named is not also reported missing.
        connection.violations().stream()
                .filter(v -> errors.stream().noneMatch(e -> e.field().equals(v.field())))
                .forEach(v -> errors.add(FieldError.of(v.field(), v.rule())));
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }

        Map<String, String> stored = new LinkedHashMap<>();
        if (reasons == null) {
            stored.putAll(row.getFailureReasonMap());
        } else {
            reasons.entries().forEach((wording, reason) -> stored.put(wording, reason.name()));
        }
        row.connect(type.name(), connection.webhookEnabled(),
                signed ? (webhookSecret != null ? cipher.seal(webhookSecret, context(row, "webhook-secret"))
                        : keptSecret) : null,
                connection.webhookAuthHeader(),
                booking ? (authValue != null ? cipher.seal(authValue, context(row, "webhook-auth-value"))
                        : keptAuth) : null,
                env == null ? null : env.name(), connection.apiAccount(),
                booking ? (apiKey != null ? cipher.seal(apiKey, context(row, "api-key")) : keptKey) : null,
                stored);
    }

    /** What a carrier's secret is sealed under: the carrier and the setting. Opening it takes the same. */
    static String context(CarrierJpaEntity row, String setting) {
        return "carrier:" + row.getId() + ":" + setting;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, String field, String rule,
                                                List<FieldError> errors) {
        if (raw != null) {
            try {
                return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                // reported below
            }
        }
        errors.add(FieldError.of(field, rule));
        return null;
    }

    /** A secret as given: null when left out (keep the saved one); a blank one is a mistake, not a way to clear it. */
    private static String secret(String raw, String field, int min, List<FieldError> errors) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank() || raw.length() < min || raw.length() > MAX_SECRET || !raw.equals(raw.strip())) {
            errors.add(FieldError.of(field, min + "-" + MAX_SECRET + " characters, no surrounding spaces; leave it "
                    + "out to keep the saved one"));
            return null;
        }
        return raw;
    }

    private static FailureReasonMap reasons(Map<String, String> raw, List<FieldError> errors) {
        if (raw == null || raw.isEmpty()) {
            return FailureReasonMap.empty();
        }
        Map<String, DeliveryFailureReason> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            DeliveryFailureReason reason = null;
            try {
                reason = entry.getValue() == null ? null
                        : DeliveryFailureReason.valueOf(entry.getValue().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                // reported below
            }
            if (reason == null) {
                errors.add(FieldError.of("failureReasonMap", "'" + entry.getKey() + "' must map to CUSTOMER_ABSENT, "
                        + "REFUSED, WRONG_ADDRESS, LOST, DAMAGED or OTHER"));
                return FailureReasonMap.empty();
            }
            parsed.put(entry.getKey(), reason);
        }
        try {
            return FailureReasonMap.of(parsed);
        } catch (IllegalArgumentException broken) {
            errors.add(FieldError.of("failureReasonMap", broken.getMessage()));
            return FailureReasonMap.empty();
        }
    }

    static CarrierView view(CarrierJpaEntity row) {
        return new CarrierView(row.getId(), row.getCode(), row.getName(), row.getContactName(), row.getContactPhone(),
                row.getStatus(), new ConnectionView(row.getConnectionType(), row.isWebhookEnabled(),
                row.getWebhookSecretSealed() != null, row.getWebhookAuthHeader(),
                row.getWebhookAuthValueSealed() != null, row.getApiEnvironment(), row.getApiAccount(),
                row.getApiKeySealed() != null, Map.copyOf(row.getFailureReasonMap())), row.getVersion());
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

    private static BusinessException invalid(List<FieldError> errors) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid carrier connection", List.copyOf(errors));
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
