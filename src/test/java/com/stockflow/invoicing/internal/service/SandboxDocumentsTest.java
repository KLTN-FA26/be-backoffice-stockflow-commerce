package com.stockflow.invoicing.internal.service;

import com.stockflow.common.label.LabelFonts;
import com.stockflow.common.label.PrintedDocument;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.InvoiceDocument;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.invoicing.internal.domain.TaxRateTotal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sandbox's two documents (plan §1.3): the XML in the tax authority's layout as far as it is known, and the PDF that
 * says it is a test invoice - both carrying every mandatory content of NĐ 254 Điều 10 khoản 1.
 */
class SandboxDocumentsTest {

    private static final TaxAuthorityInvoiceXml.Issued ISSUED = new TaxAuthorityInvoiceXml.Issued(42, "SBX0123456789",
            "SBXLOOKUP01", Instant.parse("2026-11-12T03:00:05Z"));

    private static InvoiceDocument document(InvoiceParty buyer, List<EInvoiceLine> lines) {
        EInvoice invoice = EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-20261112-0001",
                EInvoiceFixtures.delivery(), EInvoiceFixtures.SELLER, buyer, lines, false);
        invoice.submit(EInvoiceFixtures.SERIES, null, EInvoiceFixtures.NOW);
        return EInvoiceIssuance.document(invoice.snapshot());
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(Element parent, String tag) {
        NodeList found = parent.getElementsByTagName(tag);
        assertThat(found.getLength()).as("<%s>", tag).isGreaterThan(0);
        return found.item(0).getTextContent();
    }

    @Test
    @DisplayName("the XML is well-formed and carries the general part, both parties, every line, the totals and the code")
    void xmlCarriesTheInvoice() throws Exception {
        InvoiceDocument invoice = document(EInvoiceFixtures.BUYER, EInvoiceFixtures.lines());

        String xml = TaxAuthorityInvoiceXml.write(invoice, ISSUED, SandboxProvider.NOTICE);
        Document parsed = parse(xml);
        Element root = parsed.getDocumentElement();

        assertThat(xml).startsWith("<?xml").contains("NO LEGAL VALUE");
        assertThat(root.getTagName()).isEqualTo("HDon");
        Element general = (Element) root.getElementsByTagName("TTChung").item(0);
        assertThat(text(general, "PBan")).as("QĐ 1233/QĐ-CT's message version").isEqualTo("2.1.1");
        assertThat(text(general, "THDon")).isEqualTo("HÓA ĐƠN GIÁ TRỊ GIA TĂNG");
        assertThat(text(general, "KHMSHDon")).isEqualTo("1");
        assertThat(text(general, "KHHDon")).isEqualTo("C26TSB");
        assertThat(text(general, "SHDon")).isEqualTo("42");
        assertThat(text(general, "NLap")).isEqualTo("2026-11-12");
        assertThat(text(general, "DVTTe")).isEqualTo("VND");

        Element seller = (Element) root.getElementsByTagName("NBan").item(0);
        assertThat(text(seller, "Ten")).isEqualTo(EInvoiceFixtures.SELLER.name());
        assertThat(text(seller, "MST")).isEqualTo("0312345678");
        Element buyer = (Element) root.getElementsByTagName("NMua").item(0);
        assertThat(text(buyer, "Ten")).isEqualTo("Cong ty Tra Sua");
        assertThat(text(buyer, "MST")).isEqualTo("0398765432");
        assertThat(text(buyer, "DChi")).isEqualTo(EInvoiceFixtures.BUYER.address());
        assertThat(text(buyer, "DCTDTu")).isEqualTo("ke.toan@trasua.example.com");

        NodeList items = root.getElementsByTagName("HHDVu");
        assertThat(items.getLength()).isEqualTo(2);
        Element goods = (Element) items.item(0);
        assertThat(text(goods, "STT")).isEqualTo("1");
        assertThat(text(goods, "DVTinh")).isEqualTo("Thung");
        assertThat(text(goods, "SLuong")).isEqualTo("20");
        assertThat(text(goods, "DGia")).isEqualTo("100000");
        assertThat(text(goods, "ThTien")).isEqualTo("2000000");
        assertThat(text(goods, "TSuat")).isEqualTo("10%");
        assertThat(text(goods, "TThue")).isEqualTo("200000");

        Element payment = (Element) root.getElementsByTagName("TToan").item(0);
        assertThat(payment.getElementsByTagName("LTSuat").getLength()).as("tax by rate: 8% and 10%").isEqualTo(2);
        assertThat(text(payment, "TgTCThue")).isEqualTo("2500000");
        assertThat(text(payment, "TgTThue")).isEqualTo("240000");
        assertThat(text(payment, "TgTTTBSo")).isEqualTo("2740000");
        assertThat(text(payment, "TgTTTBChu")).isEqualTo("Hai triệu bảy trăm bốn mươi nghìn đồng");
        assertThat(text(root, "MCCQT")).isEqualTo("SBX0123456789");
        assertThat(root.getElementsByTagName("DSCKS").getLength()).isEqualTo(1);
    }

    @Test
    @DisplayName("a name with markup in it stays data in the XML")
    void xmlEscapesWhatPeopleTyped() throws Exception {
        InvoiceParty buyer = new InvoiceParty("Tra & Sua <script>alert(1)</script> \"A\"", "0398765432",
                "12 Nguyen Hue ]]> --", "a@b.vn");
        List<EInvoiceLine> lines = List.of(EInvoiceLine.goods(UUID.randomUUID(), 1, UUID.randomUUID(), "SKU-1",
                "Ly <b>12oz</b> & nap", "Thung", 1, 50, new BigDecimal("1000"), new BigDecimal("10")));

        String xml = TaxAuthorityInvoiceXml.write(document(buyer, lines), ISSUED, "a -- b");
        Element root = parse(xml).getDocumentElement();

        assertThat(xml).doesNotContain("<script>").doesNotContain("<b>");
        assertThat(root.getElementsByTagName("script").getLength()).isZero();
        Element parsedBuyer = (Element) root.getElementsByTagName("NMua").item(0);
        assertThat(text(parsedBuyer, "Ten")).isEqualTo(buyer.name());
        assertThat(text(parsedBuyer, "DChi")).isEqualTo(buyer.address());
        assertThat(text((Element) root.getElementsByTagName("HHDVu").item(0), "THHDVu"))
                .isEqualTo("Ly <b>12oz</b> & nap");
    }

    @Test
    @DisplayName("the PDF says it is a test invoice first and last, and states what an invoice must")
    void pdfSaysItIsATestInvoice() {
        InvoiceDocument invoice = document(EInvoiceFixtures.BUYER, EInvoiceFixtures.lines());

        List<String> lines = SandboxInvoicePdf.lines(invoice, ISSUED).stream().map(PrintedDocument.Line::text).toList();

        String banner = "HOÁ ĐƠN THỬ NGHIỆM – KHÔNG CÓ GIÁ TRỊ PHÁP LÝ";
        assertThat(lines.getFirst()).isEqualTo(banner);
        assertThat(lines.getLast()).isEqualTo(banner);
        String all = String.join("\n", lines);
        assertThat(all).contains("HÓA ĐƠN GIÁ TRỊ GIA TĂNG")
                .contains("Mẫu số: 1    Ký hiệu: C26TSB    Số: 00000042")
                .contains("Ngày 12 tháng 11 năm 2026")
                .contains("Mã của cơ quan thuế: SBX0123456789")
                .contains("Đơn vị bán hàng: " + EInvoiceFixtures.SELLER.name())
                .contains("Đơn vị mua hàng: Cong ty Tra Sua")
                .contains("Mã số thuế: 0398765432")
                .contains("Hình thức thanh toán: Chuyển khoản")
                .contains("ĐVT: Thung; số lượng: 20; đơn giá: 100.000 đ; thành tiền: 2.000.000 đ; thuế suất GTGT 10%: "
                        + "200.000 đ")
                .contains("(50 cái / thùng)")
                .contains("Thuế suất 8%: thành tiền 500.000 đ; tiền thuế GTGT 40.000 đ")
                .contains("Tổng cộng tiền thanh toán: 2.740.000 đ")
                .contains("Số tiền viết bằng chữ: Hai triệu bảy trăm bốn mươi nghìn đồng")
                .contains("Mã tra cứu: SBXLOOKUP01");

        byte[] pdf = new SandboxInvoicePdf(new LabelFonts()).render(invoice, ISSUED);
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("NĐ 254 Phụ lục 4.a: a buyer's name and address too long for the layout are abbreviated on the PDF only")
    void pdfAbbreviatesOnlyWhatDoesNotFit() {
        String name = "Công ty Trách nhiệm hữu hạn Sản xuất Thương mại Dịch vụ Bao bì Giấy và Ly Nhựa Cao Cấp Xuất Khẩu "
                + "Việt Nam - Chi nhánh Thành phố Hồ Chí Minh";
        String address = "Lô B12 đường số 7, Khu công nghiệp Tân Bình mở rộng, Phường Tây Thạnh, Quận Tân Phú, Thành phố "
                + "Hồ Chí Minh, Việt Nam, toà nhà xưởng số 3, tầng 2, khu văn phòng điều hành phía Đông, cổng số 4 đường "
                + "nội bộ D2, đối diện bãi xe container";
        assertThat(name.length()).isGreaterThan(SandboxInvoicePdf.NAME_ROOM);
        assertThat(address.length()).isGreaterThan(SandboxInvoicePdf.ADDRESS_ROOM);
        InvoiceDocument invoice = document(new InvoiceParty(name, "0398765432", address, "a@b.vn"),
                EInvoiceFixtures.lines());

        String all = String.join("\n", SandboxInvoicePdf.lines(invoice, ISSUED).stream()
                .map(PrintedDocument.Line::text).toList());

        assertThat(all).contains("Đơn vị mua hàng: Công ty TNHH Sản xuất Thương mại");
        assertThat(all).contains("KCN Tân Bình mở rộng").contains("Lô B12 đường số 7").contains("Tây Thạnh")
                .contains("Hồ Chí Minh");
        assertThat(invoice.buyer().name()).as("the invoice itself keeps the full text").isEqualTo(name);
        // The seller fits and is written as registered.
        assertThat(all).contains("Địa chỉ: " + EInvoiceFixtures.SELLER.address());
        assertThat(TaxAuthorityInvoiceXml.write(invoice, ISSUED, null)).as("the XML carries the full name")
                .contains("Trách nhiệm hữu hạn");
        assertThat(TaxRateTotal.of(invoice.lines())).hasSize(2);
        assertThat(invoice.transactionId()).endsWith("-1");
        assertThat(EInvoiceStatus.SUBMITTING.wasIssued()).isFalse();
    }
}
