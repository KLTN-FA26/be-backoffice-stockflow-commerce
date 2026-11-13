package com.stockflow.order.internal.service;

import com.stockflow.common.label.LabelFonts;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderAddressSnapshot;
import com.stockflow.order.internal.domain.OrderFee;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.common.label.PrintedDocument;
import com.stockflow.common.label.PrintedDocument.Line;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.StringJoiner;

/**
 * Draws a sales contract as a PDF (4B-4, kltn-docs 14 §4.3 steps 11-13): the parties, the goods and fee lines with
 * their VAT, the three totals, the delivery terms and schedule, the payment plan and the free print-sample rounds. The
 * labels are the Vietnamese bundle's - the contract is a Vietnamese document - laid out as every document of the module
 * is ({@link PrintedDocument}). What it shows comes from the contract and its order; nothing is
 * looked up here.
 */
@Component
class SalesContractDocument {

    /** The contract's view, its order (lines, fees, address) and the day the document is dated. */
    record Facts(SalesContracts.ContractView contract, Order order, LocalDate dated) { }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final LabelFonts fonts;

    SalesContractDocument(LabelFonts fonts) {
        this.fonts = fonts;
    }

    byte[] render(Facts facts) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        SalesContracts.ContractView c = facts.contract();
        List<Line> lines = new ArrayList<>();
        lines.add(Line.title(t.getString("contract.pdf.title")));
        lines.add(Line.text(t.getString("contract.pdf.number") + ": " + c.contractNumber() + "    "
                + t.getString("contract.pdf.date") + ": " + DAY.format(facts.dated())));
        lines.add(Line.text(t.getString("contract.pdf.order") + ": " + c.orderNumber()));
        party(lines, t, "contract.pdf.seller", c.seller());
        party(lines, t, "contract.pdf.buyer", c.buyer());

        lines.add(Line.heading(t.getString("contract.pdf.goods")));
        for (OrderLine line : facts.order().lines()) {
            lines.add(Line.text(t.getString("contract.pdf.goodsLine").formatted(line.sku().code(),
                    line.cartons() == null ? 0 : line.cartons(),
                    line.unitsPerCarton() == null ? 0 : line.unitsPerCarton(), line.quantity(),
                    money(line.unitPrice().amount()), money(line.lineTotal().amount()), rate(line.taxRate()),
                    money(line.taxAmount() == null ? BigDecimal.ZERO : line.taxAmount().amount()))));
        }
        for (OrderFee fee : facts.order().fees()) {
            String kind = t.getString("contract.pdf.fee." + fee.kind().name())
                    .formatted(fee.sku() == null ? "" : fee.sku().code());
            lines.add(Line.text(t.getString("contract.pdf.feeLine").formatted(kind,
                    money(fee.amount().amount()), rate(fee.taxRate()), money(fee.taxAmount().amount()))));
        }

        lines.add(Line.heading(t.getString("contract.pdf.totals")));
        lines.add(Line.text(t.getString("contract.pdf.net") + ": " + money(c.totals().netAmount())));
        lines.add(Line.text(t.getString("contract.pdf.tax") + ": " + money(c.totals().taxAmount())));
        lines.add(Line.bold(t.getString("contract.pdf.total") + ": " + money(c.totals().totalAmount())));

        lines.add(Line.heading(t.getString("contract.pdf.delivery")));
        OrderAddressSnapshot address = facts.order().shippingAddress();
        if (address != null) {
            lines.add(Line.text(t.getString("contract.pdf.deliveryAddress") + ": " + address(address)));
            lines.add(Line.text(t.getString("contract.pdf.recipient") + ": "
                    + nonNull(address.recipientName()) + " - " + nonNull(address.phone())));
        }
        for (SalesContracts.BatchView batch : c.deliverySchedule()) {
            String window = batch.windowStart() == null ? t.getString("contract.pdf.windowPending")
                    : t.getString("contract.pdf.window").formatted(DAY.format(batch.windowStart()),
                    DAY.format(batch.windowEnd()));
            lines.add(Line.text(t.getString("contract.pdf.batch").formatted(batch.seq(), window,
                    money(batch.value()))));
            for (SalesContracts.BatchLineView line : batch.lines()) {
                lines.add(Line.text(t.getString("contract.pdf.batchLine").formatted(line.sku(),
                        line.cartons(), line.units())));
            }
        }

        lines.add(Line.heading(t.getString("contract.pdf.payment")));
        for (SalesContracts.InstallmentView installment : c.paymentPlan()) {
            String when = t.getString("contract.pdf.milestone." + installment.milestone())
                    .formatted(installment.dueDays() == null ? 0 : installment.dueDays());
            if (installment.deliveryBatchSeq() != null) {
                when = when + " " + t.getString("contract.pdf.milestone.batch").formatted(installment.deliveryBatchSeq());
            }
            lines.add(Line.text(t.getString("contract.pdf.installment").formatted(installment.seq(),
                    installment.name(), money(installment.amount()), when)));
        }

        lines.add(Line.heading(t.getString("contract.pdf.samples")));
        lines.add(Line.text(t.getString("contract.pdf.samplesText").formatted(c.freeSampleRounds())));
        lines.add(Line.heading(""));
        lines.add(Line.bold(t.getString("contract.pdf.signatures")));
        return PrintedDocument.draw(fonts, lines, t.getString("contract.pdf.title") + " " + c.contractNumber(), null);
    }

    private static void party(List<Line> lines, ResourceBundle t, String heading, SalesContracts.Party party) {
        lines.add(Line.heading(t.getString(heading)));
        lines.add(Line.bold(party.name()));
        if (party.taxCode() != null) {
            lines.add(Line.text(t.getString("contract.pdf.taxCode") + ": " + party.taxCode()));
        }
        lines.add(Line.text(t.getString("contract.pdf.address") + ": " + party.address()));
        lines.add(Line.text(t.getString("contract.pdf.representative") + ": " + party.representative()
                + " - " + party.representativeTitle()));
    }

    /** Whole dong, grouped with dots as Vietnamese documents write it: 1.234.567 VND. */
    private static String money(BigDecimal amount) {
        return PrintedDocument.money(amount, "VND");
    }

    private static String rate(BigDecimal rate) {
        return rate == null ? "0" : rate.stripTrailingZeros().toPlainString();
    }

    private static String address(OrderAddressSnapshot a) {
        StringJoiner joined = new StringJoiner(", ");
        for (String part : new String[] {a.line1(), a.line2(), a.wardName(), a.provinceName()}) {
            if (part != null && !part.isBlank()) {
                joined.add(part);
            }
        }
        return joined.toString();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
