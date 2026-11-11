package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.domain.CarrierEventResult;
import com.stockflow.fulfillment.internal.entity.CarrierEventJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierEventJpaRepository;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.identity.api.IdentityService;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * See {@link CarrierEvents}. A webhook call is handled in short transactions, none of them open while another runs:
 * <ol>
 *   <li>the call is kept - with the outcome of its credential check, done first and in memory - before anything is
 *       done with it (09 §4.5 step 2: every call is logged);</li>
 *   <li>its body is read and matched ({@link CarrierEventApplier#match});</li>
 *   <li>it is applied ({@link CarrierEventApplier#apply}); a refusal is decided NEEDS_REVIEW in one more.</li>
 * </ol>
 * A process that dies between the first and the last leaves the call RECEIVED, which the error list shows.
 */
@Service
class CarrierEventService implements CarrierEvents {

    /** A RECEIVED call younger than this is still being handled, not stranded. */
    static final Duration STILL_HANDLED = Duration.ofMinutes(5);
    private static final Set<CarrierEventResult> ERRORS = EnumSet.of(CarrierEventResult.RECEIVED,
            CarrierEventResult.UNMATCHED, CarrierEventResult.INVALID_SIGNATURE, CarrierEventResult.NEEDS_REVIEW);
    private static final Logger log = LoggerFactory.getLogger(CarrierEventService.class);

    private final CarrierEventJpaRepository events;
    private final CarrierJpaRepository carriers;
    private final DispatchLoadJpaRepository loads;
    private final ShipmentJpaRepository shipments;
    private final IdentityService identities;
    private final CarrierEventApplier applier;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final TransactionOperations tx;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    CarrierEventService(CarrierEventJpaRepository events, CarrierJpaRepository carriers, DispatchLoadJpaRepository loads,
                        ShipmentJpaRepository shipments, IdentityService identities, CarrierEventApplier applier,
                        SecretCipher cipher, ObjectMapper json, TransactionOperations tx, Clock clock) {
        this.events = events;
        this.carriers = carriers;
        this.loads = loads;
        this.shipments = shipments;
        this.identities = identities;
        this.applier = applier;
        this.cipher = cipher;
        this.json = json;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ receiving

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Receipt receive(String carrierCode, UnaryOperator<String> header, byte[] body) {
        Instant now = clock.instant();
        String code = carrierCode == null ? "" : carrierCode.strip();
        String kept = code.matches("^[!-~]{1,64}$") ? code : "?";
        CarrierJpaEntity carrier = "?".equals(kept) ? null : tx.execute(s -> carriers.findByCode(code).orElse(null));
        if (carrier == null) {
            // No carrier of that code: not a carrier's event, so nothing is kept - an anonymous caller cannot fill the
            // log by naming carriers that do not exist.
            log.warn("Carrier webhook for unknown carrier '{}' refused ({} bytes)", kept, body == null ? 0 : body.length);
            return new Receipt(null, CarrierEventResult.INVALID_SIGNATURE.name(), false, false);
        }
        String refusal = refusal(carrier, header, body);
        UUID eventId = Identifiers.newId();
        // A refused call is kept as its size, digest and first characters: enough to recognise, too little to fill the
        // log with what anyone can send.
        String payload = refusal == null ? CarrierWebhookPayload.stored(body, json)
                : CarrierWebhookPayload.summary(body, json);
        tx.executeWithoutResult(s -> events.saveAndFlush(new CarrierEventJpaEntity(eventId, carrier.getId(), kept, now,
                payload, refusal == null, refusal)));
        if (refusal != null) {
            log.warn("Carrier webhook {} for '{}' refused: {}", eventId, kept, refusal);
            return new Receipt(eventId, CarrierEventResult.INVALID_SIGNATURE.name(), false, false);
        }
        CarrierEventResult result;
        try {
            CarrierEventApplier.Matched matched = tx.execute(s -> applier.match(eventId, carrier, body));
            result = matched == null ? tx.execute(s -> events.findById(eventId).orElseThrow().getResult())
                    : tx.execute(s -> applier.apply(eventId, carrier, matched));
        } catch (CarrierWebhookRetry later) {
            tx.executeWithoutResult(s -> applier.retryLater(eventId, later.getMessage()));
            log.info("Carrier webhook {} for {}: to be sent again ({})", eventId, kept, later.getMessage());
            return new Receipt(eventId, CarrierEventResult.RECEIVED.name(), true, true);
        } catch (RuntimeException failure) {
            if (!(failure instanceof BusinessException) && !uniqueRace(failure)) {
                // A deadlock, a lost connection, a lock that waited too long: not the report's fault. Left to be sent
                // again rather than decided, so a carrier that retries delivers it.
                log.error("Carrier webhook {} for {} could not be applied now", eventId, kept, failure);
                tx.executeWithoutResult(s -> applier.retryLater(eventId, "Could not be applied ("
                        + failure.getClass().getSimpleName() + "): the carrier is asked to send it again"));
                return new Receipt(eventId, CarrierEventResult.RECEIVED.name(), true, true);
            }
            result = tx.execute(s -> applier.failed(eventId, failure));
        }
        log.info("Carrier webhook {} for {}: {}", eventId, kept, result);
        return new Receipt(eventId, Objects.requireNonNull(result).name(), true, false);
    }

    /** Two copies of one report applied at the same moment: one of them meets {@code uk_carrier_event_applied}. */
    private static boolean uniqueRace(RuntimeException failure) {
        return failure instanceof org.springframework.dao.DataIntegrityViolationException taken
                && String.valueOf(taken.getMostSpecificCause().getMessage()).contains("uk_carrier_event_applied");
    }

    /**
     * Why the call is not taken as the carrier's (09 §4.5 step 3), or null when it is: an API_BOOKING carrier's
     * configured header must carry the configured value; a WEBHOOK_LOAD carrier's body must be signed with its secret.
     * The reason names no value.
     */
    private String refusal(CarrierJpaEntity carrier, UnaryOperator<String> header, byte[] body) {
        if (!carrier.isWebhookEnabled()) {
            return "Carrier %s does not take webhooks now".formatted(carrier.getCode());
        }
        try {
            if (CarrierConnectionType.API_BOOKING.name().equals(carrier.getConnectionType())) {
                String expected = cipher.open(carrier.getWebhookAuthValueSealed(),
                        CarrierService.context(carrier, "webhook-auth-value"));
                return WebhookCredentials.headerHolds(expected, header.apply(carrier.getWebhookAuthHeader())) ? null
                        : "The %s header does not carry the credential configured".formatted(
                                carrier.getWebhookAuthHeader());
            }
            if (CarrierConnectionType.WEBHOOK_LOAD.name().equals(carrier.getConnectionType())) {
                String secret = cipher.open(carrier.getWebhookSecretSealed(),
                        CarrierService.context(carrier, "webhook-secret"));
                return WebhookCredentials.signatureHolds(secret, body, header.apply(WebhookCredentials.SIGNATURE_HEADER))
                        ? null : "The " + WebhookCredentials.SIGNATURE_HEADER + " signature does not match the body";
            }
            return "Carrier %s is connected %s, without webhooks".formatted(carrier.getCode(),
                    carrier.getConnectionType());
        } catch (RuntimeException unopened) {
            // No secrets key on this instance, or one other than the key the credential was sealed with.
            log.error("Carrier {}: its webhook credential could not be opened ({})", carrier.getCode(),
                    unopened.getClass().getSimpleName());
            return "The carrier's webhook credential could not be opened on this server";
        }
    }

    // ------------------------------------------------------------------ the log and the error list

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EventRow> list(String result, Boolean open, UUID carrierId, UUID loadId, int page, int size) {
        Set<CarrierEventResult> results = results(result);
        Specification<CarrierEventJpaEntity> spec = (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (results != null) {
                all.add(root.get("result").in(results));
            }
            if (open != null) {
                all.add(open ? cb.isNull(root.get("resolvedAt")) : cb.isNotNull(root.get("resolvedAt")));
            }
            if (carrierId != null) {
                all.add(cb.equal(root.get("carrierId"), carrierId));
            }
            if (loadId != null) {
                all.add(cb.equal(root.get("loadId"), loadId));
            }
            WarehouseScope.restriction().ifPresent(limit -> all.add(limit.ids().isEmpty()
                    ? cb.isNull(root.get("warehouseId"))
                    : cb.or(cb.isNull(root.get("warehouseId")), root.get("warehouseId").in(limit.ids()))));
            return cb.and(all.toArray(Predicate[]::new));
        };
        Page<CarrierEventJpaEntity> found = events.findAll(spec, Pages.of(page, size,
                Sort.by(Sort.Direction.DESC, "receivedAt", "id")));
        Map<UUID, EventRow> rows = rows(found.getContent()).stream()
                .collect(Collectors.toMap(EventRow::eventId, r -> r));
        return Pages.toResponse(found, e -> rows.get(e.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public EventDetail get(UUID eventId) {
        CarrierEventJpaEntity event = visible(eventId);
        return new EventDetail(rows(List.of(event)).getFirst(), event.getPayload());
    }

    @Override
    @Transactional
    public EventDetail resolve(UUID eventId, String note, CurrentUser actor) {
        String why = note == null || note.isBlank() ? null : note.strip();
        if (why == null || why.length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Resolving an event says how it was dealt with",
                    List.of(FieldError.of("note", "required, at most 1000 characters")));
        }
        CarrierEventJpaEntity event = visible(eventId);
        Instant now = clock.instant();
        if (!event.getResult().errorList()) {
            throw new BusinessException(ErrorCode.CARRIER_EVENT_NOT_RESOLVABLE, ("Event %s was %s: only the error list "
                    + "is resolved by hand").formatted(eventId, event.getResult()));
        }
        if (event.getResolvedAt() != null) {
            throw new BusinessException(ErrorCode.CARRIER_EVENT_NOT_RESOLVABLE, "Event %s was resolved already"
                    .formatted(eventId));
        }
        if (event.getResult() == CarrierEventResult.RECEIVED
                && event.getReceivedAt().isAfter(now.minus(STILL_HANDLED))) {
            throw new BusinessException(ErrorCode.CARRIER_EVENT_NOT_RESOLVABLE, "Event %s is still being handled"
                    .formatted(eventId));
        }
        event.resolve(actor.userId(), now, why);
        events.saveAndFlush(event);
        log.info("Carrier event {} ({}) resolved by {}", eventId, event.getResult(), actor.userId());
        return new EventDetail(rows(List.of(event)).getFirst(), event.getPayload());
    }

    private CarrierEventJpaEntity visible(UUID eventId) {
        CarrierEventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_EVENT_NOT_FOUND));
        if (event.getWarehouseId() != null) {
            WarehouseScope.requireWarehouse(event.getWarehouseId());
        }
        return event;
    }

    private static Set<CarrierEventResult> results(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        if ("ERRORS".equals(value)) {
            return ERRORS;
        }
        try {
            return EnumSet.of(CarrierEventResult.valueOf(value));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown event result",
                    List.of(FieldError.of("result", "ERRORS or one of " + java.util.Arrays.toString(
                            CarrierEventResult.values()))));
        }
    }

    /** Rows with their carrier codes, load and shipment codes and resolvers' names, one query each. */
    private List<EventRow> rows(Collection<CarrierEventJpaEntity> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> loadCodes = new HashMap<>();
        Set<UUID> loadIds = found.stream().map(CarrierEventJpaEntity::getLoadId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!loadIds.isEmpty()) {
            loads.findByIdIn(loadIds).forEach(l -> loadCodes.put(l.getId(), l.getCode()));
        }
        Map<UUID, String> shipmentCodes = new HashMap<>();
        Set<UUID> shipmentIds = found.stream().map(CarrierEventJpaEntity::getShipmentId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!shipmentIds.isEmpty()) {
            shipments.findAllById(shipmentIds).forEach(s -> shipmentCodes.put(s.getId(), s.getCode()));
        }
        Map<UUID, String> names = new HashMap<>(identities.displayNames(found.stream()
                .map(CarrierEventJpaEntity::getResolvedBy).filter(Objects::nonNull).distinct().toList()));
        return found.stream().map(e -> new EventRow(e.getId(), e.getCarrierId(), e.getCarrierCode(), e.getReceivedAt(),
                e.isSignatureValid(), e.getEventKind() == null ? null : e.getEventKind().name(),
                e.getCarrierReference(), e.getCarrierReportedAt(), e.getWarehouseId(), e.getLoadId(),
                loadCodes.get(e.getLoadId()), e.getShipmentId(), shipmentCodes.get(e.getShipmentId()),
                e.getResult().name(), e.getResultNote(), e.getDecidedAt(), e.getResolvedAt(), e.getResolvedBy(),
                names.get(e.getResolvedBy()), e.getResolutionNote())).toList();
    }
}
