package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Field;
import com.stockflow.common.document.BusinessDocument.Figure;
import com.stockflow.common.document.BusinessDocument.Layout;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Signer;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.BusinessDocument.Together;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import com.stockflow.common.document.Style;
import com.stockflow.fulfillment.api.ShippedCarton;
import org.springframework.stereotype.Component;

import java.awt.Color;
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
 * <p>It is set to fit one page: a note of a handful of lines that runs onto a second page gets signed on a page that
 * shows none of the goods, and half of it is left on the dashboard. So the codes sit in the head, not in a row of
 * their own; the text is a point smaller than a contract's - the margins of Nghị định 30 stay; and the handover is two
 * short blocks, not a list. A long list of cartons still runs over, the signatures under its total.</p>
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

    /** A point under an official paper's 13 pt and its tables' 11 pt: what lets eight lines of goods share a page. */
    private static final float BODY = 11;
    private static final float TABLE = 10;
    /**
     * The room to sign in, with the line the kit leaves under it for a name: three people sign by hand and write their
     * names, nobody sets a seal - a contract's 27 mm is a seal's.
     */
    private static final double SIGNING_MM = 14;

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
        blocks.add(BusinessDocument.headCodes(f.shipmentCode()));
        blocks.add(new Title(t.getString("delivery-note.pdf.title"), null,
                scheduled ? t.getString("delivery-note.pdf.batch").formatted(f.batchSeq()) : null));
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
                ? DocumentParts.lead(t.getString("delivery-note.pdf.todayBlank")).after(3)
                : DocumentParts.today(t, head, LocalDate.ofInstant(f.handedOverAt(), DocumentFormats.VIETNAM))
                        .after(3));

        // Our tax code and phone share a line: two short values, and a line each is a line the goods do not get.
        List<Field> ours = new ArrayList<>();
        if (DocumentParts.filled(head.taxCode()) || DocumentParts.filled(head.phone())) {
            boolean both = DocumentParts.filled(head.taxCode()) && DocumentParts.filled(head.phone());
            ours.add(Field.of(t.getString(DocumentParts.filled(head.taxCode()) ? "document.party.taxCode"
                            : "document.party.phone"),
                    both ? head.taxCode() + " - " + t.getString("document.party.phone") + ": " + head.phone()
                            : DocumentParts.filled(head.taxCode()) ? head.taxCode() : head.phone()));
        }
        ours.add(Field.of(t.getString("delivery-note.pdf.warehouse"), warehouse(f, blank)));
        DocumentParts.party(blocks, t, t.getString("delivery-note.pdf.sender"),
                new DocumentParts.Party(head.name(), null, head.address(), null, null), ours);
        DocumentParts.party(blocks, t, t.getString("delivery-note.pdf.receiver"),
                new DocumentParts.Party(DocumentParts.orBlank(t, f.customerName()), null, null, null, null),
                List.of(Field.of(t.getString("delivery-note.pdf.deliveryAddress"),
                                DocumentParts.orBlank(t, f.deliveryAddress())),
                        Field.of(t.getString("delivery-note.pdf.recipient"),
                                DocumentParts.orBlank(t, f.recipient()))));

        blocks.add(DocumentParts.lead(t.getString("delivery-note.pdf.agreed")).after(1));
        blocks.add(goods(t, f.goods()));

        // The handover - the vehicle, the moment, the state of the goods, the confirmation, the signatures - follows
        // the goods where they end, and stays whole: whoever signs has the vehicle and the boxes they ticked on the
        // page they sign. It used to open a page of its own always, which left most of a page empty under a short
        // goods table and put the signatures on a third page after a long one (demo run 24/11); now it moves on only
        // when it does not fit under the goods. The moment shares the heading's line - one fact, one line saved - and
        // each vehicle is a point under it.
        blocks.add(new Together(handoverHeight(f.vehicles().size())));
        blocks.add(Paragraph.of(Run.bold(t.getString("delivery-note.pdf.carriage") + " "),
                Run.of(t.getString("delivery-note.pdf.handedOverAt").formatted(f.handedOverAt() == null ? blank
                        : DocumentFormats.moment(f.handedOverAt())))).align(Align.LEFT).after(0));
        if (f.vehicles().isEmpty()) {
            blocks.add(point(t.getString("delivery-note.pdf.noVehicle").formatted(blank, blank, blank, blank)));
        }
        for (Vehicle v : f.vehicles()) {
            blocks.add(point(t.getString("delivery-note.pdf.vehicle").formatted(v.loadCode(),
                    DocumentParts.orBlank(t, v.carrierName()), DocumentParts.orBlank(t, v.plate()),
                    DocumentParts.orBlank(t, v.driverName()), DocumentParts.orBlank(t, v.driverPhone()),
                    DocumentParts.orBlank(t, v.waybillNo()))));
        }
        blocks.add(condition(t.getString("delivery-note.pdf.condition"),
                t.getString("delivery-note.pdf.condition.good"),
                t.getString("delivery-note.pdf.condition.differs").formatted("").strip()));
        // One paragraph: what Bên B confirms and how many copies were made are the record's closing words together.
        blocks.add(DocumentParts.lead(t.getString("delivery-note.pdf.confirm") + " "
                + t.getString("delivery-note.pdf.copies")).after(0));

        String caption = t.getString("document.sign.named");
        blocks.add(new Signatures(null, List.of(
                new Signer(t.getString("delivery-note.pdf.sign.sender"), caption, null),
                new Signer(t.getString("delivery-note.pdf.sign.driver"), caption, null),
                new Signer(t.getString("delivery-note.pdf.sign.receiver"), caption, null)),
                BusinessDocument.mm(SIGNING_MM)));
        blocks.add(Paragraph.italic(t.getString("delivery-note.pdf.pod")).size(-2.5f));
        Layout official = DocumentParts.official(t, f.shipmentCode(), null);
        return BusinessDocument.draw(fonts, new Layout(official.margins(), BODY, TABLE, official.accent(),
                        official.footerLeft(), official.footerCentre(), official.pageLabel(), official.watermark()),
                t.getString("delivery-note.pdf.title") + " " + f.shipmentCode(), blocks);
    }

    /**
     * The room the handover takes, heading to the note under the signatures, in points: its lines at the body's
     * leading - a vehicle's point runs to two - and the room to sign. Counted, not measured, and a few points short
     * rather than over: over, a note that fits one page would be sent to a second.
     */
    private static float handoverHeight(int vehicles) {
        float line = BODY * 1.25f;
        int lines = 1 + 2 * Math.max(1, vehicles) + 2 + 3 + 2 + 1 + 1;
        return lines * line + BusinessDocument.mm(SIGNING_MM) + 10;
    }

    /**
     * The state of the goods on receipt as a form reads: the question at the left, its two answers as boxes to tick
     * under one another beside it, and the line to write a difference on running to the margin - the longer it is, the
     * more of what is wrong gets written down. Drawn, not typed: the fonts have no ballot box.
     */
    private static Figure condition(String question, String good, String differs) {
        float lineHeight = BODY * 1.25f;
        float box = BODY * 0.78f;
        return new Figure(2 * lineHeight + 4, canvas -> {
            float baseline = canvas.top() - lineHeight;
            canvas.text(Style.BOLD, BODY, canvas.left(), baseline, question, Color.BLACK);
            float x = canvas.left() + canvas.textWidth(Style.BOLD, BODY, question) + 10;
            float room = canvas.left() + canvas.width() - x - box - 5;
            StringBuilder written = new StringBuilder(differs).append(' ');
            while (canvas.textWidth(Style.REGULAR, BODY, written + ".") <= room) {
                written.append('.');
            }
            canvas.stream().setLineWidth(0.7f);
            for (String answer : List.of(good, written.toString())) {
                canvas.stream().addRect(x, baseline - 1, box, box);
                canvas.stream().stroke();
                canvas.text(Style.REGULAR, BODY, x + box + 5, baseline, canvas.cut(Style.REGULAR, BODY, answer, room),
                        Color.BLACK);
                baseline -= lineHeight;
            }
        });
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
        return Paragraph.numbered("-", Run.of(text)).indent(14).align(Align.LEFT).after(0);
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
        // The name gets what the other columns can spare: a name on one line keeps a row at two (name, LPNs).
        return new Table(List.of(new Column(t.getString("document.goods.no"), 26, Align.CENTRE),
                new Column(t.getString("document.goods.code"), 88, Align.LEFT),
                new Column(t.getString("document.goods.name"), 186, Align.LEFT),
                new Column(t.getString("document.goods.unit"), 34, Align.CENTRE),
                new Column(t.getString("delivery-note.pdf.col.cartons"), 56, Align.RIGHT),
                new Column(t.getString("document.goods.quantity"), 56, Align.RIGHT),
                new Column(t.getString("delivery-note.pdf.col.note"), 52, Align.LEFT)), rows);
    }
}
