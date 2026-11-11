package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.common.security.SecretCipherProperties;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The carrier catalogue of kltn-docs 09 §3: codes are unique, a change is checked against the version read; and each
 * carrier's connection (§4.1 step 2, BR-21): secrets sealed and never shown, kept when left out, cleared with the
 * connection that used them.
 */
class CarrierServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final CarrierJpaRepository repository = mock(CarrierJpaRepository.class);
    private final SecretCipher cipher = new SecretCipher(new SecretCipherProperties(KEY));
    private final CarrierService service = new CarrierService(repository, cipher);

    private static Carriers.SaveCarrier save(String code, String name, String status, Long version) {
        return new Carriers.SaveCarrier(code, name, "  An  ", " 0900000000 ", status, null, version);
    }

    private static Carriers.SaveCarrier connect(Carriers.ConnectionInput connection, Long version) {
        return new Carriers.SaveCarrier("AHA", "Ahamove", null, null, null, connection, version);
    }

    private static Carriers.ConnectionInput booking(String environment, String apiKey, String authValue) {
        return new Carriers.ConnectionInput("API_BOOKING", null, null, "apikey", authValue, environment,
                "84908000000", apiKey, Map.of("Khách không nghe máy", "customer_absent"));
    }

    private CarrierJpaEntity existing(String code, String status) {
        CarrierJpaEntity row = new CarrierJpaEntity(UUID.randomUUID(), code);
        row.apply(code, "GHN Express", null, null, status);
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        return row;
    }

    private BusinessException refusal(Runnable call) {
        return FulfillmentFixtures.refusal(call);
    }

    @Test
    @DisplayName("a new carrier is saved with its code upper-cased, its text trimmed and ACTIVE by default")
    void createNormalisesAndDefaultsToActive() {
        when(repository.findByCode("GHN-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        Carriers.CarrierView view = service.create(save(" ghn-1 ", "  GHN Express ", null, null));

        assertThat(view.code()).isEqualTo("GHN-1");
        assertThat(view.name()).isEqualTo("GHN Express");
        assertThat(view.contactName()).isEqualTo("An");
        assertThat(view.contactPhone()).isEqualTo("0900000000");
        assertThat(view.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a blank contact is stored as nothing")
    void blankContactBecomesNull() {
        when(repository.findByCode("GHN-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        Carriers.CarrierView view = service.create(new Carriers.SaveCarrier("GHN-1", "GHN", "  ", "", "inactive", null,
                null));

        assertThat(view.contactName()).isNull();
        assertThat(view.contactPhone()).isNull();
        assertThat(view.status()).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("a code already taken is CARRIER_CODE_EXISTS and nothing is saved")
    void duplicateCodeIsRefused() {
        when(repository.findByCode("GHN-1")).thenReturn(Optional.of(new CarrierJpaEntity(UUID.randomUUID(), "GHN-1")));

        assertThat(refusal(() -> service.create(save("ghn-1", "GHN", null, null))).errorCode())
                .isEqualTo(ErrorCode.CARRIER_CODE_EXISTS);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a code of one character, with a space or starting with a dash is VALIDATION_FAILED on 'code'")
    void malformedCodesAreRefused() {
        for (String bad : new String[] {null, "", "A", "-AB", "AB CD", "A".repeat(33)}) {
            BusinessException thrown = refusal(() -> service.create(save(bad, "GHN", null, null)));
            assertThat(thrown.errorCode()).as("code %s", bad).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("code");
        }
    }

    @Test
    @DisplayName("a name that is blank or over 120 characters is VALIDATION_FAILED on 'name'")
    void nameIsRequiredAndBounded() {
        when(repository.findByCode("GHN-1")).thenReturn(Optional.empty());
        for (String bad : new String[] {null, "   ", "x".repeat(121)}) {
            BusinessException thrown = refusal(() -> service.create(save("GHN-1", bad, null, null)));
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("name");
        }
    }

    @Test
    @DisplayName("a status other than ACTIVE or INACTIVE is VALIDATION_FAILED on 'status'")
    void statusIsOneOfTwo() {
        when(repository.findByCode("GHN-1")).thenReturn(Optional.empty());

        BusinessException thrown = refusal(() -> service.create(save("GHN-1", "GHN", "PAUSED", null)));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("status");
    }

    @Test
    @DisplayName("an update without the version read, or with a stale one, is OPTIMISTIC_LOCK")
    void updateChecksTheVersion() {
        CarrierJpaEntity row = existing("GHN-1", "ACTIVE");
        ReflectionTestUtils.setField(row, "version", 3L);

        assertThat(refusal(() -> service.update(row.getId(), save("GHN-1", "GHN", null, null))).errorCode())
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(refusal(() -> service.update(row.getId(), save("GHN-1", "GHN", null, 2L))).errorCode())
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updating a carrier that does not exist is CARRIER_NOT_FOUND")
    void updateOfUnknownCarrier() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(refusal(() -> service.update(id, save("GHN-1", "GHN", null, 0L))).errorCode())
                .isEqualTo(ErrorCode.CARRIER_NOT_FOUND);
        assertThat(refusal(() -> service.get(id)).errorCode()).isEqualTo(ErrorCode.CARRIER_NOT_FOUND);
    }

    @Test
    @DisplayName("an update to a code another carrier holds is CARRIER_CODE_EXISTS; to its own code it is fine")
    void updateCodeMustStayUnique() {
        CarrierJpaEntity row = existing("GHN-1", "ACTIVE");
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        CarrierJpaEntity other = new CarrierJpaEntity(UUID.randomUUID(), "VTP");
        when(repository.findByCode("VTP")).thenReturn(Optional.of(other));
        when(repository.findByCode("GHN-1")).thenReturn(Optional.of(row));

        assertThat(refusal(() -> service.update(row.getId(), save("VTP", "GHN", null, 0L))).errorCode())
                .isEqualTo(ErrorCode.CARRIER_CODE_EXISTS);
        assertThat(service.update(row.getId(), save("ghn-1", "GHN Express 2", null, 0L)).name())
                .isEqualTo("GHN Express 2");
    }

    @Test
    @DisplayName("an update without a status keeps the one the carrier has")
    void updateKeepsStatusWhenNoneGiven() {
        CarrierJpaEntity row = existing("GHN-1", "INACTIVE");
        when(repository.findByCode("GHN-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(service.update(row.getId(), save("GHN-1", "GHN", null, 0L)).status()).isEqualTo("INACTIVE");
        assertThat(service.update(row.getId(), save("GHN-1", "GHN", "active", 0L)).status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a list of carriers is mapped to views with the page numbers")
    void listMapsRows() {
        CarrierJpaEntity row = new CarrierJpaEntity(UUID.randomUUID(), "GHN-1");
        row.apply("GHN-1", "GHN", null, null, "ACTIVE");
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), org.springframework.data.domain.PageRequest.of(0, 20), 1));

        var page = service.list("active", " ghn ", 0, 20);

        assertThat(page.items()).extracting(Carriers.CarrierView::code).containsExactly("GHN-1");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("filtering the list by an unknown status is VALIDATION_FAILED")
    void listRefusesUnknownStatus() {
        assertThat(refusal(() -> service.list("PAUSED", null, 0, 20)).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a new carrier is MANUAL with no webhooks until a connection is given")
    void newCarrierIsManual() {
        when(repository.findByCode("GHN-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        Carriers.ConnectionView connection = service.create(save("GHN-1", "GHN", null, null)).connection();

        assertThat(connection.type()).isEqualTo("MANUAL");
        assertThat(connection.webhookEnabled()).isFalse();
        assertThat(connection.failureReasonMap()).isEmpty();
    }

    @Test
    @DisplayName("an API_BOOKING carrier's secrets are sealed under its own id and the view says only that they are set")
    void apiBookingSealsSecrets() {
        CarrierJpaEntity row = existing("AHA", "ACTIVE");
        when(repository.findByCode("AHA")).thenReturn(Optional.of(row));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        Carriers.CarrierView view = service.update(row.getId(),
                connect(booking("staging", "key-12345678", "header-credential-1234"), 0L));

        assertThat(view.connection()).isEqualTo(new Carriers.ConnectionView("API_BOOKING", true, false, "apikey", true,
                "STAGING", "84908000000", true, Map.of("Khách không nghe máy", "CUSTOMER_ABSENT")));
        assertThat(row.getApiKeySealed()).startsWith("v1:").doesNotContain("key-12345678");
        assertThat(cipher.open(row.getApiKeySealed(), "carrier:" + row.getId() + ":api-key")).isEqualTo("key-12345678");
        assertThat(cipher.open(row.getWebhookAuthValueSealed(), "carrier:" + row.getId() + ":webhook-auth-value"))
                .isEqualTo("header-credential-1234");
    }

    @Test
    @DisplayName("secrets left out are kept; a new environment without a new key is refused; MANUAL clears them all")
    void secretsAreKeptAndCleared() {
        CarrierJpaEntity row = existing("AHA", "ACTIVE");
        when(repository.findByCode("AHA")).thenReturn(Optional.of(row));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        service.update(row.getId(), connect(booking("STAGING", "key-12345678", "header-credential-1234"), 0L));
        String sealedKey = row.getApiKeySealed();

        assertThat(service.update(row.getId(), connect(booking("STAGING", null, null), 0L)).connection()
                .apiKeyConfigured()).isTrue();
        assertThat(row.getApiKeySealed()).isEqualTo(sealedKey);

        BusinessException other = refusal(() -> service.update(row.getId(),
                connect(booking("PRODUCTION", null, null), 0L)));
        assertThat(other.fieldErrors()).extracting(f -> f.field()).containsExactly("apiKey");

        Carriers.ConnectionView manual = service.update(row.getId(), connect(new Carriers.ConnectionInput("MANUAL",
                null, null, null, null, null, null, null, null), 0L)).connection();
        // The failure-reason table is the carrier's vocabulary, not a setting of the API: left out, it stays.
        assertThat(manual).isEqualTo(new Carriers.ConnectionView("MANUAL", false, false, null, false, null, null,
                false, Map.of("Khách không nghe máy", "CUSTOMER_ABSENT")));
        assertThat(row.getApiKeySealed()).isNull();
        assertThat(row.getWebhookAuthValueSealed()).isNull();
    }

    @Test
    @DisplayName("each missing or malformed setting is named, and nothing is sealed while any rule is broken")
    void incompleteConnectionsAreRefused() {
        CarrierJpaEntity row = existing("AHA", "ACTIVE");
        when(repository.findByCode("AHA")).thenReturn(Optional.of(row));

        BusinessException missing = refusal(() -> service.update(row.getId(), connect(new Carriers.ConnectionInput(
                "API_BOOKING", null, null, null, null, null, null, null, null), 0L)));
        assertThat(missing.fieldErrors()).extracting(f -> f.field()).containsExactlyInAnyOrder("webhookAuthHeader",
                "webhookAuthValue", "apiEnvironment", "apiAccount", "apiKey");

        BusinessException malformed = refusal(() -> service.update(row.getId(), connect(new Carriers.ConnectionInput(
                "WEBHOOK_LOAD", true, "short", null, null, "MOON", null, " ", Map.of("x", "LATE")), 0L)));
        assertThat(malformed.fieldErrors()).extracting(f -> f.field()).contains("webhookSecret", "apiEnvironment",
                "apiKey", "failureReasonMap");

        assertThat(refusal(() -> service.update(row.getId(), connect(new Carriers.ConnectionInput("PIGEON", null,
                null, null, null, null, null, null, null), 0L))).fieldErrors()).extracting(f -> f.field())
                .containsExactly("connectionType");
        assertThat(refusal(() -> service.update(row.getId(), connect(new Carriers.ConnectionInput("MANUAL", true,
                null, null, null, null, null, null, null), 0L))).fieldErrors()).extracting(f -> f.field())
                .containsExactly("webhookEnabled");
        assertThat(row.getApiKeySealed()).isNull();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a table left out keeps the one saved, an empty one empties it; a new account needs a new key")
    void reasonTableAndAccount() {
        CarrierJpaEntity row = existing("AHA", "ACTIVE");
        when(repository.findByCode("AHA")).thenReturn(Optional.of(row));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        service.update(row.getId(), connect(booking("STAGING", "key-12345678", "header-credential-1234"), 0L));

        Carriers.ConnectionInput noTable = new Carriers.ConnectionInput("API_BOOKING", null, null, "apikey", null,
                "STAGING", "84908000000", null, null);
        assertThat(service.update(row.getId(), connect(noTable, 0L)).connection().failureReasonMap())
                .containsEntry("Khách không nghe máy", "CUSTOMER_ABSENT");
        Carriers.ConnectionInput emptied = new Carriers.ConnectionInput("API_BOOKING", null, null, "apikey", null,
                "STAGING", "84908000000", null, Map.of());
        assertThat(service.update(row.getId(), connect(emptied, 0L)).connection().failureReasonMap()).isEmpty();

        Carriers.ConnectionInput otherAccount = new Carriers.ConnectionInput("API_BOOKING", null, null, "apikey", null,
                "STAGING", "84911111111", null, null);
        assertThat(refusal(() -> service.update(row.getId(), connect(otherAccount, 0L))).fieldErrors())
                .extracting(f -> f.field()).containsExactly("apiKey");
    }

    @Test
    @DisplayName("a malformed setting is named once, not also reported missing")
    void oneErrorPerField() {
        CarrierJpaEntity row = existing("AHA", "ACTIVE");
        when(repository.findByCode("AHA")).thenReturn(Optional.of(row));

        BusinessException refused = refusal(() -> service.update(row.getId(), connect(booking("MOON",
                "key-12345678", "header-credential-1234"), 0L)));

        assertThat(refused.fieldErrors()).extracting(f -> f.field()).containsExactly("apiEnvironment");
    }

    @Test
    @DisplayName("the service's records never print a secret")
    void recordsMaskSecrets() {
        Carriers.ConnectionInput input = booking("STAGING", "key-12345678", "header-credential-1234");
        String printed = input + " " + connect(input, 0L);

        assertThat(printed).doesNotContain("key-12345678").doesNotContain("header-credential-1234").contains("***")
                .contains("84908000000");
    }

    @Test
    @DisplayName("without a secrets key a connection with a secret is 503 SECRETS_UNAVAILABLE; MANUAL still saves")
    void noKeyNoSecrets() {
        CarrierService keyless = new CarrierService(repository, new SecretCipher(new SecretCipherProperties("")));
        CarrierJpaEntity row = existing("AHA", "ACTIVE");
        when(repository.findByCode("AHA")).thenReturn(Optional.of(row));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(refusal(() -> keyless.update(row.getId(), connect(new Carriers.ConnectionInput("WEBHOOK_LOAD",
                null, "signing-secret-123456", null, null, null, null, null, null), 0L))).errorCode())
                .isEqualTo(ErrorCode.SECRETS_UNAVAILABLE);
        assertThat(keyless.update(row.getId(), connect(new Carriers.ConnectionInput("MANUAL", null, null, null, null,
                null, null, null, Map.of("Mất hàng", "LOST")), 0L)).connection().failureReasonMap())
                .containsEntry("Mất hàng", "LOST");
        // Every rule first, the seal last: a request refused anyway is 400, not 503, even with a secret in it.
        assertThat(refusal(() -> keyless.update(row.getId(), connect(new Carriers.ConnectionInput("API_BOOKING",
                null, null, "apikey", "header-credential-1234", null, "84908000000", "key-12345678", null), 0L)))
                .errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
