package com.stockflow.design.internal.controller;

import com.stockflow.design.internal.controller.dto.DesignDocumentResponse;
import com.stockflow.design.internal.service.CustomerDesignService;
import org.springframework.stereotype.Component;

/** A customer's layout to the storefront's P3 shape. */
@Component
class CustomerDesignWebMapper {

    DesignDocumentResponse toResponse(CustomerDesignService.DocumentView view) {
        return new DesignDocumentResponse(view.designId(), view.version(), view.document(), view.checksum(),
                view.updatedAt());
    }
}
