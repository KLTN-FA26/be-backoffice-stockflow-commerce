package com.stockflow.common.document;

/**
 * Our company as its papers present it: the name and tax code, how to reach it, the account it is paid into, and its
 * logo. One value for every document, so a quotation, the contract after it and the record of its delivery cannot
 * print three versions of the same company. Any part but the name may be null: a document leaves out what is not
 * known.
 *
 * @param city where its papers are dated from ("Thành phố Hồ Chí Minh")
 * @param logo a PNG, read once at startup ({@link SellerLetterhead})
 */
public record Letterhead(String name, String taxCode, String address, String phone, String email, String website,
                         String bankAccountNumber, String bankName, String bankBranch, String city, byte[] logo) {
}
