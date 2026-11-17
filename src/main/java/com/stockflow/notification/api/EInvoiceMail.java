package com.stockflow.notification.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An issued e-invoice as the message to its buyer words it (WF-05 E5; plan §1.4: "gửi email cho người mua kèm PDF, XML
 * và mã tra cứu"): what {@link EInvoiceDirectory} hands notification when the message is written. Every value is the
 * invoice's own, as issued - the buyer and the address are the ones copied onto the invoice from the customer's legal
 * profile, not the profile as it reads today.
 *
 * <p>{@link #toString()} names the invoice only: a name, an address or a lookup code in a log line is a leak.</p>
 *
 * @param buyerName      the buyer as the invoice names it: the name to greet
 * @param recipientEmail the address the invoice is sent to: the buyer's invoice e-mail as the invoice holds it
 * @param reference      the invoice as people quote it: template code, symbol and number ({@code 1C26TSF-00000042})
 * @param lookupCode     the code the buyer looks the invoice up by at the provider
 */
public record EInvoiceMail(UUID einvoiceId, UUID customerId, String buyerName, String recipientEmail,
                           String reference, String orderNumber, LocalDate issueDate, BigDecimal grandTotal,
                           String currency, String taxAuthorityCode, String lookupCode) {

    /**
     * A file of the invoice.
     *
     * @param name        the name the buyer saves it under
     * @param contentType its media type
     */
    public record File(String name, String contentType, byte[] content) {

        @Override
        public String toString() {
            return "File[" + name + "]";
        }
    }

    @Override
    public String toString() {
        return "EInvoiceMail[einvoiceId=" + einvoiceId + "]";
    }
}
