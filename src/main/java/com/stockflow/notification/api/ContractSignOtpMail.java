package com.stockflow.notification.api;

import java.time.Instant;

/**
 * The one-time code a customer enters to sign a contract on the portal, mailed to them. The code is a credential, so
 * {@link #toString()} leaves it out and nothing here may be persisted (an event would be: this is a direct call, as the
 * portal invitation's is) or logged.
 *
 * @param contractNumber the number of the contract being signed, as the mail names it
 * @param expiresAt      when the code stops working
 */
public record ContractSignOtpMail(String recipient, String contractNumber, String code, Instant expiresAt) {

    @Override
    public String toString() {
        return "ContractSignOtpMail[recipient=" + recipient + ", contractNumber=" + contractNumber
                + ", code=REDACTED, expiresAt=" + expiresAt + "]";
    }
}
