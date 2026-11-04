package com.stockflow.fulfillment.internal.controller.dto;

import java.util.List;
import java.util.UUID;

/** Someone a task can be assigned to. Name and roles only: no e-mail, phone or account status. */
public record AssigneeResponse(UUID userId, String displayName, List<String> roles) { }
