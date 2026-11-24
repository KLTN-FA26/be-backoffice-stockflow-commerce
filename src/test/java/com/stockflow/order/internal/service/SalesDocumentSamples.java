package com.stockflow.order.internal.service;

import com.stockflow.common.document.Letterhead;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What the quotation's and the contract's tests share: the demo company's letterhead, the two parties, the products'
 * names, a way to read a PDF back - and a way to keep it.
 *
 * <p>Every PDF a test draws is also written to {@code target/document-samples/}. A test can assert that a word is on
 * the page and inside the margins; it cannot tell whether the page looks like a paper a company would send. Someone
 * has to look, and the files are there for that - open them after a run, or turn them into images.</p>
 */
final class SalesDocumentSamples {

    static final SalesContracts.Party SELLER = new SalesContracts.Party("CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT",
            "0312345678", "Số 268 Đường số 7, Phường Tân Tạo, Thành phố Hồ Chí Minh", "Trần Quốc An", "Giám đốc");

    static final SalesContracts.Party BUYER = new SalesContracts.Party("CÔNG TY CỔ PHẦN CÀ PHÊ MỘC LAM",
            "0398765432", "Số 45 Đường Hồng Hà, Phường Tân Sơn Nhất, Thành phố Hồ Chí Minh", "Lê Thị Mộc Lam",
            "Tổng Giám đốc");

    /** A real legal name: long, in capitals, Vietnamese - far wider than its letters suggest. */
    static final SalesContracts.Party LONG_BUYER = new SalesContracts.Party("CÔNG TY TRÁCH NHIỆM HỮU HẠN THƯƠNG MẠI "
            + "DỊCH VỤ SẢN XUẤT BAO BÌ GIẤY VÀ NHỰA MIỀN NAM VIỆT NAM", "0312345678", "Số 12 Đường Nguyễn Huệ, Phường "
            + "Sài Gòn, Thành phố Hồ Chí Minh, Việt Nam - tầng 15, toà nhà Bitexco Financial Tower",
            "Trần Văn Bình", "Tổng giám đốc");

    static final Map<String, String> NAMES = Map.of(
            "LY12-MLC-01", "Ly giấy 12oz in logo Mộc Lam",
            "LY16-MLC-02", "Ly giấy 16oz hai lớp in logo Mộc Lam",
            "TUI-MLC-03", "Túi giấy kraft có quai in logo Mộc Lam",
            "LY12-ACME-01", "Ly giấy 12oz in theo thiết kế");

    private SalesDocumentSamples() {
    }

    static Letterhead letterhead() {
        try {
            return new Letterhead(SELLER.name(), SELLER.taxCode(), SELLER.address(), "(028) 3765 0000",
                    "kinhdoanh@baobianphat.example", "www.baobianphat.example", "0071001234567",
                    "Ngân hàng TMCP Ngoại thương Việt Nam (Vietcombank)", "Chi nhánh Bình Tây",
                    "Thành phố Hồ Chí Minh", new ClassPathResource("branding/seller-logo.png").getContentAsByteArray());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A letterhead with nothing but the legal identity: what a deployment that set only the five fields prints. */
    static Letterhead bareLetterhead() {
        return new Letterhead(SELLER.name(), SELLER.taxCode(), SELLER.address(), null, null, null, null, null, null,
                null, null);
    }

    /** Keeps the PDF under {@code target/document-samples/} to be looked at, and returns it. */
    static byte[] keep(String name, byte[] pdf) {
        try {
            Path directory = Path.of("target", "document-samples");
            Files.createDirectories(directory);
            Files.write(directory.resolve(name + ".pdf"), pdf);
            return pdf;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** The text of the PDF, spaces collapsed. */
    static String text(byte[] pdf) throws IOException {
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }

    /**
     * The text of the PDF with no whitespace at all: for what a reader sees as one phrase but a PDF holds in pieces -
     * a watermark, whose turned letters come out one by one, or a code wrapped over two lines of its cell.
     */
    static String compact(byte[] pdf) throws IOException {
        return text(pdf).replace(" ", "");
    }

    /**
     * How many pages carry turned text - a watermark. Counted by the glyphs' direction, not by reading the word: the
     * letters of a turned word come out of a PDF in whatever order the page's other text leaves them.
     */
    static int watermarkedPages(byte[] pdf) throws IOException {
        int marked = 0;
        try (var document = Loader.loadPDF(pdf)) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                boolean[] turned = {false};
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void writeString(String text, List<TextPosition> positions) throws IOException {
                        turned[0] |= positions.stream().anyMatch(glyph -> glyph.getDir() != 0);
                        super.writeString(text, positions);
                    }
                };
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.getText(document);
                marked += turned[0] ? 1 : 0;
            }
        }
        return marked;
    }

    static int pages(byte[] pdf) throws IOException {
        try (var document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    /**
     * The left-most and right-most points any upright glyph reaches, over every page: {@code [left, right]}. The
     * watermark is turned and is not text a margin holds.
     */
    static float[] extent(byte[] pdf) throws IOException {
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
}
