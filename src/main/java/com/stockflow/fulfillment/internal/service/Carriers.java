package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;

import java.util.Map;
import java.util.UUID;

/**
 * The carrier catalogue (G4-2a; kltn-docs 09 §3: a shipment is handed to a carrier "chọn từ danh sách hãng đã khai
 * báo: tên, liên hệ") and each carrier's connection settings (3-1; 09 §4.1 step 2, BR-21). Read by dispatch staff and
 * managers; kept by the warehouse manager.
 */
public interface Carriers {

    /**
     * A carrier as shown. Its secrets are never part of it - only whether each is set (09 §4.1: "không hiển thị lại
     * sau khi lưu").
     */
    record CarrierView(UUID id, String code, String name, String contactName, String contactPhone, String status,
                       ConnectionView connection, long version) { }

    /**
     * @param type                       MANUAL, WEBHOOK_LOAD or API_BOOKING
     * @param webhookSecretConfigured    WEBHOOK_LOAD: whether the signing secret is set
     * @param webhookAuthValueConfigured API_BOOKING: whether the credential expected in {@code webhookAuthHeader} is set
     * @param webhookAuthValueNextConfigured API_BOOKING: whether a second credential holds as well, the first being
     *                                   replaced at the carrier
     * @param apiKeyConfigured           API_BOOKING: whether the API key is set
     * @param failureReasonMap           the carrier's wording of a failed delivery to the internal reason
     */
    record ConnectionView(String type, boolean webhookEnabled, boolean webhookSecretConfigured,
                          String webhookAuthHeader, boolean webhookAuthValueConfigured,
                          boolean webhookAuthValueNextConfigured, String apiEnvironment, String apiAccount,
                          boolean apiKeyConfigured, Map<String, String> failureReasonMap) { }

    /**
     * @param connection the connection settings to save; null leaves them as they are (and a new carrier MANUAL)
     * @param version    required to change an existing carrier: the version read (optimistic lock); ignored on create
     */
    record SaveCarrier(String code, String name, String contactName, String contactPhone, String status,
                       ConnectionInput connection, Long version) {

        /** The connection's own {@code toString}, which never prints a secret. */
        @Override
        public String toString() {
            return "SaveCarrier[code=" + code + ", name=" + name + ", status=" + status + ", connection=" + connection
                    + ", version=" + version + "]";
        }
    }

    /**
     * The connection settings, replaced whole except for the secrets and the failure-reason table: a secret left null
     * keeps the one saved, a new value replaces it. A setting the chosen type does not use is cleared, secrets
     * included, so nothing outlives the connection it belonged to; a secret given for such a setting is ignored.
     *
     * @param type             MANUAL, WEBHOOK_LOAD or API_BOOKING; required
     * @param webhookEnabled   null: kept as it is for a carrier that already sent webhooks, on for one that starts to,
     *                         off for MANUAL (which can never be on)
     * @param webhookSecret    WEBHOOK_LOAD: 16-512 characters
     * @param webhookAuthHeader API_BOOKING: the header name, e.g. {@code apikey} or {@code Authorization}
     * @param webhookAuthValue API_BOOKING: the whole value expected in that header (e.g. {@code Bearer ...}), 16-512
     * @param apiEnvironment   API_BOOKING: STAGING or PRODUCTION; changing it needs a new {@code apiKey}
     * @param apiAccount       API_BOOKING: the account the key is issued to; changing it needs a new {@code apiKey}
     * @param apiKey           API_BOOKING: 8-512 characters
     * @param failureReasonMap wording to CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED or OTHER; null keeps the
     *                         table saved, an empty one empties it
     */
    record ConnectionInput(String type, Boolean webhookEnabled, String webhookSecret, String webhookAuthHeader,
                           String webhookAuthValue, String apiEnvironment, String apiAccount, String apiKey,
                           Map<String, String> failureReasonMap) {

        /** Never prints a secret: a record logged or put in a message must not carry one. */
        @Override
        public String toString() {
            return "ConnectionInput[type=" + type + ", webhookEnabled=" + webhookEnabled + ", webhookSecret="
                    + (webhookSecret == null ? null : "***") + ", webhookAuthHeader=" + webhookAuthHeader
                    + ", webhookAuthValue=" + (webhookAuthValue == null ? null : "***") + ", apiEnvironment="
                    + apiEnvironment + ", apiAccount=" + apiAccount + ", apiKey=" + (apiKey == null ? null : "***")
                    + ", failureReasonMap=" + failureReasonMap + "]";
        }
    }

    /** Paged, by name; {@code status} ACTIVE / INACTIVE or null for both; {@code search} in code or name. */
    PageResponse<CarrierView> list(String status, String search, int page, int size);

    CarrierView get(UUID id);

    /**
     * @throws com.stockflow.common.error.BusinessException {@code CARRIER_CODE_EXISTS}; {@code VALIDATION_FAILED}
     *         naming each field a connection lacks; 503 {@code SECRETS_UNAVAILABLE} when a secret is given and no
     *         secrets key is configured
     */
    CarrierView create(SaveCarrier carrier);

    /**
     * Name, contact, status, code and connection. An INACTIVE carrier keeps its shipments and loads and takes no new
     * load.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CARRIER_NOT_FOUND}; {@code CARRIER_CODE_EXISTS};
     *         {@code OPTIMISTIC_LOCK} when {@code version} is not the current one; {@code VALIDATION_FAILED};
     *         503 {@code SECRETS_UNAVAILABLE}
     */
    CarrierView update(UUID id, SaveCarrier carrier);

    /** A step in replacing an API_BOOKING carrier's webhook credential without a moment when neither holds. */
    enum CredentialRotation {
        /** A second credential starts to hold beside the current one. */
        START,
        /** The second credential becomes the only one: the carrier sends it now. */
        PROMOTE,
        /** The second credential is dropped; the current one stays. */
        DISCARD
    }

    /**
     * Replaces the credential an API_BOOKING carrier's webhooks carry in two steps (WF-05 fix plan item 6): START makes
     * {@code value} hold beside the current one, so the carrier can be told the new value while calls with the old one
     * still arrive; PROMOTE, once the carrier sends the new one, makes it the only one; DISCARD gives the change up.
     * Saving a new {@code webhookAuthValue} through {@link #update} ends a rotation too: the second credential goes.
     *
     * @param value   START: the new credential, 16-512 characters, not the current one; ignored otherwise
     * @param version the version read (optimistic lock)
     * @throws com.stockflow.common.error.BusinessException {@code CARRIER_NOT_FOUND}; {@code OPTIMISTIC_LOCK}; 409
     *         {@code CARRIER_NOT_API_BOOKING}; 409 {@code CARRIER_CREDENTIAL_ROTATION_NOT_STARTED} to PROMOTE or DISCARD
     *         with no second credential; {@code VALIDATION_FAILED}; 503 {@code SECRETS_UNAVAILABLE}
     */
    CarrierView rotateWebhookCredential(UUID id, CredentialRotation step, String value, Long version);
}
