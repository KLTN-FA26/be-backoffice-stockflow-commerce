package com.stockflow.reporting.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.customer.api.CustomerSummaries;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.inventory.api.StockLedgerTotals;
import com.stockflow.inventory.api.StockMovementTotals;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.reporting.internal.domain.StockSummaryLine;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stock summary put together (2026-11-14 plan, BE2-C): the ledger's totals named - owner, warehouse, pieces per
 * carton - filtered and exported. The ledger's own sums against real flows are {@code StockLedgerTotalsIntegrationTest}'s.
 */
class StockSummaryReportServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T03:00:00Z"), ZoneOffset.UTC);
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private final List<StockMovementTotals> totals = new ArrayList<>();
    private final List<Object[]> asked = new ArrayList<>();
    private final Map<UUID, CustomerSummary> customers = new HashMap<>();
    private CustomerSkus customerSkus;
    private WarehouseService warehouses;
    private StockSummaryReportService service;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));

        StockLedgerTotals ledger = (from, to, prefixes, skus) -> {
            asked.add(new Object[]{from, to, prefixes, skus});
            return totals.stream().filter(row -> prefixes == null || prefixes.contains(row.warehousePrefix()))
                    .filter(row -> skus == null || skus.contains(row.sku())).toList();
        };
        UomConversionService units = mock(UomConversionService.class);
        when(units.conversionsOf(anyCollection())).thenReturn(Map.of(
                "CUP-A", List.of(unit("CUP-A", 50)),
                "CUP-C", List.of(unit("CUP-C", 30))));
        customerSkus = mock(CustomerSkus.class);
        when(customerSkus.ownersOf(anyCollection())).thenReturn(Map.of("CUP-A", OWNER, "CUP-C", OWNER));
        when(customerSkus.ownedBy(OWNER, null)).thenReturn(List.of(
                new CustomerSku(UUID.randomUUID(), UUID.randomUUID(), "CUP-A", "Cup A", OWNER, null, null, "CUP",
                        "ACTIVE"),
                new CustomerSku(UUID.randomUUID(), UUID.randomUUID(), "CUP-C", "Cup C", OWNER, null, null, "CUP",
                        "ACTIVE")));
        warehouses = mock(WarehouseService.class);
        WarehouseView hcm = new WarehouseView(HCM, "HCM", "Kho Hồ Chí Minh", "Q7", true);
        when(warehouses.activeWarehouses()).thenReturn(List.of(hcm));
        when(warehouses.findWarehouse(any())).thenReturn(Optional.empty());
        when(warehouses.findWarehouse(HCM)).thenReturn(Optional.of(hcm));
        customers.put(OWNER, new CustomerSummary(OWNER, null, "Công ty Ly Giấy", "a@example.com", null, "0312345678",
                null, "ACTIVE", 0, Instant.EPOCH));
        CustomerSummaries summaries = ids -> pick(customers, ids);
        service = new StockSummaryReportService(ledger, units, customerSkus, summaries, warehouses,
                new Messages(source), CLOCK);

        totals.add(new StockMovementTotals("HCM", "CUP-A", 100, 500, 50, 50, 300, 0, 50, 0, 0, 350));
        totals.add(new StockMovementTotals("HCM", "CUP-B", 0, 7, 0, 0, 0, 0, 0, 0, 0, 7));
        totals.add(new StockMovementTotals("HCM", "CUP-C", 0, 90, 0, 0, 0, 10, 0, 0, 0, 100));
        totals.add(new StockMovementTotals("DN", "CUP-A", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static UomConversion unit(String sku, int factor) {
        return new UomConversion(UUID.randomUUID(), sku, "CARTON", factor, null, null, null, null, true, 0);
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

    /** What the last ledger read was asked for: argument {@code index} (2 the warehouses, 3 the SKUs). */
    @SuppressWarnings("unchecked")
    private Collection<String> lastAsked(int index) {
        return (Collection<String>) asked.getLast()[index];
    }

    private static StockSummaryReportService.Filter filter(UUID warehouse, UUID customer, String sku,
                                                           boolean includeZero) {
        return new StockSummaryReportService.Filter(null, null, warehouse, customer, sku, includeZero);
    }

    @Test
    @DisplayName("the last 30 days by default; each row named, counted in cartons where whole; empty rows left out")
    void defaultsAndNames() {
        var summary = service.summary(filter(null, null, null, false), 0, 20, null);

        assertThat(summary.from()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(summary.to()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat((Object) asked.getFirst()[2]).as("every warehouse").isNull();
        assertThat(summary.rows().items()).extracting(StockSummaryLine::sku)
                .as("by SKU; the empty DN row is left out").containsExactly("CUP-A", "CUP-B", "CUP-C");
        StockSummaryLine a = summary.rows().items().getFirst();
        assertThat(a.warehouseId()).isEqualTo(HCM);
        assertThat(a.warehouseName()).isEqualTo("Kho Hồ Chí Minh");
        assertThat(a.ownerCode()).isEqualTo("0312345678");
        assertThat(a.ownerName()).isEqualTo("Công ty Ly Giấy");
        assertThat(a.unitsPerCarton()).isEqualTo(50);
        assertThat(a.cartons(a.closingUnits())).isEqualTo(7L);
        assertThat(a.cartonNote()).isEqualTo(StockSummaryLine.CartonNote.NONE);
        assertThat(a.balances()).isTrue();
        StockSummaryLine b = summary.rows().items().get(1);
        assertThat(b.ownerId()).as("an ordinary variant has no owner").isNull();
        assertThat(b.cartonNote()).isEqualTo(StockSummaryLine.CartonNote.NO_CARTON_UNIT);
        assertThat(b.cartons(7)).isNull();
        StockSummaryLine c = summary.rows().items().get(2);
        assertThat(c.cartonNote()).isEqualTo(StockSummaryLine.CartonNote.NOT_WHOLE_CARTONS);
        assertThat(c.cartons(90)).isEqualTo(3L);
        assertThat(c.cartons(100)).isNull();

        assertThat(service.summary(filter(null, null, null, true), 0, 20, null).rows().items()).hasSize(4);
        assertThat(service.summary(filter(null, null, null, false), 0, 20, "closingUnits,desc").rows().items())
                .extracting(StockSummaryLine::sku).containsExactly("CUP-A", "CUP-C", "CUP-B");
    }

    @Test
    @DisplayName("a warehouse reads its prefix; a customer reads its own SKUs, cut down by a SKU asked")
    void filters() {
        service.summary(filter(HCM, null, null, false), 0, 20, null);
        assertThat(lastAsked(2)).containsExactly("HCM");

        var owned = service.summary(filter(null, OWNER, null, false), 0, 20, null);
        assertThat(lastAsked(3)).containsExactlyInAnyOrder("CUP-A", "CUP-C");
        assertThat(owned.rows().items()).extracting(StockSummaryLine::sku).containsExactly("CUP-A", "CUP-C");

        var one = service.summary(filter(null, OWNER, " cup-c ", false), 0, 20, null);
        assertThat(one.rows().items()).extracting(StockSummaryLine::sku).containsExactly("CUP-C");
        var none = service.summary(filter(null, OWNER, "CUP-B", false), 0, 20, null);
        assertThat(lastAsked(3)).as("not the customer's SKU: nothing asked").isEmpty();
        assertThat(none.rows().items()).isEmpty();

        Throwable warehouse = catchThrowable(() -> service.summary(filter(UUID.randomUUID(), null, null, false), 0, 20,
                null));
        assertThat(((BusinessException) warehouse).errorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        Throwable customer = catchThrowable(() -> service.summary(filter(null, UUID.randomUUID(), null, false), 0, 20,
                null));
        assertThat(((BusinessException) customer).errorCode()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);
        Throwable tooLong = catchThrowable(() -> service.summary(new StockSummaryReportService.Filter(
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 11, 15), null, null, null, false), 0, 20, null));
        assertThat(((BusinessException) tooLong).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("the export: the period in the title, each measure in cartons and pieces, a note where not whole")
    void export() throws IOException {
        var file = service.export(new StockSummaryReportService.Filter(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 15), HCM, null, null, false), null);

        assertThat(file.filename()).isEqualTo("stock-summary-20261115-1000.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Xuất nhập tồn");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Kỳ 01/11/2026 – 15/11/2026");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Bộ lọc: kho HCM Kho Hồ Chí Minh");
            Row header = sheet.getRow(5);
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("Tồn đầu (thùng)");
            assertThat(header.getCell(7).getStringCellValue()).isEqualTo("Tồn đầu (cái)");
            assertThat(header.getCell(25).getStringCellValue()).isEqualTo("Tồn cuối (cái)");
            assertThat(header.getCell(26).getStringCellValue()).isEqualTo("Ghi chú");
            Row a = sheet.getRow(6);
            assertThat(a.getCell(2).getStringCellValue()).isEqualTo("CUP-A");
            assertThat(a.getCell(6).getNumericCellValue()).isEqualTo(2d);
            assertThat(a.getCell(7).getNumericCellValue()).isEqualTo(100d);
            assertThat(a.getCell(26)).as("whole cartons: no note").isNull();
            Row b = sheet.getRow(7);
            assertThat(b.getCell(6)).as("no carton unit: no cartons").isNull();
            assertThat(b.getCell(26).getStringCellValue()).startsWith("SKU chưa có quy cách thùng");
        }
    }
}
