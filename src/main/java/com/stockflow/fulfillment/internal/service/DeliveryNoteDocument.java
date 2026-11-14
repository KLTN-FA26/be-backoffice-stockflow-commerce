package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.label.LabelFonts;
import com.stockflow.common.label.PrintedDocument;
import com.stockflow.common.label.PrintedDocument.Line;
import com.stockflow.fulfillment.api.ShippedCarton;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.TreeMap;

/**
 * Draws the delivery note of a shipment as a PDF (kltn-docs 09 §4.2; glossary "POD"): the paper the warehouse, the
 * driver and the customer sign when the goods change hands. It names the shipment - printed as a barcode and a QR code
 * too, the code a carrier reports its outcome with (open-questions E20) - the order, the contract and delivery batch,
 * the two parties, the vehicle the goods leave on, and the cartons by SKU with their LPNs (08 BR-02: the LPN carton is
 * the shipping carton), then the three signatures and how the signed copy becomes the shipment's proof of delivery (E16:
 * optional, uploaded on the shipment).
 *
 * <p>Printed before the goods leave as well as after: what is not known yet - a vehicle not booked, a driver the carrier
 * has not named, the moment of the handover - is a dotted line to fill in by hand, never left out, so the paper is the
 * same form whenever it is printed. SKUs are named by code only: the shipping module does not read the catalogue, and
 * the code is what is printed on the carton's label.</p>
 *
 * <p>The labels are the Vietnamese bundle's - the parties are Vietnamese - laid out as every document is
 * ({@link PrintedDocument}). What it shows is given; nothing is looked up here.</p>
 */
@Component
class DeliveryNoteDocument {

    /**
     * Everything the note says.
     *
     * @param contractNumber null with {@code batchSeq} for an order shipped without a delivery schedule
     * @param redelivery     the goods of a shipment that came back, delivered again (WF-05 fix plan, section 2)
     * @param handedOverAt   null until the vehicle is signed off
     */
    record Facts(String shipmentCode, String orderNumber, String contractNumber, Integer batchSeq, boolean redelivery,
                 String warehouseName, String warehouseAddress, String customerName, String deliveryAddress,
                 String recipient, List<Vehicle> vehicles, Instant handedOverAt, List<SkuCartons> goods,
                 LocalDate printedOn) {
    }

    /** A vehicle the shipment is on; any field may be unknown yet (null). */
    record Vehicle(String loadCode, String carrierName, String plate, String driverName, String driverPhone,
                   String waybillNo) {
    }

    /** The cartons of one SKU, whole (4C-2): how many, the pieces in them, and their LPNs. */
    record SkuCartons(String sku, int cartons, int units, List<String> lpns) {
    }

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(VIETNAM);

    private final LabelFonts fonts;

    DeliveryNoteDocument(LabelFonts fonts) {
        this.fonts = fonts;
    }

    /** The cartons a shipment carried, by SKU in code order, each SKU's LPNs in order. */
    static List<SkuCartons> bySku(List<ShippedCarton> cartons) {
        TreeMap<String, List<ShippedCarton>> grouped = new TreeMap<>();
        cartons.forEach(carton -> grouped.computeIfAbsent(carton.sku(), sku -> new ArrayList<>()).add(carton));
        List<SkuCartons> goods = new ArrayList<>();
        grouped.forEach((sku, ofSku) -> goods.add(new SkuCartons(sku, ofSku.size(),
                ofSku.stream().mapToInt(ShippedCarton::quantity).sum(),
                ofSku.stream().map(ShippedCarton::lpn).sorted().toList())));
        return goods;
    }

    byte[] render(Facts f) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String blank = t.getString("delivery-note.pdf.blank");
        List<Line> lines = new ArrayList<>();
        lines.add(Line.title(t.getString("delivery-note.pdf.title")));
        lines.add(Line.bold(t.getString("delivery-note.pdf.shipment").formatted(f.shipmentCode(),
                DAY.format(f.printedOn()))));
        lines.add(Line.text(t.getString("delivery-note.pdf.order").formatted(f.orderNumber())));
        if (f.contractNumber() != null && f.batchSeq() != null) {
            lines.add(Line.text(t.getString("delivery-note.pdf.contractBatch").formatted(f.contractNumber(),
                    f.batchSeq())));
        }
        if (f.redelivery()) {
            lines.add(Line.text(t.getString("delivery-note.pdf.redelivery")));
        }

        lines.add(Line.heading(t.getString("delivery-note.pdf.sender")));
        lines.add(Line.bold(or(f.warehouseName(), blank)));
        if (f.warehouseAddress() != null && !f.warehouseAddress().isBlank()) {
            lines.add(Line.text(f.warehouseAddress()));
        }
        lines.add(Line.heading(t.getString("delivery-note.pdf.receiver")));
        lines.add(Line.bold(or(f.customerName(), blank)));
        lines.add(Line.text(t.getString("delivery-note.pdf.deliveryAddress")
                .formatted(or(f.deliveryAddress(), blank))));
        lines.add(Line.text(t.getString("delivery-note.pdf.recipient").formatted(or(f.recipient(), blank))));

        lines.add(Line.heading(t.getString("delivery-note.pdf.carriage")));
        if (f.vehicles().isEmpty()) {
            lines.add(Line.text(t.getString("delivery-note.pdf.noVehicle").formatted(blank, blank, blank, blank)));
        }
        for (Vehicle v : f.vehicles()) {
            lines.add(Line.text(t.getString("delivery-note.pdf.vehicle").formatted(v.loadCode(),
                    or(v.carrierName(), blank), or(v.plate(), blank), or(v.driverName(), blank),
                    or(v.driverPhone(), blank), or(v.waybillNo(), blank))));
        }
        lines.add(Line.text(t.getString("delivery-note.pdf.handedOverAt").formatted(f.handedOverAt() == null ? blank
                : MOMENT.format(f.handedOverAt()))));

        lines.add(Line.heading(t.getString("delivery-note.pdf.goods")));
        int cartons = 0;
        int units = 0;
        for (SkuCartons sku : f.goods()) {
            lines.add(Line.bold(t.getString("delivery-note.pdf.sku").formatted(sku.sku(), sku.cartons(), sku.units())));
            lines.add(Line.text(t.getString("delivery-note.pdf.lpns").formatted(String.join(", ", sku.lpns()))));
            cartons += sku.cartons();
            units += sku.units();
        }
        lines.add(Line.bold(t.getString("delivery-note.pdf.total").formatted(cartons, units)));
        lines.add(Line.text(t.getString("delivery-note.pdf.condition").formatted(blank + blank)));

        lines.add(Line.heading(""));
        lines.add(Line.bold(t.getString("delivery-note.pdf.signatures")));
        lines.add(Line.text(t.getString("delivery-note.pdf.signHint")));
        lines.add(Line.heading(""));
        lines.add(Line.heading(""));
        lines.add(Line.heading(""));
        lines.add(Line.text(t.getString("delivery-note.pdf.pod")));
        return PrintedDocument.draw(fonts, lines, t.getString("delivery-note.pdf.title") + " " + f.shipmentCode(),
                f.shipmentCode());
    }

    private static String or(String value, String blank) {
        return value == null || value.isBlank() ? blank : value;
    }
}
