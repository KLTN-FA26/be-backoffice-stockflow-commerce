package com.stockflow.procurement.internal.domain;

/** Contact and commercial invariants also apply to callers that bypass HTTP validation. */
public record SupplierProfile(String code, String name, String email, String phone, String taxCode,
        int paymentTermDays, int leadTimeDays, String communicationChannel, String apiEndpoint) {
    public SupplierProfile {
        if (code == null || !code.matches("[A-Za-z0-9._-]{1,64}")
                || name == null || name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException("Supplier code and name are required and must fit their limits");
        }
        if (paymentTermDays < 0 || paymentTermDays > 365 || leadTimeDays < 0 || leadTimeDays > 365) {
            throw new IllegalArgumentException("Payment terms and lead time must be between 0 and 365 days");
        }
        if (email != null && !email.isBlank() && (email.length() > 320 || !email.matches("[^\\s@]+@[^\\s@]+"))) {
            throw new IllegalArgumentException("Invalid supplier email");
        }
        if (phone != null && !phone.isEmpty()) {
            long digits = phone.chars().filter(c -> c >= '0' && c <= '9').count();
            if (phone.length() > 32 || digits < 8 || digits > 15 || !phone.matches("\\+?[0-9][0-9 .()-]*[0-9]")) {
                throw new IllegalArgumentException("Invalid supplier phone");
            }
        }
        if (taxCode != null && !taxCode.isEmpty()
                && !taxCode.matches("(?=.*[0-9])[0-9A-Za-z][0-9A-Za-z-]{6,30}[0-9A-Za-z]")) {
            throw new IllegalArgumentException("Invalid supplier tax identifier");
        }
        var channel = SupplierCommunicationChannel.valueOf(communicationChannel);
        if (channel == SupplierCommunicationChannel.EMAIL && (email == null || email.isBlank())) {
            throw new IllegalArgumentException("Email is required for EMAIL communication");
        }
        if (channel == SupplierCommunicationChannel.API) {
            var uri = java.net.URI.create(apiEndpoint == null ? "" : apiEndpoint);
            if (apiEndpoint == null || apiEndpoint.length() > 500 || !"https".equals(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getQuery() != null || uri.getPort() != -1 && uri.getPort() != 443) {
                throw new IllegalArgumentException("A valid HTTPS supplier API endpoint is required");
            }
        }
    }
}
