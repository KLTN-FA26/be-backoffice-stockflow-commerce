package com.stockflow.common.export;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The XLSX writer of every export (2026-11-14 plan, BE2-A), read back with POI as Excel would read it: the layout,
 * the cell types and formats, the text that stays text, and the row limit.
 */
class XlsxTableTest {

    private record Line(String name, Long count, BigDecimal amount, BigDecimal volume, LocalDate day, Instant at) {
    }

    private static final List<XlsxTable.Column<Line>> COLUMNS = List.of(
            new XlsxTable.Column<>("Name", XlsxTable.Kind.TEXT, Line::name),
            new XlsxTable.Column<>("Count", XlsxTable.Kind.INTEGER, Line::count),
            new XlsxTable.Column<>("Amount", XlsxTable.Kind.MONEY, Line::amount),
            new XlsxTable.Column<>("Volume", XlsxTable.Kind.DECIMAL, Line::volume),
            new XlsxTable.Column<>("Day", XlsxTable.Kind.DATE, Line::day),
            new XlsxTable.Column<>("At", XlsxTable.Kind.DATETIME, Line::at));

    private static XSSFWorkbook read(byte[] file) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(file));
    }

    private static Line line(String name) {
        return new Line(name, 1_234L, new BigDecimal("1500000"), new BigDecimal("0.125"),
                LocalDate.of(2026, 11, 14), Instant.parse("2026-11-14T17:30:00Z"));
    }

    @Test
    @DisplayName("titles, a blank row, a bold header with an autofilter and frozen panes, then typed rows")
    void layoutAndTypes() throws IOException {
        byte[] file = XlsxTable.write("Phiếu thu", List.of("Danh sách phiếu thu", "Bộ lọc: không"), COLUMNS,
                List.of(line("Công ty A"), line("Công ty B")));

        try (XSSFWorkbook workbook = read(file)) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Phiếu thu");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Danh sách phiếu thu");
            assertThat(workbook.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Bộ lọc: không");
            assertThat((Object) sheet.getRow(2)).as("a blank row between the titles and the table").isNull();

            Row header = sheet.getRow(3);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Name");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("At");
            assertThat(workbook.getFontAt(header.getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).as("frozen below the header")
                    .isEqualTo((short) 4);
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A4:F6");

            Row first = sheet.getRow(4);
            assertThat(first.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("Công ty A");
            assertThat(first.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(first.getCell(1).getNumericCellValue()).isEqualTo(1234d);
            assertThat(first.getCell(1).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(first.getCell(2).getNumericCellValue()).isEqualTo(1_500_000d);
            assertThat(first.getCell(2).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(first.getCell(3).getNumericCellValue()).isEqualTo(0.125d);
            assertThat(first.getCell(3).getCellStyle().getDataFormatString()).isEqualTo("0.000");
            assertThat(first.getCell(4).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 14));
            assertThat(first.getCell(4).getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy");
            assertThat(first.getCell(5).getLocalDateTimeCellValue())
                    .as("17:30 UTC is half past midnight the next day in Ho Chi Minh City")
                    .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 30));
            assertThat(first.getCell(5).getCellStyle().getDataFormatString()).isEqualTo("hh:mm dd/mm/yyyy");
            assertThat(sheet.getLastRowNum()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("text that looks like a formula stays text: no cell of the file is a formula")
    void textIsNeverAFormula() throws IOException {
        byte[] file = XlsxTable.write("Injection", List.of(), COLUMNS, List.of(line("=1+1"),
                line("=HYPERLINK(\"http://evil\",\"x\")"), line("@SUM(A1)"), line("+1"), line("-1")));

        try (XSSFWorkbook workbook = read(file)) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).as("no title: the header is the first row")
                    .isEqualTo("Name");
            Cell formula = sheet.getRow(1).getCell(0);
            assertThat(formula.getCellType()).isEqualTo(CellType.STRING);
            assertThat(formula.getStringCellValue()).isEqualTo("=1+1");
            assertThat(formula.getCellStyle().getDataFormatString()).as("Excel's text format").isEqualTo("@");
            List<CellType> types = new ArrayList<>();
            sheet.forEach(row -> row.forEach(cell -> types.add(cell.getCellType())));
            assertThat(types).doesNotContain(CellType.FORMULA);
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("=HYPERLINK(\"http://evil\",\"x\")");
        }
    }

    @Test
    @DisplayName("a null value leaves its cell empty; several sheets in one workbook; an unsafe sheet name is made safe")
    void nullsAndSheets() throws IOException {
        Line empty = new Line("only a name", null, null, null, null, null);
        byte[] file = XlsxTable.write(List.of(
                new XlsxTable.Sheet<>("Receipts", List.of("Title"), COLUMNS, List.of(empty)),
                new XlsxTable.Sheet<>("Lines/details [2026]?", List.of(), List.of(
                        new XlsxTable.Column<String>("Value", XlsxTable.Kind.TEXT, s -> s)), List.of("a", "b"))));

        try (XSSFWorkbook workbook = read(file)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            Row row = workbook.getSheetAt(0).getRow(3);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("only a name");
            for (int c = 1; c < COLUMNS.size(); c++) {
                assertThat(row.getCell(c)).as("column " + c).isNull();
            }
            XSSFSheet second = workbook.getSheetAt(1);
            assertThat(second.getSheetName()).doesNotContain("/", "[", "]", "?");
            assertThat(second.getRow(2).getCell(0).getStringCellValue()).isEqualTo("b");
        }
    }

    @Test
    @DisplayName("more than MAX_ROWS rows is EXPORT_TOO_LARGE, whether the rows are a list or a stream")
    void rowLimit() throws IOException {
        List<Line> tooMany = IntStream.rangeClosed(0, XlsxTable.MAX_ROWS).mapToObj(i -> line("n" + i)).toList();
        Throwable listed = catchThrowable(() -> XlsxTable.write("Big", List.of(), COLUMNS, tooMany));
        assertThat(listed).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) listed).errorCode()).isEqualTo(ErrorCode.EXPORT_TOO_LARGE);
        assertThat(ErrorCode.EXPORT_TOO_LARGE.httpStatus()).isEqualTo(422);

        Iterable<Line> streamed = () -> new Iterator<>() {
            private int n;

            @Override
            public boolean hasNext() {
                return n <= XlsxTable.MAX_ROWS;
            }

            @Override
            public Line next() {
                return line("s" + n++);
            }
        };
        Throwable counted = catchThrowable(() -> XlsxTable.write("Big", List.of(), COLUMNS, streamed));
        assertThat(((BusinessException) counted).errorCode()).isEqualTo(ErrorCode.EXPORT_TOO_LARGE);

        byte[] atTheLimit = XlsxTable.write("Full", List.of(), COLUMNS, tooMany.subList(0, XlsxTable.MAX_ROWS));
        try (XSSFWorkbook workbook = read(atTheLimit)) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).as("header plus MAX_ROWS rows")
                    .isEqualTo(XlsxTable.MAX_ROWS);
        }
    }

    @Test
    @DisplayName("a value of the wrong type for its column is a programming error, not a silent blank")
    void wrongType() {
        List<XlsxTable.Column<String>> columns = List.of(
                new XlsxTable.Column<>("Day", XlsxTable.Kind.DATE, s -> s));
        assertThat(catchThrowable(() -> XlsxTable.write("x", List.of(), columns, List.of("2026-11-14"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
