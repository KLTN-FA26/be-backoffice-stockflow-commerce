package com.stockflow.design.internal.service;

import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The print file a design is submitted and approved with (plan D2): one page per print area the size of its bleed box,
 * read from disk - and a damaged file is a finding, never a server error, however PDFBox fails on it.
 */
class PrintFileGeometryTest {

    private static final float PT = 72f / 25.4f;

    private static PrintConfig bag() {
        return new PrintConfig(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "BAG-1", "BAG-FACES", "Túi",
                300, "CMYK", List.of("image/png"), 20, null, null, true,
                new BagModel(200, 80, 300, 40, "twisted", "kraft-brown"), List.of(
                        new PrintArea("FRONT", "Mặt trước", 0, 0, 200, 300, 5, 3, new PrintAreaShape.Rect(), "front"),
                        new PrintArea("BACK", "Mặt sau", 0, 0, 200, 300, 5, 3, new PrintAreaShape.Rect(), "back")),
                null, null, 1);
    }

    private static byte[] pdf(int pages, Integer declaredCount) throws Exception {
        try (var document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage(new PDRectangle(206 * PT, 306 * PT)));
            }
            if (declaredCount != null) {
                document.getPages().getCOSObject().setInt(COSName.COUNT, declaredCount);
            }
            var out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static String problemOf(byte[] bytes) throws Exception {
        try (PrintFileCopy file = PrintFileCopy.empty()) {
            Files.write(file.path(), bytes);
            return PrintFileGeometry.problem(file, bag());
        }
    }

    @Test
    void onePagePerPrintAreaTheSizeOfItsBleedBoxFits() throws Exception {
        assertThat(problemOf(pdf(2, null))).isNull();
        assertThat(problemOf(pdf(1, null))).contains("1 page(s)");
    }

    @Test
    void aDamagedFileIsAFindingNotAnError() throws Exception {
        assertThat(problemOf("not a pdf".getBytes())).isEqualTo("The print file is not a readable PDF");
        // The page tree claims a page it does not have: PDFBox fails on reaching it with a runtime exception.
        assertThat(problemOf(pdf(1, 2))).isEqualTo("The print file is not a readable PDF");
    }

    @Test
    void theTemporaryFileIsGoneOnceClosed() throws Exception {
        java.nio.file.Path path;
        try (PrintFileCopy file = PrintFileCopy.empty()) {
            path = file.path();
            assertThat(path).exists();
        }
        assertThat(path).doesNotExist();
    }
}
