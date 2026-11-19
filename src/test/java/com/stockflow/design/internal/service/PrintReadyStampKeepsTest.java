package com.stockflow.design.internal.service;

import com.stockflow.design.internal.domain.PrintSurface;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What stamping an approved print file must leave as the storefront wrote it, and what it must set (FE request
 * 2026-11-18 BE-3, BE-4, BE-5): the output intent the application ships with, the storefront's XMP with only the
 * stamp's own fields replaced, and every layer - the guide layer still off, a plastic cup's white underlay still on,
 * its {@code White} separation and overprint intact. Each file is built here with PDFBox the way the studio exports it,
 * on a cup template, whose page is the wall's bleed box under both the old and the new page-box rules.
 */
class PrintReadyStampKeepsTest {

    private static final float PT = 72f / 25.4f;
    private static final String BUNDLED = "classpath:print/icc/ISOcoated_v2_eci.icc";
    private static final String DOCUMENT_ID = "uuid:0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    private static PrintConfig cup() {
        return new PrintConfig(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP-12", "CUP12-BODY",
                "Ly 12oz", 300, "CMYK", List.of("image/png"), 20, null, null, true,
                new CupModel(90, 60, 110, 3, 8, 6, 8, "pet-clear"),
                List.of(new PrintArea("BODY", "Thân ly", 0, 0, 280, 104, 3, 2, new PrintAreaShape.Rect(), "body")),
                null, null, 3);
    }

    private static PDPage wallPage() {
        PrintConfig cup = cup();
        PrintSurface wall = PrintSurface.of(cup.model(), cup.printAreas().get(0));
        var page = new PDPage(new PDRectangle((float) wall.flatWidth() * PT, (float) wall.flatHeight() * PT));
        page.setBleedBox(page.getMediaBox());
        return page;
    }

    private static byte[] saved(PDDocument document) throws Exception {
        var out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    private static byte[] stamped(byte[] pdf, PrintOutputIntentProperties intent, String title) throws Exception {
        try (PrintFileCopy in = PrintFileCopy.empty(); PrintFileCopy out = PrintFileCopy.empty()) {
            Files.write(in.path(), pdf);
            new PrintReadyStamp(intent).stamp(in, cup(), title, out);
            return Files.readAllBytes(out.path());
        }
    }

    private static PrintOutputIntentProperties bundled() {
        return new PrintOutputIntentProperties(BUNDLED, null, null, null);
    }

    private static Document xmp(PDDocument document) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(document.getDocumentCatalog().getMetadata()
                .toByteArray()));
    }

    private static List<String> values(Document xmp, String namespace, String name) {
        List<String> found = new ArrayList<>();
        var elements = xmp.getElementsByTagNameNS(namespace, name);
        for (int i = 0; i < elements.getLength(); i++) {
            found.add(elements.item(i).getTextContent().strip());
        }
        var descriptions = xmp.getElementsByTagNameNS(PrintFileXmp.RDF, "Description");
        for (int i = 0; i < descriptions.getLength(); i++) {
            Element description = (Element) descriptions.item(i);
            if (description.hasAttributeNS(namespace, name)) {
                found.add(description.getAttributeNS(namespace, name));
            }
        }
        return found;
    }

    // ---- BE-3: the output intent ----

    @Test
    void theBundledProfileIsStampedAsFogra39InPlaceOfTheOneTheFileBrought() throws Exception {
        byte[] original;
        try (var document = new PDDocument()) {
            document.addPage(wallPage());
            // The storefront's own FOGRA39 intent: the stamp replaces it, so there is still exactly one.
            try (InputStream profile = PrintReadyStamp.resource(BUNDLED).getInputStream()) {
                var theirs = new PDOutputIntent(document, profile);
                theirs.getCOSObject().setItem(COSName.S, COSName.getPDFName("GTS_PDFX"));
                theirs.setOutputConditionIdentifier("FOGRA39");
                document.getDocumentCatalog().addOutputIntent(theirs);
            }
            original = saved(document);
        }

        try (PDDocument document = Loader.loadPDF(stamped(original, bundled(), "Print file"))) {
            var intents = document.getDocumentCatalog().getOutputIntents();
            assertThat(intents).hasSize(1);
            assertThat(intents.get(0).getOutputConditionIdentifier()).isEqualTo("FOGRA39");
            assertThat(intents.get(0).getInfo()).isEqualTo("ISO Coated v2 (ECI)");
            assertThat(intents.get(0).getRegistryName()).isEqualTo("http://www.color.org");
            // A CMYK profile, ECI's file as shipped in the jar.
            assertThat(intents.get(0).getDestOutputIntent().getInt(COSName.N)).isEqualTo(4);
            byte[] embedded;
            try (InputStream in = intents.get(0).getDestOutputIntent().createInputStream()) {
                embedded = in.readAllBytes();
            }
            try (InputStream shipped = PrintReadyStamp.resource(BUNDLED).getInputStream()) {
                assertThat(embedded).isEqualTo(shipped.readAllBytes());
            }
        }
    }

    @Test
    void aPrintersOwnProfileIsReadFromAPlainPathAndNeedsItsCondition() throws Exception {
        Path copy = Files.createTempFile("printer", ".icc");
        try (InputStream profile = PrintReadyStamp.resource(BUNDLED).getInputStream()) {
            Files.write(copy, profile.readAllBytes());
            String path = copy.toAbsolutePath().toString();
            assertThatThrownBy(() -> new PrintOutputIntentProperties(path, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);

            byte[] original;
            try (var document = new PDDocument()) {
                document.addPage(wallPage());
                original = saved(document);
            }
            var own = new PrintOutputIntentProperties(path, "FOGRA51", "PSO Coated v3", null);
            try (PDDocument document = Loader.loadPDF(stamped(original, own, "x"))) {
                assertThat(document.getDocumentCatalog().getOutputIntents().get(0).getOutputConditionIdentifier())
                        .isEqualTo("FOGRA51");
            }
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    @Test
    void aBlankProfileIsTheInterimSrgbIntentAndNamesNoPressCondition() {
        var interim = new PrintOutputIntentProperties(" ", "FOGRA39", "ISO Coated v2 (ECI)", null);

        assertThat(interim.interim()).isTrue();
        assertThat(interim.outputConditionIdentifier()).isEqualTo(PrintOutputIntentProperties.SRGB);
        assertThat(interim.info()).isEqualTo(PrintOutputIntentProperties.SRGB);
    }

    // ---- BE-4: the storefront's XMP ----

    @Test
    void theStorefrontsXmpIsKeptAndOnlyTheStampsFieldsAreSetAgain() throws Exception {
        String packet = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"" + PrintFileXmp.RDF + "\">"
                + "<rdf:Description rdf:about=\"\" xmlns:xmp=\"" + PrintFileXmp.XMP + "\""
                + " xmlns:xmpMM=\"http://ns.adobe.com/xap/1.0/mm/\" xmlns:pdf=\"" + PrintFileXmp.PDF + "\""
                + " xmlns:dc=\"" + PrintFileXmp.DC + "\" xmlns:pdfxid=\"" + PrintFileXmp.PDFXID + "\""
                + " pdf:Trapped=\"Unknown\" xmp:CreatorTool=\"StockFlow Studio\">"
                + "<xmpMM:DocumentID>" + DOCUMENT_ID + "</xmpMM:DocumentID>"
                + "<xmpMM:InstanceID>" + DOCUMENT_ID + "</xmpMM:InstanceID>"
                + "<xmpMM:VersionID>3</xmpMM:VersionID><xmpMM:RenditionClass>default</xmpMM:RenditionClass>"
                + "<xmp:CreateDate>2026-11-18T08:00:00+07:00</xmp:CreateDate>"
                + "<xmp:ModifyDate>2026-11-18T08:00:00+07:00</xmp:ModifyDate>"
                + "<pdf:Producer>pdf-lib</pdf:Producer>"
                + "<pdfxid:GTS_PDFXVersion>PDF/X-4</pdfxid:GTS_PDFXVersion>"
                + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">print-ready</rdf:li></rdf:Alt></dc:title>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        byte[] original;
        COSArray id = new COSArray();
        id.add(new COSString("0f1e2d3c4b5a69788796a5b4c3d2e1f0"));
        id.add(new COSString("0f1e2d3c4b5a69788796a5b4c3d2e1f0"));
        try (var document = new PDDocument()) {
            document.addPage(wallPage());
            var metadata = new PDMetadata(document);
            metadata.importXMPMetadata(packet.getBytes(StandardCharsets.UTF_8));
            document.getDocumentCatalog().setMetadata(metadata);
            document.getDocument().getTrailer().setItem(COSName.ID, id);
            original = saved(document);
        }

        try (PDDocument document = Loader.loadPDF(stamped(original, bundled(), "Print file CUP12-BODY & <A>"))) {
            Document xmp = xmp(document);
            String mm = "http://ns.adobe.com/xap/1.0/mm/";
            assertThat(values(xmp, mm, "DocumentID")).containsExactly(DOCUMENT_ID);
            assertThat(values(xmp, mm, "InstanceID")).containsExactly(DOCUMENT_ID);
            assertThat(values(xmp, mm, "VersionID")).containsExactly("3");
            assertThat(values(xmp, mm, "RenditionClass")).containsExactly("default");
            assertThat(values(xmp, PrintFileXmp.XMP, "CreateDate")).containsExactly("2026-11-18T08:00:00+07:00");
            assertThat(values(xmp, PrintFileXmp.XMP, "CreatorTool")).containsExactly("StockFlow Studio");
            assertThat(values(xmp, PrintFileXmp.PDF, "Producer")).containsExactly("pdf-lib");
            // The stamp's own fields: once each, the attribute shorthand of the old value gone too.
            assertThat(values(xmp, PrintFileXmp.DC, "title")).containsExactly("Print file CUP12-BODY & <A>");
            assertThat(values(xmp, PrintFileXmp.PDF, "Trapped")).containsExactly("False");
            assertThat(values(xmp, PrintFileXmp.PDFXID, "GTS_PDFXVersion")).containsExactly("PDF/X-4");
            List<String> modified = values(xmp, PrintFileXmp.XMP, "ModifyDate");
            assertThat(modified).hasSize(1).doesNotContain("2026-11-18T08:00:00+07:00");
            assertThat(values(xmp, PrintFileXmp.XMP, "MetadataDate")).isEqualTo(modified);
            assertThat(document.getDocumentInformation().getModificationDate().toInstant())
                    .isEqualTo(java.time.Instant.parse(modified.get(0)));
            // The trailer's /ID, which DocumentID repeats, survives the save.
            assertThat(document.getDocument().getTrailer().getCOSArray(COSName.ID).getString(0))
                    .isEqualTo("0f1e2d3c4b5a69788796a5b4c3d2e1f0");
            String raw = new String(document.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertThat(raw).startsWith("<?xpacket begin=").endsWith("<?xpacket end=\"w\"?>");
            assertThat(raw.split("<\\?xpacket begin", -1)).hasSize(2);
        }
    }

    @Test
    void aFileWithNoXmpOrAnUnreadableOneGetsTheStampsFieldsAlone() throws Exception {
        byte[] none;
        byte[] broken;
        byte[] huge;
        try (var document = new PDDocument()) {
            document.addPage(wallPage());
            none = saved(document);
            var metadata = new PDMetadata(document);
            metadata.importXMPMetadata("<x:xmpmeta><unclosed>".getBytes(StandardCharsets.UTF_8));
            document.getDocumentCatalog().setMetadata(metadata);
            broken = saved(document);
            // Larger than any real packet (MAX_PACKET_BYTES): not inflated whole, so it is treated as unreadable.
            var oversized = new PDMetadata(document);
            oversized.importXMPMetadata(("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"" + PrintFileXmp.RDF
                    + "\"><rdf:Description rdf:about=\"\" xmlns:xmp=\"" + PrintFileXmp.XMP + "\" xmp:CreatorTool=\""
                    + "x".repeat(PrintFileXmp.MAX_PACKET_BYTES) + "\"/></rdf:RDF></x:xmpmeta>")
                    .getBytes(StandardCharsets.UTF_8));
            document.getDocumentCatalog().setMetadata(oversized);
            huge = saved(document);
        }

        for (byte[] original : List.of(none, broken, huge)) {
            try (PDDocument document = Loader.loadPDF(stamped(original, bundled(), "Print file"))) {
                Document xmp = xmp(document);
                assertThat(values(xmp, PrintFileXmp.DC, "title")).containsExactly("Print file");
                assertThat(values(xmp, PrintFileXmp.PDFXID, "GTS_PDFXVersion")).containsExactly("PDF/X-4");
                assertThat(values(xmp, PrintFileXmp.PDF, "Trapped")).containsExactly("False");
                assertThat(values(xmp, PrintFileXmp.XMP, "ModifyDate")).hasSize(1);
                assertThat(xmp.getElementsByTagNameNS(PrintFileXmp.RDF, "Description").getLength()).isEqualTo(1);
                assertThat(document.getDocumentInformation().getCustomMetadataValue("GTS_PDFXVersion"))
                        .isEqualTo("PDF/X-4");
            }
        }
    }

    // ---- BE-5: the layers ----

    @Test
    void everyLayerSurvivesTheStampWithTheGuideOffAndTheWhiteUnderlayOn() throws Exception {
        byte[] original;
        try (var document = new PDDocument()) {
            PDPage page = wallPage();
            document.addPage(page);
            var layers = new PDOptionalContentProperties();
            var logo = new PDOptionalContentGroup("Logo");
            var text = new PDOptionalContentGroup("Chữ: Cà phê A");
            var underlay = new PDOptionalContentGroup("Lót trắng (mực White)");
            var guide = new PDOptionalContentGroup("Hướng dẫn kỹ thuật (không in)");
            for (var group : List.of(logo, text, underlay, guide)) {
                layers.addGroup(group);
            }
            layers.setGroupEnabled(guide, false);
            document.getDocumentCatalog().setOCProperties(layers);

            // White: a spot colour whose alternate is CMYK 0 0 0 0, so it previews as nothing and plates as white ink.
            var tint = new COSDictionary();
            tint.setInt(COSName.FUNCTION_TYPE, 2);
            tint.setItem(COSName.DOMAIN, array(0, 1));
            tint.setItem(COSName.C0, array(0, 0, 0, 0));
            tint.setItem(COSName.C1, array(0, 0, 0, 0));
            tint.setInt(COSName.N, 1);
            var white = new PDSeparation();
            white.setColorantName("White");
            white.setAlternateColorSpace(PDDeviceCMYK.INSTANCE);
            white.setTintTransform(PDFunction.create(tint));
            var overprint = new PDExtendedGraphicsState();
            overprint.setNonStrokingOverprintControl(true);
            overprint.setStrokingOverprintControl(true);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginMarkedContent(COSName.OC, underlay);
                content.setNonStrokingColor(new PDColor(new float[] {1f}, white));
                content.addRect(10, 10, 100, 50);
                content.fill();
                content.endMarkedContent();
                content.beginMarkedContent(COSName.OC, logo);
                content.setGraphicsStateParameters(overprint);
                content.setNonStrokingColor(0f, 1f, 1f, 0f);
                content.addRect(20, 20, 40, 20);
                content.fill();
                content.endMarkedContent();
                content.beginMarkedContent(COSName.OC, guide);
                content.addRect(0, 0, 5, 5);
                content.stroke();
                content.endMarkedContent();
            }
            original = saved(document);
        }

        try (PDDocument document = Loader.loadPDF(stamped(original, bundled(), "x"))) {
            PDOptionalContentProperties layers = document.getDocumentCatalog().getOCProperties();
            assertThat(layers.getGroupNames()).containsExactlyInAnyOrder("Logo", "Chữ: Cà phê A",
                    "Lót trắng (mực White)", "Hướng dẫn kỹ thuật (không in)");
            assertThat(layers.isGroupEnabled("Hướng dẫn kỹ thuật (không in)")).isFalse();
            assertThat(layers.isGroupEnabled("Logo")).isTrue();
            assertThat(layers.isGroupEnabled("Chữ: Cà phê A")).isTrue();
            assertThat(layers.isGroupEnabled("Lót trắng (mực White)")).isTrue();
            COSArray off = ((COSDictionary) layers.getCOSObject().getDictionaryObject(COSName.D))
                    .getCOSArray(COSName.OFF);
            assertThat(off.size()).isEqualTo(1);

            PDResources resources = document.getPage(0).getResources();
            List<String> marked = new ArrayList<>();
            resources.getPropertiesNames().forEach(name -> marked.add(
                    ((PDOptionalContentGroup) resources.getProperties(name)).getName()));
            assertThat(marked).contains("Logo", "Lót trắng (mực White)", "Hướng dẫn kỹ thuật (không in)");

            PDSeparation white = null;
            for (COSName name : resources.getColorSpaceNames()) {
                if (resources.getColorSpace(name) instanceof PDSeparation separation) {
                    white = separation;
                }
            }
            assertThat(white).isNotNull();
            assertThat(white.getColorantName()).isEqualTo("White");
            assertThat(white.getAlternateColorSpace()).isInstanceOf(PDDeviceCMYK.class);
            // [/Separation /White /DeviceCMYK tint]: full tint is CMYK 0 0 0 0, as the studio wrote it.
            COSDictionary transform = (COSDictionary) ((COSArray) white.getCOSObject()).getObject(3);
            assertThat(transform.getCOSArray(COSName.C1).toFloatArray()).containsExactly(0f, 0f, 0f, 0f);

            List<Boolean> overprints = new ArrayList<>();
            resources.getExtGStateNames().forEach(name -> overprints.add(
                    resources.getExtGState(name).getNonStrokingOverprintControl()));
            assertThat(overprints).containsExactly(true);
        }
    }

    private static COSArray array(float... values) {
        var array = new COSArray();
        for (float value : values) {
            array.add(value == Math.rint(value) ? COSInteger.get((long) value) : new COSFloat(value));
        }
        return array;
    }
}
