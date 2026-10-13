package com.stockflow.notification.internal.controller;

import com.stockflow.notification.internal.controller.dto.UserNotificationPageResponse;
import com.stockflow.notification.internal.controller.dto.UserNotificationResponse;
import com.stockflow.notification.internal.service.UserInbox;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface UserInboxWebMapper {

    @Mapping(target = "read", expression = "java(item.readAt() != null)")
    UserNotificationResponse toResponse(UserInbox.Item item);

    default UserNotificationPageResponse toResponse(UserInbox.InboxPage inbox) {
        var page = inbox.page();
        return new UserNotificationPageResponse(page.items().stream().map(this::toResponse).toList(), page.page(),
                page.size(), page.totalElements(), page.totalPages(), page.hasNext(), page.hasPrevious(),
                inbox.unreadCount());
    }
}
