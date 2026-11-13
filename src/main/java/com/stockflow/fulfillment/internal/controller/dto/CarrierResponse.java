package com.stockflow.fulfillment.internal.controller.dto;

import java.util.Map;
import java.util.UUID;

/** A carrier of the catalogue (G4-2a) and how it connects (3-1). No secret is ever part of it. */
public record CarrierResponse(UUID carrierId, String code, String name, String contactName, String contactPhone,
                              String status, Connection connection, long version) {

    /**
     * @param webhookSecretConfigured    WEBHOOK_LOAD: whether the signing secret is set
     * @param webhookAuthValueConfigured API_BOOKING: whether the header credential is set
     * @param webhookAuthValueNextConfigured API_BOOKING: whether a second header credential holds as well, the first
     *                                   being replaced at the carrier (a rotation under way)
     * @param apiKeyConfigured           API_BOOKING: whether the API key is set
     */
    public record Connection(String connectionType, boolean webhookEnabled, boolean webhookSecretConfigured,
                             String webhookAuthHeader, boolean webhookAuthValueConfigured,
                             boolean webhookAuthValueNextConfigured, String apiEnvironment, String apiAccount,
                             boolean apiKeyConfigured, Map<String, String> failureReasonMap) {
    }
}
