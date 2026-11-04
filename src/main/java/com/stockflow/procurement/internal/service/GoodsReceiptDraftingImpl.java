package com.stockflow.procurement.internal.service;

import com.stockflow.procurement.api.GoodsReceiptDrafting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@link GoodsReceiptDrafting} over the receipt service the screen uses: the same {@code create} and
 * {@code replaceLines}, so a draft made this way passes exactly the checks a draft made on the screen does, in one
 * transaction — a line refused leaves no empty draft behind.
 */
@Service
class GoodsReceiptDraftingImpl implements GoodsReceiptDrafting {

    private final GoodsReceipts receipts;

    GoodsReceiptDraftingImpl(GoodsReceipts receipts) {
        this.receipts = receipts;
    }

    @Override
    @Transactional
    public DraftedReceipt draft(DraftReceiptCommand command, UUID userId) {
        GoodsReceipts.ReceiptView created = receipts.create(new GoodsReceipts.Create(command.purchaseOrderId(),
                command.deliveryNote(), command.note()), userId);
        GoodsReceipts.ReceiptView counted = receipts.replaceLines(created.id(), command.lines().stream()
                .map(line -> new GoodsReceipts.NewLine(line.purchaseOrderLineId(), line.quantity(), line.lotNumber(),
                        line.expiryDate(), line.locationCode(), line.note()))
                .toList());
        return new DraftedReceipt(counted.id(), counted.number(), counted.warehouseId());
    }
}
