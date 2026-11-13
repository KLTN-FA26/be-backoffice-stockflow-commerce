package com.stockflow.notification.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A 04/SS-HĐĐT notice as the message to the buyer words it (WF-05 E4/E5): what {@link EInvoiceDirectory} hands
 * notification when it tells a buyer that the invoice it holds names it wrongly. The invoice's values are the ones it
 * was issued with; the right name and address are the notice's.
 *
 * <p>{@link #toString()} names the notice and the invoice only: a name or an address in a log line is a leak.</p>
 *
 * @param buyerName           the buyer as the invoice names it
 * @param recipientEmail      the address the invoice is sent to: the buyer's invoice e-mail as the invoice holds it
 * @param reference           the invoice as people quote it: template code, symbol and number
 * @param formCode            the form the tax authority is notified on, by the name the law prints and the buyer reads
 *                            ({@code 04/SS-HĐĐT})
 * @param reason              why the invoice is wrong, as the accountant wrote it on the notice
 * @param correctBuyerName    the name the invoice should have carried; null when the name was right
 * @param correctBuyerAddress the address the invoice should have carried; null when the address was right
 */
public record EInvoiceErrorNoticeMail(UUID noticeId, UUID einvoiceId, UUID customerId, String buyerName,
                                      String recipientEmail, String reference, String orderNumber,
                                      LocalDate issueDate, String formCode, String reason, String correctBuyerName,
                                      String correctBuyerAddress) {

    /** The name to greet: the right one when the notice gives it, not the wrong one the invoice carries. */
    public String addresseeName() {
        return correctBuyerName != null && !correctBuyerName.isBlank() ? correctBuyerName : buyerName;
    }

    @Override
    public String toString() {
        return "EInvoiceErrorNoticeMail[noticeId=" + noticeId + ", einvoiceId=" + einvoiceId + "]";
    }
}
