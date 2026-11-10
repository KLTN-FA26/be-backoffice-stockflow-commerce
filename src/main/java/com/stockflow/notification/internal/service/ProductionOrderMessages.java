package com.stockflow.notification.internal.service;

import com.stockflow.common.security.SignedLinks;
import com.stockflow.notification.api.ProductionOrderMail;
import com.stockflow.notification.internal.domain.Email;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Words the messages a producer receives about a production order (4A-3, kltn-docs 17 §4.3 steps 7, 10, 13): the order
 * sent, its sample approved, the order stopped. Plain text in Vietnamese, as the purchase-order mail was; the
 * reply goes to the coordinator who drew the order up.
 *
 * <p>The links to the slip and the print files are signed here, when the message is written ({@link SignedLinks}), and
 * live until {@link ProductionOrderMail#filesAvailableUntil()}: never stored, never logged - {@link Email#toString()}
 * prints sizes only. A deployment that has no public address configured gets a message saying the coordinator sends
 * the files, rather than links nobody can open.</p>
 */
@Component
class ProductionOrderMessages {

    /** What the producer is told. */
    enum Kind {
        SENT("sent", "production_order_sent"),
        SAMPLE_APPROVED("sample-approved", "production_order_sample_approved"),
        STOPPED("stopped", "production_order_stopped");

        /** The last segment of the message's reference. */
        final String segment;
        /** The template code on each delivery attempt, and the provider tag. */
        final String template;

        Kind(String segment, String template) {
            this.segment = segment;
            this.template = template;
        }
    }

    /** The business runs in Vietnam; the producer reads dates and deadlines in its local time. */
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(VIETNAM);

    private final EmailProperties properties;
    private final SignedLinks links;

    ProductionOrderMessages(EmailProperties properties, SignedLinks links) {
        this.properties = properties;
        this.links = links;
    }

    /**
     * @param reference the message's reference, its idempotency key at the provider: a retry is the same message
     * @param replyTo   the coordinator's address, or null
     */
    Email compose(Kind kind, ProductionOrderMail mail, String reference, String replyTo) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        List<String> text = new ArrayList<>();
        text.add(t.getString("production-order.mail.greeting").formatted(mail.producerName()));
        text.add("");
        String subject = switch (kind) {
            case SENT -> {
                sent(t, mail, text);
                yield t.getString("production-order.mail.sent.subject").formatted(mail.code(),
                        DAY.format(mail.needByDate()));
            }
            case SAMPLE_APPROVED -> {
                sampleApproved(t, mail, text);
                yield t.getString("production-order.mail.sample.subject").formatted(mail.code());
            }
            case STOPPED -> {
                String outcome = "CLOSED".equals(mail.status()) ? "CLOSED" : "CANCELLED";
                text.add(t.getString("production-order.mail.stop." + outcome).formatted(mail.code()));
                if (mail.stopReason() != null) {
                    text.add(t.getString("production-order.mail.stop.reason").formatted(mail.stopReason()));
                }
                yield t.getString("production-order.mail.stop.subject." + outcome).formatted(mail.code());
            }
        };
        text.add("");
        text.add(t.getString("production-order.mail.signature"));
        return new Email(properties.from(), List.of(mail.producerEmail()), replyTo, subject, String.join("\n", text),
                null, reference, kind.template);
    }

    private void sent(ResourceBundle t, ProductionOrderMail mail, List<String> text) {
        text.add(t.getString("production-order.mail.sent.intro").formatted(mail.code()));
        text.add("");
        text.add(t.getString("production-order.mail.warehouse").formatted(mail.warehouseName(),
                orDash(mail.warehouseAddress())));
        text.add(t.getString("production-order.mail.needBy").formatted(DAY.format(mail.needByDate())));
        text.add(t.getString(mail.sampleApprovalRequired() ? "production-order.mail.sample.required"
                : "production-order.mail.sample.notRequired"));
        if (mail.unitPrice() != null) {
            text.add(t.getString("production-order.mail.unitPrice").formatted(amount(mail.unitPrice()),
                    mail.currency()));
        }
        if (mail.note() != null) {
            text.add(t.getString("production-order.mail.note").formatted(mail.note()));
        }
        text.add("");
        text.add(t.getString("production-order.mail.lines"));
        for (ProductionOrderMail.Line line : mail.lines()) {
            text.add(t.getString("production-order.mail.line").formatted(line.sku(), orDash(line.name()),
                    line.cartons(), line.unitsPerCarton(), line.totalUnits(),
                    line.cartonSize() == null ? t.getString("production-order.mail.unknown") : line.cartonSize()));
            if (line.packingRequirements() != null) {
                text.add(t.getString("production-order.mail.line.packing").formatted(line.packingRequirements()));
            }
        }
        text.add("");
        files(t, mail, true, text);
        text.add("");
        text.add(t.getString("production-order.mail.sent.closing"));
    }

    private void sampleApproved(ResourceBundle t, ProductionOrderMail mail, List<String> text) {
        text.add(t.getString("production-order.mail.sample.intro").formatted(mail.code()));
        text.add(t.getString("production-order.mail.needBy").formatted(DAY.format(mail.needByDate())));
        text.add("");
        files(t, mail, false, text);
        text.add("");
        text.add(t.getString("production-order.mail.sample.closing"));
    }

    /** The slip (when {@code withSlip}) and each line's print file, as links that work until the stated time. */
    private void files(ResourceBundle t, ProductionOrderMail mail, boolean withSlip, List<String> text) {
        if (!links.available()) {
            text.add(t.getString("production-order.mail.links.unavailable"));
            return;
        }
        Instant until = mail.filesAvailableUntil();
        if (withSlip && mail.slip() != null) {
            text.add(t.getString("production-order.mail.slip").formatted(links.url(mail.slip().downloadPath(), until)));
        }
        for (ProductionOrderMail.Line line : mail.lines()) {
            if (line.printFile() != null) {
                text.add(t.getString("production-order.mail.printFile").formatted(line.sku(),
                        links.url(line.printFile().downloadPath(), until)));
            }
        }
        text.add(t.getString("production-order.mail.links.until").formatted(MOMENT.format(until)));
    }

    private static String amount(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
