package com.stockflow.design.internal.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The XMP packet of a stamped print file ({@link PrintReadyStamp}): the one the storefront wrote, with only the fields
 * the stamp owns set again - {@code dc:title}, {@code pdfxid:GTS_PDFXVersion}, {@code pdf:Trapped},
 * {@code xmp:ModifyDate} and {@code xmp:MetadataDate} (FE request 2026-11-18 BE-4).
 *
 * <p>Everything else stays: {@code xmpMM:DocumentID} and {@code InstanceID} (derived from the layout's checksum, equal
 * to the trailer's {@code /ID}, which a full save keeps), {@code xmpMM:VersionID}, {@code xmpMM:RenditionClass}, the
 * creation date and {@code xmp:CreatorTool}. Writing a fresh packet, as the stamp once did, threw them away, and with
 * them what ties the stamped file to the layout it was exported from.</p>
 *
 * <p>An owned field is removed wherever the packet has it - an element or the attribute shorthand, in any
 * {@code rdf:Description} - and written once, in a description of its own: XMP allows several descriptions of one
 * resource, so nothing else in the packet has to be understood to be kept. A file with no XMP, or one that cannot be
 * parsed, gets a packet of the owned fields alone, as before. The parser reads no DTD and resolves no entity: the
 * packet comes from the customer's browser.</p>
 */
final class PrintFileXmp {

    static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    static final String DC = "http://purl.org/dc/elements/1.1/";
    static final String PDFXID = "http://www.npes.org/pdfx/ns/id/";
    static final String PDF = "http://ns.adobe.com/pdf/1.3/";
    static final String XMP = "http://ns.adobe.com/xap/1.0/";
    private static final String META = "adobe:ns:meta/";
    /** Larger than any real XMP packet (the storefront's is a few kilobytes); beyond it the packet is not read. */
    static final int MAX_PACKET_BYTES = 1024 * 1024;

    /** The fields the stamp owns, as {namespace, local name}. */
    private static final List<String[]> OWNED = List.of(new String[] {DC, "title"},
            new String[] {PDFXID, "GTS_PDFXVersion"}, new String[] {PDF, "Trapped"},
            new String[] {XMP, "ModifyDate"}, new String[] {XMP, "MetadataDate"});

    private static final String EMPTY = "<x:xmpmeta xmlns:x=\"" + META + "\"><rdf:RDF xmlns:rdf=\"" + RDF
            + "\"/></x:xmpmeta>";

    private PrintFileXmp() {
    }

    /** {@code document}'s XMP, stamped as {@code title} at {@code at}, as the bytes of a complete packet. */
    static byte[] stamped(PDDocument document, String title, Instant at) {
        Document packet = existing(document);
        if (packet == null) {
            packet = parse(EMPTY.getBytes(StandardCharsets.UTF_8));
        }
        Element rdf = (Element) packet.getElementsByTagNameNS(RDF, "RDF").item(0);
        String about = "";
        for (Element description : descriptions(rdf)) {
            if (about.isEmpty() && description.hasAttributeNS(RDF, "about")) {
                about = description.getAttributeNS(RDF, "about");
            }
            dropOwned(description);
        }
        rdf.appendChild(owned(packet, about, title, at));
        return serialized(packet.getDocumentElement());
    }

    /** The document's packet parsed, or null when it has none, it cannot be read, or it holds no {@code rdf:RDF}. */
    private static Document existing(PDDocument document) {
        PDMetadata metadata = document.getDocumentCatalog().getMetadata();
        if (metadata == null) {
            return null;
        }
        // Decoded with a ceiling: the stream is the customer's, and a compressed bomb inflated whole would take the heap
        // down during an approval. A packet larger than any real one is treated as unreadable.
        byte[] packet;
        try (InputStream in = metadata.createInputStream()) {
            packet = in.readNBytes(MAX_PACKET_BYTES + 1);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        if (packet.length > MAX_PACKET_BYTES) {
            return null;
        }
        try {
            Document parsed = parse(packet);
            return parsed.getElementsByTagNameNS(RDF, "RDF").getLength() == 0 ? null : parsed;
        } catch (IllegalStateException unreadable) {
            // Someone else's broken packet is no reason to refuse an approval: the stamp writes its own instead.
            return null;
        }
    }

    private static List<Element> descriptions(Element rdf) {
        List<Element> found = new ArrayList<>();
        for (Node child = rdf.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && RDF.equals(element.getNamespaceURI())
                    && "Description".equals(element.getLocalName())) {
                found.add(element);
            }
        }
        return found;
    }

    private static void dropOwned(Element description) {
        NamedNodeMap attributes = description.getAttributes();
        for (int i = attributes.getLength() - 1; i >= 0; i--) {
            Attr attribute = (Attr) attributes.item(i);
            if (owned(attribute.getNamespaceURI(), attribute.getLocalName())) {
                description.removeAttributeNode(attribute);
            }
        }
        Node child = description.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();
            if (child instanceof Element element && owned(element.getNamespaceURI(), element.getLocalName())) {
                description.removeChild(element);
            }
            child = next;
        }
    }

    private static boolean owned(String namespace, String localName) {
        return OWNED.stream().anyMatch(field -> field[0].equals(namespace) && field[1].equals(localName));
    }

    private static Element owned(Document packet, String about, String title, Instant at) {
        Element description = packet.createElementNS(RDF, "rdf:Description");
        description.setAttributeNS(RDF, "rdf:about", about);
        // Declared here, not left to the serializer: the packet's own root may bind these prefixes to something else.
        description.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:pdfxid", PDFXID);
        description.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:pdf", PDF);
        description.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xmp", XMP);
        description.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:dc", DC);
        String date = DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS));
        description.appendChild(text(packet, PDFXID, "pdfxid:GTS_PDFXVersion", "PDF/X-4"));
        description.appendChild(text(packet, PDF, "pdf:Trapped", "False"));
        description.appendChild(text(packet, XMP, "xmp:ModifyDate", date));
        description.appendChild(text(packet, XMP, "xmp:MetadataDate", date));
        Element alt = packet.createElementNS(RDF, "rdf:Alt");
        Element item = text(packet, RDF, "rdf:li", title);
        item.setAttributeNS(XMLConstants.XML_NS_URI, "xml:lang", "x-default");
        alt.appendChild(item);
        Element dcTitle = packet.createElementNS(DC, "dc:title");
        dcTitle.appendChild(alt);
        description.appendChild(dcTitle);
        return description;
    }

    private static Element text(Document packet, String namespace, String name, String value) {
        Element element = packet.createElementNS(namespace, name);
        element.setTextContent(value);
        return element;
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Refuses what is not well-formed without printing it to stderr, as the parser's default handler would.
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException unreadable) {
            throw new IllegalStateException("Not an XMP packet", unreadable);
        }
    }

    /**
     * {@code root} inside a fresh {@code xpacket} wrapper: the packet's own processing instructions are outside its root
     * and are not copied, so a file never ends up with two headers.
     */
    private static byte[] serialized(Element root) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter xml = new StringWriter();
            transformer.transform(new DOMSource(root), new StreamResult(xml));
            return ("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>" + xml
                    + "<?xpacket end=\"w\"?>").getBytes(StandardCharsets.UTF_8);
        } catch (TransformerException ex) {
            throw new IllegalStateException("Could not write the XMP packet", ex);
        }
    }
}
