package com.stockflow.fulfillment.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The waybill import template (kltn-docs 09 §4.4; QD-17: CSV UTF-8 with a byte-order mark): what Excel saves in a
 * Vietnamese or another locale is read, a file that is not such a table is refused whole, and a line's content is left
 * to the row check.
 */
class WaybillImportFileTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withBom(String text) {
        byte[] body = utf8(text);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    @Test
    @DisplayName("the template is the byte-order mark and the five headings, a heading with a comma quoted")
    void template() {
        byte[] bytes = WaybillImportFile.template(List.of("Mã chuyến", "Mã vận đơn", "Biển số", "Tên, tài xế",
                "Điện thoại"));

        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8))
                .isEqualTo("Mã chuyến,Mã vận đơn,Biển số,\"Tên, tài xế\",Điện thoại\r\n");
        assertThatThrownBy(() -> WaybillImportFile.template(List.of("a"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("lines are read by position after the heading, trimmed; blank cells are null and blank lines skipped")
    void readsByPosition() {
        List<WaybillImportFile.Row> rows = WaybillImportFile.parse(withBom("""
                Mã chuyến,Mã vận đơn,Biển số,Tên tài xế,Điện thoại tài xế\r
                 LD-1 , WB-1 ,51C-1,Tài,0900\r
                \r
                LD-2,WB-2\r
                ,,,,\r
                LD-3,WB-3,,  ,\r
                """));

        assertThat(rows).containsExactly(
                new WaybillImportFile.Row(2, "LD-1", "WB-1", "51C-1", "Tài", "0900"),
                new WaybillImportFile.Row(4, "LD-2", "WB-2", null, null, null),
                new WaybillImportFile.Row(6, "LD-3", "WB-3", null, null, null));
    }

    @Test
    @DisplayName("a semicolon or tab file - a spreadsheet in another locale - is read with its own delimiter")
    void otherDelimiters() {
        assertThat(WaybillImportFile.parse(utf8("a;b;c;d;e\nLD-1;WB-1;51C-1;An;09\n")))
                .containsExactly(new WaybillImportFile.Row(2, "LD-1", "WB-1", "51C-1", "An", "09"));
        assertThat(WaybillImportFile.parse(utf8("a\tb\tc\nLD-1\tWB,1\n")))
                .containsExactly(new WaybillImportFile.Row(2, "LD-1", "WB,1", null, null, null));
    }

    @Test
    @DisplayName("quoted cells keep the delimiter, doubled quotes and line breaks")
    void quoting() {
        List<WaybillImportFile.Row> rows = WaybillImportFile.parse(utf8("h1,h2,h3,h4\n"
                + "\"LD-1\",\"WB \"\"X\"\"\",\"51C,1\",\"Nguyễn\nVăn\"\n"));

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.waybillNo()).isEqualTo("WB \"X\"");
            assertThat(r.vehiclePlate()).isEqualTo("51C,1");
            assertThat(r.driverName()).isEqualTo("Nguyễn\nVăn");
        });
    }

    @Test
    @DisplayName("not UTF-8, empty, no data line, a quote left open, over 1,000 lines or 1 MB: refused whole")
    void malformed() {
        assertThatThrownBy(() -> WaybillImportFile.parse(new byte[] {'a', ',', (byte) 0xC3, (byte) 0x28, '\n'}))
                .isInstanceOf(WaybillImportFile.Malformed.class).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> WaybillImportFile.parse(new byte[0])).isInstanceOf(WaybillImportFile.Malformed.class);
        assertThatThrownBy(() -> WaybillImportFile.parse(utf8("only,a,heading\n\n")))
                .isInstanceOf(WaybillImportFile.Malformed.class).hasMessageContaining("no line");
        assertThatThrownBy(() -> WaybillImportFile.parse(utf8("h\n\"LD-1,WB-1\n")))
                .isInstanceOf(WaybillImportFile.Malformed.class).hasMessageContaining("quote");
        StringBuilder many = new StringBuilder("h1,h2\n");
        for (int i = 0; i <= WaybillImportFile.MAX_ROWS; i++) {
            many.append("LD-").append(i).append(",WB-").append(i).append('\n');
        }
        assertThatThrownBy(() -> WaybillImportFile.parse(utf8(many.toString())))
                .isInstanceOf(WaybillImportFile.Malformed.class).hasMessageContaining("1000");
        assertThatThrownBy(() -> WaybillImportFile.parse(new byte[WaybillImportFile.MAX_BYTES + 1]))
                .isInstanceOf(WaybillImportFile.Malformed.class);
    }
}
