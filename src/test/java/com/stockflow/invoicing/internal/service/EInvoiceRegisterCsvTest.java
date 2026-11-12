package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.domain.ProviderCallLog;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileTransfers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The period register as CSV: what a spreadsheet opens, and what it must not run. */
class EInvoiceRegisterCsvTest {

    private final EInvoiceQueries queries = mock(EInvoiceQueries.class);
    private final EInvoiceQueryService service = new EInvoiceQueryService(mock(EInvoiceRepository.class), queries,
            mock(ProviderCallLog.class), mock(FileTransfers.class));

    @Test
    @DisplayName("UTF-8 with a byte-order mark, a header, one line per invoice; numbers padded, money whole")
    void csv() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);
        when(queries.register(from, to)).thenReturn(List.of(
                new EInvoiceQueries.RegisterEntry("1", "C26TSB", 7, LocalDate.of(2026, 11, 12), EInvoiceType.ORIGINAL,
                        EInvoiceStatus.ISSUED, false, "Công ty \"Trà Sữa\", CN 1", "0398765432", "12 Nguyễn Huệ",
                        "SO-20261101-0007", new BigDecimal("2500000.0000"), new BigDecimal("240000.0000"),
                        new BigDecimal("2740000.0000"), "SBX01", "SBXL1", null)));

        String csv = service.registerCsv(from, to);

        String[] lines = csv.split("\r\n");
        assertThat(csv).startsWith("\uFEFFtemplate_code,symbol,invoice_number,issue_date,");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).isEqualTo("\"1\",\"C26TSB\",00000007,12/11/2026,ORIGINAL,ISSUED,false,"
                + "\"Công ty \"\"Trà Sữa\"\", CN 1\",\"0398765432\",\"12 Nguyễn Huệ\",\"SO-20261101-0007\","
                + "2500000,240000,2740000,\"SBX01\",\"SBXL1\",");
    }

    @Test
    @DisplayName("a value a spreadsheet would run as a formula is led by an apostrophe")
    void formulasAreDefused() {
        assertThat(EInvoiceQueryService.cell("=HYPERLINK(\"http://x\")")).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(EInvoiceQueryService.cell("+84 28 1234")).isEqualTo("\"'+84 28 1234\"");
        assertThat(EInvoiceQueryService.cell("-1")).isEqualTo("\"'-1\"");
        assertThat(EInvoiceQueryService.cell("@SUM(A1)")).isEqualTo("\"'@SUM(A1)\"");
        assertThat(EInvoiceQueryService.cell("Cong ty A")).isEqualTo("\"Cong ty A\"");
        assertThat(EInvoiceQueryService.cell(null)).isEmpty();
    }

    @Test
    @DisplayName("a period that ends before it starts, or is longer than the register allows, is refused")
    void thePeriodIsBounded() {
        LocalDate day = LocalDate.of(2026, 11, 1);
        for (LocalDate[] wrong : new LocalDate[][] {{day, day.minusDays(1)}, {day, day.plusDays(401)}, {null, day},
                {day, null}}) {
            assertThatThrownBy(() -> service.register(wrong[0], wrong[1]))
                    .isInstanceOfSatisfying(BusinessException.class,
                            refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        when(queries.register(day, day.plusDays(400))).thenReturn(List.of());
        assertThat(service.register(day, day.plusDays(400))).isEmpty();
    }
}
