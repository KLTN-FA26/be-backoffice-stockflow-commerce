package com.stockflow.fulfillment.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The delivery-outcome template (WF-05 fix plan item 5): eight columns by position, read by the reader the waybill
 * import uses - a file Excel saved with semicolons included - and refused whole only when it is not a table at all.
 */
class DeliveryOutcomeFileTest {

    private static final String HEADING = "Mã vận đơn,Kết quả,Lý do,Thời điểm,Người nhận,Ghi chú,Mã shipment,Mã hãng\r\n";

    private static byte[] file(String lines) {
        return ("﻿" + HEADING + lines).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the template is the byte-order mark and the eight headings, and reads back as a file with no line")
    void templateIsTheHeadingLine() {
        byte[] template = DeliveryOutcomeFile.template(List.of("Mã vận đơn", "Kết quả", "Lý do", "Thời điểm",
                "Người nhận", "Ghi chú", "Mã shipment", "Mã hãng"));

        assertThat(template).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(template, StandardCharsets.UTF_8)).isEqualTo("﻿" + HEADING);
        assertThatThrownBy(() -> DeliveryOutcomeFile.parse(template)).isInstanceOf(ImportCsv.Malformed.class)
                .hasMessageContaining("no line below its heading");
        assertThatThrownBy(() -> DeliveryOutcomeFile.template(List.of("a", "b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a line is read by position: cells trimmed, a blank one null, a short line's missing cells null, the "
            + "line numbered as Excel shows it")
    void readsLinesByPosition() {
        List<DeliveryOutcomeFile.Row> rows = DeliveryOutcomeFile.parse(file(
                "WB-1, DELIVERED ,,2026-11-09 10:15,Chị Lan,\"giao tận tay, ký nhận\",SO-1-S1, ghn \r\n"
                        + "\r\n"
                        + "WB-2,DELIVERY_FAILED,Khách vắng,2026-11-09T11:00:00+07:00\r\n"));

        assertThat(rows).containsExactly(
                new DeliveryOutcomeFile.Row(2, "WB-1", "DELIVERED", null, "2026-11-09 10:15", "Chị Lan",
                        "giao tận tay, ký nhận", "SO-1-S1", "ghn"),
                new DeliveryOutcomeFile.Row(4, "WB-2", "DELIVERY_FAILED", "Khách vắng", "2026-11-09T11:00:00+07:00",
                        null, null, null, null));
    }

    @Test
    @DisplayName("a file Excel saved with semicolons, its comma heading kept in one cell, is read by its lines")
    void readsSemicolons() {
        List<DeliveryOutcomeFile.Row> rows = DeliveryOutcomeFile.parse(("﻿" + HEADING.strip() + ";;;;;;\r\n"
                + "WB-1;DELIVERED;;9/11/2026 10:15;;;\r\n").getBytes(StandardCharsets.UTF_8));

        assertThat(rows).containsExactly(new DeliveryOutcomeFile.Row(2, "WB-1", "DELIVERED", null, "9/11/2026 10:15",
                null, null, null, null));
    }

    @Test
    @DisplayName("what is not such a table is refused whole: empty, not UTF-8, a quote left open, over 1,000 lines")
    void refusesWhatIsNotATable() {
        assertThatThrownBy(() -> DeliveryOutcomeFile.parse(new byte[0])).isInstanceOf(ImportCsv.Malformed.class);
        assertThatThrownBy(() -> DeliveryOutcomeFile.parse(new byte[] {(byte) 0xC3, (byte) 0x28, 0x0A, 0x41}))
                .isInstanceOf(ImportCsv.Malformed.class).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> DeliveryOutcomeFile.parse(file("WB-1,\"DELIVERED\r\n")))
                .isInstanceOf(ImportCsv.Malformed.class).hasMessageContaining("quote");
        assertThatThrownBy(() -> DeliveryOutcomeFile.parse(file("WB-1,DELIVERED,,2026-11-09 10:15\r\n"
                .repeat(DeliveryOutcomeFile.MAX_ROWS + 1))))
                .isInstanceOf(ImportCsv.Malformed.class).hasMessageContaining("1000");
    }
}
