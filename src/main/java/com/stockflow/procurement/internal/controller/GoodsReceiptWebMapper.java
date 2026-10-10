package com.stockflow.procurement.internal.controller;

import com.stockflow.procurement.internal.controller.dto.GoodsReceiptResponse;
import com.stockflow.procurement.internal.controller.dto.GoodsReceiptRowResponse;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface GoodsReceiptWebMapper {

    GoodsReceiptResponse toResponse(GoodsReceipts.ReceiptView receipt);

    GoodsReceiptResponse.Line toResponse(GoodsReceipts.LineView line);

    GoodsReceiptResponse.Inspection toResponse(GoodsReceipts.InspectionView inspection);

    GoodsReceiptRowResponse toResponse(GoodsReceipts.Row row);
}
