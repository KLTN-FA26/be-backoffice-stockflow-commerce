package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Field;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Signer;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import com.stockflow.fulfillment.api.ShippedCarton;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;

/**
 * Draws the delivery note of a shipment as a PDF (kltn-docs 09 §4.2; glossary "POD"): the record ("biên bản giao nhận
 * hàng hóa") the company, the driver and the customer sign when the goods change hands. No law fixes its form; it is
 * the proof of delivery, the moment risk passes, and the day Luật Thương mại 2005 Điều 318 counts the complaint period
 * from - so it is set as a record two companies sign: our company and the record's number beside Quốc hiệu and Tiêu
 * ngữ, the contract and order it is made under, the two parties, the goods in a ruled table, and three signature
 * blocks with room to sign.
 *
 * <p>It names the shipment - printed as a barcode and a QR code too, the code a carrier reports its outcome with
 * (open-questions E20) - and the cartons by SKU with their LPNs (08 BR-02: the LPN carton is the shipping carton). No
 * price: a handover record states what changed hands, and the driver who carries it is not shown what it cost. The
 * party handing over is the selling company, as the contract names it, with the warehouse the goods leave from as a
 * line of its block - the customer signs with a company, not with a building.</p>
 *
 * <p>Printed before the goods leave as well as after: what is not known yet - a vehicle not booked, a driver the
 * carrier has not named, the day and moment of the handover - is a dotted line to fill in by hand, never left out, so
 * the paper is the same form whenever it is printed. The state of the goods on receipt is two boxes to tick. Each SKU
 * is printed as its code - what the carton's label shows - and its variant's name from the catalogue; a SKU the
 * catalogue does not name prints by its code alone.</p>
 *
 * <p>The words are the Vietnamese bundle's, the layout the kit's ({@link BusinessDocument}), the company its letterhead
 * ({@link SellerLetterhead}) - the same on every paper of the sale. Everything else it shows is given; nothing is
 * looked up here.</p>
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

    /**
     * The cartons of one SKU, whole (4C-2): how many, the pieces in them, and their LPNs.
     *
     * @param name the variant's name in the catalogue; null when it has none
     */
    record SkuCartons(String sku, String name, int cartons, int units, List<String> lpns) {
    }

    private final DocumentFonts fonts;
    private final SellerLetterhead letterhead;

    DeliveryNoteDocument(DocumentFonts fonts, SellerLetterhead letterhead) {
        this.fonts = fonts;
        this.letterhead = letterhead;
    }

    /**
     * The cartons a shipment carried, by SKU in code order, each SKU's LPNs in order, each SKU with its name from
     * {@code names} (by SKU; a SKU absent from it has none).
     */
    static List<SkuCartons> bySku(List<ShippedCarton> cartons, Map<String, String> names) {
        TreeMap<String, List<ShippedCarton>> grouped = new TreeMap<>();
        cartons.forEach(carton -> grouped.computeIfAbsent(carton.sku(), sku -> new ArrayList<>()).add(carton));
        List<SkuCartons> goods = new ArrayList<>();
        grouped.forEach((sku, ofSku) -> goods.add(new SkuCartons(sku, names.get(sku), ofSku.size(),
                ofSku.stream().mapToInt(ShippedCarton::quantity).sum(),
                ofSku.stream().map(ShippedCarton::lpn).sorted().toList())));
        return goods;
    }

    byte[] render(Facts f) {
        ResourceBundle t = DocumentParts.bundle();
        Letterhead head = letterhead.get();
        String blank = t.getString("document.blank");
        List<Block> blocks = new ArrayList<>();
        blocks.add(DocumentParts.recordHead(t, head, f.shipmentCode(), f.printedOn()));
        boolean scheduled = f.contractNumber() != null && f.batchSeq() != null;
        blocks.add(new Title(t.getString("delivery-note.pdf.title"), null,
                scheduled ? t.getString("delivery-note.pdf.batch").formatted(f.batchSeq()) : null));
        blocks.add(BusinessDocument.codes(f.shipmentCode()));
        if (scheduled) {
            blocks.add(DocumentParts.basis(t.getString("delivery-note.pdf.basis.contract")
                    .formatted(f.contractNumber())));
        }
        blocks.add(DocumentParts.basis(t.getString("delivery-note.pdf.basis.order").formatted(f.orderNumber())));
        if (f.redelivery()) {
            blocks.add(DocumentParts.basis(t.getString("delivery-note.pdf.redelivery")));
        }
        // The record is of the day the goods change hands, which a copy printed for the driver does not know yet.
        blocks.add(f.handedOverAt() == null
                ? DocumentParts.lead(t.getString("delivery-note.pdf.todayBlank")).after(6)
                : DocumentParts.today(t, head, LocalDate.ofInstant(f.handedOverAt(), DocumentFormats.VIETNAM)));

        DocumentParts.party(blocks, t, t.getString("delivery-note.pdf.sender"),
                new DocumentParts.Party(head.name(), head.taxCode(), head.address(), head.phone(), null, null, null,
                        null),
                List.of(Field.of(t.getString("delivery-note.pdf.warehouse"), warehouse(f, blank))));
        DocumentParts.party(blocks, t, t.getString("delivery-note.pdf.receiver"),
                new DocumentParts.Party(DocumentParts.orBlank(t, f.customerName()), null, null, null, null),
                List.of(Field.of(t.getString("delivery-note.pdf.deliveryAddress"),
                                DocumentParts.orBlank(t, f.deliveryAddress())),
                        Field.of(t.getString("delivery-note.pdf.recipient"),
                                DocumentParts.orBlank(t, f.recipient()))));

        blocks.add(DocumentParts.lead(t.getString("delivery-note.pdf.agreed")));
        blocks.add(goods(t, f.goods()));

        // The handover - the vehicle, the moment, the state of the goods, the confirmation - follows the goods where
        // they end. It used to open a page of its own, which left most of a page empty under a short goods table and
        // put the signatures on a third page after a long one (demo run 24/11). The kit keeps the signatures with the
        // paragraph before them, so they never open a page alone.
        blocks.add(Paragraph.bold(t.getString("delivery-note.pdf.carriage")).align(Align.LEFT).after(1));
        if (f.vehicles().isEmpty()) {
            blocks.add(point(t.getString("delivery-note.pdf.noVehicle").formatted(blank, blank, blank, blank)));
        }
        for (Vehicle v : f.vehicles()) {
            blocks.add(point(t.getString("delivery-note.pdf.vehicle").formatted(v.loadCode(),
                    DocumentParts.orBlank(t, v.carrierName()), DocumentParts.orBlank(t, v.plate()),
                    DocumentParts.orBlank(t, v.driverName()), DocumentParts.orBlank(t, v.driverPhone()),
                    DocumentParts.orBlank(t, v.waybillNo()))));
        }
        blocks.add(point(t.getString("delivery-note.pdf.handedOverAt").formatted(f.handedOverAt() == null ? blank
                : DocumentFormats.moment(f.handedOverAt()))));

        blocks.add(Paragraph.bold(t.getString("delivery-note.pdf.condition")).align(Align.LEFT).after(0));
        blocks.add(DocumentParts.checkbox(t.getString("delivery-note.pdf.condition.good"), 13));
        blocks.add(DocumentParts.checkbox(t.getString("delivery-note.pdf.condition.differs")
                .formatted(blank + blank), 13));
        blocks.add(DocumentParts.lead(t.getString("delivery-note.pdf.confirm")).after(2));
        blocks.add(DocumentParts.lead(t.getString("delivery-note.pdf.copies")));

        String caption = t.getString("document.sign.named");
        blocks.add(new Signatures(null, List.of(
                new Signer(t.getString("delivery-note.pdf.sign.sender"), caption, null),
                new Signer(t.getString("delivery-note.pdf.sign.driver"), caption, null),
                new Signer(t.getString("delivery-note.pdf.sign.receiver"), caption, null))));
        blocks.add(Paragraph.italic(t.getString("delivery-note.pdf.pod")).size(-2.5f));
        return BusinessDocument.draw(fonts, DocumentParts.official(t, f.shipmentCode(), null),
                t.getString("delivery-note.pdf.title") + " " + f.shipmentCode(), blocks);
    }

    /** "Kho Hồ Chí Minh - 12 Đường Số 7, ...", or a line to write the warehouse on. */
    private static String warehouse(Facts f, String blank) {
        if (!DocumentParts.filled(f.warehouseName())) {
            return blank;
        }
        return DocumentParts.filled(f.warehouseAddress()) ? f.warehouseName() + " - " + f.warehouseAddress()
                : f.warehouseName();
    }

    private static Paragraph point(String text) {
        return Paragraph.numbered("-", Run.of(text)).indent(14).align(Align.LEFT).after(1);
    }

    /**
     * The goods by SKU: cartons and pieces in their own columns, the LPNs under the name, and a blank column for what
     * the receiver notes against a line. The last row adds the cartons and the pieces up - of one delivery, counted in
     * one unit, they do add up, and the count of cartons is what the receiver checks first.
     */
    private static Table goods(ResourceBundle t, List<SkuCartons> goods) {
        List<List<Cell>> rows = new ArrayList<>();
        String piece = t.getString("document.unit.piece");
        int cartons = 0;
        int units = 0;
        int no = 0;
        for (SkuCartons sku : goods) {
            rows.add(List.of(Cell.of(String.valueOf(++no)), Cell.of(sku.sku()),
                    Cell.bold(DocumentParts.filled(sku.name()) ? sku.name() : sku.sku()).details(List.of(
                            t.getString("delivery-note.pdf.lpns").formatted(String.join(", ", sku.lpns())))),
                    Cell.of(piece), Cell.of(DocumentFormats.number(sku.cartons())),
                    Cell.of(DocumentFormats.number(sku.units())), Cell.of("")));
            cartons += sku.cartons();
            units += sku.units();
        }
        rows.add(List.of(Cell.of(""), Cell.of(""), Cell.bold(t.getString("delivery-note.pdf.total")), Cell.of(""),
                Cell.bold(DocumentFormats.number(cartons)), Cell.bold(DocumentFormats.number(units)), Cell.of("")));
        return new Table(List.of(new Column(t.getString("document.goods.no"), 30, Align.CENTRE),
                new Column(t.getString("document.goods.code"), 96, Align.LEFT),
                new Column(t.getString("document.goods.name"), 172, Align.LEFT),
                new Column(t.getString("document.goods.unit"), 36, Align.CENTRE),
                new Column(t.getString("delivery-note.pdf.col.cartons"), 48, Align.RIGHT),
                new Column(t.getString("document.goods.quantity"), 56, Align.RIGHT),
                new Column(t.getString("delivery-note.pdf.col.note"), 62, Align.LEFT)), rows);
    }
}
