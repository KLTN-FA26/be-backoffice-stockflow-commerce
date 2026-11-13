package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.internal.domain.DesignSnapshotArtifact;
import com.stockflow.design.internal.domain.DesignSnapshotAsset;
import com.stockflow.design.internal.domain.PrintSurface;
import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.ProductModel;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * What an approval produces besides the snapshot row (kltn-docs 12 §4.5, plan D2/D3): the print file stamped with its
 * boxes and PDF/X identification - only when it fits the template - the production instruction in Vietnamese, and a
 * snapshot checksum that covers the layout and the images without changing for a snapshot of the staff workflow.
 */
class ApprovalFilesTest {

    private static final float PT = 72f / 25.4f;

    private static PrintConfig template(ProductModel model, List<PrintArea> areas) {
        return new PrintConfig(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP-12", "CUP12-BODY",
                "Ly 12oz", 300, "CMYK", List.of("image/png"), 20, null, null, true, model, areas, null, null, 3);
    }

    private static PrintConfig bag() {
        BagModel model = new BagModel(200, 80, 300, 40, "twisted", "kraft-brown");
        return template(model, List.of(
                new PrintArea("FRONT", "Mặt trước", 0, 0, 200, 300, 5, 3, new PrintAreaShape.Rect(), "front"),
                new PrintArea("BACK", "Mặt sau", 0, 0, 200, 300, 5, 3, new PrintAreaShape.Rect(), "back")));
    }

    private static PrintConfig cup() {
        return template(new CupModel(90, 60, 110, 3, 8, 6, 8, "paper-white"), List.of(
                new PrintArea("BODY", "Thân ly", 0, 0, 280, 104, 3, 2, new PrintAreaShape.Rect(), "body")));
    }

    private static byte[] pdf(double[]... pagesMm) throws Exception {
        try (var document = new PDDocument()) {
            for (double[] page : pagesMm) {
                document.addPage(new PDPage(new PDRectangle((float) page[0] * PT, (float) page[1] * PT)));
            }
            var out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static final PrintOutputIntentProperties INTERIM = new PrintOutputIntentProperties(null, null, null, null);

    /** {@code pdf} stamped for {@code template}, through files on disk as the approval does. */
    private static byte[] stamped(byte[] pdf, PrintConfig template, String title) throws Exception {
        try (PrintFileCopy in = PrintFileCopy.empty(); PrintFileCopy out = PrintFileCopy.empty()) {
            Files.write(in.path(), pdf);
            new PrintReadyStamp(INTERIM).stamp(in, template, title, out);
            return Files.readAllBytes(out.path());
        }
    }

    @Test
    void aPrintFileThatFitsIsStampedWithItsBoxesAndThePdfxIdentification() throws Exception {
        byte[] stamped = stamped(pdf(new double[] {206, 306}, new double[] {206, 306}), bag(),
                "Print file CUP12-BODY");

        try (PDDocument document = Loader.loadPDF(stamped)) {
            for (PDPage page : document.getPages()) {
                PDRectangle trim = page.getTrimBox();
                assertThat((double) trim.getLowerLeftX()).isCloseTo(3 * PT, within(0.01));
                assertThat((double) trim.getLowerLeftY()).isCloseTo(3 * PT, within(0.01));
                assertThat((double) trim.getWidth()).isCloseTo(200 * PT, within(0.01));
                assertThat((double) trim.getHeight()).isCloseTo(300 * PT, within(0.01));
                assertThat((double) page.getBleedBox().getWidth()).isCloseTo(206 * PT, within(0.01));
            }
            assertThat(document.getDocumentInformation().getCustomMetadataValue("GTS_PDFXVersion")).isEqualTo("PDF/X-4");
            assertThat(document.getDocumentInformation().getTrapped()).isEqualTo("False");
            assertThat(new String(document.getDocumentCatalog().getMetadata().toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8)).contains("<pdfxid:GTS_PDFXVersion>PDF/X-4");
            // C-1: one PDF/X output intent, the interim sRGB profile until the printer's (B35) is configured.
            var intents = document.getDocumentCatalog().getOutputIntents();
            assertThat(intents).hasSize(1);
            assertThat(intents.get(0).getCOSObject().getCOSName(COSName.S).getName()).isEqualTo("GTS_PDFX");
            assertThat(intents.get(0).getOutputConditionIdentifier()).isEqualTo("sRGB IEC61966-2.1");
            assertThat(intents.get(0).getRegistryName()).isEqualTo("http://www.color.org");
            assertThat(intents.get(0).getDestOutputIntent().getInt(COSName.N)).isEqualTo(3);
        }
    }

    @Test
    void onACupTheTrimBoxIsTheWallsBoundingBoxInsideTheBleed() throws Exception {
        PrintConfig cup = cup();
        PrintSurface wall = PrintSurface.of(cup.model(), cup.printAreas().get(0));
        byte[] stamped = stamped(pdf(new double[] {wall.flatWidth(), wall.flatHeight()}), cup, "Print file");

        try (PDDocument document = Loader.loadPDF(stamped)) {
            PDRectangle trim = document.getPage(0).getTrimBox();
            PDRectangle media = document.getPage(0).getMediaBox();
            PrintSurface.Box box = wall.trimBounds();
            assertThat(box.width()).isLessThan(wall.flatWidth());
            assertThat((double) trim.getLowerLeftX()).isCloseTo(box.x() * PT, within(0.01));
            assertThat((double) trim.getWidth()).isCloseTo(box.width() * PT, within(0.01));
            // PDF space grows upwards: the trim's top edge is the page height less its distance from the page's top.
            assertThat((double) trim.getUpperRightY()).isCloseTo(media.getHeight() - box.y() * PT, within(0.01));
        }
    }

    @Test
    void aCropBoxTighterThanThePageAndAnArtBoxDoNotSurviveTheStamp() throws Exception {
        byte[] original;
        try (var document = new PDDocument()) {
            for (int i = 0; i < 2; i++) {
                var page = new PDPage(new PDRectangle(206 * PT, 306 * PT));
                page.setCropBox(new PDRectangle(3 * PT, 3 * PT, 200 * PT, 300 * PT));
                page.setArtBox(new PDRectangle(10 * PT, 10 * PT, 100 * PT, 100 * PT));
                document.addPage(page);
            }
            var out = new ByteArrayOutputStream();
            document.save(out);
            original = out.toByteArray();
        }

        try (PDDocument document = Loader.loadPDF(stamped(original, bag(), "x"))) {
            for (PDPage page : document.getPages()) {
                assertThat((double) page.getCropBox().getWidth()).isCloseTo(206 * PT, within(0.01));
                assertThat((double) page.getCropBox().getHeight()).isCloseTo(306 * PT, within(0.01));
                assertThat(page.getCOSObject().containsKey(COSName.ART_BOX)).isFalse();
            }
        }
    }

    @Test
    void aPrintFileThatDoesNotFitIsRefusedRatherThanStamped() throws Exception {
        assertThatThrownBy(() -> stamped(pdf(new double[] {206, 306}), bag(), "x"))
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.DESIGN_PRINT_FILE_INVALID);
        assertThatThrownBy(() -> stamped(pdf(new double[] {210, 297}, new double[] {206, 306}), bag(), "x"))
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.DESIGN_PRINT_FILE_INVALID);
        assertThatThrownBy(() -> stamped("not a pdf".getBytes(), bag(), "x"))
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.DESIGN_PRINT_FILE_INVALID);
        // A page tree claiming a page it does not have: PDFBox fails with a runtime exception, never a 500 here.
        byte[] lying;
        try (var document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(206 * PT, 306 * PT)));
            document.getPages().getCOSObject().setInt(COSName.COUNT, 2);
            var out = new ByteArrayOutputStream();
            document.save(out);
            lying = out.toByteArray();
        }
        assertThatThrownBy(() -> stamped(lying, bag(), "x"))
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.DESIGN_PRINT_FILE_INVALID);
    }

    @Test
    void theProductionInstructionStatesTheJobInVietnamese() throws Exception {
        var carton = new UomConversion(UUID.randomUUID(), "CUP-12", "CARTON", 50, false, new BigDecimal("4.2"),
                new BigDecimal("60"), new BigDecimal("40"), new BigDecimal("35"), false, true, 0);
        byte[] sheet = new ProductionInstructionSheet(new LabelFonts(), INTERIM).render(new ProductionInstructionSheet.Facts(
                "CUP12-0312345678-01", "Ly giấy 12oz", "CUP-12", cup(), carton, "a".repeat(64), "b".repeat(64),
                UUID.randomUUID(), Instant.parse("2026-11-08T03:00:00Z")));

        try (PDDocument document = Loader.loadPDF(sheet)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Lệnh sản xuất in", "CUP12-0312345678-01", "Ly giấy 12oz", "CARTON 50",
                    "Thân ly", "PDF/X-4", "IEC61966-2.1", "10:00");
        }
    }

    @Test
    void theSnapshotChecksumCoversTheLayoutAndImagesOnlyWhenThereIsALayout() {
        var manifest = List.of(new DesignSnapshotArtifact(UUID.randomUUID(), DesignArtifactRole.CUSTOMER_PREVIEW,
                "design-renders/p.png", "p.png", "image/png", 10, "c".repeat(64)));
        var image = new DesignSnapshotAsset(UUID.randomUUID(), "design-assets/a.png", "a.png", "image/png", 10,
                "d".repeat(64), 100, 100);

        String staffWorkflow = DesignSnapshotJpaEntity.manifestChecksum(manifest);
        assertThat(DesignSnapshotJpaEntity.checksumOf(manifest, null, List.of())).isEqualTo(staffWorkflow);
        String withLayout = DesignSnapshotJpaEntity.checksumOf(manifest, "e".repeat(64), List.of(image));
        assertThat(withLayout).isNotEqualTo(staffWorkflow);
        assertThat(DesignSnapshotJpaEntity.checksumOf(manifest, "f".repeat(64), List.of(image))).isNotEqualTo(withLayout);
        var changedImage = new DesignSnapshotAsset(image.assetId(), image.storageKey(), image.originalName(),
                image.contentType(), image.sizeBytes(), "0".repeat(64), 100, 100);
        assertThat(DesignSnapshotJpaEntity.checksumOf(manifest, "e".repeat(64), List.of(changedImage)))
                .isNotEqualTo(withLayout);
    }
}
