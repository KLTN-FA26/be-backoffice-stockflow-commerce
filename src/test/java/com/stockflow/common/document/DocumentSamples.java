package com.stockflow.common.document;

import com.stockflow.common.config.SellerProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the tests of every module's business documents share: the demo company as a deployment configures it, a demo
 * customer, a way to read a PDF back - and a way to keep it.
 *
 * <p>Every PDF a test draws is also written to {@code target/document-samples/}. A test can assert that a word is on
 * the page and inside the margins; it cannot tell whether the page looks like a paper a company would send. Someone
 * has to look, and the files are there for that - open them after a run, or turn them into images.</p>
 */
public final class DocumentSamples {

    /** The customer of the samples, by its legal identity. */
    public static final DocumentParts.Party BUYER = new DocumentParts.Party("CÔNG TY CỔ PHẦN CÀ PHÊ MỘC LAM",
            "0398765432", "Số 45 Đường Hồng Hà, Phường Tân Sơn Nhất, Thành phố Hồ Chí Minh", "Lê Thị Mộc Lam",
            "Tổng Giám đốc");

    /** A real legal name: long, in capitals, Vietnamese - far wider than its letters suggest. */
    public static final String LONG_NAME = "CÔNG TY TRÁCH NHIỆM HỮU HẠN THƯƠNG MẠI DỊCH VỤ SẢN XUẤT BAO BÌ GIẤY VÀ "
            + "NHỰA MIỀN NAM VIỆT NAM";

    private DocumentSamples() {
    }

    /** The seller's letterhead as the application builds it from {@code stockflow.seller.*}, logo included. */
    public static SellerLetterhead seller() {
        return new SellerLetterhead(new SellerProperties("CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "0312345678",
                "Số 268 Đường số 7, Phường Tân Tạo, Thành phố Hồ Chí Minh", "Trần Quốc An", "Giám đốc",
                "(028) 3765 0000", "kinhdoanh@baobianphat.example", "www.baobianphat.example", "0071001234567",
                "Ngân hàng TMCP Ngoại thương Việt Nam (Vietcombank)", "Chi nhánh Bình Tây", "Thành phố Hồ Chí Minh",
                "classpath:branding/seller-logo.png"), new DefaultResourceLoader());
    }

    /** Keeps the PDF under {@code target/document-samples/} to be looked at, and returns it. */
    public static byte[] keep(String name, byte[] pdf) {
        try {
            Path directory = Path.of("target", "document-samples");
            Files.createDirectories(directory);
            Files.write(directory.resolve(name + ".pdf"), pdf);
            return pdf;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * A download endpoint's answer: 200, {@code application/pdf}, saved under a Vietnamese name that starts with
     * {@code fileName} (RFC 5987, as {@code PdfDownload} sends it). The PDF is kept as {@code sample}, drawn from the
     * rows a test made - the papers as a deployment prints them - and its text returned.
     */
    public static String downloaded(ResultActions answer, String sample, String fileName) throws Exception {
        MockHttpServletResponse response = answer.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andReturn().getResponse();
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).as("the name the paper is saved under")
                .startsWith("inline")
                .contains("filename*=UTF-8''" + UriUtils.encode(fileName, StandardCharsets.UTF_8));
        return text(keep(sample, response.getContentAsByteArray()));
    }

    /** The text of the PDF, spaces collapsed. */
    public static String text(byte[] pdf) throws IOException {
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }

    /** The text of one page (from 1), spaces collapsed. */
    public static String text(byte[] pdf, int page) throws IOException {
        try (var document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document).replaceAll("\\s+", " ");
        }
    }

    /**
     * The text of the PDF with no whitespace at all: for what a reader sees as one phrase but a PDF holds in pieces -
     * a code wrapped over two lines of its cell, a justified line.
     */
    public static String compact(byte[] pdf) throws IOException {
        return text(pdf).replace(" ", "");
    }

    public static int pages(byte[] pdf) throws IOException {
        try (var document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    /**
     * The left-most and right-most points any upright glyph reaches, over every page: {@code [left, right]}. A
     * watermark is turned and is not text a margin holds.
     */
    public static float[] extent(byte[] pdf) throws IOException {
        float[] box = {PDRectangle.A4.getWidth(), 0};
        try (var document = Loader.loadPDF(pdf)) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    for (TextPosition glyph : positions) {
                        if (glyph.getDir() == 0) {
                            box[0] = Math.min(box[0], glyph.getXDirAdj());
                            box[1] = Math.max(box[1], glyph.getXDirAdj() + glyph.getWidthDirAdj());
                        }
                    }
                    super.writeString(text, positions);
                }
            }.getText(document);
        }
        return box;
    }

    /** Whether every upright glyph is inside margins of {@code left} and {@code right} millimetres. */
    public static boolean within(byte[] pdf, double left, double right) throws IOException {
        float[] extent = extent(pdf);
        return extent[0] >= BusinessDocument.mm(left) - 0.5f
                && extent[1] <= PDRectangle.A4.getWidth() - BusinessDocument.mm(right) + 0.5f;
    }
}
