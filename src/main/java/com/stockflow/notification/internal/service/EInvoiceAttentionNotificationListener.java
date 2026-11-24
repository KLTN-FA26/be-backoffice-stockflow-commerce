package com.stockflow.notification.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceNeedsAttention;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Tells the people who issue e-invoices that one was refused, has waited too long for the provider, is no longer known
 * to the provider and will not be sent again unless they send it (WF-05 E5 and E3), or invoices a delivery that was
 * corrected to a failure after it went to the provider and needs a decrease adjustment (kltn-docs 21 §4.5; plan
 * §1.4 "Bị từ chối hoặc lỗi thì báo kế toán"), or was issued with a seller's signature that does not hold and was not
 * sent to the buyer (HĐ-8), or is a draft the automatic issuing holds back (audit r2 M-2: told at once, since the
 * invoice is due by the end of its delivery day). Without it a refusal is seen only by whoever happens to open the list,
 * while the invoice's deadline - the end of its date (WF-05 Q-1) - keeps running. An in-app message, as the deadline
 * warning is ({@link EInvoiceDueNotificationListener}); nothing by e-mail.
 *
 * <p>Who is told is decided by permission: the holders of {@link EInvoiceDueNotificationListener#AUDIENCE}. One message
 * per person, invoice, cause and submission ({@code einvoice-<cause>:<id>:<submittedAt>}): an invoice refused,
 * corrected, sent again and refused again is a new refusal and is announced again; a redelivered event tells nobody
 * twice. System text only - codes and dates; the provider's sentence about a refusal is read on the invoice.</p>
 */
@Component
class EInvoiceAttentionNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceAttentionNotificationListener.class);

    static final String CATEGORY = "EINVOICE_ATTENTION";

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final StaffDirectory staff;
    private final Inbox inbox;

    EInvoiceAttentionNotificationListener(StaffDirectory staff, Inbox inbox) {
        this.staff = staff;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(EInvoiceNeedsAttention event) {
        // Invoices are the business's, not a warehouse's: the business-wide holders (null: scope ALL).
        List<StaffContact> recipients = staff.activeHolders(EInvoiceDueNotificationListener.AUDIENCE, null);
        if (recipients.isEmpty()) {
            log.warn("E-invoice {} needs attention ({}) and nobody may issue it", LogSafe.text(event.code()),
                    event.cause());
            return;
        }
        int told = inbox.post(recipients.stream().map(StaffContact::userId).filter(Objects::nonNull).toList(),
                message(event));
        log.info("E-invoice {} needs attention ({}): {} recipient(s), {} newly told", LogSafe.text(event.code()),
                event.cause(), recipients.size(), told);
    }

    static InboxMessage message(EInvoiceNeedsAttention event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String due = MOMENT.format(event.issueDueAt().atZone(BusinessCalendar.ZONE));
        String sent = event.submittedAt() == null ? "-" : MOMENT.format(event.submittedAt().atZone(BusinessCalendar.ZONE));
        String kind = switch (event.cause()) {
            case REJECTED -> "rejected";
            case STUCK_SUBMITTING -> "stuck";
            case UNKNOWN_TO_PROVIDER -> "unknown";
            case DELIVERY_FAILED_AFTER_SUBMISSION -> "delivery-failed";
            case SIGNATURE_INVALID -> "signature-invalid";
            case DELIVERY_FAILED_REVERSAL_DRAWN_UP -> "delivery-reversal";
            case DELIVERY_FAILED_AWAITING_PROVIDER -> "delivery-failed-awaiting";
            // WF-05 full fix (decisions of 2026-11-23): texts in place; the invoicing module publishes them.
            case DUPLICATE_RISK -> "duplicate-risk";
            case PROVIDER_RESULT_OVERDUE -> "provider-result-overdue";
            case REDUCE_ON_PROVIDER_PORTAL -> "reduce-on-portal";
            case DELIVERY_FAILED_NOT_REVERSED -> "delivery-failed-not-reversed";
            case SERIES_MISMATCH -> "series-mismatch";
            case ISSUED_LATE -> "issued-late";
            case REPLACEMENT_NOT_REDUCIBLE -> "replacement-not-reducible";
            case AUTO_ISSUE_HELD -> "auto-issue-held";
        };
        String key = "inbox.einvoice." + kind;
        String body = switch (event.cause()) {
            case REJECTED -> texts.getString(key + ".body").formatted(event.code(), event.orderNumber(),
                    event.rejectionCode() == null ? "-" : event.rejectionCode(), due);
            case ISSUED_LATE -> texts.getString(key + ".body").formatted(event.code(), event.orderNumber(),
                    day(event.issueDate()), day(event.deliveredOn()));
            case AUTO_ISSUE_HELD -> texts.getString(key + ".body").formatted(event.code(), event.orderNumber(),
                    reasons(event.rejectionCode(), texts), due);
            default -> texts.getString(key + ".body").formatted(event.code(), event.orderNumber(), sent, due);
        };
        // Stuck, and a failed delivery's invoice at the provider, still resolve themselves when the provider answers,
        // and an invoice issued late is told for the record; refused, unknown and a draft held back from the automatic
        // issuing (audit r2 M-2: due by the end of its delivery day) do not, whoever waits.
        InboxSeverity severity = event.cause() == EInvoiceNeedsAttention.Cause.STUCK_SUBMITTING
                || event.cause() == EInvoiceNeedsAttention.Cause.DELIVERY_FAILED_AWAITING_PROVIDER
                || event.cause() == EInvoiceNeedsAttention.Cause.ISSUED_LATE
                ? InboxSeverity.WARN : InboxSeverity.CRITICAL;
        return new InboxMessage(CATEGORY, severity, texts.getString(key + ".title").formatted(event.code()),
                body.length() <= InboxMessage.BODY_MAX ? body : body.substring(0, InboxMessage.BODY_MAX),
                "/invoicing/einvoices/" + event.einvoiceId(), EInvoiceDueNotificationListener.SOURCE_TYPE,
                event.einvoiceId().toString(),
                "einvoice-" + kind + ":" + event.einvoiceId() + ":" + occasion(event));
    }

    /**
     * What makes one notice of an invoice another: the submission it is about - or, for a draft held back from the
     * automatic issuing (audit r2 M-2), which was never submitted, the reason it is held, so a draft told of a tax
     * warning is told again when it is refused for a missing series, and never twice for one reason.
     */
    private static String occasion(EInvoiceNeedsAttention event) {
        if (event.cause() == EInvoiceNeedsAttention.Cause.AUTO_ISSUE_HELD) {
            // The reason is up to 200 characters and the key's limit is 200 in all: a long one goes as its hash.
            String reason = String.valueOf(event.rejectionCode());
            return reason.length() <= 100 ? reason : "h" + Integer.toHexString(reason.hashCode());
        }
        return String.valueOf(event.submittedAt() == null ? 0 : event.submittedAt().toEpochMilli());
    }

    /**
     * The reason a draft was held back, in words (audit r3 EINV-6): it comes as the draft's warning codes comma-joined
     * ({@code TAX_CLASS_UNKNOWN,TAX_RATE_DIFFERS_FROM_CONTRACT}) or the code of the refusal ({@code
     * EINVOICE_SERIES_NOT_CONFIGURED}), and an accountant reading a Vietnamese inbox was handed the codes. Each is named
     * by its {@code inbox.einvoice.reason.*} label, a refusal by its error message; a code neither knows is shown as it
     * came rather than dropped, so a new reason still says something.
     */
    static String reasons(String codes, ResourceBundle texts) {
        if (codes == null || codes.isBlank()) {
            return "-";
        }
        return java.util.Arrays.stream(codes.split(",")).map(String::strip).filter(code -> !code.isEmpty())
                .map(code -> texts.containsKey("inbox.einvoice.reason." + code)
                        ? texts.getString("inbox.einvoice.reason." + code)
                        : texts.containsKey("error." + code) ? texts.getString("error." + code) : code)
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static String day(LocalDate date) {
        return date == null ? "-" : DAY.format(date);
    }
}
