package com.stockflow.procurement.internal.service;

import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ReturnRequestLookup;
import com.stockflow.order.api.ReturnRequestReference;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Draws the stock-in voucher of a return receipt ("Phiếu nhập kho", Mẫu số 01 - VT of Thông tư 99/2025/TT-BTC) as a
 * PDF: the accounting paper for goods a customer sent back, or a carrier brought back, once the warehouse confirmed
 * them into quarantine.
 *
 * <p>Read through {@link ReturnReceiptService#get}, so the caller's warehouses bound it as they bound the receipt on
 * screen; the customer who asked for the return reads its vouchers as a record of what came back, any other customer
 * finds no such return. Only a confirmed receipt has a voucher: a draft has put nothing into stock, and a voucher for it would be a
 * paper for goods the books do not hold (409).</p>
 *
 * <p>One line per SKU: "by the documents" is what the cartons' labels say, "actually received" what went into
 * stock - the count of the cartons stocked (WF-05 Q-18): a carton opened or emptied on its way back is counted on the
 * receipt and destroyed, so it shows as a difference on its line with its count in the note. Units that came back with no carton label are
 * a line of their own with nothing received - they are recorded and destroyed, never stocked. The unit cost is left
 * blank: this module does not hold what the goods left stock at, and the form's total is then left for the accountant
 * to write.</p>
 */
@Service
public class ReturnReceiptPaperService {

    /** A drawn paper and the name its download carries. */
    public record Pdf(String fileName, byte[] content) {
    }

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** More parcels than any return comes back in: the page of receipts read for one return. */
    private static final int MAX_RECEIPTS = 100;

    private final ReturnReceiptService receipts;
    private final OrderService orders;
    private final WarehouseService warehouses;
    private final IdentityService identities;
    private final ReturnGoodsReceiptDocument document;
    private final ReturnRequestLookup returnRequests;
    private final CustomerService customers;
    private final CustomerLegalProfiles legalProfiles;
    private final CustomerSkus skus;

    ReturnReceiptPaperService(ReturnReceiptService receipts, OrderService orders, WarehouseService warehouses,
                              IdentityService identities, ReturnGoodsReceiptDocument document,
                              ReturnRequestLookup returnRequests, CustomerService customers,
                              CustomerLegalProfiles legalProfiles, CustomerSkus skus) {
        this.returnRequests = returnRequests;
        this.customers = customers;
        this.legalProfiles = legalProfiles;
        this.skus = skus;
        this.receipts = receipts;
        this.orders = orders;
        this.warehouses = warehouses;
        this.identities = identities;
        this.document = document;
    }

    /**
     * The voucher of one receipt, for the warehouse and the accountant.
     *
     * @throws BusinessException {@code GOODS_RECEIPT_NOT_FOUND}; {@code FORBIDDEN} outside the caller's warehouses;
     *         {@code INVALID_RECEIPT_TRANSITION} while the receipt is not confirmed
     */
    @Transactional(readOnly = true)
    public Pdf voucher(UUID receiptId) {
        ReturnReceiptService.ReturnReceiptView receipt = receipts.get(receiptId);
        if (receipt.confirmedAt() == null) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION, ("Return receipt %s is %s: its stock-in "
                    + "voucher is drawn once it is confirmed").formatted(receipt.number(), receipt.status()));
        }
        ResourceBundle t = DocumentParts.bundle();
        return new Pdf(t.getString("document.file.returnReceipt").formatted(receipt.number()), draw(t, receipt));
    }

    /**
     * The vouchers of a return the customer asked for, for that customer: what the warehouse confirmed it received.
     * A return may come back in several parcels, each counted on a receipt of its own (WF-05 TH-3), so every confirmed
     * receipt of it is a voucher, one after another in one PDF, oldest first. A carrier's return of an undelivered
     * shipment has no return request and no customer copy: the customer sent nothing back.
     *
     * @throws BusinessException {@code RETURN_REQUEST_NOT_FOUND} for a return that is not the customer's, as for one
     *         that does not exist; {@code GOODS_RECEIPT_NOT_FOUND} while nothing of it was confirmed received
     */
    @Transactional(readOnly = true)
    public Pdf vouchersOfCustomerReturn(UUID customerId, UUID returnRequestId) {
        ReturnRequestReference request = returnRequests.find(returnRequestId)
                .filter(found -> customerId.equals(found.customerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RETURN_REQUEST_NOT_FOUND,
                        "No return request " + returnRequestId));
        List<ReturnReceiptService.ReturnReceiptView> confirmed = receipts.list(null, null, null, null,
                        request.id(), null, 0, MAX_RECEIPTS, null).items().stream()
                .map(row -> receipts.get(row.id()))
                .filter(receipt -> receipt.confirmedAt() != null && receipt.status() != GoodsReceiptStatus.CANCELLED)
                .sorted(Comparator.comparing(ReturnReceiptService.ReturnReceiptView::confirmedAt))
                .toList();
        if (confirmed.isEmpty()) {
            throw new BusinessException(ErrorCode.GOODS_RECEIPT_NOT_FOUND, ("Nothing of return %s was confirmed "
                    + "received yet").formatted(request.rmaNumber()));
        }
        ResourceBundle t = DocumentParts.bundle();
        String number = confirmed.size() == 1 ? confirmed.getFirst().number() : request.rmaNumber();
        List<byte[]> vouchers = confirmed.stream().map(receipt -> draw(t, receipt)).toList();
        return new Pdf(t.getString("document.file.returnReceipt").formatted(number), joined(vouchers));
    }

    private byte[] draw(ResourceBundle t, ReturnReceiptService.ReturnReceiptView receipt) {
        String customer = orders.findById(receipt.orderId()).map(this::customerOf).orElse("");
        WarehouseView warehouse = warehouses.findWarehouse(receipt.warehouseId()).orElse(null);
        String basis = receipt.rmaNumber() != null
                ? t.getString("return-papers.basis.request").formatted(receipt.rmaNumber())
                : t.getString("return-papers.basis.shipment").formatted(receipt.shipmentCode());
        List<String> attachments = new ArrayList<>();
        attachments.add(basis);
        attachments.add(t.getString("return-papers.attachment.order").formatted(receipt.orderNumber()));
        String preparedBy = receipt.confirmedBy() == null ? null
                : identities.displayNames(List.of(receipt.confirmedBy())).get(receipt.confirmedBy());
        return document.render(new ReturnGoodsReceiptDocument.Facts(receipt.number(),
                LocalDate.ofInstant(receipt.confirmedAt(), BUSINESS_ZONE), null, basis, customer, null,
                warehouse == null ? "" : warehouse.name(), warehouse == null ? null : warehouse.address(),
                lines(t, receipt), attachments, preparedBy));
    }

    /** Several vouchers as one PDF, page after page: one download for the customer, each voucher whole. */
    private static byte[] joined(List<byte[]> vouchers) {
        if (vouchers.size() == 1) {
            return vouchers.getFirst();
        }
        try (PDDocument all = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (byte[] voucher : vouchers) {
                try (PDDocument one = Loader.loadPDF(voucher)) {
                    merger.appendDocument(all, one);
                }
            }
            all.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not join the return's vouchers", ex);
        }
    }

    private List<ReturnGoodsReceiptDocument.Line> lines(ResourceBundle t,
                                                        ReturnReceiptService.ReturnReceiptView receipt) {
        String unit = t.getString("document.unit.piece");
        // Each SKU by the name its quote, contract and invoice gave it; one the product module cannot name (a legacy
        // SKU) as the goods it is.
        Map<String, String> names = new HashMap<>();
        Function<String, String> name = sku -> names.computeIfAbsent(sku, code -> skus.find(code)
                .map(CustomerSku::name).filter(DocumentParts::filled).orElse(t.getString("return-papers.goods")));
        Map<String, List<ReturnReceiptService.CartonView>> bySku = new LinkedHashMap<>();
        receipt.cartons().forEach(carton -> bySku.computeIfAbsent(carton.sku(), sku -> new ArrayList<>())
                .add(carton));
        List<ReturnGoodsReceiptDocument.Line> lines = new ArrayList<>();
        bySku.forEach((sku, cartons) -> {
            long labelled = cartons.stream().mapToLong(ReturnReceiptService.CartonView::quantity).sum();
            // "Thực nhập" is what went into stock (WF-05 Q-18, BE-20): a carton opened or emptied on its way back is
            // counted on the receipt and destroyed, never stocked, so its count is a note and not a received unit.
            long stocked = cartons.stream().filter(ReturnReceiptService.CartonView::stockEntered)
                    .mapToLong(ReturnReceiptService.CartonView::countedQuantity).sum();
            List<ReturnReceiptService.CartonView> notStocked = cartons.stream()
                    .filter(carton -> !carton.stockEntered()).toList();
            Set<String> lots = new LinkedHashSet<>();
            cartons.stream().map(ReturnReceiptService.CartonView::lotNumber).filter(DocumentParts::filled)
                    .forEach(lots::add);
            List<String> notes = new ArrayList<>();
            notes.add(t.getString("return-papers.cartons").formatted(cartons.size()));
            if (!lots.isEmpty()) {
                notes.add(t.getString("return-papers.lot").formatted(String.join(", ", lots)));
            }
            if (!notStocked.isEmpty()) {
                notes.add(t.getString("return-papers.notStocked").formatted(notStocked.size(),
                        DocumentFormats.number(notStocked.stream()
                                .mapToLong(ReturnReceiptService.CartonView::countedQuantity).sum())));
            }
            lines.add(new ReturnGoodsReceiptDocument.Line(name.apply(sku), sku, unit, labelled, stocked, null,
                    String.join("; ", notes)));
        });
        for (ReturnReceiptService.LooseLineView loose : receipt.looseLines()) {
            // Loose units are photographed on the receipt (Q-18, BE-43): the photo is the evidence the voucher refers
            // to, kept with the receipt, since the units themselves are destroyed.
            lines.add(new ReturnGoodsReceiptDocument.Line(name.apply(loose.sku()), loose.sku(), unit,
                    loose.quantity(), 0, null, loose.photoId() == null ? t.getString("return-papers.loose")
                    : t.getString("return-papers.loose") + "; " + t.getString("return-papers.loosePhoto")));
        }
        return lines;
    }

    /**
     * Whose goods come back: the customer as its legal profile names it, as the contract and the invoice do; one
     * without a profile by its own name; failing both, the order's contact or who it was delivered to.
     */
    private String customerOf(OrderSummary order) {
        return legalProfiles.legalProfile(order.customerId()).map(CustomerLegalProfile::companyName)
                .filter(DocumentParts::filled)
                .or(() -> customers.findById(order.customerId()).map(CustomerSummary::fullName)
                        .filter(DocumentParts::filled))
                .orElseGet(() -> DocumentParts.filled(order.contactName()) ? order.contactName()
                        : order.shippingAddress() == null || order.shippingAddress().recipientName() == null ? ""
                        : order.shippingAddress().recipientName());
    }
}
