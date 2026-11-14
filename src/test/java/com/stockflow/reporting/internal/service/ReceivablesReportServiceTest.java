package com.stockflow.reporting.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerCredits;
import com.stockflow.customer.api.CustomerSummaries;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.payment.api.CustomerReceivable;
import com.stockflow.payment.api.Receivables;
import com.stockflow.reporting.internal.domain.ReceivableLine;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The receivables report put together (2026-11-14 plan, BE2-B): the payment module's amounts beside the customer
 * module's names and credit, filtered, sorted and paged in memory, totalled over every row, and exported. The two
 * modules are stood in for by fixed answers; what they read from the database is {@code PaymentReportsIntegrationTest}'s.
 */
class ReceivablesReportServiceTest {

    /** 17:00 UTC on the 14th is midnight on the 15th in Ho Chi Minh City: the report's today is the 15th. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T17:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    private final UUID anh = UUID.randomUUID();
    private final UUID binh = UUID.randomUUID();
    private final UUID cuong = UUID.randomUUID();
    private final Map<UUID, CustomerSummary> summaries = new HashMap<>();
    private final Map<UUID, CreditTerms> terms = new HashMap<>();
    private List<CustomerReceivable> owed = List.of();
    private LocalDate askedFor;
    private ReceivablesReportService service;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        Receivables receivables = (today, customerId) -> {
            askedFor = today;
            return owed.stream().filter(row -> customerId == null || row.customerId().equals(customerId)).toList();
        };
        CustomerSummaries customers = ids -> pick(summaries, ids);
        CustomerCredits credits = ids -> pick(terms, ids);
        service = new ReceivablesReportService(receivables, customers, credits, new Messages(source), CLOCK);

        summaries.put(anh, customer(anh, "Công ty Ánh", "0301234567"));
        summaries.put(binh, customer(binh, "Công ty Bình", "0307654321"));
        summaries.put(cuong, customer(cuong, "Công ty Cường", null));
        terms.put(anh, credit(anh, 10_000_000));
        terms.put(binh, credit(binh, 1_000_000));
        terms.put(cuong, CreditTerms.none(cuong));
        owed = List.of(
                // Ánh: 3,000,000 receivable (1,000,000 of it 40 days late), 1,000,000 committed.
                receivable(anh, 0, 2_000_000, 0, 1_000_000, 0, 0, 1_000_000, LocalDate.of(2026, 10, 6), 2),
                // Bình: 500,000 receivable, 100 days late; 800,000 committed - over its limit of 1,000,000.
                receivable(binh, 0, 0, 0, 0, 0, 500_000, 800_000, LocalDate.of(2026, 8, 7), 1),
                // Cường: nothing overdue, no credit.
                receivable(cuong, 700_000, 700_000, 0, 0, 0, 0, 0, TODAY, 1));
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static <T> Map<UUID, T> pick(Map<UUID, T> all, Collection<UUID> ids) {
        Map<UUID, T> found = new HashMap<>();
        ids.forEach(id -> {
            if (all.containsKey(id)) {
                found.put(id, all.get(id));
            }
        });
        return found;
    }

    private static CustomerSummary customer(UUID id, String name, String taxCode) {
        return new CustomerSummary(id, null, name, id + "@example.com", null, taxCode, null, "ACTIVE", 0,
                Instant.EPOCH);
    }

    private static CreditTerms credit(UUID id, long limit) {
        return new CreditTerms(id, true, BigDecimal.valueOf(limit), 30, "VND", UUID.randomUUID(), Instant.EPOCH,
                null, 0);
    }

    private static CustomerReceivable receivable(UUID id, long receivable, long notYetDue, long to30, long to60,
                                                 long to90, long over90, long committed, LocalDate oldest, int items) {
        return new CustomerReceivable(id, "VND", BigDecimal.valueOf(receivable == 0 ? notYetDue + to30 + to60 + to90
                + over90 : receivable), BigDecimal.valueOf(notYetDue), BigDecimal.valueOf(to30),
                BigDecimal.valueOf(to60), BigDecimal.valueOf(to90), BigDecimal.valueOf(over90),
                BigDecimal.valueOf(committed), oldest, items);
    }

    private static BigDecimal dong(long amount) {
        return BigDecimal.valueOf(amount);
    }

    @Test
    @DisplayName("each customer with its code, name, credit and what is left of it; the most overdue first; today")
    void joinsAndSortsByOverdue() {
        var report = service.report(null, false, 0, 20, null);

        assertThat(report.asOf()).isEqualTo(TODAY);
        assertThat(askedFor).as("aged against the business day of today").isEqualTo(TODAY);
        assertThat(report.rows().items()).extracting(ReceivableLine::customerId).containsExactly(anh, binh, cuong);
        ReceivableLine first = report.rows().items().getFirst();
        assertThat(first.customerName()).isEqualTo("Công ty Ánh");
        assertThat(first.customerCode()).isEqualTo("0301234567");
        assertThat(first.overdue()).isEqualByComparingTo(dong(1_000_000));
        assertThat(first.exposure()).as("receivable + committed").isEqualByComparingTo(dong(4_000_000));
        assertThat(first.creditLimit()).isEqualByComparingTo(dong(10_000_000));
        assertThat(first.available()).isEqualByComparingTo(dong(6_000_000));

        ReceivableLine over = report.rows().items().get(1);
        assertThat(over.exposure()).isEqualByComparingTo(dong(1_300_000));
        assertThat(over.available()).as("over the limit: nothing left, never negative")
                .isEqualByComparingTo(BigDecimal.ZERO);
        ReceivableLine none = report.rows().items().get(2);
        assertThat(none.customerCode()).as("no tax code: KH and the id's tail").startsWith("KH");
        assertThat(none.creditLimit()).isNull();
        assertThat(none.available()).as("no credit, no credit left").isNull();
    }

    @Test
    @DisplayName("the totals are over every row the filter keeps, not the page")
    void totalsOverEveryRow() {
        var report = service.report(null, false, 0, 1, null);

        assertThat(report.rows().items()).hasSize(1);
        assertThat(report.rows().totalElements()).isEqualTo(3);
        assertThat(report.rows().hasNext()).isTrue();
        assertThat(report.totals()).singleElement().satisfies(total -> {
            assertThat(total.currency()).isEqualTo("VND");
            assertThat(total.customers()).isEqualTo(3);
            assertThat(total.receivable()).isEqualByComparingTo(dong(4_200_000));
            assertThat(total.notYetDue()).isEqualByComparingTo(dong(2_700_000));
            assertThat(total.overdue31To60()).isEqualByComparingTo(dong(1_000_000));
            assertThat(total.overdueOver90()).isEqualByComparingTo(dong(500_000));
            assertThat(total.overdue()).isEqualByComparingTo(dong(1_500_000));
            assertThat(total.committedNotReached()).isEqualByComparingTo(dong(1_800_000));
            assertThat(total.exposure()).isEqualByComparingTo(dong(6_000_000));
        });
        assertThat(service.report(null, false, 1, 1, null).rows().items()).extracting(ReceivableLine::customerId)
                .containsExactly(binh);
    }

    @Test
    @DisplayName("overdue only, one customer, the other sorts; an unknown customer is a 404")
    void filtersAndSorts() {
        assertThat(service.report(null, true, 0, 20, null).rows().items()).extracting(ReceivableLine::customerId)
                .containsExactly(anh, binh);
        assertThat(service.report(binh, false, 0, 20, null).rows().items()).extracting(ReceivableLine::customerId)
                .containsExactly(binh);
        assertThat(service.report(null, false, 0, 20, "customerName").rows().items())
                .extracting(ReceivableLine::customerName)
                .as("Vietnamese order: Á before B before C")
                .containsExactly("Công ty Ánh", "Công ty Bình", "Công ty Cường");
        assertThat(service.report(null, false, 0, 20, "receivable,desc").rows().items())
                .extracting(ReceivableLine::customerId).containsExactly(anh, cuong, binh);
        assertThat(service.report(null, false, 0, 20, "oldestDueDate").rows().items())
                .extracting(ReceivableLine::customerId).containsExactly(binh, anh, cuong);

        Throwable unknown = catchThrowable(() -> service.report(UUID.randomUUID(), false, 0, 20, null));
        assertThat(((BusinessException) unknown).errorCode()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);
        Throwable badSort = catchThrowable(() -> service.report(null, false, 0, 20, "creditLimit"));
        assertThat(badSort).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a customer who owes nothing is an empty report, not a 404")
    void knownCustomerOwingNothing() {
        UUID dung = UUID.randomUUID();
        summaries.put(dung, customer(dung, "Công ty Dũng", null));
        var report = service.report(dung, false, 0, 20, null);
        assertThat(report.rows().items()).isEmpty();
        assertThat(report.totals()).isEmpty();
    }

    @Test
    @DisplayName("the export: Vietnamese headings, today in the title, every row and a total row")
    void export() throws IOException {
        var file = service.export(null, false, null);

        assertThat(file.filename()).isEqualTo("receivables-20261115-0000.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Công nợ");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("Công nợ phải thu theo khách và tuổi nợ");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).startsWith("Tính tại ngày 15/11/2026");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Bộ lọc: không có");
            Row header = sheet.getRow(5);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Mã khách");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Công nợ phải thu");
            assertThat(sheet.getRow(6).getCell(1).getStringCellValue()).isEqualTo("Công ty Ánh");
            Row total = sheet.getRow(9);
            assertThat(total.getCell(1).getStringCellValue()).isEqualTo("Tổng cộng");
            assertThat(total.getCell(3).getNumericCellValue()).isEqualTo(4_200_000d);
            assertThat(sheet.getLastRowNum()).isEqualTo(9);
        }
    }
}
