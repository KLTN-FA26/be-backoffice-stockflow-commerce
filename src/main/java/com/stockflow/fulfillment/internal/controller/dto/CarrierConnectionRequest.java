package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * How a carrier connects (kltn-docs 09 §4.1 step 2, BR-21), replaced whole except for the three secrets: a secret left
 * out keeps the one saved, a value replaces it. Secrets are write-only - the response says only whether each is set.
 * Settings the chosen type does not use are cleared.
 *
 * @param connectionType    MANUAL, WEBHOOK_LOAD or API_BOOKING
 * @param webhookEnabled    whether its webhooks are accepted now; left out: on for a carrier that sends webhooks
 * @param webhookSecret     WEBHOOK_LOAD: the secret its events are signed with (HMAC-SHA256 of the body), 16-512
 * @param webhookAuthHeader API_BOOKING: the header its webhooks carry the credential in ({@code apikey},
 *                          {@code Authorization})
 * @param webhookAuthValue  API_BOOKING: the whole value expected in that header, 16-512
 * @param apiEnvironment    API_BOOKING: STAGING or PRODUCTION; changing it needs a new {@code apiKey}
 * @param apiAccount        API_BOOKING: the account the carrier issued the key to (Ahamove: the phone number)
 * @param apiKey            API_BOOKING: 8-512 characters
 * @param failureReasonMap  the carrier's wording of a failed delivery to CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST,
 *                          DAMAGED or OTHER; wording missing from it is recorded as OTHER with the carrier's words
 */
public record CarrierConnectionRequest(@NotBlank String connectionType,
                                       Boolean webhookEnabled,
                                       @Size(max = 512) String webhookSecret,
                                       @Size(max = 64) String webhookAuthHeader,
                                       @Size(max = 512) String webhookAuthValue,
                                       String apiEnvironment,
                                       @Size(max = 64) String apiAccount,
                                       @Size(max = 512) String apiKey,
                                       @Size(max = 100) Map<String, String> failureReasonMap) {

    /** Never prints a secret: a request logged or shown in an error must not carry one. */
    @Override
    public String toString() {
        return "CarrierConnectionRequest[connectionType=" + connectionType + ", webhookEnabled=" + webhookEnabled
                + ", webhookSecret=" + (webhookSecret == null ? null : "***") + ", webhookAuthHeader="
                + webhookAuthHeader + ", webhookAuthValue=" + (webhookAuthValue == null ? null : "***")
                + ", apiEnvironment=" + apiEnvironment + ", apiAccount=" + apiAccount + ", apiKey="
                + (apiKey == null ? null : "***") + ", failureReasonMap=" + failureReasonMap + "]";
    }
}
