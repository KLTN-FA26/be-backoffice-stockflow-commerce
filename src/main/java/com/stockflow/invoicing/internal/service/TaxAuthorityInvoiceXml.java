package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.InvoiceDocument;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.invoicing.internal.domain.TaxRateTotal;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Writes an invoice as the XML of the tax authority's e-invoice data format (TT 91/2026/TT-BTC Điều 3: business data,
 * the tax authority's code, the signatures).
 *
 * <h2>The element names here are provisional - read before relying on them</h2>
 *
 * <p>The format in force is the annex of Quyết định 1233/QĐ-CT (27/8/2026), message version 2.1.1, signed with XAdES
 * (DSig still accepted until 09/4/2027). Package E0 could read the decision but <b>not its annex, and found no XSD</b>
 * (plans/E0-legal.md §2(c)). So the structure below follows the layout of the format's earlier versions as it is
 * publicly known - {@code HDon / DLHDon / TTChung, NDHDon (NBan, NMua, DSHHDVu, TToan) / MCCQT / DSCKS} - and has not
 * been checked against 2.1.1. It is good for what the sandbox needs: a well-formed document carrying every mandatory
 * content of NĐ 254 Điều 10 khoản 1. It is not evidence that a real provider's XML looks like this.</p>
 *
 * <p>That is why this class stands alone: it takes the invoice as plain values and returns text, knows nothing of the
 * sandbox, storage or the database, and keeps every element name in {@link Tags}. When the annex is in hand the mapping
 * is corrected here and nowhere else. A real provider returns its own XML and never passes through this class.</p>
 *
 * <p>The signature block is a placeholder: the sandbox signs nothing. A document from here has no legal value, which it
 * says in its first comment.</p>
 */
final class TaxAuthorityInvoiceXml {

    /** The message version this layout is meant to become: QĐ 1233/QĐ-CT. Pending verification against its annex. */
    static final String MESSAGE_VERSION = "2.1.1";

    /** Element names, in one place. PROVISIONAL: after the earlier versions of the format, not verified against 2.1.1. */
    static final class Tags {
        static final String INVOICE = "HDon";
        static final String DATA = "DLHDon";
        static final String GENERAL = "TTChung";
        static final String VERSION = "PBan";
        static final String INVOICE_NAME = "THDon";
        static final String TEMPLATE_CODE = "KHMSHDon";
        static final String SYMBOL = "KHHDon";
        static final String NUMBER = "SHDon";
        static final String ISSUE_DATE = "NLap";
        static final String CURRENCY = "DVTTe";
        static final String EXCHANGE_RATE = "TGia";
        static final String PAYMENT_METHOD = "HTTToan";
        static final String CONTENT = "NDHDon";
        static final String SELLER = "NBan";
        static final String BUYER = "NMua";
        static final String NAME = "Ten";
        static final String TAX_CODE = "MST";
        static final String ADDRESS = "DChi";
        static final String EMAIL = "DCTDTu";
        static final String ITEMS = "DSHHDVu";
        static final String ITEM = "HHDVu";
        static final String ITEM_NATURE = "TChat";
        static final String LINE_NO = "STT";
        static final String ITEM_CODE = "MHHDVu";
        static final String ITEM_NAME = "THHDVu";
        static final String UNIT = "DVTinh";
        static final String QUANTITY = "SLuong";
        static final String UNIT_PRICE = "DGia";
        static final String AMOUNT = "ThTien";
        static final String TAX_RATE = "TSuat";
        static final String PAYMENT = "TToan";
        static final String TOTALS_BY_RATE = "THTTLTSuat";
        static final String RATE_TOTAL = "LTSuat";
        static final String TAX_AMOUNT = "TThue";
        static final String TOTAL_BEFORE_TAX = "TgTCThue";
        static final String TOTAL_TAX = "TgTThue";
        static final String TOTAL_PAYABLE = "TgTTTBSo";
        static final String TOTAL_IN_WORDS = "TgTTTBChu";
        static final String OTHER = "TTKhac";
        static final String OTHER_ITEM = "TTin";
        static final String OTHER_FIELD = "TTruong";
        static final String OTHER_VALUE = "DLieu";
        static final String TAX_AUTHORITY_CODE = "MCCQT";
        static final String SIGNATURES = "DSCKS";

        private Tags() {
        }
    }

    /** What the provider adds to an invoice when it issues it. */
    record Issued(int invoiceNumber, String taxAuthorityCode, String lookupCode, Instant signedAt) {
    }

    private static final String INVOICE_NAME = "H\u00d3A \u0110\u01a0N GI\u00c1 TR\u1eca GIA T\u0102NG";

    private TaxAuthorityInvoiceXml() {
    }

    /**
     * @param notice a comment put first in the document, or null; the sandbox says there that it has no legal value
     */
    static String write(InvoiceDocument invoice, Issued issued, String notice) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document xml = factory.newDocumentBuilder().newDocument();
            xml.setXmlStandalone(true);
            if (notice != null) {
                xml.appendChild(xml.createComment(" " + notice.replace("--", "- -") + " "));
            }
            Element root = xml.createElement(Tags.INVOICE);
            xml.appendChild(root);
            Element data = child(xml, root, Tags.DATA);
            data.setAttribute("Id", "data");

            Element general = child(xml, data, Tags.GENERAL);
            text(xml, general, Tags.VERSION, MESSAGE_VERSION);
            text(xml, general, Tags.INVOICE_NAME, INVOICE_NAME);
            text(xml, general, Tags.TEMPLATE_CODE, invoice.templateCode());
            text(xml, general, Tags.SYMBOL, invoice.symbol());
            text(xml, general, Tags.NUMBER, Integer.toString(issued.invoiceNumber()));
            text(xml, general, Tags.ISSUE_DATE, DateTimeFormatter.ISO_LOCAL_DATE.format(invoice.issueDate()));
            text(xml, general, Tags.CURRENCY, invoice.currency());
            text(xml, general, Tags.EXCHANGE_RATE, "1");
            text(xml, general, Tags.PAYMENT_METHOD, invoice.paymentMethod());

            Element content = child(xml, data, Tags.CONTENT);
            party(xml, child(xml, content, Tags.SELLER), invoice.seller());
            party(xml, child(xml, content, Tags.BUYER), invoice.buyer());
            Element items = child(xml, content, Tags.ITEMS);
            for (EInvoiceLine line : invoice.lines()) {
                Element item = child(xml, items, Tags.ITEM);
                // 1: goods or a service. The format's other natures (promotion, discount, note) are not produced here.
                text(xml, item, Tags.ITEM_NATURE, "1");
                text(xml, item, Tags.LINE_NO, Integer.toString(line.lineNo()));
                if (line.sku() != null) {
                    text(xml, item, Tags.ITEM_CODE, line.sku());
                }
                text(xml, item, Tags.ITEM_NAME, line.itemName());
                text(xml, item, Tags.UNIT, line.unit());
                text(xml, item, Tags.QUANTITY, Integer.toString(line.quantity()));
                text(xml, item, Tags.UNIT_PRICE, number(line.unitPrice()));
                text(xml, item, Tags.AMOUNT, number(line.amount()));
                text(xml, item, Tags.TAX_RATE, rate(line.taxRate()));
                text(xml, item, Tags.TAX_AMOUNT, number(line.taxAmount()));
            }
            Element payment = child(xml, content, Tags.PAYMENT);
            Element byRate = child(xml, payment, Tags.TOTALS_BY_RATE);
            for (TaxRateTotal total : invoice.totalsByRate()) {
                Element one = child(xml, byRate, Tags.RATE_TOTAL);
                text(xml, one, Tags.TAX_RATE, rate(total.taxRate()));
                text(xml, one, Tags.AMOUNT, number(total.amount()));
                text(xml, one, Tags.TAX_AMOUNT, number(total.taxAmount()));
            }
            text(xml, payment, Tags.TOTAL_BEFORE_TAX, number(invoice.subtotal()));
            text(xml, payment, Tags.TOTAL_TAX, number(invoice.taxTotal()));
            text(xml, payment, Tags.TOTAL_PAYABLE, number(invoice.grandTotal()));
            text(xml, payment, Tags.TOTAL_IN_WORDS, invoice.amountInWords());

            // NĐ 254 Điều 10 khoản 5: the contract and the order may be named on the invoice.
            Element other = child(xml, data, Tags.OTHER);
            other(xml, other, "OrderNumber", invoice.orderNumber());
            other(xml, other, "ContractNumber", invoice.contractNumber());
            other(xml, other, "ReferenceCode", invoice.referenceCode());
            other(xml, other, "LookupCode", issued.lookupCode());

            text(xml, root, Tags.TAX_AUTHORITY_CODE, issued.taxAuthorityCode());
            Element signatures = child(xml, root, Tags.SIGNATURES);
            signatures.appendChild(xml.createComment(" No signature: a sandbox document. A provider signs here (XAdES; "
                    + "QD 1233/QD-CT). Signing time recorded: " + issued.signedAt() + " "));

            TransformerFactory transformers = TransformerFactory.newInstance();
            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = transformers.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(xml), new StreamResult(out));
            return out.toString();
        } catch (ParserConfigurationException | TransformerException failure) {
            throw new IllegalStateException("Could not write the invoice's XML", failure);
        }
    }

    private static void party(Document xml, Element element, InvoiceParty party) {
        text(xml, element, Tags.NAME, party.name());
        text(xml, element, Tags.TAX_CODE, party.taxCode());
        text(xml, element, Tags.ADDRESS, party.address());
        if (party.email() != null) {
            text(xml, element, Tags.EMAIL, party.email());
        }
    }

    private static void other(Document xml, Element parent, String field, String value) {
        if (value == null) {
            return;
        }
        Element item = child(xml, parent, Tags.OTHER_ITEM);
        text(xml, item, Tags.OTHER_FIELD, field);
        text(xml, item, Tags.OTHER_VALUE, value);
    }

    private static Element child(Document xml, Element parent, String name) {
        Element element = xml.createElement(name);
        parent.appendChild(element);
        return element;
    }

    /** The DOM escapes the text: a name with {@code &} or {@code <} in it stays data. */
    private static void text(Document xml, Element parent, String name, String value) {
        child(xml, parent, name).setTextContent(value == null ? "" : value);
    }

    /** Whole dong, no grouping. */
    private static String number(BigDecimal amount) {
        return amount.setScale(0, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    /** "10%", "8%", "0%": the rate as the format writes it. */
    private static String rate(BigDecimal percent) {
        return percent.stripTrailingZeros().toPlainString() + "%";
    }
}
