package com.stockflow.product.internal.controller;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.product.internal.controller.dto.MediaResponse;
import com.stockflow.product.internal.service.ProductMediaService;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds {@link MediaResponse}s with the uploader's and publisher's names, asking identity once for a whole list
 * (a variant holds at most 20 images, shown together), never once per image.
 *
 * <p>The two people are stored differently and stay so on the wire: {@code publishedBy} is an account id, while
 * {@code uploadedBy} is the row's audit column, the uploader's username (their id when the account had none,
 * {@code "system"} for a job). identity's API turns ids into names, not usernames, so a username is shown as it is -
 * the same string a display name falls back to when there is no full name. Only an audit column that holds an id is
 * looked up.</p>
 */
@Component
class ProductMediaResponses {

    private final ProductAdminWebMapper mapper;
    private final IdentityService identity;

    ProductMediaResponses(ProductAdminWebMapper mapper, IdentityService identity) {
        this.mapper = mapper;
        this.identity = identity;
    }

    MediaResponse toResponse(ProductMediaService.MediaView image) {
        return toResponses(List.of(image)).get(0);
    }

    List<MediaResponse> toResponses(List<ProductMediaService.MediaView> images) {
        var ids = new HashSet<UUID>();
        for (var image : images) {
            if (image.publishedBy() != null) {
                ids.add(image.publishedBy());
            }
            UUID uploader = accountIdOf(image.uploadedBy());
            if (uploader != null) {
                ids.add(uploader);
            }
        }
        Map<UUID, String> names = ids.isEmpty() ? Map.of() : identity.displayNames(ids);
        return images.stream().map(image -> mapper.toResponse(image, uploaderName(image.uploadedBy(), names),
                image.publishedBy() == null ? null : names.get(image.publishedBy()))).toList();
    }

    private static String uploaderName(String uploadedBy, Map<UUID, String> names) {
        UUID account = accountIdOf(uploadedBy);
        return account == null ? uploadedBy : names.getOrDefault(account, uploadedBy);
    }

    /** The audit column holds an id only for a caller without a username; anything else is not one. */
    private static UUID accountIdOf(String auditor) {
        if (auditor == null || auditor.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(auditor);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
