package com.stockflow.fulfillment.internal.domain;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The waybill import template read from an Excel workbook (.xlsx): the same table, lines and numbers as the CSV of the
 * same data, with the cells of a spreadsheet - numbers, formulas, blanks - read as text, and a file that is not such a
 * workbook refused whole.
 */
class WaybillImportXlsxTest {

    private static final String[] HEADINGS = {"Mã chuyến", "Mã vận đơn", "Biển số", "Tên tài xế", "Điện thoại tài xế"};

    @FunctionalInterface
    private interface SheetFiller {
        void fill(XSSFWorkbook workbook, Sheet sheet);
    }

    private static byte[] workbook(SheetFiller filler) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Nhập");
            filler.fill(workbook, sheet);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void text(Row row, int column, String value) {
        row.createCell(column, CellType.STRING).setCellValue(value);
    }

    private static void heading(Sheet sheet) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < HEADINGS.length; i++) {
            text(row, i, HEADINGS[i]);
        }
    }

    private static byte[] withRows(String[]... lines) {
        return workbook((wb, sheet) -> {
            heading(sheet);
            for (int r = 0; r < lines.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < lines[r].length; c++) {
                    if (lines[r][c] != null) {
                        text(row, c, lines[r][c]);
                    }
                }
            }
        });
    }

    private static byte[] csv(String body) {
        return ("﻿Mã chuyến,Mã vận đơn,Biển số,Tên tài xế,Điện thoại tài xế\r\n" + body)
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a workbook is read as the CSV of the same data: first sheet, heading skipped, columns by position, "
            + "cells trimmed, blank cells null, blank rows skipped, a row's number the one Excel shows")
    void sameLinesAsTheCsv() {
        byte[] xlsx = workbook((wb, sheet) -> {
            heading(sheet);
            Row one = sheet.createRow(1);
            text(one, 0, " LD-1 ");
            text(one, 1, " WB-1 ");
            text(one, 2, "51C-1");
            text(one, 3, "Tài");
            text(one, 4, "0900");
            sheet.createRow(2).createCell(0, CellType.STRING).setCellValue("   ");
            Row three = sheet.createRow(3);
            text(three, 0, "LD-2");
            text(three, 1, "WB-2");
            Row five = sheet.createRow(5);
            text(five, 0, "LD-3");
            text(five, 1, "WB-3");
            text(five, 3, "  ");
            wb.createSheet("Another").createRow(1).createCell(0).setCellValue("LD-X");
        });
        byte[] csv = csv(" LD-1 , WB-1 ,51C-1,Tài,0900\r\n\r\nLD-2,WB-2\r\n\r\nLD-3,WB-3,,  ,\r\n");

        List<WaybillImportFile.Row> fromXlsx = WaybillImportFile.parse(xlsx);

        assertThat(fromXlsx).containsExactly(
                new WaybillImportFile.Row(2, "LD-1", "WB-1", "51C-1", "Tài", "0900"),
                new WaybillImportFile.Row(4, "LD-2", "WB-2", null, null, null),
                new WaybillImportFile.Row(6, "LD-3", "WB-3", null, null, null));
        assertThat(fromXlsx).isEqualTo(WaybillImportFile.parse(csv));
    }

    @Test
    @DisplayName("the format is found from the bytes: a workbook named or declared as anything is a workbook, text is text")
    void formatFromBytes() {
        byte[] xlsx = withRows(new String[] {"LD-1", "WB-1"});

        assertThat(WaybillImportFile.isXlsx(xlsx)).isTrue();
        assertThat(WaybillImportFile.contentType(xlsx)).isEqualTo(WaybillImportFile.XLSX_TYPE);
        assertThat(WaybillImportFile.isXlsx(csv("LD-1,WB-1\r\n"))).isFalse();
        assertThat(WaybillImportFile.contentType(csv("LD-1,WB-1\r\n"))).isEqualTo("text/csv");
        assertThat(WaybillImportFile.parse(xlsx)).hasSize(1);
    }

    @Test
    @DisplayName("numbers become text without an exponent or a .0; a zero-padded format keeps its zeros; text cells "
            + "stay as typed")
    void numericCells() {
        byte[] xlsx = workbook((wb, sheet) -> {
            heading(sheet);
            DataFormat formats = wb.createDataFormat();
            CellStyle scientific = wb.createCellStyle();
            scientific.setDataFormat(formats.getFormat("0.00E+00"));
            CellStyle padded = wb.createCellStyle();
            padded.setDataFormat(formats.getFormat("0000000000"));
            CellStyle grouped = wb.createCellStyle();
            grouped.setDataFormat(formats.getFormat("#,##0"));
            Row r = sheet.createRow(1);
            text(r, 0, "LD-1");
            r.createCell(1, CellType.NUMERIC).setCellValue(123456789012d);
            r.createCell(2, CellType.NUMERIC).setCellValue(51d);
            Cell phone = r.createCell(4, CellType.NUMERIC);
            phone.setCellValue(912345678d);
            phone.setCellStyle(padded);
            Row s = sheet.createRow(2);
            text(s, 0, "LD-2");
            Cell sci = s.createCell(1, CellType.NUMERIC);
            sci.setCellValue(123456789012d);
            sci.setCellStyle(scientific);
            Cell group = s.createCell(2, CellType.NUMERIC);
            group.setCellValue(1234567d);
            group.setCellStyle(grouped);
            s.createCell(3, CellType.NUMERIC).setCellValue(12.5d);
            text(s, 4, "0900123456");
            Row t = sheet.createRow(3);
            text(t, 0, "LD-3");
            text(t, 1, "007");
        });

        List<WaybillImportFile.Row> rows = WaybillImportFile.parse(xlsx);

        assertThat(rows.get(0)).isEqualTo(new WaybillImportFile.Row(2, "LD-1", "123456789012", "51", null,
                "0912345678"));
        assertThat(rows.get(1)).isEqualTo(new WaybillImportFile.Row(3, "LD-2", "123456789012", "1234567", "12.5",
                "0900123456"));
        assertThat(rows.get(2).waybillNo()).isEqualTo("007");
    }

    @Test
    @DisplayName("a formula is read as the value stored with it and never evaluated; a boolean or an error as shown")
    void formulaCells() {
        byte[] xlsx = workbook((wb, sheet) -> {
            heading(sheet);
            Row r = sheet.createRow(1);
            text(r, 0, "LD-1");
            Cell formula = r.createCell(1);
            formula.setCellFormula("\"WB-\"&\"1\"");
            Cell literal = r.createCell(2);
            literal.setCellFormula("1+1");
            r.createCell(3, CellType.BOOLEAN).setCellValue(true);
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
        });

        List<WaybillImportFile.Row> rows = WaybillImportFile.parse(xlsx);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.waybillNo()).isEqualTo("WB-1");
            assertThat(row.vehiclePlate()).isEqualTo("2");
            assertThat(row.driverName()).isEqualTo("TRUE");
        });
    }

    @Test
    @DisplayName("a formula never calculated (no stored value, as a library writes it) is a blank cell: nothing is evaluated here")
    void uncalculatedFormula() {
        byte[] xlsx = workbook((wb, sheet) -> {
            heading(sheet);
            Row r = sheet.createRow(1);
            text(r, 0, "LD-1");
            text(r, 1, "WB-1");
            r.createCell(2).setCellFormula("1+1");
        });

        assertThat(WaybillImportFile.parse(xlsx)).singleElement()
                .satisfies(row -> assertThat(row.vehiclePlate()).isNull());
    }

    @Test
    @DisplayName("the extract of a workbook is a workbook holding the heading and only the rows asked for")
    void extractStaysAWorkbook() {
        byte[] xlsx = withRows(new String[] {"LD-1", "WB-1"}, new String[] {"LD-2", "WB,2", "51C-2"},
                new String[] {"LD-3", "WB-3"});

        byte[] extract = WaybillImportFile.extract(xlsx, Set.of(3));

        assertThat(WaybillImportFile.isXlsx(extract)).isTrue();
        assertThat(WaybillImportFile.parse(extract)).containsExactly(
                new WaybillImportFile.Row(2, "LD-2", "WB,2", "51C-2", null, null));
    }

    @Test
    @DisplayName("an empty workbook, one with only a heading, or none at all: refused whole")
    void noTable() {
        byte[] empty = workbook((wb, sheet) -> { });
        byte[] headingOnly = workbook((wb, sheet) -> heading(sheet));

        assertThatThrownBy(() -> WaybillImportFile.parse(empty)).isInstanceOf(ImportCsv.Malformed.class)
                .hasMessageContaining("no heading");
        assertThatThrownBy(() -> WaybillImportFile.parse(headingOnly)).isInstanceOf(ImportCsv.Malformed.class)
                .hasMessageContaining("no line");
    }

    @Test
    @DisplayName("over 1,000 data rows: refused whole, 1,000 are read")
    void rowLimit() {
        byte[] atLimit = workbook((wb, sheet) -> fill(sheet, WaybillImportFile.MAX_ROWS));
        byte[] over = workbook((wb, sheet) -> fill(sheet, WaybillImportFile.MAX_ROWS + 1));

        assertThat(WaybillImportFile.parse(atLimit)).hasSize(WaybillImportFile.MAX_ROWS);
        assertThatThrownBy(() -> WaybillImportFile.parse(over)).isInstanceOf(ImportCsv.Malformed.class)
                .hasMessageContaining("1000");
    }

    private static void fill(Sheet sheet, int rows) {
        heading(sheet);
        for (int i = 1; i <= rows; i++) {
            Row row = sheet.createRow(i);
            text(row, 0, "LD-" + i);
            text(row, 1, "WB-" + i);
        }
    }

    @Test
    @DisplayName("data far below the heading, past 10,000 rows: refused; formatted blank rows there are not read")
    void lineLimit() {
        byte[] far = workbook((wb, sheet) -> {
            heading(sheet);
            text(sheet.createRow(1), 0, "LD-1");
            text(sheet.getRow(1), 1, "WB-1");
            text(sheet.createRow(10_000), 0, "LD-2");
        });
        byte[] formattedBlanks = workbook((wb, sheet) -> {
            heading(sheet);
            Row one = sheet.createRow(1);
            text(one, 0, "LD-1");
            text(one, 1, "WB-1");
            CellStyle style = wb.createCellStyle();
            style.setWrapText(true);
            Row blank = sheet.createRow(20_000);
            blank.createCell(0).setCellStyle(style);
        });

        assertThatThrownBy(() -> WaybillImportFile.parse(far)).isInstanceOf(ImportCsv.Malformed.class)
                .hasMessageContaining("10000");
        assertThat(WaybillImportFile.parse(formattedBlanks)).hasSize(1);
    }

    @Test
    @DisplayName("over 1 MB, an old .xls or encrypted workbook, a corrupt archive, an archive that is no workbook, "
            + "a zip bomb: refused whole, the message naming no cell")
    void refused() throws IOException {
        byte[] big = new byte[WaybillImportFile.MAX_BYTES + 1];
        big[0] = 'P';
        big[1] = 'K';
        big[2] = 3;
        big[3] = 4;
        byte[] ole2 = new byte[512];
        byte[] signature = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
                (byte) 0xE1};
        System.arraycopy(signature, 0, ole2, 0, signature.length);
        byte[] valid = withRows(new String[] {"LD-1", "WB-1"});
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length / 2);
        byte[] notAWorkbook = zip("hello.txt", "hello".getBytes(StandardCharsets.UTF_8));
        // 20 MB of zeros deflates to ~20 KB: well within the 1 MB limit, far over what a workbook holds.
        byte[] bomb = zip("xl/worksheets/sheet1.xml", new byte[20 * 1024 * 1024]);

        assertThat(bomb.length).isLessThan(WaybillImportFile.MAX_BYTES);
        assertThatThrownBy(() -> WaybillImportFile.parse(big)).isInstanceOf(ImportCsv.Malformed.class)
                .hasMessageContaining("bytes");
        assertThatThrownBy(() -> WaybillImportFile.parse(ole2)).isInstanceOf(ImportCsv.Malformed.class)
                .hasMessageContaining("encrypted or an old .xls");
        assertThatThrownBy(() -> WaybillImportFile.parse(truncated)).isInstanceOf(ImportCsv.Malformed.class)
                .hasMessageContaining(".xlsx");
        assertThatThrownBy(() -> WaybillImportFile.parse(notAWorkbook)).isInstanceOf(ImportCsv.Malformed.class)
                .hasMessageContaining(".xlsx");
        assertThatThrownBy(() -> WaybillImportFile.parse(bomb)).isInstanceOf(ImportCsv.Malformed.class)
                .hasMessageContaining("unpacked");
    }

    @Test
    @DisplayName("text pretending to be a workbook is read as CSV, a workbook is not read as text")
    void notConfused() {
        assertThatThrownBy(() -> WaybillImportFile.parse("PK\u0003\u0004 not really".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ImportCsv.Malformed.class);
        assertThat(WaybillImportFile.parse(csv("LD-1,WB-1\r\n"))).hasSize(1);
    }

    private static byte[] zip(String entry, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
