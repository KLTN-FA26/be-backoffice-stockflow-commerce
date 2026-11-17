package com.stockflow.fulfillment.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a carrier's connection settings must hold for its kind of connection (kltn-docs 09 §4.1 step 2, BR-21), stated
 * once here and once as the table's {@code ck_carrier_*} checks.
 *
 * <p>The secrets themselves never reach this class: it is told only whether each one is set, which is all a rule needs
 * and all that may be shown back once a secret is saved ("không hiển thị lại sau khi lưu").</p>
 *
 * @param type                the kind of connection
 * @param webhookEnabled      whether webhooks of this carrier are accepted now; the warehouse manager can stop them
 *                            while the carrier misbehaves without losing the configuration. Never on for MANUAL
 * @param webhookSecretSet    WEBHOOK_LOAD: the secret its events are signed with
 * @param webhookAuthHeader   API_BOOKING: the header the carrier's webhooks carry the credential in ({@code apikey},
 *                            {@code Authorization})
 * @param webhookAuthValueSet API_BOOKING: whether the credential expected in that header is set
 * @param apiEnvironment      API_BOOKING: which of the carrier's environments the calls go to
 * @param apiAccount          API_BOOKING: the account the token is asked for (Ahamove: the registered phone number)
 * @param apiKeySet           API_BOOKING: whether the key that account is issued is set
 */
public record CarrierConnection(CarrierConnectionType type, boolean webhookEnabled, boolean webhookSecretSet,
                                String webhookAuthHeader, boolean webhookAuthValueSet,
                                CarrierApiEnvironment apiEnvironment, String apiAccount, boolean apiKeySet) {

    /** An HTTP header name (RFC 9110 token, letters, digits and '-' only). */
    public static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    /** An account name a carrier issues: a phone number, a login - printable, no spaces. */
    public static final Pattern ACCOUNT = Pattern.compile("^[!-~]{3,64}$");

    /** A carrier nobody has connected: everything recorded by hand. */
    public static CarrierConnection manual() {
        return new CarrierConnection(CarrierConnectionType.MANUAL, false, false, null, false, null, null, false);
    }

    /** One rule a connection breaks: the request field to fix and what it must be. */
    public record Violation(String field, String rule) {
    }

    /** Every rule these settings break; empty when they are complete for {@link #type}. */
    public List<Violation> violations() {
        List<Violation> found = new ArrayList<>();
        if (type == null) {
            found.add(new Violation("connectionType", "MANUAL, WEBHOOK_LOAD or API_BOOKING"));
            return found;
        }
        if (type == CarrierConnectionType.MANUAL && webhookEnabled) {
            found.add(new Violation("webhookEnabled", "a carrier without webhooks cannot have them enabled"));
        }
        if (type == CarrierConnectionType.WEBHOOK_LOAD && !webhookSecretSet) {
            found.add(new Violation("webhookSecret", "required for WEBHOOK_LOAD: the secret its events are signed with"));
        }
        if (type == CarrierConnectionType.API_BOOKING) {
            if (webhookAuthHeader == null || !HEADER_NAME.matcher(webhookAuthHeader).matches()) {
                found.add(new Violation("webhookAuthHeader",
                        "required for API_BOOKING: the header name its webhooks carry the credential in"));
            }
            if (!webhookAuthValueSet) {
                found.add(new Violation("webhookAuthValue",
                        "required for API_BOOKING: the credential its webhooks carry"));
            }
            if (apiEnvironment == null) {
                found.add(new Violation("apiEnvironment", "required for API_BOOKING: STAGING or PRODUCTION"));
            }
            if (apiAccount == null || !ACCOUNT.matcher(apiAccount).matches()) {
                found.add(new Violation("apiAccount", "required for API_BOOKING: 3-64 characters, no spaces"));
            }
            if (!apiKeySet) {
                found.add(new Violation("apiKey", "required for API_BOOKING"));
            }
        }
        return found;
    }
}
