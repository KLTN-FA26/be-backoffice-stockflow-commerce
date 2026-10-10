package com.stockflow.identity.api;

import java.util.List;

/**
 * @param password  null to have one generated; either way the user must change it at first sign-in
 * @param roleCodes at least one staff role; never CUSTOMER
 */
public record CreateStaffUserCommand(String username, String email, String fullName, String password,
                                     List<String> roleCodes) {
}
