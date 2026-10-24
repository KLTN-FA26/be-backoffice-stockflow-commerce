package com.stockflow.inventory.internal.controller;

import com.stockflow.common.storage.DownloadLink;
import com.stockflow.inventory.internal.controller.dto.DamagePhotoLinkResponse;
import com.stockflow.inventory.internal.controller.dto.DamageReportResponse;
import com.stockflow.inventory.internal.controller.dto.DamageReportRowResponse;
import com.stockflow.inventory.internal.service.StockDamages;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Damage report views to their wire shapes, field for field (see {@code InventoryWebMapper} on why MapStruct). */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface DamageReportWebMapper {

    DamageReportResponse toResponse(StockDamages.DamageReportView report);

    DamageReportResponse.Photo toResponse(StockDamages.PhotoView photo);

    DamageReportResponse.Disposition toResponse(StockDamages.DispositionView disposition);

    DamageReportResponse.SupplierHandOver toResponse(StockDamages.SupplierHandOverView handOver);

    DamageReportRowResponse toResponse(StockDamages.DamageReportRow row);

    DamagePhotoLinkResponse toResponse(DownloadLink link);
}
