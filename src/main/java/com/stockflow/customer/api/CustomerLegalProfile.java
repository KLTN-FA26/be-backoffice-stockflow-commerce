package com.stockflow.customer.api;

import java.util.UUID;

/**
 * Who a customer is on a contract (kltn-docs 18 §3 "thông tin pháp lý", BR-05; QD-14; team plan 4B-4): the company's
 * name and tax code from its profile, and the registered business address, legal representative and their title that a
 * sales contract names the buyer by. A contract copies it when drawn up, so a later change leaves contracts already
 * drawn up as they were (18 §6).
 *
 * @param taxCode  normalised; null for a household business that has none yet
 * @param version  the version to send back when changing it
 */
public record CustomerLegalProfile(UUID customerId, String companyName, String taxCode, String registeredAddress,
                                   String representativeName, String representativeTitle, long version) {
}
